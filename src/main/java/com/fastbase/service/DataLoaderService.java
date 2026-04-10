package com.fastbase.service;

import com.fastbase.exception.TableNotFoundException;
import com.fastbase.model.Column;
import com.fastbase.model.Row;
import com.fastbase.model.Table;
import com.fastbase.model.enums.ColumnType;
import com.fastbase.storage.DataStorage;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@Service
public class DataLoaderService {

    private static final int BATCH_SIZE  = 10_000;
    private static final int BUFFER_SIZE = 1024 * 1024;

    private final DataStorage dataStorage;

    public DataLoaderService(DataStorage dataStorage) {
        this.dataStorage = dataStorage;
    }

    public int loadCsvData(String tableName, String filePath) throws IOException {
        Table table = dataStorage.getTable(tableName)
                .orElseThrow(() -> new TableNotFoundException(tableName));

        List<Column> columns = table.getColumns();
        int totalRows = 0;

        try (BufferedReader reader = new BufferedReader(new FileReader(filePath), BUFFER_SIZE)) {

            String headerLine = reader.readLine();
            if (headerLine == null) throw new IOException("Le fichier CSV est vide");

            String[] csvHeaders   = parseCsvLine(headerLine);
            int[]    columnMapping = buildColumnMapping(csvHeaders, columns);

            List<Row> batch = new ArrayList<>(BATCH_SIZE);
            String line;

            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;

                String[] rawValues = parseCsvLine(line);
                Row row = new Row(columns.size());

                for (int csvIdx = 0; csvIdx < csvHeaders.length; csvIdx++) {
                    int colIdx = columnMapping[csvIdx];
                    if (colIdx < 0) continue;
                    String raw = csvIdx < rawValues.length ? rawValues[csvIdx] : null;
                    row.setValue(colIdx, parseValue(raw, columns.get(colIdx).getType()));
                }

                batch.add(row);

                if (batch.size() >= BATCH_SIZE) {
                    table.addRows(batch);
                    totalRows += batch.size();
                    batch = new ArrayList<>(BATCH_SIZE);
                }
            }

            if (!batch.isEmpty()) {
                table.addRows(batch);
                totalRows += batch.size();
            }
        }

        return totalRows;
    }

    /**
     * Parse une valeur brute String vers le type Java correspondant.
     * Centralisé ici dans le service, pas dans le modèle.
     */
    private Object parseValue(String value, ColumnType type) {
        if (value == null || value.isBlank()) return null;
        return switch (type) {
            case INTEGER -> { try { yield Integer.parseInt(value.trim()); } catch (NumberFormatException e) { yield null; } }
            case LONG    -> { try { yield Long.parseLong(value.trim());    } catch (NumberFormatException e) { yield null; } }
            case DOUBLE  -> { try { yield Double.parseDouble(value.trim());} catch (NumberFormatException e) { yield null; } }
            case BOOLEAN -> Boolean.parseBoolean(value.trim());
            default      -> value.trim();
        };
    }

    private int[] buildColumnMapping(String[] csvHeaders, List<Column> columns) {
        int[] mapping = new int[csvHeaders.length];
        for (int i = 0; i < csvHeaders.length; i++) {
            mapping[i] = -1;
            String header = csvHeaders[i].trim();
            for (int j = 0; j < columns.size(); j++) {
                if (columns.get(j).getName().equalsIgnoreCase(header)) {
                    mapping[i] = j;
                    break;
                }
            }
        }
        return mapping;
    }

    private String[] parseCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"'); i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (c == ',' && !inQuotes) {
                fields.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        fields.add(current.toString().trim());
        return fields.toArray(new String[0]);
    }

    public int loadParquetData(String tableName, String filePath) {
        throw new UnsupportedOperationException("Le chargement Parquet n'est pas encore implémenté");
    }
}