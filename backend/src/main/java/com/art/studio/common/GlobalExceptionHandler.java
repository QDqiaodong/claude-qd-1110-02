package com.art.studio.common;

import java.util.stream.Collectors;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import jakarta.persistence.PessimisticLockException;
import jakarta.persistence.LockTimeoutException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 业务校验失败：400 + 中文业务提示
     */
    @ExceptionHandler(BizException.class)
    public ResponseEntity<ApiResponse<Void>> handleBiz(BizException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.fail(e.getMessage()));
    }

    /**
     * 参数注解校验失败：400 + 字段名与提示
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValid(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("；"));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.fail(message.isEmpty() ? "请求参数校验失败" : message));
    }

    /**
     * 请求体格式错误：400
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotReadable(HttpMessageNotReadableException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.fail("请求体格式有误，请检查 JSON 字段类型"));
    }

    /**
     * 并发冲突：同一学员 / 课次正在被另一个请求处理（行锁等待超时）。
     * 课后记录并发、课次取消与重排并发都会走到这里；客户端重试即可，
     * 后到的请求会基于最终状态走幂等分支 / 重新校验，不会产生重复数据或双占时段。
     */
    @ExceptionHandler({CannotAcquireLockException.class, PessimisticLockException.class,
            LockTimeoutException.class})
    public ResponseEntity<ApiResponse<Void>> handleLock(Exception e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.fail("该学员的排课 / 课次操作正在被另一个请求处理，请稍后重试（系统按最终状态裁决，不会产生重复排课或重复记录）"));
    }

    /**
     * 唯一约束 / 外键冲突：400。课后记录一课次一份由唯一索引兜底，
     * 命中时给出贴近业务的提示，避免抛出原始 SQL 报错。
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleIntegrity(DataIntegrityViolationException e) {
        String root = e.getMostSpecificCause() != null && e.getMostSpecificCause().getMessage() != null
                ? e.getMostSpecificCause().getMessage() : "";
        if (root.contains("uk_cr_schedule") || root.contains("course_record")) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(ApiResponse.fail("该课次已存在课后记录（一份为限），请刷新后在原记录上操作"));
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.fail("存在唯一性约束冲突，请检查编号、手机号是否重复"));
    }

    /**
     * 兜底：500
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleOther(Exception e) {
        String detail = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiResponse.fail("服务异常：" + detail));
    }
}
