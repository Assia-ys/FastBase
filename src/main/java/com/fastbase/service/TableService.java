package com.fastbase.service;

import com.fastbase.model.Column;
import com.fastbase.model.Table;
import com.fastbase.storage.DataStorage;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Service pour la gestion des tables
 */
@Service
public class TableService {

    private final DataStorage dataStorage;

    public TableService(DataStorage dataStorage) {
        this.dataStorage = dataStorage;
    }

    /**
     * Crée une nouvelle table
     */
    public Table createTable(String tableName, List<Column> columns) {
        // Validation
        if (tableName == null || tableName.trim().isEmpty()) {
            throw new IllegalArgumentException("Le nom de la table ne peut pas être vide");
        }
        if (columns == null || columns.isEmpty()) {
            throw new IllegalArgumentException("La table doit avoir au moins une colonne");
        }

        // Vérifier que la table n'existe pas déjà
        if (dataStorage.tableExists(tableName)) {
            throw new IllegalStateException("La table '" + tableName + "' existe déjà");
        }

        // Créer la table
        Table table = new Table(tableName, columns);
        dataStorage.createTable(table);

        return table;
    }

    /**
     * Récupère une table par son nom
     */
    public Table getTable(String tableName) {
        return dataStorage.getTable(tableName)
                .orElseThrow(() -> new IllegalArgumentException("La table '" + tableName + "' n'existe pas"));
    }

    /**
     * Récupère toutes les tables
     */
    public List<Table> getAllTables() {
        return dataStorage.getAllTables();
    }

    /**
     * Supprime une table
     */
    public boolean deleteTable(String tableName) {
        return dataStorage.deleteTable(tableName);
    }

    /**
     * Vérifie si une table existe
     */
    public boolean tableExists(String tableName) {
        return dataStorage.tableExists(tableName);
    }
}
