package com.art.studio.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "art_material")
public class ArtMaterial {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "name", nullable = false, unique = true, length = 128)
    public String name;

    @Column(name = "category", length = 16)
    public String category;

    @Column(name = "stock", nullable = false)
    public Integer stock;

    @Column(name = "unit", length = 16)
    public String unit;

    @Column(name = "status", length = 16)
    public String status;

    @Column(name = "created_at")
    public LocalDateTime createdAt;
}
