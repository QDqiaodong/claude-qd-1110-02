package com.art.studio.repository;

import java.util.List;
import java.util.Optional;

import com.art.studio.entity.CourseRecord;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CourseRecordRepository extends JpaRepository<CourseRecord, Long> {

    Optional<CourseRecord> findByScheduleId(Long scheduleId);

    List<CourseRecord> findByStudentId(Long studentId);

    List<CourseRecord> findByStudentIdAndStatus(Long studentId, String status);

    List<CourseRecord> findByStatus(String status);
}
