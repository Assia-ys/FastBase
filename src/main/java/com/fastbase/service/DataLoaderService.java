package com.fastbase.service;

import com.fastbase.exception.TableNotFoundException;
import com.fastbase.model.Column;
import com.fastbase.model.Table;
import com.fastbase.model.enums.ColumnType;
import com.fastbase.storage.DataStorage;
import jakarta.validation.constraints.NotBlank;
import org.apache.parquet.column.ColumnDescriptor;
import org.apache.parquet.column.ColumnReader;
import org.apache.parquet.column.impl.ColumnReadStoreImpl;
import org.apache.parquet.column.page.PageReadStore;
import org.apache.parquet.example.data.Group;
import org.apache.parquet.example.data.simple.convert.GroupRecordConverter;
import org.apache.parquet.hadoop.ParquetFileReader;
import org.apache.parquet.io.ColumnIOFactory;
import org.apache.parquet.io.InputFile;
import org.apache.parquet.io.MessageColumnIO;
import org.apache.parquet.io.RecordReader;
import org.apache.parquet.io.SeekableInputStream;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName;
import org.apache.parquet.schema.Type;
import java.nio.file.Paths;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

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

        table.trimRowCount(totalRows); // corrige rowCount si dernier batch non plein
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

        try (ParquetFileReader fileReader = ParquetFileReader.open(localFile(filePath))) {
            MessageType schema      = fileReader.getFooter().getFileMetaData().getSchema();
            long        totalInFile = fileReader.getRecordCount();
            List<Type>  parquetFields = schema.getFields();

            int cap = maxRows > 0 ? maxRows : (int) Math.min(totalInFile, 70_000_000);
            table.reserveCapacity(cap);

            // Mapping Parquet col index → table col index
            int[] columnMapping = buildParquetColumnMappingFromSchema(parquetFields, columns);

            // Pré-calcul des slots typés pour chaque colonne Parquet (1 fois avant la boucle)
            List<ColumnDescriptor> colDescs = schema.getColumns();
            int nCols = colDescs.size();
            int[] fltSlots  = new int[nCols]; int[] intSlots  = new int[nCols];
            int[] longSlots = new int[nCols]; int[] strSlots  = new int[nCols];
            boolean[] isBoolSlot = new boolean[nCols];
            boolean[] isDoubleParquet = new boolean[nCols];
            PrimitiveTypeName[] colPtn = new PrimitiveTypeName[nCols];
            for (int pi = 0; pi < nCols; pi++) {
                int tc = columnMapping[pi];
                fltSlots[pi]  = tc >= 0 ? table.getFltSlot(tc)  : -1;
                intSlots[pi]  = tc >= 0 ? table.getIntSlot(tc)  : -1;
                longSlots[pi] = tc >= 0 ? table.getLongSlot(tc) : -1;
                strSlots[pi]  = tc >= 0 ? table.getStrSlot(tc)  : -1;
                if (tc >= 0 && columns.get(tc).getType() == ColumnType.BOOLEAN) isBoolSlot[pi] = true;
                PrimitiveTypeName ptn = colDescs.get(pi).getPrimitiveType().getPrimitiveTypeName();
                isDoubleParquet[pi] = (ptn == PrimitiveTypeName.DOUBLE);
                colPtn[pi] = ptn;
            }

            int  totalAllocated = 0;
            PageReadStore pages;

            // P16 : pipeline parallèle — main thread lit les row groups (I/O séquentiel),
            // les workers décodent en parallèle (CPU).
            // Safe car chaque worker a son propre ColumnReadStoreImpl + ColumnReader.
            int nWorkers  = Runtime.getRuntime().availableProcessors();
            int maxFlight = Math.min(nWorkers, 6); // max 6 PageReadStores en mémoire simultanément
            ExecutorService pool      = Executors.newFixedThreadPool(nWorkers);
            Semaphore       semaphore = new Semaphore(maxFlight);
            List<Future<?>> futures   = new ArrayList<>();

            final int[]       fColumnMapping   = columnMapping;
            final boolean[]   fIsBoolSlot      = isBoolSlot;
            final boolean[]   fIsDoubleParquet  = isDoubleParquet;
            final int[]       fFltSlots        = fltSlots;
            final int[]       fIntSlots        = intSlots;
            final int[]       fLongSlots       = longSlots;
            final int[]       fStrSlots        = strSlots;
            final MessageType fSchema          = schema;
            final List<ColumnDescriptor> fColDescs = colDescs;
            final int         fNCols           = nCols;

            while ((pages = fileReader.readNextRowGroup()) != null) {
                if (maxRows > 0 && totalAllocated >= maxRows) break;
                int groupRows = (int) pages.getRowCount();
                if (maxRows > 0) groupRows = Math.min(groupRows, maxRows - totalAllocated);

                int startRow = table.allocateBatch(groupRows);
                totalAllocated += groupRows;

                semaphore.acquire();
                final PageReadStore fp       = pages;
                final int           fStart   = startRow;
                final int           fRows    = groupRows;

                futures.add(pool.submit(() -> {
                    try {
                        ColumnReadStoreImpl cs = new ColumnReadStoreImpl(
                                fp, new GroupRecordConverter(fSchema).getRootConverter(),
                                fSchema, "parquet-mr");

                        ColumnReader[] readers = new ColumnReader[fNCols];
                        for (int pi = 0; pi < fNCols; pi++)
                            if (fColumnMapping[pi] >= 0)
                                readers[pi] = cs.getColumnReader(fColDescs.get(pi));

                        for (int pi = 0; pi < fNCols; pi++) {
                            ColumnReader cr = readers[pi];
                            if (cr == null) continue;
                            int maxDef = fColDescs.get(pi).getMaxDefinitionLevel();
                            int flt = fFltSlots[pi], ini = fIntSlots[pi],
                                lng = fLongSlots[pi], str = fStrSlots[pi];
                            PrimitiveTypeName ptn = fColDescs.get(pi).getPrimitiveType().getPrimitiveTypeName();

                            if (flt >= 0) {
                                switch (ptn) {
                                    case DOUBLE -> { for (int r = 0; r < fRows; r++) { if (cr.getCurrentDefinitionLevel() >= maxDef) table.writeFloat(flt, fStart + r, (float) cr.getDouble()); cr.consume(); } }
                                    case INT32  -> { for (int r = 0; r < fRows; r++) { if (cr.getCurrentDefinitionLevel() >= maxDef) table.writeFloat(flt, fStart + r, (float) cr.getInteger()); cr.consume(); } }
                                    case INT64  -> { for (int r = 0; r < fRows; r++) { if (cr.getCurrentDefinitionLevel() >= maxDef) table.writeFloat(flt, fStart + r, (float) cr.getLong()); cr.consume(); } }
                                    default     -> { for (int r = 0; r < fRows; r++) { if (cr.getCurrentDefinitionLevel() >= maxDef) table.writeFloat(flt, fStart + r, cr.getFloat()); cr.consume(); } }
                                }
                            } else if (ini >= 0) {
                                if (fIsBoolSlot[pi]) {
                                    for (int r = 0; r < fRows; r++) { if (cr.getCurrentDefinitionLevel() >= maxDef) table.writeInt(ini, fStart + r, cr.getBoolean() ? 1 : 0); cr.consume(); }
                                } else {
                                    switch (ptn) {
                                        case INT32  -> { for (int r = 0; r < fRows; r++) { if (cr.getCurrentDefinitionLevel() >= maxDef) table.writeInt(ini, fStart + r, cr.getInteger()); cr.consume(); } }
                                        case INT64  -> { for (int r = 0; r < fRows; r++) { if (cr.getCurrentDefinitionLevel() >= maxDef) table.writeInt(ini, fStart + r, (int) cr.getLong()); cr.consume(); } }
                                        case DOUBLE -> { for (int r = 0; r < fRows; r++) { if (cr.getCurrentDefinitionLevel() >= maxDef) table.writeInt(ini, fStart + r, (int) cr.getDouble()); cr.consume(); } }
                                        default     -> { for (int r = 0; r < fRows; r++) cr.consume(); }
                                    }
                                }
                            } else if (lng >= 0) {
                                switch (ptn) {
                                    case INT64  -> { for (int r = 0; r < fRows; r++) { if (cr.getCurrentDefinitionLevel() >= maxDef) table.writeLong(lng, fStart + r, cr.getLong()); cr.consume(); } }
                                    case INT32  -> { for (int r = 0; r < fRows; r++) { if (cr.getCurrentDefinitionLevel() >= maxDef) table.writeLong(lng, fStart + r, (long) cr.getInteger()); cr.consume(); } }
                                    default     -> { for (int r = 0; r < fRows; r++) cr.consume(); }
                                }
                            } else if (str >= 0) {
                                for (int r = 0; r < fRows; r++) { if (cr.getCurrentDefinitionLevel() >= maxDef) table.writeString(str, fStart + r, cr.getBinary().toStringUsingUTF8()); cr.consume(); }
                            } else {
                                for (int r = 0; r < fRows; r++) cr.consume();
                            }
                        }
                    } finally {
                        semaphore.release(); // toujours libéré, même si exception
                    }
                }));
            }

            pool.shutdown();
            if (!pool.awaitTermination(30, TimeUnit.MINUTES))
                throw new IOException("Timeout décodage Parquet");
            for (Future<?> f : futures)
                try { f.get(); } catch (Exception e) { throw new IOException("Erreur décodage", e); }

            table.trimRowCount(totalAllocated);
            return totalAllocated;
        }
    }

    /** Chargement incrémental : saute skipRows lignes puis charge maxRows lignes supplémentaires. */
    public int loadParquetData(
            @NotBlank String tableName,
            @NotBlank String filePath,
            int skipRows,
            int maxRows) throws IOException {
        if (skipRows <= 0) return loadParquetData(tableName, filePath, maxRows);

        Table table = dataStorage.getTable(tableName)
                .orElseThrow(() -> new TableNotFoundException(tableName));
        List<Column> columns  = table.getColumns();
        int existingRows      = table.getRowCount();

        try (ParquetFileReader fileReader = ParquetFileReader.open(localFile(filePath))) {
            MessageType schema = fileReader.getFooter().getFileMetaData().getSchema();

            // Saute les row-groups entièrement compris dans skipRows (O(1) par groupe)
            long rowsSkipped = 0;
            for (org.apache.parquet.hadoop.metadata.BlockMetaData block
                    : fileReader.getFooter().getBlocks()) {
                if (rowsSkipped + block.getRowCount() <= skipRows) {
                    fileReader.readNextRowGroup();
                    rowsSkipped += block.getRowCount();
                } else break;
            }
            long inGroupSkip = skipRows - rowsSkipped;

            MessageColumnIO columnIO      = new ColumnIOFactory().getColumnIO(schema);
            List<Type>      parquetFields = schema.getFields();
            int[]           colMapping    = null;
            int             totalRows     = 0;
            boolean         firstGroup    = true;

            PageReadStore pages;
            outer:
            while ((pages = fileReader.readNextRowGroup()) != null) {
                RecordReader<Group> rr = columnIO.getRecordReader(pages, new GroupRecordConverter(schema));
                long groupSize  = pages.getRowCount();
                long rowStart   = firstGroup ? inGroupSkip : 0;
                firstGroup = false;

                for (long j = 0; j < rowStart; j++) rr.read();

                int batchStart = 0, batchFilled = 0;
                for (long i = rowStart; i < groupSize; i++) {
                    if (maxRows > 0 && totalRows + batchFilled >= maxRows) break outer;

                    Group group = rr.read();
                    if (colMapping == null) colMapping = buildParquetColumnMapping(group, columns);

                    if (batchFilled == 0) {
                        int allocCount = maxRows > 0
                                ? Math.min(BATCH_SIZE, maxRows - totalRows) : BATCH_SIZE;
                        batchStart = table.allocateBatch(allocCount);
                    }
                    writeParquetRow(group, batchStart + batchFilled, colMapping, columns, table, parquetFields);
                    batchFilled++;
                    if (batchFilled >= BATCH_SIZE) { totalRows += batchFilled; batchFilled = 0; }
                }
                totalRows += batchFilled;
            }
            table.trimRowCount(existingRows + totalRows);
            return totalRows;
        }
    }

    /** Chargement incrémental CSV : saute skipRows lignes de données puis charge maxRows. */
    public int loadCsvData(
            @NotBlank String tableName,
            @NotBlank String filePath,
            int skipRows,
            int maxRows) throws IOException {
        if (skipRows <= 0) return loadCsvData(tableName, filePath, maxRows);

        try (BufferedReader reader = new BufferedReader(new FileReader(filePath), BUFFER_SIZE)) {
            Table table = dataStorage.getTable(tableName)
                    .orElseThrow(() -> new TableNotFoundException(tableName));
            List<Column> columns = table.getColumns();
            int existingRows = table.getRowCount();

            String headerLine = reader.readLine();
            if (headerLine == null) throw new IOException("Fichier CSV vide");
            String[] csvHeaders = parseCsvLine(headerLine);
            int[] columnMapping = buildColumnMapping(csvHeaders, columns);

            for (int s = 0; s < skipRows; s++) if (reader.readLine() == null) return 0;

            if (maxRows > 0) table.reserveCapacity(existingRows + maxRows);

            int totalRows = 0, batchStart = 0, batchFilled = 0;
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                if (maxRows > 0 && totalRows >= maxRows) break;
                if (batchFilled == 0) {
                    int allocCount = maxRows > 0
                            ? Math.min(BATCH_SIZE, maxRows - totalRows) : BATCH_SIZE;
                    batchStart = table.allocateBatch(allocCount);
                }
                writeCsvRow(line, batchStart + batchFilled, columnMapping, columns, table);
                batchFilled++;
                if (batchFilled >= BATCH_SIZE || (maxRows > 0 && totalRows + batchFilled >= maxRows)) {
                    totalRows += batchFilled; batchFilled = 0;
                }
            }
            totalRows += batchFilled;
            table.trimRowCount(existingRows + totalRows);
            return totalRows;
        }
    }

    public int loadParquetData(
            @NotBlank String tableName,
            InputStream inputStream) throws IOException {
        return loadParquetData(tableName, inputStream, 0);
    }

    public int loadParquetData(
            @NotBlank String tableName,
            InputStream inputStream,
            int maxRows) throws IOException {

        java.nio.file.Path tempFile = Files.createTempFile("fastbase-upload-", ".parquet");
        tempFile.toFile().deleteOnExit(); // fallback si suppression immédiate échoue (Windows)
        try (inputStream) {
            Files.copy(inputStream, tempFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            try {
                long rowCount = countParquetRows(tempFile.toString());
                int cap = maxRows > 0 ? maxRows : (int) Math.min(rowCount, 60_000_000);
                dataStorage.getTable(tableName).ifPresent(t -> t.reserveCapacity(cap));
            } catch (Exception ignored) {}
            return loadParquetData(tableName, tempFile.toString(), maxRows);
        } finally {
            try { Files.deleteIfExists(tempFile); } catch (Exception ignored) {}
        }
    }

    public long countParquetRows(@NotBlank String filePath) throws IOException {
        try (ParquetFileReader reader = ParquetFileReader.open(localFile(filePath))) {
            return reader.getFooter().getBlocks().stream().mapToLong(b -> b.getRowCount()).sum();
        }
    }

    private static InputFile localFile(String path) {
        return new InputFile() {
            @Override public long getLength() throws IOException {
                return Files.size(java.nio.file.Paths.get(path));
            }
            @Override public SeekableInputStream newStream() throws IOException {
                RandomAccessFile raf = new RandomAccessFile(path, "r");
                return new SeekableInputStream() {
                    @Override public long getPos()  throws IOException { return raf.getFilePointer(); }
                    @Override public void seek(long p) throws IOException { raf.seek(p); }
                    @Override public void readFully(byte[] b) throws IOException { raf.readFully(b); }
                    @Override public void readFully(byte[] b, int s, int l) throws IOException { raf.readFully(b, s, l); }
                    @Override public int read(ByteBuffer buf) throws IOException {
                        byte[] tmp = new byte[buf.remaining()];
                        int n = raf.read(tmp);
                        if (n > 0) buf.put(tmp, 0, n);
                        return n;
                    }
                    @Override public void readFully(ByteBuffer buf) throws IOException {
                        byte[] tmp = new byte[buf.remaining()];
                        raf.readFully(tmp);
                        buf.put(tmp);
                    }
                    @Override public int read() throws IOException { return raf.read(); }
                    @Override public int read(byte[] b, int off, int len) throws IOException { return raf.read(b, off, len); }
                    @Override public void close() throws IOException { raf.close(); }
                };
            }
        };
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
     * Écrit un enregistrement Parquet directement dans les arrays colonnaires.
     * Utilise les getters natifs (getInteger, getLong, getFloat…) pour éviter
     * de créer une String par champ — 0 allocation String pour les colonnes numériques.
     */
    private void writeParquetRow(Group group, int rowIdx, int[] columnMapping,
                                 List<Column> columns, Table table,
                                 List<Type> parquetFields) {
        for (int pi = 0; pi < columnMapping.length; pi++) {
            int colIdx = columnMapping[pi];
            if (colIdx < 0 || group.getFieldRepetitionCount(pi) == 0) continue;
            table.setColumnValue(rowIdx, colIdx,
                readParquetField(group, pi, parquetFields.get(pi), columns.get(colIdx).getType()));
        }
    }

    private Object readParquetField(Group group, int idx, Type field, ColumnType target) {
        if (!field.isPrimitive()) {
            return parseValue(group.getValueToString(idx, 0), target);
        }
        PrimitiveTypeName ptn = field.asPrimitiveType().getPrimitiveTypeName();
        try {
            return switch (ptn) {
                case INT32   -> group.getInteger(idx, 0);
                case INT64   -> group.getLong(idx, 0);
                case FLOAT   -> group.getFloat(idx, 0);
                case DOUBLE  -> group.getDouble(idx, 0);
                case BOOLEAN -> group.getBoolean(idx, 0) ? 1 : 0;
                // INT96 / BINARY → chaîne, puis parsing selon le type cible
                default -> {
                    String s = group.getValueToString(idx, 0);
                    yield target == ColumnType.LONG ? parseLongValue(s) : s;
                }
            };
        } catch (Exception e) { return null; }
    }

    private static Long parseLongValue(String v) {
        if (v == null || v.isBlank()) return null;
        try { return Long.parseLong(v.trim()); }
        catch (NumberFormatException e) {
            try { return LocalDateTime.parse(v.trim(), DT_FORMATTER).toEpochSecond(ZoneOffset.UTC); }
            catch (Exception e2) { return null; }
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

    private int[] buildParquetColumnMappingFromSchema(List<Type> parquetFields, List<Column> columns) {
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