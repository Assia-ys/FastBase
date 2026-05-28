package com.fastbase;

import com.fastbase.model.Column;
import com.fastbase.model.Table;
import com.fastbase.model.enums.ColumnType;
import com.fastbase.model.enums.FileFormat;
import com.fastbase.service.BenchmarkService;
import com.fastbase.service.DataLoaderService;
import com.fastbase.service.QueryService;
import com.fastbase.service.TableService;
import com.fastbase.storage.DataStorage;
import com.fastbase.storage.InMemoryStorage;
import org.junit.jupiter.api.*;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.*;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Benchmark sur données réelles NYC Yellow Taxi 2016-01 (19 colonnes, jusqu'à 4M lignes).
 * Utilise DataLoaderService pour un mapping correct par nom de colonne.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RealDataBenchmarkTest {

    private static final FileFormat DATA_FORMAT = FileFormat.valueOf(
            System.getProperty("fastbase.benchmark.format", "PARQUET").toUpperCase());
    private static final String DATA_PATH = System.getProperty(
            "fastbase.benchmark.path", "../data_NYC/yellow_tripdata_2016-01.parquet");

    // Schéma complet des 19 colonnes NYC Taxi Yellow Trip 2022
    // Différences par rapport à 2016 : suppression lat/lon, ajout PULocationID/DOLocationID,
    // passenger_count passe en DOUBLE (nullable), ajout congestion_surcharge et airport_fee
    private static final List<Column> SCHEMA = List.of(
            new Column("VendorID",              ColumnType.INTEGER),
            new Column("tpep_pickup_datetime",  ColumnType.LONG),
            new Column("tpep_dropoff_datetime", ColumnType.LONG),
            new Column("passenger_count",       ColumnType.DOUBLE),
            new Column("trip_distance",         ColumnType.DOUBLE),
            new Column("RatecodeID",            ColumnType.DOUBLE),
            new Column("store_and_fwd_flag",    ColumnType.STRING),
            new Column("PULocationID",          ColumnType.INTEGER),
            new Column("DOLocationID",          ColumnType.INTEGER),
            new Column("payment_type",          ColumnType.INTEGER),
            new Column("fare_amount",           ColumnType.DOUBLE),
            new Column("extra",                 ColumnType.DOUBLE),
            new Column("mta_tax",               ColumnType.DOUBLE),
            new Column("tip_amount",            ColumnType.DOUBLE),
            new Column("tolls_amount",          ColumnType.DOUBLE),
            new Column("improvement_surcharge", ColumnType.DOUBLE),
            new Column("total_amount",          ColumnType.DOUBLE),
            new Column("congestion_surcharge",  ColumnType.DOUBLE),
            new Column("airport_fee",           ColumnType.DOUBLE)
    );

    private static final int[] SCALES = {100_000, 500_000, 1_000_000, 2_000_000, 4_000_000, 6_000_000, 8_000_000, 10_000_000};
    private static final List<String> CSV_LINES = new ArrayList<>();

    private static DataStorage      dataStorage;
    private static QueryService     queryService;
    private static BenchmarkService benchmarkService;
    private static TableService     tableService;
    private static DataLoaderService dataLoaderService;

    private static final String PARQUET_URL =
            "https://d37ci6vzurychx.cloudfront.net/trip-data/yellow_tripdata_2016-01.parquet";
    private static final String CSV_URL =
            "https://d37ci6vzurychx.cloudfront.net/trip-data/yellow_tripdata_2016-01.csv";

    @BeforeAll
    static void setup() throws IOException {
        dataStorage       = new InMemoryStorage();
        queryService      = new QueryService(dataStorage);
        dataLoaderService = new DataLoaderService(dataStorage);
        benchmarkService  = new BenchmarkService(queryService, dataLoaderService);
        tableService      = new TableService(dataStorage);

        CSV_LINES.add("operation,rowCount,elapsedMs,elapsedNs");

        downloadIfNeeded();
    }

    private static void downloadIfNeeded() throws IOException {
        Path dataPath = Path.of(DATA_PATH);
        if (Files.exists(dataPath)) return;

        String downloadUrl = DATA_FORMAT == FileFormat.PARQUET ? PARQUET_URL : CSV_URL;
        Files.createDirectories(dataPath.getParent() != null ? dataPath.getParent() : Path.of("."));

        System.out.println("\nFichier absent — téléchargement automatique depuis NYC TLC Open Data");
        System.out.println("URL         : " + downloadUrl);
        System.out.println("Destination : " + dataPath.toAbsolutePath());
        System.out.println("(environ 130 Mo pour le Parquet 2016-01, patience...)\n");

        HttpURLConnection conn = (HttpURLConnection) new URL(downloadUrl).openConnection();
        conn.setConnectTimeout(30_000);
        conn.setReadTimeout(600_000);
        conn.setRequestProperty("User-Agent", "FastBase-Benchmark/1.0");

        int status = conn.getResponseCode();
        if (status != HttpURLConnection.HTTP_OK)
            throw new IOException("Téléchargement échoué — HTTP " + status + " pour " + downloadUrl);

        long total = conn.getContentLengthLong();
        byte[] buffer = new byte[256 * 1024];
        long downloaded = 0;
        long lastPrint  = System.currentTimeMillis();

        try (InputStream in  = new BufferedInputStream(conn.getInputStream());
             OutputStream out = Files.newOutputStream(dataPath)) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                downloaded += read;
                long now = System.currentTimeMillis();
                if (now - lastPrint >= 3_000) {
                    if (total > 0)
                        System.out.printf("  %,d Mo / %,d Mo  (%.0f%%)%n",
                                downloaded / 1_048_576, total / 1_048_576,
                                100.0 * downloaded / total);
                    else
                        System.out.printf("  %,d Mo téléchargés...%n", downloaded / 1_048_576);
                    lastPrint = now;
                }
            }
        } catch (IOException e) {
            Files.deleteIfExists(dataPath); // pas de fichier corrompu
            throw e;
        }

        System.out.printf("%nTéléchargement terminé : %,d Mo → %s%n%n",
                Files.size(dataPath) / 1_048_576, dataPath.toAbsolutePath());
    }

    @AfterAll
    static void exportResults() throws IOException {
        Path csvOut = Path.of("target/benchmark-real.csv");
        Files.createDirectories(csvOut.getParent());
        Files.writeString(csvOut, String.join("\n", CSV_LINES) + "\n",
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

        Path pyOut = Path.of("target/plot_benchmark.py");
        Files.writeString(pyOut, buildPythonScript(),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

        System.out.println("\n=== EXPORT TERMINÉ ===");
        System.out.println("CSV  → " + csvOut.toAbsolutePath());
        System.out.println("Plot → " + pyOut.toAbsolutePath());
    }

    // --- 1. BENCHMARK réel : un chargement par palier, puis requêtes sur la table en mémoire ---
    @Test
    @Order(1)
    @DisplayName("Benchmark réel — LOAD, SELECT, WHERE (Simple/Dur), GROUP BY (Simple/Dur)")
    void benchmarkRealData() {
        System.out.println("\nFichier : " + DATA_PATH + " (" + DATA_FORMAT + ")");
        printDatasetInfo();
        warmup();

        System.out.println("\n─── BENCHMARK LOAD & QUERIES ─────────────────────────────────────────────────────────────");
        System.out.printf("%-10s %10s %10s %14s %14s %17s %17s%n",
                "Lignes", "LOAD", "SELECT", "WHERE_SIMPLE", "WHERE_COMPLEX", "GROUP_BY_SIMPLE", "GROUP_BY_COMPLEX");
        System.out.println("─".repeat(100));

        long totLoadMs = 0, totSelectMs = 0, totWsMs = 0, totWcMs = 0, totGsMs = 0, totGcMs = 0;
        long totLoadRows = 0, totSelectRows = 0, totWsRows = 0, totWcRows = 0, totGsRows = 0, totGcRows = 0;

        // Une seule table : on charge les deltas successifs sans repartir de zéro
        String tableName = "taxi_bench";
        dataStorage.deleteTable(tableName);
        tableService.createTable(tableName, new ArrayList<>(SCHEMA));
        int prevScale = 0;

        for (int scale : SCALES) {
            int delta = scale - prevScale;

            // 0. LOAD — uniquement le delta depuis le dernier palier
            BenchmarkService.BenchmarkResult load = benchmarkLoad(tableName, prevScale, delta);
            CSV_LINES.add(load.toCsvLine());
            long actualTotal = load.rowCount(); // = prevScale + lignes ajoutées
            assertThat(actualTotal).isGreaterThan(prevScale);
            if (actualTotal < scale) {
                System.out.printf("  ↳ fichier épuisé à %,d lignes (demandé : %,d) — paliers suivants ignorés%n",
                        actualTotal, scale);
                break;
            }
            prevScale = scale;

            // 1. SELECT pur
            BenchmarkService.BenchmarkResult select = benchmarkService.benchmarkSelect(
                    tableName, List.of("fare_amount", "total_amount"), null);
            CSV_LINES.add(select.toCsvLine());

            // 2. WHERE Simple
            BenchmarkService.BenchmarkResult whereSimple = benchmarkService.benchmarkSelect(
                    tableName, List.of("fare_amount", "total_amount"), "fare_amount>10");
            CSV_LINES.add(new BenchmarkService.BenchmarkResult(
                    "WHERE_SIMPLE", whereSimple.rowCount(), whereSimple.elapsedMs(), whereSimple.elapsedNs()).toCsvLine());

            // 3. WHERE Complexe
            BenchmarkService.BenchmarkResult whereComplex = benchmarkService.benchmarkSelect(
                    tableName, List.of("passenger_count", "trip_distance"), "payment_type=1");
            CSV_LINES.add(new BenchmarkService.BenchmarkResult(
                    "WHERE_COMPLEX", whereComplex.rowCount(), whereComplex.elapsedMs(), whereComplex.elapsedNs()).toCsvLine());

            // 4. GROUP BY Simple
            BenchmarkService.BenchmarkResult groupBySimple = benchmarkService.benchmarkGroupBy(
                    tableName, List.of("VendorID", "SUM(total_amount)"), null, List.of("VendorID"));
            CSV_LINES.add(new BenchmarkService.BenchmarkResult(
                    "GROUP_BY_SIMPLE", groupBySimple.rowCount(), groupBySimple.elapsedMs(), groupBySimple.elapsedNs()).toCsvLine());

            // 5. GROUP BY Complexe
            BenchmarkService.BenchmarkResult groupByComplex = benchmarkService.benchmarkGroupBy(
                    tableName,
                    List.of("passenger_count", "COUNT(VendorID)", "SUM(trip_distance)", "SUM(total_amount)", "SUM(tip_amount)"),
                    null, List.of("passenger_count"));
            CSV_LINES.add(new BenchmarkService.BenchmarkResult(
                    "GROUP_BY_COMPLEX", groupByComplex.rowCount(), groupByComplex.elapsedMs(), groupByComplex.elapsedNs()).toCsvLine());

            totLoadMs  += load.elapsedMs();         totLoadRows  += delta;
            totSelectMs += select.elapsedMs();      totSelectRows += select.rowCount();
            totWsMs     += whereSimple.elapsedMs(); totWsRows     += whereSimple.rowCount();
            totWcMs     += whereComplex.elapsedMs();totWcRows     += whereComplex.rowCount();
            totGsMs     += groupBySimple.elapsedMs();totGsRows    += groupBySimple.rowCount();
            totGcMs     += groupByComplex.elapsedMs();totGcRows   += groupByComplex.rowCount();

            System.out.printf("%-10d %7d ms %7d ms %11d ms %11d ms %14d ms %14d ms%n",
                    scale, load.elapsedMs(), select.elapsedMs(), whereSimple.elapsedMs(),
                    whereComplex.elapsedMs(), groupBySimple.elapsedMs(), groupByComplex.elapsedMs());

            System.gc();
        }
        dataStorage.deleteTable(tableName);

        // ─── TOTAUX ───────────────────────────────────────────────────────────────────────────────
        System.out.println("─".repeat(100));
        System.out.printf("%-10s %10s %10s %14s %14s %17s %17s%n",
                "", "LOAD", "SELECT", "WHERE_SIMPLE", "WHERE_COMPLEX", "GROUP_BY_SIMPLE", "GROUP_BY_COMPLEX");
        System.out.printf("%-10s %,10d %,10d %,14d %,14d %,17d %,17d%n",
                "Lignes ret.", totLoadRows, totSelectRows, totWsRows, totWcRows, totGsRows, totGcRows);
        System.out.printf("%-10s %7d ms %7d ms %11d ms %11d ms %14d ms %14d ms%n",
                "Tps total", totLoadMs, totSelectMs, totWsMs, totWcMs, totGsMs, totGcMs);
    }

    private void warmup() {
        String tableName = "taxi_warmup";
        dataStorage.deleteTable(tableName);
        tableService.createTable(tableName, new ArrayList<>(SCHEMA));

        benchmarkLoad(tableName, 50_000);

        benchmarkService.benchmarkSelect(tableName, List.of("fare_amount", "total_amount"), null);

        benchmarkService.benchmarkSelect(tableName, List.of("fare_amount", "total_amount"), "fare_amount>10");

        benchmarkService.benchmarkSelect(tableName, List.of("passenger_count", "trip_distance"), "store_and_fwd_flag='Y'");

        benchmarkService.benchmarkGroupBy(tableName, List.of("VendorID", "SUM(total_amount)"), null, List.of("VendorID"));

        benchmarkService.benchmarkGroupBy(tableName,
                List.of("passenger_count", "COUNT(VendorID)", "SUM(trip_distance)", "SUM(total_amount)", "SUM(tip_amount)"),
                null, List.of("passenger_count"));

        System.out.println("Warmup 50k terminé avec succès.");

        dataStorage.deleteTable(tableName);
        System.gc();
    }

    private void printDatasetInfo() {
        if (DATA_FORMAT != FileFormat.PARQUET) return;
        try {
            long totalRows = dataLoaderService.countParquetRows(DATA_PATH);
            System.out.printf("Lignes dans le fichier Parquet : %,d%n", totalRows);
            System.out.printf("Plus grand palier benchmark    : %,d%n", SCALES[SCALES.length - 1]);
        } catch (IOException e) {
            System.out.println("Impossible de lire les métadonnées Parquet : " + e.getMessage());
        }
    }

    // --- 2. EXPORT RÉSULTATS : requêtes réelles sur 100k lignes, hors chronométrage ---
    @Test
    @Order(2)
    @DisplayName("Export résultats — SELECT, WHERE, GROUP BY sur 100k lignes")
    void exportQueryResults() throws IOException {
        String tableName = "taxi_results_100k";
        dataStorage.deleteTable(tableName);
        tableService.createTable(tableName, new ArrayList<>(SCHEMA));
        benchmarkLoad(tableName, 100_000);

        Path outDir = Path.of("target/results");
        Files.createDirectories(outDir);

        Map<String, List<Map<String, Object>>> queries = new LinkedHashMap<>();
        queries.put("select",
                queryService.execute(tableName, List.of("fare_amount", "total_amount"), null, null));
        queries.put("where_simple",
                queryService.execute(tableName, List.of("fare_amount", "total_amount"), "fare_amount>10", null));
        queries.put("where_complex",
                queryService.execute(tableName, List.of("passenger_count", "trip_distance"), "payment_type=1", null));
        queries.put("group_by_simple",
                queryService.execute(tableName, List.of("VendorID", "SUM(total_amount)"), null, List.of("VendorID")));
        queries.put("group_by_complex",
                queryService.execute(tableName,
                        List.of("passenger_count", "COUNT(VendorID)", "SUM(trip_distance)", "SUM(total_amount)", "SUM(tip_amount)"),
                        null, List.of("passenger_count")));

        System.out.println("\n=== EXPORT RÉSULTATS (100k lignes) ===");
        for (Map.Entry<String, List<Map<String, Object>>> e : queries.entrySet()) {
            Path file = outDir.resolve(e.getKey() + ".csv");
            writeResultCsv(file, e.getValue());
            System.out.printf("  %-20s -> %s  (%,d lignes)%n",
                    e.getKey(), file.toAbsolutePath(), e.getValue().size());
        }

        dataStorage.deleteTable(tableName);
    }

    private static void writeResultCsv(Path file, List<Map<String, Object>> rows) throws IOException {
        if (rows.isEmpty()) {
            Files.writeString(file, "(aucun résultat)\n",
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            return;
        }
        List<String> cols = new ArrayList<>(rows.get(0).keySet());
        StringBuilder sb = new StringBuilder();
        sb.append(String.join(",", cols)).append("\n");
        for (Map<String, Object> row : rows) {
            StringJoiner sj = new StringJoiner(",");
            for (String col : cols) {
                String v = Objects.toString(row.get(col), "");
                if (v.contains(",") || v.contains("\"") || v.contains("\n"))
                    v = "\"" + v.replace("\"", "\"\"") + "\"";
                sj.add(v);
            }
            sb.append(sj).append("\n");
        }
        Files.writeString(file, sb.toString(),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    private BenchmarkService.BenchmarkResult benchmarkLoad(String tableName, int maxRows) {
        return switch (DATA_FORMAT) {
            case CSV     -> benchmarkService.benchmarkCsvLoad(tableName, DATA_PATH, maxRows);
            case PARQUET -> benchmarkService.benchmarkParquetLoad(tableName, DATA_PATH, maxRows);
        };
    }

    private BenchmarkService.BenchmarkResult benchmarkLoad(String tableName, int skipRows, int deltaRows) {
        return switch (DATA_FORMAT) {
            case CSV     -> benchmarkService.benchmarkCsvLoad(tableName, DATA_PATH, skipRows, deltaRows);
            case PARQUET -> benchmarkService.benchmarkParquetLoad(tableName, DATA_PATH, skipRows, deltaRows);
        };
    }

    private static String buildPythonScript() {
        return """
                import pandas as pd
                import matplotlib.pyplot as plt
                import matplotlib.ticker as mticker
                import os

                script_dir = os.path.dirname(os.path.abspath(__file__))
                csv_path = os.path.join(script_dir, "benchmark-real.csv")

                if not os.path.exists(csv_path):
                    print(f"Erreur : {csv_path} introuvable.")
                    exit(1)

                df = pd.read_csv(csv_path, comment='#')

                operations_in_csv = sorted(df['operation'].unique())
                colors = {
                    "LOAD_PARQUET":     "#2ecc71",
                    "LOAD_CSV":         "#27ae60",
                    "SELECT":           "#3498db",
                    "WHERE_SIMPLE":     "#f1c40f",
                    "WHERE_COMPLEX":    "#e67e22",
                    "GROUP_BY_SIMPLE":  "#9b59b6",
                    "GROUP_BY_COMPLEX": "#e74c3c",
                }

                ncols = 3
                nrows = -(-len(operations_in_csv) // ncols)
                fig, axes = plt.subplots(nrows, ncols, figsize=(18, 5 * nrows))
                fig.suptitle("FastBase — Benchmarks NYC Taxi", fontsize=16, fontweight='bold')
                axes = axes.flatten()

                for i, op in enumerate(operations_in_csv):
                    sub = df[df['operation'] == op].sort_values('lignesRetournees')
                    ax = axes[i]
                    if not sub.empty:
                        ax.plot(sub['lignesRetournees'], sub['elapsedMs'], marker='o', color=colors.get(op, '#333'), linewidth=2)
                        for x, y in zip(sub['lignesRetournees'], sub['elapsedMs']):
                            ax.annotate(f"{int(y)}ms", (x, y), textcoords="offset points", xytext=(0, 8), ha='center', fontsize=8)
                    ax.set_title(op.replace("_", " "))
                    ax.set_xlabel("Lignes retournees")
                    ax.set_ylabel("Temps (ms)")
                    ax.grid(True, linestyle='--', alpha=0.6)
                    ax.xaxis.set_major_formatter(mticker.FuncFormatter(lambda x, _: f"{int(x):,}".replace(",", " ")))

                for j in range(len(operations_in_csv), len(axes)):
                    axes[j].set_visible(False)

                plt.tight_layout()
                out = os.path.join(script_dir, "benchmark_results.png")
                plt.savefig(out, dpi=150)
                print(f"Graphique → {out}")
                plt.show()
                """;
    }
}