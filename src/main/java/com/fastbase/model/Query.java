package com.fastbase.model;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/**
 * Représente une requête SQL simplifiée
 */
@Setter
@Getter
public class Query {

    private String tableName;
    private List<String> selectColumns;  // SELECT
    private String whereCondition;        // WHERE
    private List<String> groupByColumns;  // GROUP BY

    public Query() {
        this.selectColumns = new ArrayList<>();
        this.groupByColumns = new ArrayList<>();
    }
    @Override
    public String toString() {
        return "Query{tableName='" + tableName + "', selectColumns=" + selectColumns +
               ", whereCondition='" + whereCondition + "', groupByColumns=" + groupByColumns + "}";
    }
}
