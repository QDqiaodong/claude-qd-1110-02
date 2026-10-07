package com.art.studio.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.art.studio.common.BizException;
import com.art.studio.dto.CourseRecordView;
import com.art.studio.dto.RecordActionResult;
import com.art.studio.entity.CourseRecord;
import com.art.studio.entity.CourseSchedule;
import com.art.studio.entity.Student;
import com.art.studio.repository.CourseRecordRepository;
import com.art.studio.repository.CourseScheduleRepository;
import com.art.studio.repository.StudentRepository;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 课后成果记录闭环。
 *
 * 并发收口策略（三重防线）：
 *   1. 所有写操作都在事务内先对课次行加写锁（PESSIMISTIC_WRITE），同课次请求在 DB 层串行；
 *   2. 串行区内做「查记录 → 校验状态机 → 落库」，重复请求走幂等分支返回同一结果；
 *   3. course_record.schedule_id 唯一索引兜底，任何漏网并发在落库时被挡下并转成业务提示。
 *
 * 业务边界：记录服务只读写 course_record，不触碰库存、学员状态等无关业务；
 * 取消/删除草稿不会对 course_schedule / art_material / student 产生任何副作用。
 */
@Service
public class CourseRecordService {

    private final CourseRecordRepository repo;
    private final CourseScheduleRepository scheduleRepo;
    private final StudentRepository studentRepo;

    public CourseRecordService(CourseRecordRepository repo,
            CourseScheduleRepository scheduleRepo, StudentRepository studentRepo) {
        this.repo = repo;
        this.scheduleRepo = scheduleRepo;
        this.studentRepo = studentRepo;
    }

    // ---------------------------------------------------------------- 查询

    public CourseRecordView getBySchedule(Long scheduleId) {
        CourseSchedule schedule = scheduleRepo.findById(scheduleId)
                .orElseThrow(() -> new BizException("课次不存在：" + scheduleId));
        Student student = studentRepo.findById(schedule.studentId).orElse(null);
        return repo.findByScheduleId(scheduleId)
                .map((r) -> toView(r, schedule, student))
                .orElseGet(() -> emptyView(schedule));
    }

    public List<CourseRecordView> list(Long studentId, String status, Long scheduleId) {
        List<CourseRecord> records;
        if (scheduleId != null) {
            records = repo.findByScheduleId(scheduleId).map(List::of).orElseGet(List::of);
        } else if (studentId != null && status != null) {
            records = repo.findByStudentIdAndStatus(studentId, status);
        } else if (studentId != null) {
            records = repo.findByStudentId(studentId);
        } else if (status != null) {
            records = repo.findByStatus(status);
        } else {
            records = repo.findAll();
        }
        Map<Long, CourseSchedule> schedules = new HashMap<>();
        for (CourseSchedule s : scheduleRepo.findAllById(scheduleIdsOf(records))) {
            schedules.put(s.id, s);
        }
        Map<Long, Student> students = new HashMap<>();
        for (Student s : studentRepo.findAllById(records.stream().map((r) -> r.studentId).distinct().toList())) {
            students.put(s.id, s);
        }
        List<CourseRecordView> views = new ArrayList<>();
        for (CourseRecord r : records) {
            views.add(toView(r, schedules.get(r.scheduleId), students.get(r.studentId)));
        }
        return views;
    }

    private List<Long> scheduleIdsOf(List<CourseRecord> records) {
        return records.stream().map((r) -> r.scheduleId).toList();
    }

    // ---------------------------------------------------------------- 保存草稿

    /**
     * 新建或继续编辑草稿。
     * 草稿允许只有教师点评、作业信息缺失；已提交/已归档只读，不允许再改。
     */
    @Transactional
    public RecordActionResult saveDraft(Long scheduleId, CourseRecord input) {
        CourseSchedule schedule = lockSchedule(scheduleId);
        requireTaught(schedule);
        Student student = studentRepo.findById(schedule.studentId)
                .orElseThrow(() -> new BizException("学员档案不存在（id=" + schedule.studentId
                        + "），无法保存课后记录，请先确认学员档案"));

        CourseRecord r = repo.findByScheduleId(scheduleId).orElse(null);
        boolean isNew = r == null;
        if (isNew) {
            if (input == null || !hasAnyContent(input)) {
                throw new BizException("草稿内容不能全空，请至少填写教师点评或一项作业信息");
            }
            r = new CourseRecord();
            r.scheduleId = scheduleId;
            r.studentId = schedule.studentId;
            r.status = "草稿";
            r.createdAt = LocalDateTime.now();
        } else {
            requireEditable(r, "保存");
        }

        applyFields(r, input);
        validateFields(r);
        r.updatedAt = LocalDateTime.now();
        try {
            r = repo.saveAndFlush(r);
        } catch (DataIntegrityViolationException e) {
            // 唯一索引兜底：并发下两边都走了新建分支
            CourseRecord existing = repo.findByScheduleId(scheduleId).orElseThrow(() -> e);
            throw new BizException("该课次已存在课后记录（当前为「" + existing.status + "」），请刷新后在原记录上继续编辑");
        }
        String msg = isNew ? "草稿已保存，可稍后补齐作业信息再提交" : "草稿已更新";
        return RecordActionResult.changed(toView(r, schedule, student), msg);
    }

    // ---------------------------------------------------------------- 提交

    /**
     * 提交（幂等）。同一课次重复点击提交、两次同时提交，都得到同一份已提交记录：
     * 草稿 → 校验课次仍为「已上课」、学员档案仍存在、作业三要素齐全 → 已提交；
     * 已是「已提交/已归档」则原样返回，changed=false，不产生第二份记录。
     */
    @Transactional
    public RecordActionResult submit(Long scheduleId, CourseRecord input) {
        CourseSchedule schedule = lockSchedule(scheduleId);
        requireTaught(schedule);
        Student student = studentRepo.findById(schedule.studentId)
                .orElseThrow(() -> new BizException("学员档案不存在（id=" + schedule.studentId
                        + "），无法提交课后记录（如学员已删除请联系教务核查）"));

        CourseRecord r = repo.findByScheduleId(scheduleId).orElse(null);
        if (r == null) {
            // 允许「带内容直接提交」：相当于先建草稿再提交，仍在同一事务、同一把课次锁内
            if (input == null || !hasAnyContent(input)) {
                throw new BizException("请先填写课后记录内容再提交（作品主题、完成度、教师点评均为必填）");
            }
            r = new CourseRecord();
            r.scheduleId = scheduleId;
            r.studentId = schedule.studentId;
            r.status = "草稿";
            r.createdAt = LocalDateTime.now();
            applyFields(r, input);
        } else if ("草稿".equals(r.status)) {
            // 前端通常已先存草稿；若提交时带了新内容，一并合并，保证「补齐后一次提交」原子完成
            if (input != null) {
                applyFields(r, input);
            }
        } else {
            // 幂等收口：重复点击 / 并发的第二个请求
            return RecordActionResult.same(toView(r, schedule, student),
                    "该课次的课后记录已" + r.status + "，无需重复提交");
        }

        List<String> missing = missingRequired(r);
        if (!missing.isEmpty()) {
            throw new BizException("提交前请补齐作业信息：" + String.join("、", missing));
        }
        validateFields(r);

        LocalDateTime now = LocalDateTime.now();
        r.status = "已提交";
        r.submittedAt = now;
        r.updatedAt = now;
        try {
            r = repo.saveAndFlush(r);
        } catch (DataIntegrityViolationException e) {
            CourseRecord existing = repo.findByScheduleId(scheduleId).orElseThrow(() -> e);
            return RecordActionResult.same(toView(existing, schedule, student),
                    "该课次的课后记录已" + existing.status + "，无需重复提交");
        }
        return RecordActionResult.changed(toView(r, schedule, student), "课后记录已提交，课次结课资料齐全");
    }

    // ---------------------------------------------------------------- 归档

    /** 已提交 → 已归档（幂等）；归档后只读。 */
    @Transactional
    public RecordActionResult archive(Long scheduleId) {
        CourseSchedule schedule = lockSchedule(scheduleId);
        CourseRecord r = repo.findByScheduleId(scheduleId)
                .orElseThrow(() -> new BizException("该课次还没有课后记录，无法归档"));
        if ("已归档".equals(r.status)) {
            return RecordActionResult.same(toView(r, schedule,
                    studentRepo.findById(schedule.studentId).orElse(null)), "记录已归档，内容只读");
        }
        if (!"已提交".equals(r.status)) {
            throw new BizException("只有「已提交」的记录可以归档（当前为「" + r.status + "」），请先补齐并提交");
        }
        LocalDateTime now = LocalDateTime.now();
        r.status = "已归档";
        r.archivedAt = now;
        r.updatedAt = now;
        r = repo.save(r);
        return RecordActionResult.changed(toView(r, schedule,
                studentRepo.findById(schedule.studentId).orElse(null)), "已归档，记录转为只读");
    }

    // ---------------------------------------------------------------- 删除草稿

    /**
     * 仅允许删除草稿（教师放弃这一条）。只删 course_record 本行，
     * 课次状态、画材库存、学员状态等一律不动；已提交/已归档不可删除。
     */
    @Transactional
    public void deleteDraft(Long scheduleId) {
        CourseSchedule schedule = lockSchedule(scheduleId);
        CourseRecord r = repo.findByScheduleId(scheduleId)
                .orElseThrow(() -> new BizException("该课次没有可删除的课后记录"));
        if (!"草稿".equals(r.status)) {
            throw new BizException("只有「草稿」可以删除（当前为「" + r.status + "」），已提交记录请走归档");
        }
        repo.delete(r);
    }

    // ---------------------------------------------------------------- 校验与装配

    /** 锁课次行：把同课次的并发写请求在数据库层串行化。 */
    private CourseSchedule lockSchedule(Long scheduleId) {
        if (scheduleId == null) {
            throw new BizException("缺少课次 id");
        }
        return scheduleRepo.findByIdForUpdate(scheduleId)
                .orElseThrow(() -> new BizException("课次不存在：" + scheduleId));
    }

    /** 只有「已上课」课次允许新建/提交记录；待上课、已取消明确拒绝，不留半条数据。 */
    private void requireTaught(CourseSchedule schedule) {
        if ("待上课".equals(schedule.status)) {
            throw new BizException("该课次尚未上课（待上课），暂不能填写课后记录，请先签到上课");
        }
        if ("已取消".equals(schedule.status)) {
            throw new BizException("该课次已取消，不能新建或提交课后记录");
        }
        if (!"已上课".equals(schedule.status)) {
            throw new BizException("课次当前状态为「" + schedule.status + "」，不能填写课后记录");
        }
    }

    private void requireEditable(CourseRecord r, String action) {
        if ("已归档".equals(r.status)) {
            throw new BizException("记录已归档，内容只读，不能再" + action);
        }
        if ("已提交".equals(r.status)) {
            throw new BizException("记录已提交，不能直接修改；如需定稿请由教务归档");
        }
    }

    private boolean hasAnyContent(CourseRecord in) {
        return notBlank(in.workTopic) || in.completion != null
                || notBlank(in.teacherComment) || notBlank(in.nextPractice);
    }

    private List<String> missingRequired(CourseRecord r) {
        List<String> missing = new ArrayList<>();
        if (!notBlank(r.workTopic)) {
            missing.add("作品主题");
        }
        if (r.completion == null) {
            missing.add("完成度");
        }
        if (!notBlank(r.teacherComment)) {
            missing.add("教师点评");
        }
        return missing;
    }

    private void applyFields(CourseRecord r, CourseRecord in) {
        if (in == null) {
            return;
        }
        if (in.workTopic != null) {
            r.workTopic = in.workTopic.trim().isEmpty() ? null : in.workTopic.trim();
        }
        if (in.completion != null) {
            r.completion = in.completion;
        }
        if (in.teacherComment != null) {
            r.teacherComment = in.teacherComment.trim().isEmpty() ? null : in.teacherComment.trim();
        }
        if (in.nextPractice != null) {
            r.nextPractice = in.nextPractice.trim().isEmpty() ? null : in.nextPractice.trim();
        }
    }

    private void validateFields(CourseRecord r) {
        if (r.workTopic != null && r.workTopic.length() > 128) {
            throw new BizException("作品主题不能超过 128 字");
        }
        if (r.completion != null && (r.completion < 0 || r.completion > 100)) {
            throw new BizException("完成度须在 0-100 之间");
        }
        if (r.teacherComment != null && r.teacherComment.length() > 1000) {
            throw new BizException("教师点评不能超过 1000 字");
        }
        if (r.nextPractice != null && r.nextPractice.length() > 1000) {
            throw new BizException("下次练习不能超过 1000 字");
        }
    }

    private boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private CourseRecordView emptyView(CourseSchedule schedule) {
        CourseRecordView v = new CourseRecordView();
        v.scheduleId = schedule.id;
        v.studentId = schedule.studentId;
        v.courseName = schedule.courseName;
        v.courseDate = schedule.date;
        v.startMin = schedule.startMin;
        v.endMin = schedule.endMin;
        v.scheduleStatus = schedule.status;
        studentRepo.findById(schedule.studentId).ifPresent((s) -> {
            v.studentCode = s.code;
            v.studentName = s.name;
            v.studentStatus = s.status;
        });
        return v;
    }

    private CourseRecordView toView(CourseRecord r, CourseSchedule schedule, Student student) {
        CourseRecordView v = new CourseRecordView();
        v.id = r.id;
        v.scheduleId = r.scheduleId;
        v.studentId = r.studentId;
        v.workTopic = r.workTopic;
        v.completion = r.completion;
        v.teacherComment = r.teacherComment;
        v.nextPractice = r.nextPractice;
        v.status = r.status;
        v.submittedAt = r.submittedAt;
        v.archivedAt = r.archivedAt;
        v.createdAt = r.createdAt;
        v.updatedAt = r.updatedAt;
        if (schedule != null) {
            v.courseName = schedule.courseName;
            v.courseDate = schedule.date;
            v.startMin = schedule.startMin;
            v.endMin = schedule.endMin;
            v.scheduleStatus = schedule.status;
        }
        if (student != null) {
            v.studentCode = student.code;
            v.studentName = student.name;
            v.studentStatus = student.status;
        }
        return v;
    }
}
