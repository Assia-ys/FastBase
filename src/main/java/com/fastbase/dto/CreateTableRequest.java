package com.fastbase.dto;

import com.fastbase.model.Column;

import java.util.List;

/**
 * DTO pour la création d'une table via l'API REST -> represente la demande du client .Spring Boot le crée automatiquement à partir du JSON via la librairie Jackson
 */
public class CreateTableRequest {

    private String tableName;
    private List<Column> columns;

    public CreateTableRequest() {
    }

    public CreateTableRequest(String tableName, List<Column> columns) {
        this.tableName = tableName;
        this.columns = columns;
    }

    public String getTableName() {
        return tableName;
    }

    public void setTableName(String tableName) {
        this.tableName = tableName;
    }

    public List<Column> getColumns() {
        return columns;
    }

    public void setColumns(List<Column> columns) {
        this.columns = columns;
    }

    @Override
    public String toString() {
        return "CreateTableRequest{tableName='" + tableName + "', columns=" + columns + "}";
    }
}
