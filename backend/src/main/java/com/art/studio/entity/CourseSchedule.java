package com.art.studio.entity;

import java.time.LocalDate;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "course_schedule")
public class CourseSchedule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "student_id", nullable = false)
    public Long studentId;

    @Column(name = "course_name", nullable = false, length = 128)
    public String courseName;

    @Column(name = "date", nullable = false)
    public LocalDate date;

    @Column(name = "start_min", nullable = false)
    public Integer startMin;

    @Column(name = "end_min", nullable = false)
    public Integer endMin;

    @Column(name = "status", length = 16)
    public String status;

    @Column(name = "created_at")
    public LocalDateTime createdAt;
}
