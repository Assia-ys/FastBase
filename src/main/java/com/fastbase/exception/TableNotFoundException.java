package com.fastbase.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public class TableNotFoundException extends ResponseStatusException {
    public TableNotFoundException(String tableName) {
        super(HttpStatus.NOT_FOUND, "La table '" + tableName + "' n'existe pas");
    }
}
