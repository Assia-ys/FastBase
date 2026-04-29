package com.fastbase;

import com.fastbase.model.Column;
import com.fastbase.model.Table;
import com.fastbase.model.enums.ColumnType;
import com.fastbase.service.BenchmarkService;
import com.fastbase.service.DataLoaderService;
import com.fastbase.service.QueryService;
import com.fastbase.service.TableService;
import com.fastbase.storage.DataStorage;
import com.fastbase.storage.InMemoryStorage;
import org.junit.jupiter.api.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Benchmark sur données réelles NYC Yellow Taxi 2016-01 (19 colonnes, jusqu'à 4M lignes).
 * Utilise DataLoaderService pour un mapping correct par nom de colonne.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RealDataBenchmarkTest {

    private static final String DATA_PATH = "../data_NYC/yellow_tripdata_2016-01.csv";

    // Schéma complet des 19 colonnes du CSV NYC Taxi 2016
    private static final List<Column> SCHEMA = List.of(
            new Column("VendorID",              ColumnType.INTEGER),
            new Column("tpep_pickup_datetime",  ColumnType.STRING),
            new Column("tpep_dropoff_datetime", ColumnType.STRING),
            new Column("passenger_count",       ColumnType.INTEGER),
            new Column("trip_distance",         ColumnType.DOUBLE),
            new Column("pickup_longitude",      ColumnType.DOUBLE),
            new Column("pickup_latitude",       ColumnType.DOUBLE),
            new Column("RatecodeID",            ColumnType.INTEGER),
            new Column("store_and_fwd_flag",    ColumnType.STRING),
            new Column("dropoff_longitude",     ColumnType.DOUBLE),
            new Column("dropoff_latitude",      ColumnType.DOUBLE),
            new Column("payment_type",          ColumnType.INTEGER),
            new Column("fare_amount",           ColumnType.DOUBLE),
            new Column("extra",                 ColumnType.DOUBLE),
            new Column("mta_tax",               ColumnType.DOUBLE),
            new Column("tip_amount",            ColumnType.DOUBLE),
            new Column("tolls_amount",          ColumnType.DOUBLE),
            new Column("improvement_surcharge", ColumnType.DOUBLE),
            new Column("total_amount",          ColumnType.DOUBLE)
    );

    private static final int[] SCALES = {100_000, 500_000, 1_000_000, 2_000_000, 4_000_000};
    private static final List<String> CSV_LINES = new ArrayList<>();

    private static DataStorage      dataStorage;
    private static QueryService     queryService;
    private static BenchmarkService benchmarkService;
    private static TableService     tableService;
    private static DataLoaderService dataLoaderService;

    @BeforeAll
    static void setup() {
        dataStorage       = new InMemoryStorage();
        queryService      = new QueryService(dataStorage);
        dataLoaderService = new DataLoaderService(dataStorage);
        benchmarkService  = new BenchmarkService(queryService, dataLoaderService);
        tableService      = new TableService(dataStorage);

        CSV_LINES.add("operation,rowCount,elapsedMs,elapsedNs");
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

    // --- 1. BENCHMARK LOAD CSV réel (parsing + insertion) ---
    @Test
    @Order(1)
    @DisplayName("LOAD CSV réel — parsing + insertion (100k → 4M)")
    void benchmarkLoad() {
        System.out.println("\n─── BENCHMARK LOAD ───────────────────────────────────");
        for (int scale : SCALES) {
            String tableName = "taxi_load_" + scale;
            dataStorage.deleteTable(tableName);
            tableService.createTable(tableName, new ArrayList<>(SCHEMA));

            BenchmarkService.BenchmarkResult result =
                    benchmarkService.benchmarkCsvLoad(tableName, DATA_PATH, scale);
            CSV_LINES.add(result.toCsvLine());

            System.out.printf("%-12d %15d ms%n", scale, result.elapsedMs());
            assertThat(result.rowCount()).isEqualTo(scale);

            // Libération mémoire entre chaque palier
            dataStorage.deleteTable(tableName);
            System.gc();
        }
    }

    // --- 2. BENCHMARK SELECT ciblé (pas de SELECT *) ---
    @Test
    @Order(2)
    @DisplayName("SELECT fare_amount, total_amount — scan ciblé")
    void benchmarkSelect() {
        System.out.println("\n─── BENCHMARK SELECT ─────────────────────────────────");
        for (int scale : SCALES) {
            String tableName = "taxi_select_" + scale;
            prepareTable(tableName, scale);

            BenchmarkService.BenchmarkResult result = benchmarkService.benchmarkSelect(
                    tableName, List.of("fare_amount", "total_amount"), null);
            CSV_LINES.add(result.toCsvLine());

            System.out.printf("[SELECT] %,d lignes → %d ms%n", scale, result.elapsedMs());

            dataStorage.deleteTable(tableName);
            System.gc();
        }
    }

    // --- 3. BENCHMARK WHERE ---
    @Test
    @Order(3)
    @DisplayName("SELECT WHERE fare_amount > 10")
    void benchmarkSelectWhere() {
        System.out.println("\n─── BENCHMARK WHERE ──────────────────────────────────");
        for (int scale : SCALES) {
            String tableName = "taxi_where_" + scale;
            prepareTable(tableName, scale);

            BenchmarkService.BenchmarkResult result = benchmarkService.benchmarkSelect(
                    tableName, List.of("fare_amount", "total_amount"), "fare_amount>10");
            CSV_LINES.add(result.toCsvLine());

            System.out.printf("[WHERE fare_amount>10] %,d lignes → %d ms%n", scale, result.elapsedMs());

            dataStorage.deleteTable(tableName);
            System.gc();
        }
    }

    // --- 4. BENCHMARK GROUP BY ---
    @Test
    @Order(4)
    @DisplayName("GROUP BY VendorID avec COUNT et SUM(total_amount)")
    void benchmarkGroupBy() {
        System.out.println("\n─── BENCHMARK GROUP BY ───────────────────────────────");
        for (int scale : SCALES) {
            String tableName = "taxi_groupby_" + scale;
            prepareTable(tableName, scale);

            BenchmarkService.BenchmarkResult result = benchmarkService.benchmarkGroupBy(
                    tableName,
                    List.of("VendorID", "COUNT(trip_distance)", "SUM(total_amount)"),
                    null,
                    List.of("VendorID"));
            CSV_LINES.add(result.toCsvLine());

            System.out.printf("[GROUP BY VendorID] %,d lignes → %d groupes en %d ms%n",
                    scale, result.rowCount(), result.elapsedMs());

            dataStorage.deleteTable(tableName);
            System.gc();
        }
    }

    // --- Helper : prépare une table avec N lignes du CSV ---
    private void prepareTable(String tableName, int maxRows) {
        dataStorage.deleteTable(tableName);
        tableService.createTable(tableName, new ArrayList<>(SCHEMA));
        try {
            dataLoaderService.loadCsvData(tableName, DATA_PATH, maxRows);
        } catch (IOException e) {
            throw new RuntimeException("Erreur préparation table : " + e.getMessage(), e);
        }
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

                df = pd.read_csv(csv_path)
                operations = ["LOAD", "SELECT", "SELECT WHERE", "GROUP_BY"]
                colors     = {"LOAD": "#2ecc71", "SELECT": "#3498db", "SELECT WHERE": "#e67e22", "GROUP_BY": "#9b59b6"}

                fig, axes = plt.subplots(2, 2, figsize=(16, 10))
                fig.suptitle("FastBase — Benchmarks NYC Taxi (jusqu'à 4M lignes)", fontsize=15, fontweight='bold')
                axes = axes.flatten()

                for i, op in enumerate(operations):
                    sub = df[df['operation'].str.upper().str.contains(op.replace(" ", "_"), na=False)]
                    ax = axes[i]
                    if not sub.empty:
                        ax.plot(sub['rowCount'], sub['elapsedMs'], marker='o', color=colors.get(op, '#333'), linewidth=2)
                        for x, y in zip(sub['rowCount'], sub['elapsedMs']):
                            ax.annotate(f"{y}ms", (x, y), textcoords="offset points", xytext=(0, 8), ha='center', fontsize=8)
                    ax.set_title(op)
                    ax.set_xlabel("Nombre de lignes")
                    ax.set_ylabel("Temps (ms)")
                    ax.grid(True, linestyle='--', alpha=0.6)
                    ax.xaxis.set_major_formatter(mticker.FuncFormatter(lambda x, _: f"{int(x):,}".replace(",", " ")))

                plt.tight_layout()
                out = os.path.join(script_dir, "benchmark_results.png")
                plt.savefig(out, dpi=150)
                print(f"Graphique → {out}")
                plt.show()
                """;
    }
}
