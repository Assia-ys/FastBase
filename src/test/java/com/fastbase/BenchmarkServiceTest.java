package com.fastbase;

import com.fastbase.model.Column;
import com.fastbase.model.Row;
import com.fastbase.model.Table;
import com.fastbase.model.enums.ColumnType;
import com.fastbase.service.QueryService;
import com.fastbase.service.DataLoaderService;
import com.fastbase.service.TableService;
import com.fastbase.storage.DataStorage;
import com.fastbase.storage.InMemoryStorage;
import com.fastbase.service.BenchmarkService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests de performance pour FastBase.
 *
 * Couvre :
 *   1. Benchmark LOAD       — insertion de 100k / 500k / 1M / 2M / 4M lignes
 *   2. Benchmark SELECT *   — scan complet sans filtre
 *   3. Benchmark WHERE      — filtre numérique sur une colonne indexée / non indexée
 *   4. Benchmark GROUP BY   — regroupement avec COUNT et SUM
 *   5. Comparatif avant/après : résultats consolidés exportés en CSV
 *
 * Les résultats CSV sont écrits dans target/benchmark-results.csv
 * (créé automatiquement à la fin de la suite).
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BenchmarkServiceTest {

    // -----------------------------------------------------------------------
    // Schéma de la table de test
    // Table : orders(id INTEGER, category VARCHAR, amount DOUBLE, region VARCHAR)
    // -----------------------------------------------------------------------
    private static final List<Column> SCHEMA = List.of(
            new Column("id",       ColumnType.INTEGER),
            new Column("category", ColumnType.VARCHAR),
            new Column("amount",   ColumnType.DOUBLE),
            new Column("region",   ColumnType.VARCHAR)
    );

    private static final String[] CATEGORIES = {"ELEC", "FOOD", "CLOTHING", "SPORT", "BEAUTY"};
    private static final String[] REGIONS     = {"NORD", "SUD", "EST", "OUEST", "CENTRE"};

    // Accumulation des résultats pour l'export CSV final
    private static final List<BenchmarkService.BenchmarkResult> ALL_RESULTS = new ArrayList<>();

    // Services partagés
    private static DataStorage    dataStorage;
    private static QueryService   queryService;
    private static BenchmarkService benchmarkService;
    private static TableService   tableService;

    // -----------------------------------------------------------------------
    // Setup / Teardown
    // -----------------------------------------------------------------------

    @BeforeAll
    static void globalSetup() {
        dataStorage      = new InMemoryStorage();
        queryService     = new QueryService(dataStorage);
        DataLoaderService dataLoaderService = new DataLoaderService(dataStorage);
        benchmarkService = new BenchmarkService(queryService, dataLoaderService);
        tableService     = new TableService(dataStorage);
    }

    @AfterAll
    static void exportResults() throws IOException {
        // Export CSV dans target/benchmark-results.csv
        Path output = Path.of("target/benchmark-results.csv");
        Files.createDirectories(output.getParent());
        String csv = benchmarkService.exportToCsv(ALL_RESULTS);
        Files.writeString(output, csv, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        System.out.println("\n=== RÉSULTATS EXPORTÉS → " + output.toAbsolutePath() + " ===");
        System.out.println(csv);
    }

    // -----------------------------------------------------------------------
    // Helper : génération de données
    // -----------------------------------------------------------------------

    /**
     * Génère {@code count} lignes de données synthétiques réalistes.
     */
    private List<Row> generateRows(int count) {
        Random rnd = new Random(42); // seed fixe pour reproductibilité
        List<Row> rows = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            Row row = new Row(4);
            row.setValue(0, i + 1);                             // id
            row.setValue(1, CATEGORIES[i % CATEGORIES.length]); // category
            double raw = rnd.nextDouble() * 9_900 + 100; // [100.0 .. 10000.0]
            row.setValue(2, Math.round(raw * 100) / 100.0); // arrondi à 2 décimales
            row.setValue(3, REGIONS[rnd.nextInt(REGIONS.length)]); // region
            rows.add(row);
        }
        return rows;
    }

    /**
     * Crée (ou recrée) une table propre dans le DataStorage.
     */
    private Table createFreshTable(String tableName) {
        // Supprime si elle existe déjà (reset entre tests paramétrés)
        dataStorage.deleteTable(tableName);
        return tableService.createTable(tableName, new ArrayList<>(SCHEMA));
    }

    // -----------------------------------------------------------------------
    // 1. BENCHMARK LOAD — paliers de 100k à 4M lignes
    // -----------------------------------------------------------------------

    @Test
    @Order(1)
    @DisplayName("LOAD — comparatif multi-paliers (100k → 4M lignes)")
    void benchmarkLoad_multipleScales() {
        int[] scales = {100_000, 500_000, 1_000_000, 2_000_000, 4_000_000};

        // Warmup JVM
        for (int i = 0; i < 3; i++) {
            Table w = createFreshTable("bench_warmup");
            w.addRows(generateRows(50_000));
            dataStorage.deleteTable("bench_warmup");
        }

        System.out.println("\n─── BENCHMARK LOAD ───────────────────────────────────");
        System.out.printf("%-12s %15s %15s%n", "Lignes", "Temps (ms)", "Temps (ns)");
        System.out.println("─".repeat(45));

        for (int scale : scales) {
            // Mesure : génération + insertion ensemble
            // durée totale ~50-800ms → bruit GC < 1%
            long t0    = System.nanoTime();
            Table table  = createFreshTable("bench_load_" + scale);
            List<Row> rows = generateRows(scale);
            table.addRows(rows);
            long elapsed = System.nanoTime() - t0;

            BenchmarkService.BenchmarkResult result =
                    new BenchmarkService.BenchmarkResult("LOAD", scale, elapsed / 1_000_000, elapsed);
            ALL_RESULTS.add(result);

            System.out.printf("%-12d %15.1f %,15d%n", scale, elapsed / 1_000_000.0, elapsed);

            rows.clear();
            dataStorage.deleteTable("bench_load_" + scale);
        }
    }

    // 2. BENCHMARK SELECT ciblé (category, amount)
    @ParameterizedTest(name = "SELECT category,amount sur {0} lignes")
    @ValueSource(ints = {100_000, 1_000_000, 4_000_000})
    @Order(2)
    @DisplayName("SELECT category, amount — projection ciblée sans filtre")
    void benchmarkSelectAll(int scale) {
        String tableName = "bench_select_" + scale;
        Table  table     = createFreshTable(tableName);
        table.addRows(generateRows(scale));

        BenchmarkService.BenchmarkResult result =
                benchmarkService.benchmarkSelect(tableName, List.of("category", "amount"), null);
        ALL_RESULTS.add(result);

        System.out.printf("[SELECT category,amount] %,d lignes → %d ms%n", scale, result.elapsedMs());
        assertThat(result.rowCount()).isEqualTo(scale);

        dataStorage.deleteTable(tableName);
    }

    // -----------------------------------------------------------------------
    // 3. BENCHMARK WHERE — filtre numérique
    // -----------------------------------------------------------------------

    @ParameterizedTest(name = "WHERE amount > 5000 sur {0} lignes")
    @ValueSource(ints = {100_000, 1_000_000, 4_000_000})
    @Order(3)
    @DisplayName("SELECT avec filtre WHERE (amount > 5000)")
    void benchmarkSelectWhere(int scale) {
        String tableName = "bench_where_" + scale;
        Table  table     = createFreshTable(tableName);
        table.addRows(generateRows(scale));

        BenchmarkService.BenchmarkResult result =
                benchmarkService.benchmarkSelect(tableName, List.of("category", "amount"), "amount>5000");
        ALL_RESULTS.add(result);

        System.out.printf("[WHERE amount>5000] %,d lignes scannées → %d lignes retournées en %d ms%n",
                scale, result.rowCount(), result.elapsedMs());

        assertThat(result.rowCount()).isBetween((long)(scale * 0.40), (long)(scale * 0.60));

        dataStorage.deleteTable(tableName);
    }

    // -----------------------------------------------------------------------
    // 4. BENCHMARK GROUP BY — agrégation
    // -----------------------------------------------------------------------

    @ParameterizedTest(name = "GROUP BY category sur {0} lignes")
    @ValueSource(ints = {100_000, 1_000_000, 4_000_000})
    @Order(4)
    @DisplayName("GROUP BY category avec COUNT(*) et SUM(amount)")
    void benchmarkGroupBy(int scale) {
        String tableName = "bench_groupby_" + scale;
        Table  table     = createFreshTable(tableName);
        table.addRows(generateRows(scale));

        BenchmarkService.BenchmarkResult result = benchmarkService.benchmarkGroupBy(
                tableName, List.of("category", "COUNT(*)", "SUM(amount)"), null, List.of("category"));
        ALL_RESULTS.add(result);

        System.out.printf("[GROUP BY category] %,d lignes → %d groupes en %d ms%n",
                scale, result.rowCount(), result.elapsedMs());

        assertThat(result.rowCount()).isEqualTo(CATEGORIES.length);

        dataStorage.deleteTable(tableName);
    }

    // -----------------------------------------------------------------------
    // 5. COMPARATIF : WHERE simple vs. WHERE + GROUP BY (même dataset 1M)
    // -----------------------------------------------------------------------

    @Test
    @Order(5)
    @DisplayName("Comparatif SELECT simple vs GROUP BY sur 1M lignes")
    void benchmarkComparison_selectVsGroupBy() {
        final int SCALE = 1_000_000;
        String tableName = "bench_compare_1M";
        Table  table     = createFreshTable(tableName);
        table.addRows(generateRows(SCALE));

        BenchmarkService.BenchmarkResult selectResult =
                benchmarkService.benchmarkSelect(tableName, List.of("id", "amount"), "region=NORD");

        BenchmarkService.BenchmarkResult groupByResult =
                benchmarkService.benchmarkGroupBy(
                        tableName,
                        List.of("region", "COUNT(*)", "SUM(amount)"),
                        null,
                        List.of("region"));

        ALL_RESULTS.add(selectResult);
        ALL_RESULTS.add(groupByResult);

        System.out.println("\n─── COMPARATIF 1M lignes ──────────────────────────────");
        System.out.printf("  SELECT WHERE region=NORD   → %,5d lignes en %4d ms%n",
                selectResult.rowCount(), selectResult.elapsedMs());
        System.out.printf("  GROUP BY region             → %,5d groupes en %4d ms%n",
                groupByResult.rowCount(), groupByResult.elapsedMs());

        assertThat(selectResult.elapsedMs()).isGreaterThanOrEqualTo(0);
        assertThat(groupByResult.elapsedMs()).isGreaterThanOrEqualTo(0);
    }

    // -----------------------------------------------------------------------
    // 6. BENCHMARK ORDER BY — tri sur grands volumes
    // -----------------------------------------------------------------------

    @Test
    @Order(6)
    @DisplayName("ORDER BY — tri sur grands volumes (100k → 4M)")
    void benchmarkOrderBy() {
        int[] scales = {100_000, 1_000_000, 4_000_000};

        // Warmup
        Table warmup = createFreshTable("warmup_orderby");
        warmup.addRows(generateRows(50_000));
        queryService.execute("warmup_orderby", List.of("category", "amount"), null, null, "amount", "DESC", null);
        dataStorage.deleteTable("warmup_orderby");

        System.out.println("\n─── BENCHMARK ORDER BY ───────────────────────────────");
        System.out.printf("%-12s %15s%n", "Lignes", "ORDER BY (ms)");
        System.out.println("─".repeat(30));

        for (int scale : scales) {
            String tableName = "bench_orderby_" + scale;
            Table table = createFreshTable(tableName);
            table.addRows(generateRows(scale));

            long t0 = System.nanoTime();
            List<Map<String, Object>> ordered = queryService.execute(
                    tableName, List.of("category", "amount"), null, null, "amount", "DESC", null);
            long ms = (System.nanoTime() - t0) / 1_000_000;

            ALL_RESULTS.add(new BenchmarkService.BenchmarkResult("ORDER_BY", scale, ms, ms * 1_000_000));
            System.out.printf("%-12d %15d ms%n", scale, ms);

            // Vérification tri correct sur un échantillon
            for (int i = 0; i < Math.min(ordered.size() - 1, 1000); i++) {
                double cur  = ((Number) ordered.get(i).get("amount")).doubleValue();
                double next = ((Number) ordered.get(i + 1).get("amount")).doubleValue();
                assertThat(cur).isGreaterThanOrEqualTo(next);
            }

            ordered = null;
            dataStorage.deleteTable(tableName);
            System.gc();
        }
    }

    // -----------------------------------------------------------------------
    // 7. BENCHMARK TOP-N — ORDER BY + LIMIT (pattern très courant)
    // -----------------------------------------------------------------------

    @Test
    @Order(7)
    @DisplayName("TOP-N — ORDER BY + LIMIT (heap O(n log N)) sur 4M lignes")
    void benchmarkTopN() {
        int[] topN = {1, 10, 100, 1_000};
        final int SCALE = 4_000_000;
        String tableName = "bench_topn";
        Table table = createFreshTable(tableName);
        table.addRows(generateRows(SCALE));

        // Warmup : active le JIT sur le chemin heap avant les mesures
        queryService.execute(tableName, List.of("category", "amount"), null, null, "amount", "DESC", 10);

        System.out.println("\n─── BENCHMARK TOP-N (heap O(n log N) sur 4M lignes) ──");
        System.out.printf("%-10s %15s%n", "LIMIT N", "Temps (ms)");
        System.out.println("─".repeat(28));

        for (int n : topN) {
            long t0 = System.nanoTime();
            List<Map<String, Object>> results = queryService.execute(
                    tableName, List.of("category", "amount"), null, null, "amount", "DESC", n);
            long ms = (System.nanoTime() - t0) / 1_000_000;

            ALL_RESULTS.add(new BenchmarkService.BenchmarkResult("TOP_" + n, n, ms, ms * 1_000_000));
            System.out.printf("%-10d %15d ms%n", n, ms);
            assertThat(results).hasSize(n);
        }

        dataStorage.deleteTable(tableName);
    }

    // -----------------------------------------------------------------------
    // 8. EXPORT CSV manuel
    // -----------------------------------------------------------------------

    @Test
    @Order(8)
    @DisplayName("Export CSV — vérification du format de sortie")
    void exportCsvFormat() {
        BenchmarkService.BenchmarkResult dummy =
                new BenchmarkService.BenchmarkResult("TEST_EXPORT", 999_999, 42L, 42_000_000L);

        String csv = benchmarkService.exportToCsv(dummy);

        assertThat(csv).startsWith("operation,rowCount,elapsedMs,elapsedNs\n");
        assertThat(csv).contains("TEST_EXPORT,999999,42,42000000");
        System.out.println("Format CSV validé :\n" + csv);
    }
}