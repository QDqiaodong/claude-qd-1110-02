package com.art.studio.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "student")
public class Student {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "code", nullable = false, unique = true, length = 32)
    public String code;

    @Column(name = "name", nullable = false, length = 64)
    public String name;

    @Column(name = "phone", length = 24)
    public String phone;

    @Column(name = "level", length = 16)
    public String level;

    @Column(name = "status", length = 16)
    public String status;

    @Column(name = "created_at")
    public LocalDateTime createdAt;
}
