package com.fastbase.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Représente une table dans FastBase
 */
public class Table {

    private String name;
    private List<Column> columns;
    private List<Row> rows;

    public Table() {
        this.columns = new ArrayList<>();
        this.rows = new ArrayList<>();
    }

    public Table(String name, List<Column> columns) {
        this.name = name;
        this.columns = columns != null ? columns : new ArrayList<>();
        this.rows = new ArrayList<>();
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public List<Column> getColumns() {
        return columns;
    }

    public void setColumns(List<Column> columns) {
        this.columns = columns;
    }

    public List<Row> getRows() {
        return rows;
    }

    public void setRows(List<Row> rows) {
        this.rows = rows;
    }

    public void addRow(Row row) {
        this.rows.add(row);
    }

    public int getRowCount() {
        return rows.size();
    }

    @Override
    public String toString() {
        return "Table{name='" + name + "', columns=" + columns.size() + ", rows=" + rows.size() + "}";
    }
}
