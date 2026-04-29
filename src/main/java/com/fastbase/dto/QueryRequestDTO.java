package com.fastbase.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

public class QueryRequestDTO {

    @NotBlank(message = "Le nom de la table est obligatoire")
    private String tableName;

    private List<String> selectColumns;
    private String whereCondition;
    private List<String> groupByColumns;
    private String  orderBy;   // nom de colonne sur laquelle trier
    private String  orderDir;  // "ASC" ou "DESC" (défaut ASC si absent)
    private Integer limit;     // nombre max de lignes retournées (optionnel)

    public QueryRequestDTO() {}

    public String getTableName()                    { return tableName; }
    public void setTableName(String t)              { this.tableName = t; }
    public List<String> getSelectColumns()          { return selectColumns; }
    public void setSelectColumns(List<String> cols) { this.selectColumns = cols; }
    public String getWhereCondition()               { return whereCondition; }
    public void setWhereCondition(String w)         { this.whereCondition = w; }
    public List<String> getGroupByColumns()         { return groupByColumns; }
    public void setGroupByColumns(List<String> g)   { this.groupByColumns = g; }
    public String getOrderBy()                      { return orderBy; }
    public void setOrderBy(String o)                { this.orderBy = o; }
    public String getOrderDir()                     { return orderDir; }
    public void setOrderDir(String d)               { this.orderDir = d; }
    public Integer getLimit()                       { return limit; }
    public void setLimit(Integer l)                 { this.limit = l; }
}