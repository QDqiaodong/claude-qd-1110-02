package com.art.studio.controller;

import java.util.List;

import com.art.studio.common.ApiResponse;
import com.art.studio.entity.CourseSchedule;
import com.art.studio.service.CourseScheduleService;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/course-schedules")
public class CourseScheduleController {

    private final CourseScheduleService service;

    public CourseScheduleController(CourseScheduleService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<CourseSchedule>> list(
            @RequestParam(required = false) Long studentId,
            @RequestParam(required = false) String status) {
        return ApiResponse.success(service.list(studentId, status));
    }

    @PostMapping
    public ApiResponse<CourseSchedule> create(@RequestBody CourseSchedule input) {
        return ApiResponse.success(service.create(input));
    }

    @PostMapping("/{id}/cancel")
    public ApiResponse<CourseSchedule> cancel(@PathVariable Long id) {
        return ApiResponse.success(service.cancel(id));
    }

    @PostMapping("/{id}/checkin")
    public ApiResponse<CourseSchedule> checkin(@PathVariable Long id) {
        return ApiResponse.success(service.checkin(id));
    }
}
