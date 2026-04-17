package com.fastbase.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public class TableAlreadyExistsException extends ResponseStatusException {
    public TableAlreadyExistsException(String tableName) {
        super(HttpStatus.CONFLICT, "La table '" + tableName + "' existe déjà");
    }
}
