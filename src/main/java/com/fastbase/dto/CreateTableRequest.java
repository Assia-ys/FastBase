package com.fastbase.dto;

import com.fastbase.model.Column;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public class CreateTableRequest {

    @NotBlank(message = "Le nom de la table est obligatoire")
    private String tableName;

    @NotEmpty(message = "La table doit avoir au moins une colonne")
    private List<Column> columns;

    public CreateTableRequest() {}

    public String getTableName()              { return tableName; }
    public void setTableName(String t)        { this.tableName = t; }
    public List<Column> getColumns()          { return columns; }
    public void setColumns(List<Column> cols) { this.columns = cols; }
}