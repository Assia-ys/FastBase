package com.fastbase;

import com.fastbase.model.Column;
import com.fastbase.model.Row;
import com.fastbase.model.Table;
import com.fastbase.model.enums.ColumnType;
import com.fastbase.service.BenchmarkService;
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
 * Benchmark réel finalisé (sans conflit mémoire).
 * Charge les données en streaming depuis le dossier externe data_NYC.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RealDataBenchmarkTest {

    private static final String DATA_PATH = "../data_NYC/yellow_tripdata_2016-01.csv";

    private static final List<Column> SCHEMA = List.of(
            new Column("VendorID", ColumnType.INTEGER),
            new Column("passenger_count", ColumnType.INTEGER),
            new Column("trip_distance", ColumnType.DOUBLE),
            new Column("fare_amount", ColumnType.DOUBLE),
            new Column("total_amount", ColumnType.DOUBLE)
    );

    private static final int[] SCALES = {100_000, 500_000, 1_000_000, 2_000_000, 4_000_000};
    private static final List<String> CSV_LINES = new ArrayList<>();

    private static DataStorage dataStorage;
    private static QueryService queryService;
    private static BenchmarkService benchmarkService;
    private static TableService tableService;

    @BeforeAll
    static void setup() {
        dataStorage = new InMemoryStorage();
        queryService = new QueryService(dataStorage);
        benchmarkService = new BenchmarkService(queryService);
        tableService = new TableService(dataStorage);

        CSV_LINES.add("operation,rowCount,elapsedMs,elapsedNs");
    }

    @AfterAll
    static void exportResults() throws IOException {
        Path csvOut = Path.of("target/benchmark-real.csv");
        Files.createDirectories(csvOut.getParent());
        Files.writeString(csvOut, String.join("\n", CSV_LINES) + "\n",
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

        Path pyOut = Path.of("target/plot_benchmark.py");
        Files.writeString(pyOut, buildPythonScript(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

        System.out.println("\n=== EXPORT TERMINÉ ===");
        System.out.println("CSV  → " + csvOut.toAbsolutePath());
        System.out.println("Plot → " + pyOut.toAbsolutePath());
    }

    // --- 1. BENCHMARK LOAD ---
    @Test
    @Order(1)
    void benchmarkLoad() throws IOException {
        System.out.println("\n─── BENCHMARK LOAD ───────────────────────────────────");
        for (int scale : SCALES) {
            String tableName = "taxi_load_" + scale;
            List<Row> rows = loadRowsFromFile(DATA_PATH, scale);

            dataStorage.deleteTable(tableName);
            Table table = tableService.createTable(tableName, new ArrayList<>(SCHEMA));

            BenchmarkService.BenchmarkResult result = benchmarkService.benchmarkLoad(table, rows);
            CSV_LINES.add(result.toCsvLine());

            System.out.printf("%-12d %15d ms%n", scale, result.elapsedMs());
            rows.clear();
            System.gc(); // Nettoyage mémoire préventif
        }
    }

    // --- 2. BENCHMARK SELECT * ---
    @Test
    @Order(2)
    @DisplayName("SELECT * — scan complet")
    void benchmarkSelectAll() {
        System.out.println("\n─── BENCHMARK SELECT * ───────────────────────────────");
        for (int scale : SCALES) {
            String tableName = "taxi_select_" + scale;
            prepareTable(tableName, scale);

            BenchmarkService.BenchmarkResult result = benchmarkService.benchmarkSelect(tableName, null, null);
            CSV_LINES.add(result.toCsvLine());

            System.out.printf("[SELECT *] %,d lignes → %d ms%n", scale, result.elapsedMs());
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

            BenchmarkService.BenchmarkResult result = benchmarkService.benchmarkSelect(tableName, null, "fare_amount>10");
            CSV_LINES.add(result.toCsvLine());

            System.out.printf("[WHERE] %,d lignes → %d ms%n", scale, result.elapsedMs());
        }
    }

    // --- HELPERS ---
    private List<Row> loadRowsFromFile(String path, int limit) throws IOException {
        List<Row> rows = new ArrayList<>(limit);
        try (BufferedReader br = new BufferedReader(new FileReader(path), 1 << 20)) {
            br.readLine(); // Skip header
            String line;
            int count = 0;
            while ((line = br.readLine()) != null && count < limit) {
                String[] parts = line.split(",", -1);
                Row row = new Row(SCHEMA.size());
                for (int c = 0; c < SCHEMA.size(); c++) {
                    String raw = c < parts.length ? parts[c].trim() : "";
                    row.setValue(c, parseValue(raw, SCHEMA.get(c).getType()));
                }
                rows.add(row);
                count++;
            }
        }
        return rows;
    }

    private void prepareTable(String tableName, int count) {
        try {
            dataStorage.deleteTable(tableName);
            Table table = tableService.createTable(tableName, new ArrayList<>(SCHEMA));
            List<Row> rows = loadRowsFromFile(DATA_PATH, count);
            table.addRows(rows);
            rows.clear();
            System.gc();
        } catch (IOException e) {
            throw new RuntimeException("Erreur de préparation : " + e.getMessage());
        }
    }

    private Object parseValue(String raw, ColumnType type) {
        if (raw == null || raw.isEmpty()) return null;
        try {
            return switch (type) {
                case INTEGER -> Integer.parseInt(raw);
                case DOUBLE -> Double.parseDouble(raw);
                default -> raw;
            };
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String buildPythonScript() {
        return """
                import pandas as pd
                import matplotlib.pyplot as plt
                import matplotlib.ticker as mticker
                import os
                
                # --- Configuration des chemins ---
                script_dir = os.path.dirname(os.path.abspath(__file__))
                csv_path = os.path.join(script_dir, "benchmark-real.csv")
                
                if not os.path.exists(csv_path):
                    print(f"Erreur : Le fichier {csv_path} est introuvable.")
                    exit(1)
                
                # --- Chargement des données ---
                df = pd.read_csv(csv_path)
                
                # --- Création de la figure ---
                fig, axes = plt.subplots(1, 3, figsize=(18, 6))
                fig.suptitle("FastBase - Benchmarks NYC Taxi (4 Millions de lignes)", fontsize=16, fontweight='bold')
                
                # Couleurs et styles
                colors = {"LOAD": "#2ecc71", "SELECT": "#3498db", "SELECT WHERE": "#e67e22"}
                
                def plot_sub_bench(ax, operation_name, color, title):
                    sub_df = df[df['operation'].str.contains(operation_name, na=False)]
                    if not sub_df.empty:
                        ax.plot(sub_df['rowCount'], sub_df['elapsedMs'], marker='o', linestyle='-', color=color, linewidth=2)
                        # Ajout des étiquettes de temps sur les points
                        for x, y in zip(sub_df['rowCount'], sub_df['elapsedMs']):
                            ax.annotate(f"{y}ms", (x, y), textcoords="offset points", xytext=(0,10), ha='center', fontsize=9)
                
                    ax.set_title(title)
                    ax.set_xlabel("Nombre de lignes")
                    ax.set_ylabel("Temps (ms)")
                    ax.grid(True, linestyle='--', alpha=0.7)
                    # Formatage de l'axe X pour afficher en Millions (M)
                    ax.xaxis.set_major_formatter(mticker.FuncFormatter(lambda x, p: format(int(x), ',').replace(',', ' ')))
                
                # 1. Graphique LOAD
                plot_sub_bench(axes[0], "LOAD", colors["LOAD"], "Ingestion (LOAD)")
                
                # 2. Graphique SELECT *
                plot_sub_bench(axes[1], "SELECT", colors["SELECT"], "Scan complet (SELECT *)")
                
                # 3. Graphique SELECT WHERE
                plot_sub_bench(axes[2], "WHERE", colors["SELECT WHERE"], "Filtre (WHERE fare_amount > 10)")
                
                plt.tight_layout(rect=[0, 0.03, 1, 0.95])
                
                # Sauvegarde
                output_png = os.path.join(script_dir, "benchmark_results.png")
                plt.savefig(output_png, dpi=150)
                print(f"Graphique généré avec succès : {output_png}")
                plt.show()
                """;
    }
}