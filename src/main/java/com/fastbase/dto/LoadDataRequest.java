package com.fastbase.dto;

import lombok.Getter;
import lombok.Setter;
import com.fastbase.model.enums.FileFormat;
/**
 * DTO pour charger des données dans une table
 */
@Setter
@Getter
public class LoadDataRequest {

    private String tableName;
    private String filePath;
    private FileFormat format;


    public LoadDataRequest(String tableName, String filePath, FileFormat format) {
        this.tableName = tableName;
        this.filePath = filePath;
        this.format = format;
    }

    @Override
    public String toString() {
        return "LoadDataRequest{tableName='" + tableName + "', filePath='" + filePath + "', format=" + format + "}";
    }
}
