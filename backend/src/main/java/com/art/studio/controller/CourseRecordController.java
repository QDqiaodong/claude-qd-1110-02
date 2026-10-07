package com.art.studio.controller;

import java.util.List;

import com.art.studio.common.ApiResponse;
import com.art.studio.dto.CourseRecordView;
import com.art.studio.dto.RecordActionResult;
import com.art.studio.entity.CourseRecord;
import com.art.studio.service.CourseRecordService;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 课后成果记录。主入口为「按课次」：查询 / 存草稿 / 提交 / 归档都挂在课次下，
 * 以课次为幂等键，天然防止一课次出现两份记录。
 */
@RestController
@RequestMapping("/api")
public class CourseRecordController {

    private final CourseRecordService service;

    public CourseRecordController(CourseRecordService service) {
        this.service = service;
    }

    /** 记录列表：可按学员 / 状态 / 课次过滤（学员档案与已完成课程列表使用） */
    @GetMapping("/course-records")
    public ApiResponse<List<CourseRecordView>> list(
            @RequestParam(required = false) Long studentId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long scheduleId) {
        return ApiResponse.success(service.list(studentId, status, scheduleId));
    }

    /** 按课次查当前记录（无记录时返回带课次/学员快照的空壳，status 为 null） */
    @GetMapping("/course-schedules/{scheduleId}/record")
    public ApiResponse<CourseRecordView> getBySchedule(@PathVariable Long scheduleId) {
        return ApiResponse.success(service.getBySchedule(scheduleId));
    }

    /** 保存草稿（不存在则新建，存在且为草稿则继续编辑；草稿允许缺作业内容） */
    @PostMapping("/course-schedules/{scheduleId}/record/draft")
    public ApiResponse<CourseRecordView> saveDraft(
            @PathVariable Long scheduleId,
            @RequestBody(required = false) CourseRecord input) {
        RecordActionResult result = service.saveDraft(scheduleId, input);
        return ApiResponse.success(result.getMessage(), result.getRecord());
    }

    /** 提交（幂等）：重复提交 / 并发提交返回同一份已提交记录与说明 */
    @PostMapping("/course-schedules/{scheduleId}/record/submit")
    public ApiResponse<CourseRecordView> submit(
            @PathVariable Long scheduleId,
            @RequestBody(required = false) CourseRecord input) {
        RecordActionResult result = service.submit(scheduleId, input);
        return ApiResponse.success(result.getMessage(), result.getRecord());
    }

    /** 归档（幂等）：已提交 → 已归档，归档后只读 */
    @PostMapping("/course-schedules/{scheduleId}/record/archive")
    public ApiResponse<CourseRecordView> archive(@PathVariable Long scheduleId) {
        RecordActionResult result = service.archive(scheduleId);
        return ApiResponse.success(result.getMessage(), result.getRecord());
    }

    /** 删除草稿（仅草稿；不影响课次、库存、学员等任何无关数据） */
    @DeleteMapping("/course-schedules/{scheduleId}/record")
    public ApiResponse<Void> deleteDraft(@PathVariable Long scheduleId) {
        service.deleteDraft(scheduleId);
        return ApiResponse.success("草稿已删除", null);
    }
}
