package com.fastbase.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * DTO pour exécuter une requête sur une table
 */
@Setter
@Getter
public class QueryRequest {

    private String tableName;
    private List<String> selectColumns;
    private String whereCondition;
    private List<String> groupByColumns;
    

    public QueryRequest(String tableName, List<String> selectColumns) {
        this.tableName = tableName;
        this.selectColumns = selectColumns;
    }

    @Override
    public String toString() {
        return "QueryRequest{tableName='" + tableName + "', selectColumns=" + selectColumns +
               ", whereCondition='" + whereCondition + "', groupByColumns=" + groupByColumns + "}";
    }
}
