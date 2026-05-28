package com.fastbase.storage;

import com.fastbase.model.Table;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class InMemoryStorage implements DataStorage {

    private final Map<String, Table> tables = new ConcurrentHashMap<>();

    @Override
    public void createTable(Table table) {
        if (table == null || table.getName() == null)
            throw new IllegalArgumentException("Table ou nom de table null");
        if (tables.containsKey(table.getName()))
            throw new IllegalStateException("La table '" + table.getName() + "' existe déjà");
        tables.put(table.getName(), table);
    }

    @Override
    public Optional<Table> getTable(String tableName) {
        return Optional.ofNullable(tables.get(tableName));
    }

    @Override
    public List<Table> getAllTables() {
        return new ArrayList<>(tables.values());
    }

    @Override
    public boolean deleteTable(String tableName) {
        return tables.remove(tableName) != null;
    }

    @Override
    public boolean tableExists(String tableName) {
        return tables.containsKey(tableName);
    }

    @Override
    public int getTableCount() {
        return tables.size();
    }
}
