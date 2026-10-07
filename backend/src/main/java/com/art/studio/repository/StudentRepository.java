package com.art.studio.repository;

import java.util.List;

import com.art.studio.entity.Student;

import org.springframework.data.jpa.repository.JpaRepository;

public interface StudentRepository extends JpaRepository<Student, Long> {

    java.util.Optional<Student> findByCode(String code);

    List<Student> findByStatus(String status);

    List<Student> findByLevel(String level);

    List<Student> findByStatusAndLevel(String status, String level);
}
