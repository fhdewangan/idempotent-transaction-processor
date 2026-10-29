package com.example.transactions.exception;

import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> handleValidation(
            MethodArgumentNotValidException ex) {

        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .collect(Collectors.joining("; "));

        return ResponseEntity.badRequest().body(
                Map.of(
                        "status", "FAILED",
                        "message", message.isBlank()
                                ? "Invalid transaction request"
                                : message
                )
        );
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleBadRequest(
            IllegalArgumentException ex) {

        String message = ex.getMessage() == null
                ? "Invalid transaction request"
                : ex.getMessage();

        HttpStatus status = message.startsWith("Insufficient funds")
                ? HttpStatus.UNPROCESSABLE_ENTITY
                : HttpStatus.BAD_REQUEST;

        return ResponseEntity.status(status).body(
                Map.of(
                        "status", "FAILED",
                        "message", message
                )
        );
    }
}
