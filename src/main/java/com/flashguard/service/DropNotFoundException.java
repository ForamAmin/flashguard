package com.flashguard.service;

public class DropNotFoundException extends RuntimeException {
    public DropNotFoundException(String message) {
        super(message);
    }
}