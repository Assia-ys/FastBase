package com.fastbase.dto;

/**
 * DTO pour charger des données dans une table
 */
public class LoadDataRequest {

    private String tableName;
    private String filePath;
    private FileFormat format;

    public enum FileFormat {
        CSV,
        PARQUET
    }

    public LoadDataRequest() {
    }

    public LoadDataRequest(String tableName, String filePath, FileFormat format) {
        this.tableName = tableName;
        this.filePath = filePath;
        this.format = format;
    }

    public String getTableName() {
        return tableName;
    }

    public void setTableName(String tableName) {
        this.tableName = tableName;
    }

    public String getFilePath() {
        return filePath;
    }

    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    public FileFormat getFormat() {
        return format;
    }

    public void setFormat(FileFormat format) {
        this.format = format;
    }

    @Override
    public String toString() {
        return "LoadDataRequest{tableName='" + tableName + "', filePath='" + filePath + "', format=" + format + "}";
    }
}
