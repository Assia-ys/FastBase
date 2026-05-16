package com.fastbase.service;

import com.fastbase.exception.TableNotFoundException;
import com.fastbase.model.Column;
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

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Chargement CSV et Parquet avec écriture directe dans le stockage colonnaire de Table.
 * Aucun objet Row n'est créé pour les données réelles — zéro pression GC.
 */
@Service
@Validated
public class DataLoaderService {

    private static final int BATCH_SIZE  = 10_000;
    private static final int BUFFER_SIZE = 1024 * 1024;

    private final DataStorage dataStorage;

    public DataLoaderService(DataStorage dataStorage) {
        this.dataStorage = dataStorage;
    }

    // ── CSV ────────────────────────────────────────────────────────

    public int loadCsvData(
            @NotBlank String tableName,
            @NotBlank String filePath) throws IOException {
        return loadCsvData(tableName, filePath, 0);
    }

    public int loadCsvData(
            @NotBlank String tableName,
            @NotBlank String filePath,
            int maxRows) throws IOException {
        try (BufferedReader reader = new BufferedReader(new FileReader(filePath), BUFFER_SIZE)) {
            return loadCsvData(tableName, reader, maxRows);
        }
    }

    public int loadCsvData(
            @NotBlank String tableName,
            InputStream inputStream) throws IOException {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(inputStream, StandardCharsets.UTF_8), BUFFER_SIZE)) {
            return loadCsvData(tableName, reader, 0);
        }
    }

    private int loadCsvData(String tableName, BufferedReader reader, int maxRows) throws IOException {
        Table table = dataStorage.getTable(tableName)
                .orElseThrow(() -> new TableNotFoundException(tableName));
        List<Column> columns = table.getColumns();

        String headerLine = reader.readLine();
        if (headerLine == null) throw new IOException("Le fichier CSV est vide");

        String[] csvHeaders    = parseCsvLine(headerLine);
        int[]    columnMapping = buildColumnMapping(csvHeaders, columns);

        // Pré-allocation : évite les resize successifs
        if (maxRows > 0) table.reserveCapacity(maxRows);

        // Buffer d'écriture directe : on pré-alloue un bloc de BATCH_SIZE lignes
        int   totalRows = 0;
        int   batchStart = 0;
        int   batchFilled = 0;
        String line;

        while ((line = reader.readLine()) != null) {
            if (line.isBlank()) continue;
            if (maxRows > 0 && totalRows >= maxRows) break;

            if (batchFilled == 0) {
                int allocCount = (maxRows > 0)
                        ? Math.min(BATCH_SIZE, maxRows - totalRows)
                        : BATCH_SIZE;
                batchStart = table.allocateBatch(allocCount);
            }

            writeCsvRow(line, batchStart + batchFilled, columnMapping, columns, table);
            batchFilled++;

            if (batchFilled >= BATCH_SIZE || (maxRows > 0 && totalRows + batchFilled >= maxRows)) {
                totalRows  += batchFilled;
                batchFilled = 0;
            }
        }
        totalRows += batchFilled; // lignes du dernier batch partiel (déjà allouées)

        return totalRows;
    }

    // ── Parquet ────────────────────────────────────────────────────

    public int loadParquetData(
            @NotBlank String tableName,
            @NotBlank String filePath) throws IOException {
        return loadParquetData(tableName, filePath, 0);
    }

    public int loadParquetData(
            @NotBlank String tableName,
            @NotBlank String filePath,
            int maxRows) throws IOException {

        Table table = dataStorage.getTable(tableName)
                .orElseThrow(() -> new TableNotFoundException(tableName));
        List<Column> columns = table.getColumns();

        // Pré-allocation depuis les métadonnées Parquet (lecture instantanée des footers)
        int cap = maxRows > 0 ? maxRows : (int) Math.min(countParquetRows(filePath), 60_000_000);
        table.reserveCapacity(cap);

        try (ParquetReader<Group> reader = ParquetReader
                .builder(new GroupReadSupport(), new Path(filePath))
                .withConf(new Configuration())
                .build()) {

            Group  group;
            int[]  columnMapping = null;
            int    totalRows     = 0;
            int    batchStart    = 0;
            int    batchFilled   = 0;

            while ((group = reader.read()) != null) {
                if (maxRows > 0 && totalRows >= maxRows) break;

                if (columnMapping == null)
                    columnMapping = buildParquetColumnMapping(group, columns);

                if (batchFilled == 0) {
                    int allocCount = (maxRows > 0)
                            ? Math.min(BATCH_SIZE, maxRows - totalRows)
                            : BATCH_SIZE;
                    batchStart = table.allocateBatch(allocCount);
                }

                writeParquetRow(group, batchStart + batchFilled, columnMapping, columns, table);
                batchFilled++;

                if (batchFilled >= BATCH_SIZE || (maxRows > 0 && totalRows + batchFilled >= maxRows)) {
                    totalRows  += batchFilled;
                    batchFilled = 0;
                }
            }
            totalRows += batchFilled;
            return totalRows;
        }
    }

    public int loadParquetData(
            @NotBlank String tableName,
            InputStream inputStream) throws IOException {

        java.nio.file.Path tempFile = Files.createTempFile("fastbase-upload-", ".parquet");
        try (inputStream) {
            Files.copy(inputStream, tempFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            try {
                long rowCount = countParquetRows(tempFile.toString());
                dataStorage.getTable(tableName)
                        .ifPresent(t -> t.reserveCapacity((int) Math.min(rowCount, 60_000_000)));
            } catch (Exception ignored) {}
            return loadParquetData(tableName, tempFile.toString(), 0);
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    public long countParquetRows(@NotBlank String filePath) throws IOException {
        InputFile inputFile = HadoopInputFile.fromPath(new Path(filePath), new Configuration());
        ParquetMetadata metadata = ParquetFileReader.open(inputFile).getFooter();
        return metadata.getBlocks().stream().mapToLong(block -> block.getRowCount()).sum();
    }

    // ── Écriture directe colonnaire ────────────────────────────────

    /**
     * Parse une ligne CSV et écrit directement dans les arrays colonnaires de Table.
     * Aucun objet Row créé.
     */
    private void writeCsvRow(String line, int rowIdx, int[] columnMapping,
                             List<Column> columns, Table table) {
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
                writeColumnValue(table, rowIdx, colIdx, current, columns);
                current.setLength(0);
                csvIdx++;
                colIdx = csvIdx < columnMapping.length ? columnMapping[csvIdx] : -1;
            } else if (colIdx >= 0) {
                current.append(c);
            }
        }
        writeColumnValue(table, rowIdx, colIdx, current, columns);
    }

    private void writeColumnValue(Table table, int rowIdx, int colIdx,
                                  StringBuilder raw, List<Column> columns) {
        if (colIdx < 0) return;
        table.setColumnValue(rowIdx, colIdx, parseValue(raw.toString(), columns.get(colIdx).getType()));
    }

    /**
     * Parse un enregistrement Parquet et écrit directement dans les arrays colonnaires.
     * Aucun objet Row créé.
     */
    private void writeParquetRow(Group group, int rowIdx, int[] columnMapping,
                                 List<Column> columns, Table table) {
        for (int parquetIdx = 0; parquetIdx < columnMapping.length; parquetIdx++) {
            int colIdx = columnMapping[parquetIdx];
            if (colIdx < 0 || group.getFieldRepetitionCount(parquetIdx) == 0) continue;
            String raw = group.getValueToString(parquetIdx, 0);
            table.setColumnValue(rowIdx, colIdx, parseValue(raw, columns.get(colIdx).getType()));
        }
    }

    // ── Parsing de valeur ──────────────────────────────────────────

    private static final DateTimeFormatter DT_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private Object parseValue(String value, ColumnType type) {
        if (value == null || value.isBlank()) return null;
        return switch (type) {
            case INTEGER -> { try { yield Integer.parseInt(value.trim());   } catch (NumberFormatException e) { yield null; } }
            case LONG    -> {
                String v = value.trim();
                try { yield Long.parseLong(v); }
                catch (NumberFormatException e) {
                    // Datetime "yyyy-MM-dd HH:mm:ss" → epoch secondes UTC
                    try { yield LocalDateTime.parse(v, DT_FORMATTER).toEpochSecond(ZoneOffset.UTC); }
                    catch (Exception e2) { yield null; }
                }
            }
            case DOUBLE  -> { try { yield Double.parseDouble(value.trim()); } catch (NumberFormatException e) { yield null; } }
            case BOOLEAN -> Boolean.parseBoolean(value.trim());
            default      -> value.trim();
        };
    }

    // ── Helpers de mapping ─────────────────────────────────────────

    private int[] buildColumnMapping(String[] csvHeaders, List<Column> columns) {
        int[] mapping = new int[csvHeaders.length];
        for (int i = 0; i < csvHeaders.length; i++) {
            mapping[i] = -1;
            String header = csvHeaders[i].trim();
            for (int j = 0; j < columns.size(); j++) {
                if (columns.get(j).getName().equalsIgnoreCase(header)) {
                    mapping[i] = j; break;
                }
            }
        }
        return mapping;
    }

    private int[] buildParquetColumnMapping(Group group, List<Column> columns) {
        List<Type> parquetFields = group.getType().getFields();
        int[] mapping = new int[parquetFields.size()];
        for (int i = 0; i < parquetFields.size(); i++) {
            mapping[i] = -1;
            String parquetName = parquetFields.get(i).getName();
            for (int j = 0; j < columns.size(); j++) {
                if (columns.get(j).getName().equalsIgnoreCase(parquetName)) {
                    mapping[i] = j; break;
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
                } else { inQuotes = !inQuotes; }
            } else if (c == ',' && !inQuotes) {
                fields.add(current.toString().trim());
                current.setLength(0);
            } else { current.append(c); }
        }
        fields.add(current.toString().trim());
        return fields.toArray(new String[0]);
    }
}