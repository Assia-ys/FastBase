package com.fastbase.model;

import lombok.Getter;
import lombok.Setter;

import java.util.HashMap;
import java.util.Map;

/**
 * Représente une ligne de données dans une table
 */
@Setter
@Getter
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

    @Override
    public String toString() {
        return "Row{data=" + data + "}";
    }



}
