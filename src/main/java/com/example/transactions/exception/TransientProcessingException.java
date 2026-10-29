package com.example.transactions.exception;

public class TransientProcessingException extends RuntimeException {

    public TransientProcessingException(String message) {
        super(message);
    }
}
