package com.art.studio.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import com.art.studio.entity.CourseSchedule;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

public interface CourseScheduleRepository extends JpaRepository<CourseSchedule, Long> {

    List<CourseSchedule> findByStudentId(Long studentId);

    List<CourseSchedule> findByStatus(String status);

    List<CourseSchedule> findByStudentIdAndStatus(Long studentId, String status);

    List<CourseSchedule> findByStudentIdAndDate(Long studentId, LocalDate date);

    /**
     * 行级写锁：课后记录的新建 / 提交都先锁课次行，
     * 保证同一课次的并发请求在数据库层串行，杜绝两份记录。
     * 锁等待 3 秒即快速失败，由全局异常处理转成 409 可理解提示。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "3000"))
    @Query("SELECT c FROM CourseSchedule c WHERE c.id = :id")
    Optional<CourseSchedule> findByIdForUpdate(@Param("id") Long id);
}
