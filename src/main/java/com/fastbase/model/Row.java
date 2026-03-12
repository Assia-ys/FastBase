package com.fastbase.model;

import java.util.HashMap;
import java.util.Map;

/**
 * Représente une ligne de données dans une table
 */
public class Row {

    private Map<String, Object> data;//nom de la colonne : la donnee

    public Row() {
        this.data = new HashMap<>();
    }

    public Row(Map<String, Object> data) {
        this.data = data;
    }

    public Object getValue(String columnName) {
        return data.get(columnName);
    }

    public void setValue(String columnName, Object value) {
        data.put(columnName, value);
    }

    public Map<String, Object> getData() {
        return data;
    }

    public void setData(Map<String, Object> data) {
        this.data = data;
    }

    @Override
    public String toString() {
        return "Row{data=" + data + "}";
    }



}
