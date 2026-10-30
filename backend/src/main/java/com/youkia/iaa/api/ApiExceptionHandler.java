package com.youkia.iaa.api;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(ApiException.class)
    ResponseEntity<?> handle(ApiException ex, HttpServletRequest req) {
        return ResponseEntity.status(ex.status()).body(Map.of("error", Map.of("code", ex.code(), "message", ex.getMessage(), "fieldErrors", List.of()), "requestId", UUID.randomUUID().toString()));
    }
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<?> handleBad(IllegalArgumentException ex) { return ResponseEntity.badRequest().body(Map.of("error", Map.of("code","INVALID_ARGUMENT","message",ex.getMessage(),"fieldErrors",List.of()),"requestId",UUID.randomUUID().toString())); }
    @ExceptionHandler({java.time.DateTimeException.class,org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,org.springframework.web.bind.MissingServletRequestParameterException.class,org.springframework.http.converter.HttpMessageNotReadableException.class,org.springframework.web.bind.MethodArgumentNotValidException.class})
    ResponseEntity<?> handleMalformed(Exception ex){return ResponseEntity.badRequest().body(Map.of("error",Map.of("code","INVALID_ARGUMENT","message","参数、日期、JSON或必填字段无效，请按接口定义提交","fieldErrors",List.of()),"requestId",UUID.randomUUID().toString()));}
}
