package com.art.studio.controller;

import java.util.List;

import com.art.studio.common.ApiResponse;
import com.art.studio.entity.Student;
import com.art.studio.service.StudentService;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/students")
public class StudentController {

    private final StudentService service;

    public StudentController(StudentService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<Student>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String level) {
        return ApiResponse.success(service.list(status, level));
    }

    @PostMapping
    public ApiResponse<Student> create(@RequestBody Student input) {
        return ApiResponse.success(service.create(input));
    }

    @PutMapping("/{id}")
    public ApiResponse<Student> update(@PathVariable Long id, @RequestBody Student input) {
        return ApiResponse.success(service.update(id, input));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ApiResponse.success("学员已删除", null);
    }
}
