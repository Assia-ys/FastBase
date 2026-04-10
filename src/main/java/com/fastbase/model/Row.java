package com.fastbase.model;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Row {

    private final Object[] values;

    public Row(int columnCount) {
        this.values = new Object[columnCount];
    }

    public Row(Object[] values) {
        this.values = values;
    }

    public Object getValue(int columnIndex) {
        return values[columnIndex];
    }

    public void setValue(int columnIndex, Object value) {
        values[columnIndex] = value;
    }

    public Object getValue(String columnName, List<Column> columns) {
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).getName().equals(columnName)) return values[i];
        }
        return null;
    }

    public Map<String, Object> toMap(List<Column> columns) {
        Map<String, Object> map = new HashMap<>(columns.size() * 2);
        for (int i = 0; i < columns.size(); i++) {
            map.put(columns.get(i).getName(), values[i]);
        }
        return map;
    }

    public Object[] getValues() {
        return values;
    }

    @Override
    public String toString() {
        return "Row{values=" + Arrays.toString(values) + "}";
    }
}