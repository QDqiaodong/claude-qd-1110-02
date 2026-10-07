package com.art.studio.controller;

import java.util.List;

import com.art.studio.common.ApiResponse;
import com.art.studio.entity.ArtMaterial;
import com.art.studio.service.ArtMaterialService;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/art-materials")
public class ArtMaterialController {

    private final ArtMaterialService service;

    public ArtMaterialController(ArtMaterialService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<ArtMaterial>> list(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String status) {
        return ApiResponse.success(service.list(category, status));
    }

    @PostMapping
    public ApiResponse<ArtMaterial> create(@RequestBody ArtMaterial input) {
        return ApiResponse.success(service.create(input));
    }

    @PutMapping("/{id}")
    public ApiResponse<ArtMaterial> update(@PathVariable Long id, @RequestBody ArtMaterial input) {
        return ApiResponse.success(service.update(id, input));
    }
}
