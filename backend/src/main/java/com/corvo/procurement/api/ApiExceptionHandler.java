package com.corvo.procurement.api;

import com.corvo.procurement.ai.AiUnavailableException;
import com.corvo.procurement.policy.amendment.PolicyValidationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(PolicyValidationException.class)
    public ResponseEntity<Map<String, Object>> policyValidation(PolicyValidationException e) {
        return error(HttpStatus.BAD_REQUEST, "POLICY_VALIDATION", e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> illegalArgument(IllegalArgumentException e) {
        return error(HttpStatus.BAD_REQUEST, "BAD_REQUEST", e.getMessage());
    }

    @ExceptionHandler(AiUnavailableException.class)
    public ResponseEntity<Map<String, Object>> aiUnavailable(AiUnavailableException e) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "AI_UNAVAILABLE", e.getMessage());
    }

    private ResponseEntity<Map<String, Object>> error(HttpStatus status, String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", code);
        body.put("message", message);
        return ResponseEntity.status(status).body(body);
    }
}
