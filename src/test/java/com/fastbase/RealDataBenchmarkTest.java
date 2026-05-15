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
            "fastbase.benchmark.path", "../data_NYC/yellow_tripdata_2016-05.parquet");

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

        for (int scale : SCALES) {
            String tableName = "taxi_bench_" + scale;
            dataStorage.deleteTable(tableName);
            tableService.createTable(tableName, new ArrayList<>(SCHEMA));

            // 0. LOAD
            BenchmarkService.BenchmarkResult load = benchmarkLoad(tableName, scale);
            CSV_LINES.add(load.toCsvLine());
            assertThat(load.rowCount()).isEqualTo(scale);

            // 1. SELECT pur
            BenchmarkService.BenchmarkResult select = benchmarkService.benchmarkSelect(
                    tableName, List.of("fare_amount", "total_amount"), null);
            CSV_LINES.add(select.toCsvLine());

            // 2. WHERE Simple (Comparaison numérique)
            BenchmarkService.BenchmarkResult whereSimple = benchmarkService.benchmarkSelect(
                    tableName, List.of("fare_amount", "total_amount"), "fare_amount>10");
            CSV_LINES.add(new BenchmarkService.BenchmarkResult(
                    "WHERE_SIMPLE", whereSimple.rowCount(), whereSimple.elapsedMs(), whereSimple.elapsedNs()).toCsvLine());

            // 3. WHERE Complexe (Comparaison de texte string, plus coûteux)
            BenchmarkService.BenchmarkResult whereComplex = benchmarkService.benchmarkSelect(
                    tableName, List.of("passenger_count", "trip_distance"), "store_and_fwd_flag='Y'");
            CSV_LINES.add(new BenchmarkService.BenchmarkResult(
                    "WHERE_COMPLEX", whereComplex.rowCount(), whereComplex.elapsedMs(), whereComplex.elapsedNs()).toCsvLine());

            // 4. GROUP BY Simple (1 clé à faible cardinalité, 1 aggrégation)
            BenchmarkService.BenchmarkResult groupBySimple = benchmarkService.benchmarkGroupBy(
                    tableName,
                    List.of("VendorID", "SUM(total_amount)"),
                    null,
                    List.of("VendorID"));
            CSV_LINES.add(new BenchmarkService.BenchmarkResult(
                    "GROUP_BY_SIMPLE", groupBySimple.rowCount(), groupBySimple.elapsedMs(), groupBySimple.elapsedNs()).toCsvLine());

            // 5. GROUP BY Complexe (Multiples aggrégations gourmandes en calcul)
            BenchmarkService.BenchmarkResult groupByComplex = benchmarkService.benchmarkGroupBy(
                    tableName,
                    List.of("passenger_count", "COUNT(VendorID)", "SUM(trip_distance)", "SUM(total_amount)", "SUM(tip_amount)"),
                    null,
                    List.of("passenger_count"));
            CSV_LINES.add(new BenchmarkService.BenchmarkResult(
                    "GROUP_BY_COMPLEX", groupByComplex.rowCount(), groupByComplex.elapsedMs(), groupByComplex.elapsedNs()).toCsvLine());

            // Affichage console
            System.out.printf("%-10d %7d ms %7d ms %11d ms %11d ms %14d ms %14d ms%n",
                    scale, load.elapsedMs(), select.elapsedMs(), whereSimple.elapsedMs(),
                    whereComplex.elapsedMs(), groupBySimple.elapsedMs(), groupByComplex.elapsedMs());

            dataStorage.deleteTable(tableName);
            System.gc();
        }
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

    private BenchmarkService.BenchmarkResult benchmarkLoad(String tableName, int maxRows) {
        return switch (DATA_FORMAT) {
            case CSV     -> benchmarkService.benchmarkCsvLoad(tableName, DATA_PATH, maxRows);
            case PARQUET -> benchmarkService.benchmarkParquetLoad(tableName, DATA_PATH, maxRows);
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

                df = pd.read_csv(csv_path)
                
                # Mise à jour des opérations et des couleurs
                operations = ["LOAD", "SELECT", "WHERE_SIMPLE", "WHERE_COMPLEX", "GROUP_BY_SIMPLE", "GROUP_BY_COMPLEX"]
                colors = {
                    "LOAD": "#2ecc71", 
                    "SELECT": "#3498db", 
                    "WHERE_SIMPLE": "#f1c40f", 
                    "WHERE_COMPLEX": "#e67e22", 
                    "GROUP_BY_SIMPLE": "#9b59b6", 
                    "GROUP_BY_COMPLEX": "#e74c3c"
                }

                # Grille 2 lignes x 3 colonnes pour afficher 6 graphiques
                fig, axes = plt.subplots(2, 3, figsize=(18, 10))
                fig.suptitle("FastBase — Benchmarks NYC Taxi (jusqu'à 4M lignes)", fontsize=16, fontweight='bold')
                axes = axes.flatten()

                for i, op in enumerate(operations):
                    # Filtrage exact pour éviter les conflits de noms
                    sub = df[df['operation'] == op]
                    ax = axes[i]
                    if not sub.empty:
                        ax.plot(sub['rowCount'], sub['elapsedMs'], marker='o', color=colors.get(op, '#333'), linewidth=2)
                        for x, y in zip(sub['rowCount'], sub['elapsedMs']):
                            ax.annotate(f"{y}ms", (x, y), textcoords="offset points", xytext=(0, 8), ha='center', fontsize=8)
                    ax.set_title(op.replace("_", " "))
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