package com.art.studio.repository;

import java.util.List;

import com.art.studio.entity.MaterialUse;

import org.springframework.data.jpa.repository.JpaRepository;

public interface MaterialUseRepository extends JpaRepository<MaterialUse, Long> {

    List<MaterialUse> findByStudentId(Long studentId);
}
