package com.fastbase.dto;

import com.fastbase.model.enums.FileFormat;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public class LoadFromUrlRequestDTO {

    @NotBlank(message = "Le nom de la table est obligatoire")
    private String tableName;

    @NotBlank(message = "L'URL est obligatoire")
    private String url;

    @NotNull(message = "Le format est obligatoire")
    private FileFormat format;

    private int maxRows = 0;

    public LoadFromUrlRequestDTO() {}

    public String getTableName()         { return tableName; }
    public void setTableName(String t)   { this.tableName = t; }
    public String getUrl()               { return url; }
    public void setUrl(String u)         { this.url = u; }
    public FileFormat getFormat()        { return format; }
    public void setFormat(FileFormat f)  { this.format = f; }
    public int getMaxRows()              { return maxRows; }
    public void setMaxRows(int m)        { this.maxRows = m; }
}