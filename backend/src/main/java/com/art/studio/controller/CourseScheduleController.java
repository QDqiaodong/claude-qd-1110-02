package com.art.studio.controller;

import java.util.List;

import com.art.studio.common.ApiResponse;
import com.art.studio.dto.ScheduleActionResult;
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
        ScheduleActionResult result = service.createWithResult(input);
        return ApiResponse.success(result.getMessage(), result.getSchedule());
    }

    @PostMapping("/{id}/cancel")
    public ApiResponse<CourseSchedule> cancel(@PathVariable Long id) {
        // 幂等：重复 / 并发取消返回同一条「已取消」课次与说明，前端据此稳定刷新
        ScheduleActionResult result = service.cancel(id);
        return ApiResponse.success(result.getMessage(), result.getSchedule());
    }

    @PostMapping("/{id}/checkin")
    public ApiResponse<CourseSchedule> checkin(@PathVariable Long id) {
        return ApiResponse.success(service.checkin(id));
    }
}
