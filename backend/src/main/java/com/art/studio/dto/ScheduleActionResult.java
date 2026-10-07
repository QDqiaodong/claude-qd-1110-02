package com.art.studio.dto;

import com.art.studio.entity.CourseSchedule;

/**
 * 会改写课次状态的操作（取消 / 签到）结果：
 * data 为最新课次，message 区分「本次完成」与「重复请求幂等命中」，
 * 使重复取消 / 并发取消都拿到同一条已取消课次及其可读原因。
 */
public class ScheduleActionResult {

    private final CourseSchedule schedule;
    private final String message;
    /** true = 本次真正发生了状态推进；false = 重复请求，直接返回既有结果 */
    private final boolean changed;

    public ScheduleActionResult(CourseSchedule schedule, String message, boolean changed) {
        this.schedule = schedule;
        this.message = message;
        this.changed = changed;
    }

    public static ScheduleActionResult changed(CourseSchedule schedule, String message) {
        return new ScheduleActionResult(schedule, message, true);
    }

    public static ScheduleActionResult same(CourseSchedule schedule, String message) {
        return new ScheduleActionResult(schedule, message, false);
    }

    public CourseSchedule getSchedule() {
        return schedule;
    }

    public String getMessage() {
        return message;
    }

    public boolean isChanged() {
        return changed;
    }
}
