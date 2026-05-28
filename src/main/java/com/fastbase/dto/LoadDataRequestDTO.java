package com.fastbase.dto;

import com.fastbase.model.enums.FileFormat;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.multipart.MultipartFile;

public class LoadDataRequestDTO {

    @NotBlank(message = "Le nom de la table est obligatoire")
    private String tableName;

    @NotNull(message = "Le fichier est obligatoire")
    private MultipartFile file;

    @NotNull(message = "Le format est obligatoire")
    private FileFormat format;


    public LoadDataRequestDTO() {}

    public String getTableName()        { return tableName; }
    public void setTableName(String t)  { this.tableName = t; }
    public MultipartFile getFile()      { return file; }
    public void setFile(MultipartFile f){ this.file = f; }
    public FileFormat getFormat()       { return format; }
    public void setFormat(FileFormat f) { this.format = f; }
}
