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
    public Object[] getValues() {
        return values;
    }

    @Override
    public String toString() {
        return "Row{values=" + Arrays.toString(values) + "}";
    }
}