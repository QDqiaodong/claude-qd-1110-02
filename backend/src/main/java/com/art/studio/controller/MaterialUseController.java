package com.art.studio.controller;

import java.util.List;

import com.art.studio.common.ApiResponse;
import com.art.studio.entity.MaterialUse;
import com.art.studio.service.MaterialUseService;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/material-uses")
public class MaterialUseController {

    private final MaterialUseService service;

    public MaterialUseController(MaterialUseService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<MaterialUse>> list() {
        return ApiResponse.success(service.list());
    }

    @PostMapping
    public ApiResponse<MaterialUse> create(@RequestBody MaterialUse input) {
        return ApiResponse.success(service.create(input));
    }
}
