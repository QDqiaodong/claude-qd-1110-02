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

    Optional<Student> findByCode(String code);

    List<Student> findByStatus(String status);

    List<Student> findByLevel(String level);

    List<Student> findByStatusAndLevel(String status, String level);

    /**
     * 行级写锁：同一学员的排课 / 取消 / 签到都先锁学员行，把同学员的并发写
     * 在数据库层串行化，保证「取消与重排同时发生」时按最终状态稳定判定。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "3000"))
    @Query("SELECT s FROM Student s WHERE s.id = :id")
    Optional<Student> findByIdForUpdate(@Param("id") Long id);
}
