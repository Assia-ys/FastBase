package com.fastbase.model;

import java.util.ArrayList;
import java.util.List;

public class Table {

    private String name;
    private List<Column> columns;
    private List<Row> rows;

    public Table() {
        this.columns = new ArrayList<>();
        this.rows    = new ArrayList<>();
    }

    public Table(String name, List<Column> columns) {
        this.name    = name;
        this.columns = columns != null ? columns : new ArrayList<>();
        // Pré-allocation à 1024 pour éviter les réallocations répétées lors du chargement massif
        this.rows    = new ArrayList<>(1024);
    }

    public String getName()              { return name; }
    public void setName(String name)     { this.name = name; }
    public List<Column> getColumns()     { return columns; }
    public void setColumns(List<Column> columns) { this.columns = columns; }
    public List<Row> getRows()           { return rows; }
    public void setRows(List<Row> rows)  { this.rows = rows; }

    public void addRows(List<Row> batch) { rows.addAll(batch); }
    public int getRowCount()             { return rows.size(); }

    // méthode clé pour la perf — évite de stocker les noms de colonnes
    public int getColumnIndex(String columnName) {
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).getName().equals(columnName)) return i;
        }
        return -1;
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