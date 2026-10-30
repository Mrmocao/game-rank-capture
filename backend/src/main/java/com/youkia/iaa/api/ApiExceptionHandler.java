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
}
