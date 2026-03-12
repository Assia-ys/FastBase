package com.fastbase.service;

import com.fastbase.model.Row;
import com.fastbase.model.Table;
import com.fastbase.storage.DataStorage;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Service pour exécuter des requêtes sur les tables
 */
@Service
public class QueryService {

    private final DataStorage dataStorage;

    public QueryService(DataStorage dataStorage) {
        this.dataStorage = dataStorage;
    }

    /**
     * Exécute une requête SELECT simple
     */
    public List<Row> executeSelect(String tableName, List<String> columns) {
        Table table = dataStorage.getTable(tableName)
                .orElseThrow(() -> new IllegalArgumentException("La table '" + tableName + "' n'existe pas"));

        // Si SELECT *, retourner toutes les colonnes
        if (columns == null || columns.isEmpty() || columns.contains("*")) {
            return new ArrayList<>(table.getRows());
        }

        // Sinon, filtrer les colonnes demandées
        return table.getRows().stream()
                .map(row -> {
                    Map<String, Object> filteredData = new HashMap<>();
                    for (String column : columns) {
                        if (row.getData().containsKey(column)) {
                            filteredData.put(column, row.getValue(column));
                        }
                    }
                    return new Row(filteredData);
                })
                .collect(Collectors.toList());
    }

    /**
     * Exécute une requête SELECT avec WHERE (simplifié pour l'instant)
     * TODO: Implémenter un vrai parser de conditions WHERE
     */
    public List<Row> executeSelectWithWhere(String tableName, List<String> columns, String whereCondition) {
        // Pour l'instant, retourne juste un SELECT simple
        // À améliorer avec un vrai parser de conditions
        return executeSelect(tableName, columns);
    }

    /**
     * Exécute une requête SELECT avec GROUP BY
     * TODO: À implémenter avec agrégations (COUNT, SUM, AVG, etc.)
     */
    public List<Row> executeSelectWithGroupBy(String tableName, List<String> columns, List<String> groupByColumns) {
        throw new UnsupportedOperationException("GROUP BY n'est pas encore implémenté");
    }
}
