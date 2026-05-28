package com.fastbase.controller;

import com.fastbase.model.Column;
import com.fastbase.model.enums.ColumnType;
import com.fastbase.model.enums.FileFormat;
import com.fastbase.service.BenchmarkService;
import com.fastbase.service.DataLoaderService;
import com.fastbase.service.TableService;
import com.fastbase.storage.DataStorage;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.parquet.hadoop.ParquetFileReader;
import org.apache.parquet.hadoop.util.HadoopInputFile;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.Type;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.function.Supplier;

/**
 * Endpoint unique pour déclencher tous les benchmarks FastBase.
 *
 * POST /api/benchmark/run
 *   - Accepte un fichier Parquet en upload multipart (pas de chemin local)
 *   - Détecte automatiquement le schéma depuis les métadonnées Parquet
 *   - Exécute LOAD + SELECT + WHERE_SIMPLE + WHERE_COMPLEX + GROUP_BY_SIMPLE + GROUP_BY_COMPLEX
 *   - Paliers par défaut : 100k, 500k, 1M, 2M, 4M lignes
 *   - Retourne les résultats en JSON (ms par opération et par palier)
 */
@RestController
@RequestMapping("/api/benchmark")
public class BenchmarkController {

    private final TableService       tableService;
    private final BenchmarkService   benchmarkService;
    private final DataLoaderService  dataLoaderService;
    private final DataStorage        dataStorage;

    public BenchmarkController(TableService tableService,
                               BenchmarkService benchmarkService,
                               DataLoaderService dataLoaderService,
                               DataStorage dataStorage) {
        this.tableService      = tableService;
        this.benchmarkService  = benchmarkService;
        this.dataLoaderService = dataLoaderService;
        this.dataStorage       = dataStorage;
    }

    /**
     * Benchmark depuis un chemin fichier local (pas d'upload).
     * Utiliser quand le fichier est déjà sur le serveur — évite de charger 1.4 GB dans le client.
     * POST /api/benchmark/run-path?filePath=...&scales=100000,50000000
     */
    @PostMapping("/run-path")
    public Map<String, Object> runBenchmarkPath(
            @RequestParam("filePath") String filePath,
            @RequestParam(value = "scales", defaultValue = "100000,500000,1000000,2000000,4000000,10000000,50000000") String scalesParam)
            throws IOException {

        if (!java.nio.file.Files.exists(java.nio.file.Paths.get(filePath)))
            throw new IllegalArgumentException("Fichier introuvable : " + filePath);

        Map<String, Object> resp = executeBenchmarks(
                filePath, java.nio.file.Paths.get(filePath).getFileName().toString(), scalesParam);
        return resp;
    }

    @PostMapping(value = "/run", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> runBenchmark(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "scales", defaultValue = "100000,500000,1000000,2000000,4000000") String scalesParam)
            throws IOException {

        if (file.isEmpty()) throw new IllegalArgumentException("Le fichier uploadé est vide");

        java.nio.file.Path tempFile = Files.createTempFile("bench-upload-", ".parquet");
        try {
            Files.copy(file.getInputStream(), tempFile, StandardCopyOption.REPLACE_EXISTING);
            String filePath = tempFile.toString();

            return executeBenchmarks(filePath, file.getOriginalFilename(), scalesParam);
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    private Map<String, Object> executeBenchmarks(String filePath, String fileName, String scalesParam)
            throws IOException {

        List<Column> schema = readParquetSchema(filePath);
        if (schema.isEmpty()) throw new IllegalArgumentException("Impossible de lire le schéma Parquet");

        long totalRowsInFile = dataLoaderService.countParquetRows(filePath);

        int[] scales = Arrays.stream(scalesParam.split(","))
                .map(String::trim).mapToInt(Integer::parseInt).toArray();

        List<Map<String, Object>> results = new ArrayList<>();

        for (int scale : scales) {
            if (scale > totalRowsInFile) continue;

            String tableName = "bench_api_" + scale;
            dataStorage.deleteTable(tableName);
            tableService.createTable(tableName, new ArrayList<>(schema));

            BenchmarkService.BenchmarkResult load =
                    benchmarkService.benchmarkParquetLoad(tableName, filePath, scale);

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("scale",           scale);
            row.put("LOAD",            toMap(load));
            row.put("SELECT",          tryBench(() -> benchmarkService.benchmarkSelect(
                    tableName, List.of("fare_amount", "total_amount"), null)));
            row.put("WHERE_SIMPLE",    tryBench(() -> benchmarkService.benchmarkSelect(
                    tableName, List.of("fare_amount", "total_amount"), "fare_amount>10")));
            row.put("WHERE_COMPLEX",   tryBench(() -> benchmarkService.benchmarkSelect(
                    tableName, List.of("passenger_count", "trip_distance"), "payment_type=1")));
            row.put("GROUP_BY_SIMPLE", tryBench(() -> benchmarkService.benchmarkGroupBy(
                    tableName, List.of("VendorID", "SUM(total_amount)"), null, List.of("VendorID"))));
            row.put("GROUP_BY_COMPLEX", tryBench(() -> benchmarkService.benchmarkGroupBy(
                    tableName,
                    List.of("passenger_count", "COUNT(VendorID)",
                            "SUM(trip_distance)", "SUM(total_amount)", "SUM(tip_amount)"),
                    null, List.of("passenger_count"))));

            results.add(row);

            dataStorage.deleteTable(tableName);
            System.gc();
            if (scale >= 5_000_000) {
                try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
                System.gc();
            }
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("file",            fileName);
        response.put("totalRowsInFile", totalRowsInFile);
        response.put("columnsDetected", schema.size());
        response.put("results",         results);
        return response;
    }

    // Lit le schéma Parquet — saute les timestamps (non utilisés dans les requêtes, économise ~3GB sur 50M lignes)
    private List<Column> readParquetSchema(String filePath) throws IOException {
        org.apache.parquet.io.InputFile inputFile =
                HadoopInputFile.fromPath(new Path(filePath), new Configuration());
        try (ParquetFileReader reader = ParquetFileReader.open(inputFile)) {
            MessageType schema = reader.getFooter().getFileMetaData().getSchema();
            List<Column> columns = new ArrayList<>(schema.getFieldCount());
            for (Type field : schema.getFields()) {
                ColumnType ct = parquetTypeToColumnType(field);
                if (ct != null) columns.add(new Column(field.getName(), ct));
            }
            return columns;
        }
    }

    /**
     * Mappe un type Parquet vers ColumnType — toutes les 19 colonnes conservées.
     * Les timestamps INT64 sont traités comme LONG (epoch µs stocké en double[]).
     * Aucune perte de précision : les timestamps 2022 (~1.6×10^15 µs) < 2^53 (limite double).
     */
    private ColumnType parquetTypeToColumnType(Type field) {
        if (!field.isPrimitive()) return ColumnType.STRING;
        var pt = field.asPrimitiveType();
        // Timestamps → LONG (stocké en numérique, pas en String → évite ~5 GB d'objets String)
        if (pt.getLogicalTypeAnnotation() instanceof
                org.apache.parquet.schema.LogicalTypeAnnotation.TimestampLogicalTypeAnnotation)
            return ColumnType.LONG;
        return switch (pt.getPrimitiveTypeName()) {
            case INT32         -> ColumnType.INTEGER;
            case INT64         -> ColumnType.LONG;
            case FLOAT, DOUBLE -> ColumnType.DOUBLE;
            case BOOLEAN       -> ColumnType.BOOLEAN;
            default            -> ColumnType.STRING;
        };
    }

    private Map<String, Object> toMap(BenchmarkService.BenchmarkResult r) {
        return Map.of("rowCount", r.rowCount(), "elapsedMs", r.elapsedMs(), "status", "ok");
    }

    private Map<String, Object> tryBench(Supplier<BenchmarkService.BenchmarkResult> fn) {
        try {
            return toMap(fn.get());
        } catch (Exception e) {
            return Map.of("status", "error", "message", e.getMessage());
        }
    }
}