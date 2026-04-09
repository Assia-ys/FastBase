package com.fastbase.model;

import lombok.Data;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/**
 * Représente une table dans FastBase
 */
@Setter
@Getter
@Data
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
}
