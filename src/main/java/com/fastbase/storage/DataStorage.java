package com.fastbase.storage;

import com.fastbase.model.Table;

import java.util.List;
import java.util.Optional;

public interface DataStorage {
    void createTable(Table table);
    Optional<Table> getTable(String tableName);
    List<Table> getAllTables();
    boolean deleteTable(String tableName);
    boolean tableExists(String tableName);
    int getTableCount();
}
