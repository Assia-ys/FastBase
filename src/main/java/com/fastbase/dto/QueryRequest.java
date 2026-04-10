package com.fastbase.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

public class QueryRequest {

    @NotBlank(message = "Le nom de la table est obligatoire")
    private String tableName;

    private List<String> selectColumns;
    private String whereCondition;
    private List<String> groupByColumns;

    public QueryRequest() {}

    public String getTableName()                    { return tableName; }
    public void setTableName(String t)              { this.tableName = t; }
    public List<String> getSelectColumns()          { return selectColumns; }
    public void setSelectColumns(List<String> cols) { this.selectColumns = cols; }
    public String getWhereCondition()               { return whereCondition; }
    public void setWhereCondition(String w)         { this.whereCondition = w; }
    public List<String> getGroupByColumns()         { return groupByColumns; }
    public void setGroupByColumns(List<String> g)   { this.groupByColumns = g; }
}