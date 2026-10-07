package com.art.studio.repository;

import java.util.List;

import com.art.studio.entity.ArtMaterial;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ArtMaterialRepository extends JpaRepository<ArtMaterial, Long> {

    java.util.Optional<ArtMaterial> findByName(String name);

    List<ArtMaterial> findByCategory(String category);

    List<ArtMaterial> findByStatus(String status);

    List<ArtMaterial> findByCategoryAndStatus(String category, String status);
}
