package com.geek.association.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * Turns validation failures into a 400 with a readable message.
 *
 * Without this a rejected source or a missing directory surfaces as a generic 500 and the UI can
 * only say "Internal Server Error". The message is what the user actually needs.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException e) {
        String message = e.getMessage() == null || e.getMessage().isBlank()
                ? "请求参数不正确" : e.getMessage();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", message));
    }
}
