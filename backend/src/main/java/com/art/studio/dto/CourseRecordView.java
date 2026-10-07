package com.art.studio.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 课后成果记录视图：在记录本体之外附带课次与学员的当前快照，
 * 供课程详情与学员档案直接展示「当前状态 + 最后更新时间」。
 */
public class CourseRecordView {

    // ---------------- 记录本体 ----------------
    public Long id;
    public Long scheduleId;
    public Long studentId;
    public String workTopic;
    public Integer completion;
    public String teacherComment;
    public String nextPractice;
    /** 草稿 / 已提交 / 已归档 */
    public String status;
    public LocalDateTime submittedAt;
    public LocalDateTime archivedAt;
    public LocalDateTime createdAt;
    public LocalDateTime updatedAt;

    // ---------------- 课次快照 ----------------
    public String courseName;
    public LocalDate courseDate;
    public Integer startMin;
    public Integer endMin;
    /** 待上课 / 已上课 / 已取消 */
    public String scheduleStatus;

    // ---------------- 学员快照（提交时校验仍存在；状态变化不影响结课） ----------------
    public String studentCode;
    public String studentName;
    public String studentStatus;
}
