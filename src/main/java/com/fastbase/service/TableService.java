package com.fastbase.service;

import com.fastbase.exception.TableAlreadyExistsException;
import com.fastbase.exception.TableNotFoundException;
import com.fastbase.model.Column;
import com.fastbase.model.Table;
import com.fastbase.storage.DataStorage;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class TableService {

    private final DataStorage dataStorage;

    public TableService(DataStorage dataStorage) {
        this.dataStorage = dataStorage;
    }

    public Table createTable(String tableName, List<Column> columns) {
        if (tableName == null || tableName.isBlank())
            throw new IllegalArgumentException("Le nom de la table ne peut pas être vide");
        if (columns == null || columns.isEmpty())
            throw new IllegalArgumentException("La table doit avoir au moins une colonne");
        if (dataStorage.tableExists(tableName))
            throw new TableAlreadyExistsException(tableName);

        Table table = new Table(tableName.trim(), columns);
        dataStorage.createTable(table);
        return table;
    }

    public Table getTable(String tableName) {
        return dataStorage.getTable(tableName)
                .orElseThrow(() -> new TableNotFoundException(tableName));
    }

    public List<Table> getAllTables() {
        return dataStorage.getAllTables();
    }

    public boolean deleteTable(String tableName) {
        if (!dataStorage.tableExists(tableName))
            throw new TableNotFoundException(tableName);
        return dataStorage.deleteTable(tableName);
    }

    public boolean tableExists(String tableName) {
        return dataStorage.tableExists(tableName);
    }
}
