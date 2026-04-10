package com.fastbase.storage;

import com.fastbase.model.Table;

import java.util.List;
import java.util.Optional;

/**
 * Interface pour le moteur de stockage des données
 * Permet de changer l'implémentation (mémoire, disque, hybride) sans modifier le reste du code
 */
public interface DataStorage {

    /**
     * Crée une nouvelle table
     */
    void createTable(Table table);

    /**
     * Récupère une table par son nom
     */
    Optional<Table> getTable(String tableName);

    /**
     * Récupère toutes les tables
     */
    List<Table> getAllTables();

    /**
     * Supprime une table
     */
    boolean deleteTable(String tableName);

    /**
     * Vérifie si une table existe
     */
    boolean tableExists(String tableName);

    /**
     * Retourne le nombre total de tables
     */
    int getTableCount();
}
