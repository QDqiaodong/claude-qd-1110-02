package com.art.studio.dto;

/**
 * 会改写记录状态的操作（保存 / 提交 / 归档）结果：
 * data 为最新记录视图，message 区分「本次完成」与「重复请求幂等命中」，
 * 使重复点击 / 并发提交都拿到同一份数据且能给出可理解的原因。
 */
public class RecordActionResult {

    private final CourseRecordView record;
    private final String message;
    /** true = 本次真正发生了状态推进；false = 重复请求，直接返回既有结果 */
    private final boolean changed;

    public RecordActionResult(CourseRecordView record, String message, boolean changed) {
        this.record = record;
        this.message = message;
        this.changed = changed;
    }

    public static RecordActionResult changed(CourseRecordView record, String message) {
        return new RecordActionResult(record, message, true);
    }

    public static RecordActionResult same(CourseRecordView record, String message) {
        return new RecordActionResult(record, message, false);
    }

    public CourseRecordView getRecord() {
        return record;
    }

    public String getMessage() {
        return message;
    }

    public boolean isChanged() {
        return changed;
    }
}
