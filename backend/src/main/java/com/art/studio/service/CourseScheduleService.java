package com.art.studio.service;

import java.time.LocalDateTime;
import java.util.List;

import com.art.studio.common.BizException;
import com.art.studio.entity.CourseSchedule;
import com.art.studio.entity.Student;
import com.art.studio.repository.CourseScheduleRepository;
import com.art.studio.repository.StudentRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 课程排期状态闭环。
 *
 * 状态机：待上课 →（签到）已上课（终态）；待上课 →（取消）已取消（终态留痕，不物理删除）。
 *
 * 时段占用规则：只有「待上课 / 已上课」占用时段；「已取消」记录保留供历史查看，
 * 不再参与重叠校验，原日期时段取消后立即可重新排课。
 *
 * 并发收口（与课后记录服务同一套悲观锁范式）：
 *   1. 所有写操作都在事务内先锁学员行（findByIdForUpdate），同学员的排课 / 取消 / 签到
 *      在 DB 层完全串行；加锁顺序统一为「学员行 → 课次行」，避免交叉死锁；
 *   2. 串行区内再用当前读（FOR UPDATE）重查课次最新状态后按状态机判定，
 *      「取消与重排同时发生」时一律以最终状态为准，不会出现已取消仍被拦截、
 *      也不会出现两条有效课次占用同一时段；
 *   3. 锁等待 3 秒快速失败，由全局异常处理转成 409 可理解提示，客户端重试即可。
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

    @Transactional
    public CourseSchedule create(CourseSchedule input) {
        if (input.studentId == null) {
            throw new BizException("排课必须指定学员");
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
        // 先锁学员行：同学员的并发排课/取消/签到在此串行，保证下面的重叠判定基于最终状态
        Student st = studentRepo.findByIdForUpdate(input.studentId)
                .orElseThrow(() -> new BizException("学员不存在：" + input.studentId));
        if (!"在读".equals(st.status)) {
            throw new BizException("只有「在读」学员才能排课（" + st.name + " 当前为「" + st.status + "」）");
        }
        // 同学员同日期时段不重叠：a.startMin<b.endMin && b.startMin<a.endMin。
        // 当前读拿最新状态，仅「待上课 / 已上课」占位；已取消课次跳过 → 原时段可立即重排。
        List<CourseSchedule> sameDay = repo.findByStudentIdAndDateForUpdate(input.studentId, input.date);
        for (CourseSchedule c : sameDay) {
            if (isActive(c.status) && input.startMin < c.endMin && c.startMin < input.endMin) {
                throw new BizException("该学员在 " + input.date + " " + fmtMin(c.startMin) + "-"
                        + fmtMin(c.endMin) + " 已有「" + c.status + "」课次（" + c.courseName
                        + "），不能安排重叠时段；相邻时段不受影响。"
                        + "若该课次刚被取消，请刷新列表后再排（取消与重排并发时以刷新后的最终状态为准）");
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
        return repo.save(s);
    }

    @Transactional
    public CourseSchedule cancel(Long id) {
        CourseSchedule c = lockSchedule(id);
        if ("已取消".equals(c.status)) {
            // 重复取消给出明确原因，幂等返回当前状态，不做任何写入
            throw new BizException("该课次已是「已取消」状态，无需重复取消；原时段已释放，可重新排课");
        }
        if ("已上课".equals(c.status)) {
            // 已上课为终态：签到与课后记录已发生，取消会把已占用时段错误地变回可复用
            throw new BizException("该课次已签到上课，不能取消（已发生签到与课后记录，时段不可再复用）");
        }
        if (!"待上课".equals(c.status)) {
            throw new BizException("只有「待上课」的课程可以取消（当前为「" + c.status + "」）");
        }
        c.status = "已取消";
        return repo.save(c);
    }

    @Transactional
    public CourseSchedule checkin(Long id) {
        CourseSchedule c = lockSchedule(id);
        if ("已上课".equals(c.status)) {
            throw new BizException("该课次已签到上课，无需重复签到");
        }
        if ("已取消".equals(c.status)) {
            throw new BizException("该课次已取消，不能签到；如需上课请重新排课");
        }
        if (!"待上课".equals(c.status)) {
            throw new BizException("只有「待上课」的课程可以签到上课（当前为「" + c.status + "」）");
        }
        c.status = "已上课";
        return repo.save(c);
    }

    /** 占用时段的有效课次：待上课 / 已上课；已取消只作历史留痕，不参与重叠校验。 */
    private boolean isActive(String status) {
        return "待上课".equals(status) || "已上课".equals(status);
    }

    /**
     * 固定加锁顺序「学员行 → 课次行」：排课 / 取消 / 签到以及课后记录写操作全部同序，
     * 同学员的写请求在学员行上统一排队，杜绝交叉等待死锁。
     * 先用无锁读拿到学员 id（纯存在性判断），再锁学员行，最后锁课次行；
     * 锁内当前读到的状态即本次判定的最终状态。
     */
    private CourseSchedule lockSchedule(Long id) {
        if (id == null) {
            throw new BizException("缺少课次 id");
        }
        CourseSchedule c = repo.findById(id)
                .orElseThrow(() -> new BizException("排课记录不存在：" + id));
        studentRepo.findByIdForUpdate(c.studentId)
                .orElseThrow(() -> new BizException("学员档案不存在（id=" + c.studentId
                        + "），无法操作该课次，请联系教务核查"));
        return repo.findByIdForUpdate(id)
                .orElseThrow(() -> new BizException("排课记录不存在：" + id));
    }

    private String fmtMin(Integer min) {
        int h = min / 60;
        int m = min % 60;
        return String.format("%02d:%02d", h, m);
    }
}
