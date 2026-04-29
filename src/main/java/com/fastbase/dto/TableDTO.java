package com.fastbase.dto;

import com.fastbase.model.Column;

import java.util.List;

public class TableDTO {

    private String tableName;
    private List<Column> columns;

    public TableDTO() {}

    public TableDTO(String tableName, List<Column> columns) {
        this.tableName = tableName;
        this.columns   = columns;
    }

    public String getTableName()              { return tableName; }
    public void setTableName(String t)        { this.tableName = t; }
    public List<Column> getColumns()          { return columns; }
    public void setColumns(List<Column> cols) { this.columns = cols; }

    @Override
    public String toString() {
        return "TableDTO{tableName='" + tableName + "', columns=" + columns + "}";
    }
}

