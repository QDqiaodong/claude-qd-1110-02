package com.art.studio.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 课后成果记录：依附于课次（schedule_id 唯一），同一课次全局只有一份。
 * 状态：草稿 → 已提交 → 已归档；归档后只读。
 */
@Entity
@Table(name = "course_record")
public class CourseRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "schedule_id", nullable = false, unique = true)
    public Long scheduleId;

    @Column(name = "student_id", nullable = false)
    public Long studentId;

    /** 本次作品主题：草稿可空，提交必填 */
    @Column(name = "work_topic", length = 128)
    public String workTopic;

    /** 完成度 0-100：草稿可空，提交必填 */
    @Column(name = "completion")
    public Integer completion;

    /** 教师点评：草稿可空（允许先快速记一条点评），提交必填 */
    @Column(name = "teacher_comment", length = 1000)
    public String teacherComment;

    /** 下次练习：选填 */
    @Column(name = "next_practice", length = 1000)
    public String nextPractice;

    @Column(name = "status", nullable = false, length = 16)
    public String status;

    @Column(name = "submitted_at")
    public LocalDateTime submittedAt;

    @Column(name = "archived_at")
    public LocalDateTime archivedAt;

    @Column(name = "created_at")
    public LocalDateTime createdAt;

    @Column(name = "updated_at")
    public LocalDateTime updatedAt;
}
