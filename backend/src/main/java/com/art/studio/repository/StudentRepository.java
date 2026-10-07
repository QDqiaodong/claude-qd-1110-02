package com.art.studio.repository;

import java.util.List;
import java.util.Optional;

import com.art.studio.entity.Student;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

public interface StudentRepository extends JpaRepository<Student, Long> {

    java.util.Optional<Student> findByCode(String code);

    List<Student> findByStatus(String status);

    List<Student> findByLevel(String level);

    List<Student> findByStatusAndLevel(String status, String level);

    /**
     * 学员行行级写锁：排课 / 取消 / 签到先锁学员，把同一学员的所有写操作串行化，
     * 保证「取消与重排同时发生」时按最终落库状态裁决，既不会已取消仍被拦截，
     * 也不会并发排出两条占用同一时段的有效课次。锁等待 3 秒快速失败转 409。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "3000"))
    @Query("SELECT s FROM Student s WHERE s.id = :id")
    Optional<Student> findByIdForUpdate(@Param("id") Long id);

    /**
     * 按课次 id 直接锁其学员行（子查询定位，课次此前不读、不入一级缓存）：
     * 取消 / 签到先拿学员锁，随后首次读取课次行即为最终落库状态，
     * 避免「无锁预读 → 缓存旧状态 → 取消后仍按待上课推进」的状态翻转。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "3000"))
    @Query("SELECT s FROM Student s WHERE s.id = "
            + "(SELECT c.studentId FROM CourseSchedule c WHERE c.id = :scheduleId)")
    Optional<Student> findByScheduleIdForUpdate(@Param("scheduleId") Long scheduleId);
}
