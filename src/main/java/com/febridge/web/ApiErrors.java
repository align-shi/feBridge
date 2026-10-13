package com.febridge.web;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class ApiErrors {

    @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
    public ResponseEntity<ProblemDetail> status(
        org.springframework.web.server.ResponseStatusException exception
    ) {
        return ResponseEntity.status(exception.getStatusCode()).body(
            ProblemDetail.forStatusAndDetail(
                exception.getStatusCode(),
                exception.getReason() == null ? "请求失败" : exception.getReason()
            )
        );
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ProblemDetail> conflict() {
        var problem = ProblemDetail.forStatusAndDetail(
            HttpStatus.CONFLICT,
            "数据冲突：请检查模型标识是否重复或关联记录是否存在"
        );
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> invalid(MethodArgumentNotValidException exception) {
        var detail = exception
            .getBindingResult()
            .getAllErrors()
            .stream()
            .map(e -> e.getDefaultMessage())
            .findFirst()
            .orElse("参数无效");
        return ResponseEntity.badRequest().body(
            ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail)
        );
    }
}
