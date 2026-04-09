package com.fastbase.dto;

import com.fastbase.model.Column;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * DTO pour la création d'une table via l'API REST -> represente la demande du client .Spring Boot le crée automatiquement à partir du JSON via la librairie Jackson
 */
@Getter
@Setter
public class CreateTableRequest {

    private String tableName;
    private List<Column> columns;


    public CreateTableRequest(String tableName, List<Column> columns) {
        this.tableName = tableName;
        this.columns = columns;
    }
    @Override
    public String toString() {
        return "CreateTableRequest{tableName='" + tableName + "', columns=" + columns + "}";
    }
}
