package com.fastbase.service;

import com.fastbase.exception.TableAlreadyExistsException;
import com.fastbase.exception.TableNotFoundException;
import com.fastbase.model.Column;
import com.fastbase.model.Table;
import com.fastbase.storage.DataStorage;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import java.util.List;

@Service
@Validated
public class TableService {

    private final DataStorage dataStorage;

    public TableService(DataStorage dataStorage) {
        this.dataStorage = dataStorage;
    }

    public Table createTable(
            @NotBlank(message = "Le nom de la table ne peut pas être vide") String tableName,
            @NotEmpty(message = "La table doit avoir au moins une colonne") List<Column> columns) {

        if (dataStorage.tableExists(tableName))
            throw new TableAlreadyExistsException(tableName);

        Table table = new Table(tableName.trim(), columns);
        dataStorage.createTable(table);
        return table;
    }

    public Table getTable(
            @NotBlank(message = "Le nom de la table ne peut pas être vide") String tableName) {
        return dataStorage.getTable(tableName)
                .orElseThrow(() -> new TableNotFoundException(tableName));
    }

    public List<Table> getAllTables() {
        return dataStorage.getAllTables();
    }

    public boolean deleteTable(
            @NotBlank(message = "Le nom de la table ne peut pas être vide") String tableName) {
        return dataStorage.deleteTable(tableName);
    }

    public boolean tableExists(
            @NotBlank(message = "Le nom de la table ne peut pas être vide") String tableName) {
        return dataStorage.tableExists(tableName);
    }
}