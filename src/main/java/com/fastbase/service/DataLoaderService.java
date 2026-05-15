package com.fastbase.service;

import com.fastbase.exception.TableNotFoundException;
import com.fastbase.model.Column;
import com.fastbase.model.Row;
import com.fastbase.model.Table;
import com.fastbase.model.enums.ColumnType;
import com.fastbase.storage.DataStorage;
import jakarta.validation.constraints.NotBlank;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.parquet.example.data.Group;
import org.apache.parquet.hadoop.ParquetFileReader;
import org.apache.parquet.hadoop.ParquetReader;
import org.apache.parquet.hadoop.example.GroupReadSupport;
import org.apache.parquet.hadoop.util.HadoopInputFile;
import org.apache.parquet.io.InputFile;
import org.apache.parquet.hadoop.metadata.ParquetMetadata;
import org.apache.parquet.schema.Type;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

@Service
@Validated
public class DataLoaderService {

    private static final int BATCH_SIZE  = 10_000;
    private static final int BUFFER_SIZE = 1024 * 1024;

    private final DataStorage dataStorage;

    public DataLoaderService(DataStorage dataStorage) {
        this.dataStorage = dataStorage;
    }

    public int loadCsvData(
            @NotBlank(message = "Le nom de la table ne peut pas être vide") String tableName,
            @NotBlank(message = "Le chemin du fichier ne peut pas être vide") String filePath)
            throws IOException {
        return loadCsvData(tableName, filePath, 0);
    }

    public int loadCsvData(
            @NotBlank(message = "Le nom de la table ne peut pas être vide") String tableName,
            @NotBlank(message = "Le chemin du fichier ne peut pas être vide") String filePath,
            int maxRows)
            throws IOException {

        try (BufferedReader reader = new BufferedReader(new FileReader(filePath), BUFFER_SIZE)) {
            return loadCsvData(tableName, reader, maxRows);
        }
    }

    public int loadCsvData(
            @NotBlank(message = "Le nom de la table ne peut pas être vide") String tableName,
            InputStream inputStream)
            throws IOException {

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(inputStream, StandardCharsets.UTF_8), BUFFER_SIZE)) {
            return loadCsvData(tableName, reader, 0);
        }
    }

    private int loadCsvData(String tableName, BufferedReader reader, int maxRows) throws IOException {

        Table table = dataStorage.getTable(tableName)
                .orElseThrow(() -> new TableNotFoundException(tableName));

        List<Column> columns = table.getColumns();
        int totalRows = 0;

        String headerLine = reader.readLine();
        if (headerLine == null) throw new IOException("Le fichier CSV est vide");

        String[] csvHeaders    = parseCsvLine(headerLine);
        int[]    columnMapping = buildColumnMapping(csvHeaders, columns);

        List<Row> batch = new ArrayList<>(BATCH_SIZE);
        String line;

        while ((line = reader.readLine()) != null) {
            if (line.isBlank()) continue;
            if (maxRows > 0 && totalRows + batch.size() >= maxRows) break;

            Row row = parseCsvRow(line, columnMapping, columns);
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

        return totalRows;
    }

    public int loadParquetData(
            @NotBlank(message = "Le nom de la table ne peut pas être vide") String tableName,
            @NotBlank(message = "Le chemin du fichier ne peut pas être vide") String filePath)
            throws IOException {
        return loadParquetData(tableName, filePath, 0);
    }

    public int loadParquetData(
            @NotBlank(message = "Le nom de la table ne peut pas être vide") String tableName,
            @NotBlank(message = "Le chemin du fichier ne peut pas être vide") String filePath,
            int maxRows)
            throws IOException {

        Table table = dataStorage.getTable(tableName)
                .orElseThrow(() -> new TableNotFoundException(tableName));

        List<Column> columns = table.getColumns();
        int totalRows = 0;

        try (ParquetReader<Group> reader = ParquetReader
                .builder(new GroupReadSupport(), new Path(filePath))
                .withConf(new Configuration())
                .build()) {

            Group group;
            int[] columnMapping = null;
            List<Row> batch = new ArrayList<>(BATCH_SIZE);

            while ((group = reader.read()) != null) {
                if (maxRows > 0 && totalRows + batch.size() >= maxRows) break;
                if (columnMapping == null) {
                    columnMapping = buildParquetColumnMapping(group, columns);
                }

                Row row = parseParquetRow(group, columnMapping, columns);
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

    public long countParquetRows(
            @NotBlank(message = "Le chemin du fichier ne peut pas être vide") String filePath)
            throws IOException {

        InputFile inputFile = HadoopInputFile.fromPath(new Path(filePath), new Configuration());
        ParquetMetadata metadata = ParquetFileReader.open(inputFile).getFooter();
        return metadata.getBlocks().stream().mapToLong(block -> block.getRowCount()).sum();
    }

    public int loadParquetData(
            @NotBlank(message = "Le nom de la table ne peut pas être vide") String tableName,
            InputStream inputStream)
            throws IOException {

        java.nio.file.Path tempFile = Files.createTempFile("fastbase-upload-", ".parquet");
        try (inputStream) {
            Files.copy(inputStream, tempFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return loadParquetData(tableName, tempFile.toString(), 0);
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    private Object parseValue(String value, ColumnType type) {
        if (value == null || value.isBlank()) return null;
        return switch (type) {
            case INTEGER -> { try { yield Integer.parseInt(value.trim());   } catch (NumberFormatException e) { yield null; } }
            case LONG    -> { try { yield Long.parseLong(value.trim());     } catch (NumberFormatException e) { yield null; } }
            case DOUBLE  -> { try { yield Double.parseDouble(value.trim()); } catch (NumberFormatException e) { yield null; } }
            case BOOLEAN -> Boolean.parseBoolean(value.trim());
            default      -> value.trim();
        };
    }

    // Mappe chaque colonne CSV à son index dans le schéma de la table par nom (insensible à la casse).
    // Retourne -1 pour les colonnes CSV absentes du schéma → ignorées silencieusement au chargement.
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

    private Row parseCsvRow(String line, int[] columnMapping, List<Column> columns) {
        Row row = new Row(columns.size());
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        int csvIdx = 0;
        int colIdx = columnMapping.length > 0 ? columnMapping[0] : -1;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    if (colIdx >= 0) current.append('"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (c == ',' && !inQuotes) {
                setMappedValue(row, colIdx, current, columns);
                current.setLength(0);
                csvIdx++;
                colIdx = csvIdx < columnMapping.length ? columnMapping[csvIdx] : -1;
            } else if (colIdx >= 0) {
                current.append(c);
            }
        }

        setMappedValue(row, colIdx, current, columns);
        return row;
    }

    private void setMappedValue(Row row, int colIdx, StringBuilder raw, List<Column> columns) {
        if (colIdx < 0) return;
        row.setValue(colIdx, parseValue(raw.toString(), columns.get(colIdx).getType()));
    }

    private int[] buildParquetColumnMapping(Group group, List<Column> columns) {
        List<Type> parquetFields = group.getType().getFields();
        int[] mapping = new int[parquetFields.size()];
        for (int i = 0; i < parquetFields.size(); i++) {
            mapping[i] = -1;
            String parquetName = parquetFields.get(i).getName();
            for (int j = 0; j < columns.size(); j++) {
                if (columns.get(j).getName().equalsIgnoreCase(parquetName)) {
                    mapping[i] = j;
                    break;
                }
            }
        }
        return mapping;
    }

    private Row parseParquetRow(Group group, int[] columnMapping, List<Column> columns) {
        Row row = new Row(columns.size());
        for (int parquetIdx = 0; parquetIdx < columnMapping.length; parquetIdx++) {
            int colIdx = columnMapping[parquetIdx];
            if (colIdx < 0 || group.getFieldRepetitionCount(parquetIdx) == 0) continue;

            String raw = group.getValueToString(parquetIdx, 0);
            row.setValue(colIdx, parseValue(raw, columns.get(colIdx).getType()));
        }
        return row;
    }

    // Respecte les guillemets RFC 4180 : champs avec virgules et guillemets doublés
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
}
