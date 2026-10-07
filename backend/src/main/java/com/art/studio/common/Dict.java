package com.art.studio.common;

import java.util.List;

/**
 * 画室业务词典与规则参数表：学员级别 / 学员状态、画材类别 / 画材状态、
 * 课程状态流转。规则集中在此，便于出题时被要求调整或扩展。
 */
public final class Dict {

    private Dict() {
    }

    // ---------------- 学员 ----------------
    /** 少儿 / 成人 / 艺考 */
    public static final List<String> STUDENT_LEVELS = List.of("少儿", "成人", "艺考");

    /** 在读 / 休学 / 结业 */
    public static final List<String> STUDENT_STATUS = List.of("在读", "休学", "结业");

    // ---------------- 画材 ----------------
    /** 颜料 / 画笔 / 画布 / 工具 */
    public static final List<String> MATERIAL_CATEGORIES = List.of("颜料", "画笔", "画布", "工具");

    /** 在库 / 停用 */
    public static final List<String> MATERIAL_STATUS = List.of("在库", "停用");

    // ---------------- 课程排期 ----------------
    /** 待上课 / 已上课 / 已取消 */
    public static final List<String> COURSE_STATUS = List.of("待上课", "已上课", "已取消");

    /** 课程状态流转：待上课 → 已上课（签到）；待上课 → 已取消（取消） */
    public static final List<String> COURSE_FLOW = List.of("待上课", "已上课", "已取消");

    // ---------------- 课后成果记录 ----------------
    /** 草稿 / 已提交 / 已归档 */
    public static final List<String> RECORD_STATUS = List.of("草稿", "已提交", "已归档");

    /** 记录状态流转：草稿 → 已提交（提交）；已提交 → 已归档（归档）；已归档为终态只读 */
    public static final List<String> RECORD_FLOW = List.of("草稿", "已提交", "已归档");

    // ---------------- 取值方法 ----------------

    public static boolean isValidStudentLevel(String level) {
        return level != null && STUDENT_LEVELS.contains(level);
    }

    public static boolean isValidStudentStatus(String status) {
        return status != null && STUDENT_STATUS.contains(status);
    }

    public static boolean isValidMaterialCategory(String category) {
        return category != null && MATERIAL_CATEGORIES.contains(category);
    }

    public static boolean isValidMaterialStatus(String status) {
        return status != null && MATERIAL_STATUS.contains(status);
    }

    public static boolean isValidCourseStatus(String status) {
        return status != null && COURSE_STATUS.contains(status);
    }

    public static boolean isValidRecordStatus(String status) {
        return status != null && RECORD_STATUS.contains(status);
    }

    /**
     * 课程状态机前进：待上课可签到(已上课)或取消(已取消)，已上课/已取消为终态。
     */
    public static boolean canTransferCourse(String from, String to) {
        if (!"待上课".equals(from)) {
            return false;
        }
        return "已上课".equals(to) || "已取消".equals(to);
    }

    /**
     * 成果记录状态机前进：草稿可提交(已提交)，已提交可归档(已归档)，已归档为终态。
     */
    public static boolean canTransferRecord(String from, String to) {
        if ("草稿".equals(from)) {
            return "已提交".equals(to);
        }
        if ("已提交".equals(from)) {
            return "已归档".equals(to);
        }
        return false;
    }
}
