package com.fastbase.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public class InvalidQueryException extends ResponseStatusException {
    public InvalidQueryException(String detail) {
        super(HttpStatus.BAD_REQUEST, detail);
    }
}
