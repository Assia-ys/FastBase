package com.fastbase.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Table {

    private String name;
    private List<Column> columns;
    private List<Row> rows;
    private final Map<String, Integer> columnIndex = new HashMap<>();

    public Table() {
        this.columns = new ArrayList<>();
        this.rows    = new ArrayList<>();
    }

    public Table(String name, List<Column> columns) {
        this.name    = name;
        this.columns = columns != null ? columns : new ArrayList<>();
        this.rows    = new ArrayList<>(10_000);
        rebuildIndex();
    }

    private void rebuildIndex() {
        columnIndex.clear();
        for (int i = 0; i < columns.size(); i++)
            columnIndex.put(columns.get(i).getName(), i);
    }

    public String getName()              { return name; }
    public void setName(String name)     { this.name = name; }
    public List<Column> getColumns()     { return columns; }
    public void setColumns(List<Column> columns) {
        this.columns = columns;
        rebuildIndex();
    }
    public List<Row> getRows()           { return rows; }
    public void setRows(List<Row> rows)  { this.rows = rows; }

    public void addRows(List<Row> batch) { rows.addAll(batch); }
    public int getRowCount()             { return rows.size(); }

    public int getColumnIndex(String columnName) {
        Integer idx = columnIndex.get(columnName);
        return idx != null ? idx : -1;
    }

    public Column getColumn(String columnName) {
        int idx = getColumnIndex(columnName);
        return idx >= 0 ? columns.get(idx) : null;
    }

    @Override
    public String toString() {
        return "Table{name='" + name + "', columns=" + columns.size() + ", rows=" + rows.size() + "}";
    }
}
