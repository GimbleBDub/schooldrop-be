package com.gimble.bus_stop.exceptions;

public class PhoneAlreadyExistsException extends RuntimeException {
    public PhoneAlreadyExistsException() {
        super("Phone number already registered");
    }
}
