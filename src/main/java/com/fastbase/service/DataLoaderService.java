package com.fastbase.service;

import com.fastbase.model.Row;
import com.fastbase.model.Table;
import com.fastbase.storage.DataStorage;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Service pour charger des données depuis des fichiers CSV ou Parquet
 */
@Service
public class DataLoaderService {

    private final DataStorage dataStorage;

    public DataLoaderService(DataStorage dataStorage) {
        this.dataStorage = dataStorage;
    }

    /**
     * Charge des données CSV dans une table
     */
    public int loadCsvData(String tableName, String filePath) throws IOException {
        Table table = dataStorage.getTable(tableName)
                .orElseThrow(() -> new IllegalArgumentException("La table '" + tableName + "' n'existe pas"));

        int rowCount = 0;

        try (BufferedReader reader = new BufferedReader(new FileReader(filePath))) {
            // Lire l'en-tête (première ligne)
            String headerLine = reader.readLine();
            if (headerLine == null) {
                throw new IOException("Le fichier CSV est vide");
            }

            String[] headers = headerLine.split(",");

            // Lire les données ligne par ligne
            String line;
            while ((line = reader.readLine()) != null) {
                String[] values = line.split(",");

                // Créer une Row
                Map<String, Object> rowData = new HashMap<>();
                for (int i = 0; i < headers.length && i < values.length; i++) {
                    rowData.put(headers[i].trim(), values[i].trim());
                }

                Row row = new Row(rowData);
                table.addRow(row);
                rowCount++;
            }
        }

        return rowCount;
    }

    /**
     * Charge des données Parquet dans une table
     * TODO: À implémenter avec la librairie parquet-hadoop
     */
    public int loadParquetData(String tableName, String filePath) {
        throw new UnsupportedOperationException("Le chargement Parquet n'est pas encore implémenté");
    }
}
