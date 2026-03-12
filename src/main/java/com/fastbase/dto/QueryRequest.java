package com.fastbase.dto;

import java.util.List;

/**
 * DTO pour exécuter une requête sur une table
 */
public class QueryRequest {

    private String tableName;
    private List<String> selectColumns;
    private String whereCondition;
    private List<String> groupByColumns;

    public QueryRequest() {
    }

    public QueryRequest(String tableName, List<String> selectColumns) {
        this.tableName = tableName;
        this.selectColumns = selectColumns;
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
        return "QueryRequest{tableName='" + tableName + "', selectColumns=" + selectColumns +
               ", whereCondition='" + whereCondition + "', groupByColumns=" + groupByColumns + "}";
    }
}
