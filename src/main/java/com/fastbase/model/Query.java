package com.fastbase.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Représente une requête SQL simplifiée
 */
public class Query {

    private String tableName;
    private List<String> selectColumns;  // SELECT
    private String whereCondition;        // WHERE (simplifié pour l'instant)
    private List<String> groupByColumns;  // GROUP BY

    public Query() {
        this.selectColumns = new ArrayList<>();
        this.groupByColumns = new ArrayList<>();
    }

    public String getTableName() {
        return tableName;
    }

    public void setTableName(String tableName) {
        this.tableName = tableName;
    }

    public List<String> getSelectColumns() {
        return selectColumns;
    }

    public void setSelectColumns(List<String> selectColumns) {
        this.selectColumns = selectColumns;
    }

    public String getWhereCondition() {
        return whereCondition;
    }

    public void setWhereCondition(String whereCondition) {
        this.whereCondition = whereCondition;
    }

    public List<String> getGroupByColumns() {
        return groupByColumns;
    }

    public void setGroupByColumns(List<String> groupByColumns) {
        this.groupByColumns = groupByColumns;
    }

    @Override
    public String toString() {
        return "Query{tableName='" + tableName + "', selectColumns=" + selectColumns +
               ", whereCondition='" + whereCondition + "', groupByColumns=" + groupByColumns + "}";
    }
}
