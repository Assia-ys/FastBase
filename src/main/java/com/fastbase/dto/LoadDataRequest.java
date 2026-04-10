package com.fastbase.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public class LoadDataRequest {

    @NotBlank(message = "Le nom de la table est obligatoire")
    private String tableName;

    @NotBlank(message = "Le chemin du fichier est obligatoire")
    private String filePath;

    @NotNull(message = "Le format est obligatoire")
    private FileFormat format;

    public enum FileFormat { CSV, PARQUET }

    public LoadDataRequest() {}

    public String getTableName()        { return tableName; }
    public void setTableName(String t)  { this.tableName = t; }
    public String getFilePath()         { return filePath; }
    public void setFilePath(String p)   { this.filePath = p; }
    public FileFormat getFormat()       { return format; }
    public void setFormat(FileFormat f) { this.format = f; }
}