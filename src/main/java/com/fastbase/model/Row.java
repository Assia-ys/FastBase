package com.fastbase.model;

import java.util.Arrays;

/**
 * Ligne legacy — utilisée uniquement par les tests synthétiques (BenchmarkServiceTest etc.)
 * qui créent new Row(int) et appellent setValue/getValue.
 *
 * Le stockage réel des données est désormais colonnaire dans Table (double[][] + String[][]).
 * DataLoaderService écrit directement dans Table.setColumnValue() sans créer de Row.
 */
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

    public Object[] getValues() { return values; }

    @Override
    public String toString() {
        return "Row{values=" + Arrays.toString(values) + "}";
    }
}