package com.art.studio.service;

import java.time.LocalDateTime;
import java.util.List;

import com.art.studio.common.BizException;
import com.art.studio.common.Dict;
import com.art.studio.entity.CourseSchedule;
import com.art.studio.entity.Student;
import com.art.studio.repository.CourseScheduleRepository;
import com.art.studio.repository.StudentRepository;

import org.springframework.stereotype.Service;

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
        if (input.studentId == null) {
            throw new BizException("排课必须指定学员");
        }
        Student st = studentRepo.findById(input.studentId)
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
        // 同学员同日期时段不重叠：a.startMin<b.endMin && b.startMin<a.endMin
        List<CourseSchedule> same = repo.findByStudentIdAndDate(input.studentId, input.date);
        for (CourseSchedule c : same) {
            if (input.startMin < c.endMin && c.startMin < input.endMin) {
                throw new BizException("该学员在 " + input.date + " 已有重叠时段（"
                        + c.courseName + " " + c.startMin + "-" + c.endMin + "）");
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

    public CourseSchedule cancel(Long id) {
        CourseSchedule c = repo.findById(id).orElseThrow(() -> new BizException("排课记录不存在：" + id));
        if (!"待上课".equals(c.status)) {
            throw new BizException("只有「待上课」的课程可以取消（当前为「" + c.status + "」）");
        }
        c.status = "已取消";
        return repo.save(c);
    }

    public CourseSchedule checkin(Long id) {
        CourseSchedule c = repo.findById(id).orElseThrow(() -> new BizException("排课记录不存在：" + id));
        if (!"待上课".equals(c.status)) {
            throw new BizException("只有「待上课」的课程可以签到上课（当前为「" + c.status + "」）");
        }
        c.status = "已上课";
        return repo.save(c);
    }
}
