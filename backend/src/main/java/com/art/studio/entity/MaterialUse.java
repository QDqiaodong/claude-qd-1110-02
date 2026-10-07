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
@Table(name = "material_use")
public class MaterialUse {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "student_id", nullable = false)
    public Long studentId;

    @Column(name = "material_id", nullable = false)
    public Long materialId;

    @Column(name = "qty", nullable = false)
    public Integer qty;

    @Column(name = "use_date", nullable = false)
    public LocalDate useDate;

    @Column(name = "created_at")
    public LocalDateTime createdAt;
}
