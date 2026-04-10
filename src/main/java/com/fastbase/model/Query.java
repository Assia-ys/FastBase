package com.fastbase.model;

import java.util.ArrayList;
import java.util.List;

public class Query {

    private String tableName;
    private List<String> selectColumns  = new ArrayList<>();
    private String whereCondition;
    private List<String> groupByColumns = new ArrayList<>();

    public Query() {}

    public String getTableName()                         { return tableName; }
    public void setTableName(String tableName)           { this.tableName = tableName; }
    public List<String> getSelectColumns()               { return selectColumns; }
    public void setSelectColumns(List<String> cols)      { this.selectColumns = cols; }
    public String getWhereCondition()                    { return whereCondition; }
    public void setWhereCondition(String whereCondition) { this.whereCondition = whereCondition; }
    public List<String> getGroupByColumns()              { return groupByColumns; }
    public void setGroupByColumns(List<String> cols)     { this.groupByColumns = cols; }

    public boolean isSelectAll()  { return selectColumns == null || selectColumns.isEmpty() || selectColumns.contains("*"); }
    public boolean hasWhere()     { return whereCondition != null && !whereCondition.isBlank(); }
    public boolean hasGroupBy()   { return groupByColumns != null && !groupByColumns.isEmpty(); }

    @Override
    public String toString() {
        return "Query{table='" + tableName + "', select=" + selectColumns + ", where='" + whereCondition + "', groupBy=" + groupByColumns + "}";
    }
}