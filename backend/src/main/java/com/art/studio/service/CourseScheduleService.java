package com.art.studio.service;

import java.time.LocalDateTime;
import java.util.List;

import com.art.studio.common.BizException;
import com.art.studio.dto.ScheduleActionResult;
import com.art.studio.entity.CourseSchedule;
import com.art.studio.entity.Student;
import com.art.studio.repository.CourseScheduleRepository;
import com.art.studio.repository.StudentRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 课程排期状态闭环。
 *
 * 状态机（{@link com.art.studio.common.Dict#canTransferCourse}）：
 *   待上课 ──签到──▶ 已上课（终态，已发生签到与课后记录，不可取消、不可复用时段）
 *        └──取消──▶ 已取消（终态；记录保留供历史查看，但不再占用时段，原日期时段立即可重排）
 *
 * 并发收口策略：
 *   1. 排课 / 取消 / 签到都在事务内先锁学员行（PESSIMISTIC_WRITE），
 *      把同一学员的课次写请求在 DB 层串行——取消与重排同时发生时，后执行的请求
 *      一定读到先执行请求的最终状态，不会出现「明明已取消却仍被拦截」，
 *      也不会出现两条有效课次占用同一时段；
 *   2. 排课在串行区内用加锁查询读取当日「有效课次」（待上课 / 已上课），
 *      已取消课次不参与重叠校验（时段已释放，行仍保留）；
 *   3. 取消为幂等：重复取消 / 并发取消返回同一条「已取消」课次与可理解说明。
 */
@Service
public class CourseScheduleService {

    private final CourseScheduleRepository repo;
    private final StudentRepository studentRepo;

    public CourseScheduleService(CourseScheduleRepository repo, StudentRepository studentRepo) {
        this.repo = repo;
        this.studentRepo = studentRepo;
    }

    public List<CourseSchedule> list(Long studentId, String status) {
        if (studentId != null && status != null) {
            return repo.findByStudentIdAndStatus(studentId, status);
        }
        if (studentId != null) {
            return repo.findByStudentId(studentId);
        }
        if (status != null) {
            return repo.findByStatus(status);
        }
        return repo.findAll();
    }

    public CourseSchedule create(CourseSchedule input) {
        return createWithResult(input).getSchedule();
    }

    /**
     * 排课。同一学员串行区内做重叠校验，已取消课次不占时段：
     * 取消后原日期时段立即可重新排课；待上课 / 已上课课次仍参与重叠校验。
     */
    @Transactional
    public ScheduleActionResult createWithResult(CourseSchedule input) {
        if (input.studentId == null) {
            throw new BizException("排课必须指定学员");
        }
        // 先锁学员行：同一学员的排课 / 取消 / 签到在此串行，重叠裁决以最终状态为准
        Student st = studentRepo.findByIdForUpdate(input.studentId)
                .orElseThrow(() -> new BizException("学员不存在：" + input.studentId));
        if (!"在读".equals(st.status)) {
            throw new BizException("只有「在读」学员才能排课（" + st.name + " 当前为「" + st.status + "」）");
        }
        if (input.courseName == null || input.courseName.isBlank()) {
            throw new BizException("课程名称不能为空");
        }
        if (input.date == null) {
            throw new BizException("上课日期不能为空");
        }
        if (input.startMin == null || input.endMin == null) {
            throw new BizException("开始 / 结束时间不能为空");
        }
        if (input.startMin >= input.endMin) {
            throw new BizException("开始时间须早于结束时间");
        }
        // 同学员同日期时段不重叠：a.startMin < b.endMin && b.startMin < a.endMin
        // 只校验「仍占用时段」的有效课次（待上课 / 已上课）；已取消课次时段已释放
        List<CourseSchedule> same = repo.findActiveForUpdate(input.studentId, input.date);
        for (CourseSchedule c : same) {
            if (input.startMin < c.endMin && c.startMin < input.endMin) {
                throw new BizException("该学员在 " + input.date + " 已有" + c.status + "课次占用重叠时段（"
                        + c.courseName + " " + c.startMin + "-" + c.endMin + "），请更换时段或先处理原课次");
            }
        }
        CourseSchedule s = new CourseSchedule();
        s.studentId = input.studentId;
        s.courseName = input.courseName;
        s.date = input.date;
        s.startMin = input.startMin;
        s.endMin = input.endMin;
        s.status = "待上课";
        s.createdAt = LocalDateTime.now();
        return ScheduleActionResult.changed(repo.save(s), "排课成功，课次状态为「待上课」");
    }

    /**
     * 取消课次（幂等）。仅「待上课」可取消：
     * 已上课是终态——签到与课后记录已经发生，取消会把历史变成可复用时段，必须拒绝；
     * 重复 / 并发取消返回同一条「已取消」课次，不报错、不产生新记录。
     * 取消只改状态位，课次行保留供历史列表 / 学员档案查看，且不再参与重叠校验。
     */
    @Transactional
    public ScheduleActionResult cancel(Long id) {
        CourseSchedule c = lockScheduleByStudent(id);
        if ("已取消".equals(c.status)) {
            // 幂等收口：重复点击 / 并发的第二个请求拿到同一最终状态
            return ScheduleActionResult.same(c, "该课次已是「已取消」状态，无需重复取消（原时段已释放，可重新排课）");
        }
        if (!"待上课".equals(c.status)) {
            throw new BizException("只有「待上课」的课程可以取消（当前为「" + c.status
                    + "」）；已上课课次的签到与课后记录已留存，不能取消或释放其时段");
        }
        c.status = "已取消";
        return ScheduleActionResult.changed(repo.save(c), "课次已取消，原时段已释放，可立即为该学员重新排课");
    }

    /**
     * 签到上课（终态）。并发签到在学员行锁内串行；已上课重复签到给出明确原因，
     * 已取消课次不能再签到。
     */
    @Transactional
    public CourseSchedule checkin(Long id) {
        CourseSchedule c = lockScheduleByStudent(id);
        if ("已上课".equals(c.status)) {
            throw new BizException("该课次已签到上课，无需重复操作");
        }
        if (!"待上课".equals(c.status)) {
            throw new BizException("只有「待上课」的课程可以签到上课（当前为「" + c.status + "」）");
        }
        c.status = "已上课";
        return repo.save(c);
    }

    /**
     * 先锁学员行再读课次行，使同一学员的排课 / 取消 / 签到共用同一加锁顺序
     *（学员 → 课次），避免死锁并保证并发裁决基于最终状态。
     *
     * 关键：课次必须在拿到学员锁之后【首次】读取。若加锁前先用无锁查询把课次读入
     * 一级缓存，随后的行锁查询会命中缓存、跳过 ResultSet，拿到的仍是对方提交前的
     * 旧状态（例如取消已提交却仍按「待上课」签到）。因此这里直接以课次 id 为条件
     * 锁学员行（子查询定位，课次全程不预读），再用行锁查询首次加载课次。
     */
    private CourseSchedule lockScheduleByStudent(Long id) {
        if (id == null) {
            throw new BizException("缺少课次 id");
        }
        // 课次不存在时子查询匹配不到学员行，转成可读的业务原因
        studentRepo.findByScheduleIdForUpdate(id)
                .orElseThrow(() -> new BizException("排课记录不存在：" + id));
        return repo.findByIdForUpdate(id)
                .orElseThrow(() -> new BizException("排课记录不存在：" + id));
    }
}
