package com.fastbase.model;

import com.fastbase.model.enums.ColumnType;
import lombok.Getter;
import lombok.Setter;

/**
 * Représente une colonne dans une table
 */
@Getter
@Setter
public class Column {

    private String name;
    private ColumnType type;

    public Column(String name, ColumnType type) {
        this.name = name;
        this.type = type;
    }

    @Override
    public String toString() {
        return "Column{name='" + name + "', type=" + type + "}";
    }
}
