package com.gimble.bus_stop.exceptions;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/** A request that is well-formed but breaks a business rule (wrong state, no seats left, ...). */
@Getter
public class BusinessException extends RuntimeException {

    private final HttpStatus status;

    public BusinessException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public static BusinessException badRequest(String message) {
        return new BusinessException(HttpStatus.BAD_REQUEST, message);
    }

    public static BusinessException conflict(String message) {
        return new BusinessException(HttpStatus.CONFLICT, message);
    }
}
