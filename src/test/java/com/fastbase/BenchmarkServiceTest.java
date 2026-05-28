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
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Assumptions;

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

    // Schéma complet NYC Yellow Taxi 2022 (19 colonnes) — pour le benchmark grand volume
    private static final List<Column> SCHEMA_TAXI = List.of(
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
            new Column("airport_fee",           ColumnType.DOUBLE));

    private static final double[] EXTRA_VALUES = {0.0, 0.5, 1.0};

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
    // 6. BENCHMARK WHERE AND/OR — conditions composées
    // -----------------------------------------------------------------------

    @Test
    @Order(6)
    @DisplayName("WHERE AND/OR — comparatif simple vs composé sur 4M lignes")
    void benchmarkWhereAndOr() {
        final int SCALE = 1_000_000;
        String tableName = "bench_andor";
        Table table = createFreshTable(tableName);
        table.addRows(generateRows(SCALE));

        // Warmup
        queryService.execute(tableName, List.of("category", "amount"), "amount>5000", null, null, null, null);

        System.out.println("\n─── BENCHMARK WHERE AND/OR (4M lignes) ──────────────");

        long t1 = System.nanoTime();
        List<Map<String, Object>> r1 = queryService.execute(
                tableName, List.of("category", "amount"), "amount>5000", null, null, null, null);
        long ms1 = (System.nanoTime() - t1) / 1_000_000;

        long t2 = System.nanoTime();
        List<Map<String, Object>> r2 = queryService.execute(
                tableName, List.of("category", "amount"), "amount>5000 AND category=ELEC", null, null, null, null);
        long ms2 = (System.nanoTime() - t2) / 1_000_000;

        long t3 = System.nanoTime();
        List<Map<String, Object>> r3 = queryService.execute(
                tableName, List.of("category", "amount"), "amount>5000 AND category=ELEC OR category=FOOD", null, null, null, null);
        long ms3 = (System.nanoTime() - t3) / 1_000_000;

        System.out.printf("  WHERE simple         → %,7d résultats en %4d ms%n", r1.size(), ms1);
        System.out.printf("  WHERE AND            → %,7d résultats en %4d ms%n", r2.size(), ms2);
        System.out.printf("  WHERE AND + OR       → %,7d résultats en %4d ms%n", r3.size(), ms3);

        ALL_RESULTS.add(new BenchmarkService.BenchmarkResult("WHERE_SIMPLE", r1.size(), ms1, ms1 * 1_000_000));
        ALL_RESULTS.add(new BenchmarkService.BenchmarkResult("WHERE_AND",    r2.size(), ms2, ms2 * 1_000_000));
        ALL_RESULTS.add(new BenchmarkService.BenchmarkResult("WHERE_AND_OR", r3.size(), ms3, ms3 * 1_000_000));

        r1 = null; r2 = null; r3 = null;
        dataStorage.deleteTable(tableName);
        System.gc();
    }

    // -----------------------------------------------------------------------
    // 7. BENCHMARK ORDER BY — tri sur grands volumes
    // -----------------------------------------------------------------------

    @Test
    @Order(7)
    @DisplayName("ORDER BY — tri sur grands volumes (100k → 4M)")
    void benchmarkOrderBy() {
        int[] scales = { 1_000_000, 4_000_000};

        // Warmup avec 1M lignes pour déclencher parallelSort (seuil 500k)
        Table warmup = createFreshTable("warmup_orderby");
        warmup.addRows(generateRows(600_000));
        queryService.execute("warmup_orderby", List.of("category", "amount"), null, null, "amount", "DESC", null);
        dataStorage.deleteTable("warmup_orderby");
        System.gc();
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
    @Order(8)
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
    // 9. BENCHMARK PROGRESSIF 19 COLONNES NYC TAXI
    //    Une seule table, chargement incrémental : 4M→20M→50M→60M.
    //    Requêtes : SELECT, WHERE simple/complexe, GROUP BY simple/complexe, TOP-N.
    // -----------------------------------------------------------------------

    @Test
    @Order(9)
    @DisplayName("BENCHMARK NYC Taxi 19 col — 4M→20M→50M→60M, toutes requêtes")
    void benchmarkTaxiScales() {
        Assumptions.assumeTrue(
                Runtime.getRuntime().maxMemory() >= 8L * 1024 * 1024 * 1024,
                "Heap < 8 GB — relancer avec -Xmx12g");

        int[] milestones = {4_000_000, 20_000_000, 50_000_000, 60_000_000};
        long maxMem = Runtime.getRuntime().maxMemory();

        String tableName = "bench_taxi";
        dataStorage.deleteTable(tableName);
        tableService.createTable(tableName, new ArrayList<>(SCHEMA_TAXI));

        System.out.println("\n─── BENCHMARK NYC TAXI 19 COLONNES — 4M / 20M / 50M / 60M ────────────────────────────────────────");
        System.out.printf("%-10s %7s %7s %8s %8s %8s %8s %8s%n",
                "Lignes", "LOAD", "SELECT", "WHERE_S", "WHERE_C", "GRP_S", "GRP_C", "TOP100");
        System.out.println("─".repeat(76));

        int  loaded          = 0;
        long cumulativeLoadMs = 0;
        for (int milestone : milestones) {
            long estMemMB = (long) milestone * 152 / 1024 / 1024;
            long threshMB = maxMem * 9 / 10 / 1024 / 1024;
            if (estMemMB > threshMB) {
                System.out.printf("%-10d  SKIPPED — ~%,d MB > seuil %,d MB%n", milestone, estMemMB, threshMB);
                continue;
            }

            // GC avant allocation pour libérer les temporaires du palier précédent
            System.gc();
            try { Thread.sleep(800); } catch (InterruptedException ignored) {}
            System.gc();

            dataStorage.getTable(tableName).orElseThrow().reserveCapacity(milestone);

            int delta = milestone - loaded;
            long t0 = System.nanoTime();
            fillDirectTaxiDelta(tableName, delta);
            cumulativeLoadMs += (System.nanoTime() - t0) / 1_000_000;
            loaded = milestone;
            String tag = milestone / 1_000_000 + "M";

            // LOAD affiché = temps cumulé depuis le début (plus intuitif que le delta seul)
            ALL_RESULTS.add(new BenchmarkService.BenchmarkResult("LOAD_" + tag, milestone, cumulativeLoadMs, cumulativeLoadMs * 1_000_000));

            // SELECT fare_amount, total_amount — scan complet
            BenchmarkService.BenchmarkResult sel   = benchmarkService.benchmarkSelect(tableName, List.of("fare_amount", "total_amount"), null);
            ALL_RESULTS.add(new BenchmarkService.BenchmarkResult("SELECT_" + tag,   sel.rowCount(),   sel.elapsedMs(), sel.elapsedNs()));

            // WHERE simple : numérique (fare_amount > 10, ~90% passent)
            BenchmarkService.BenchmarkResult whereS = benchmarkService.benchmarkSelect(tableName, List.of("fare_amount", "total_amount"), "fare_amount>10");
            ALL_RESULTS.add(new BenchmarkService.BenchmarkResult("WHERE_S_" + tag,  whereS.rowCount(), whereS.elapsedMs(), whereS.elapsedNs()));

            // WHERE complexe : entier (payment_type = 1, ~25% passent)
            BenchmarkService.BenchmarkResult whereC = benchmarkService.benchmarkSelect(tableName, List.of("passenger_count", "trip_distance"), "payment_type=1");
            ALL_RESULTS.add(new BenchmarkService.BenchmarkResult("WHERE_C_" + tag,  whereC.rowCount(), whereC.elapsedMs(), whereC.elapsedNs()));

            // GROUP BY simple : VendorID (2 groupes), 1 agrégat SUM
            BenchmarkService.BenchmarkResult grpS  = benchmarkService.benchmarkGroupBy(tableName,
                    List.of("VendorID", "SUM(total_amount)"), null, List.of("VendorID"));
            ALL_RESULTS.add(new BenchmarkService.BenchmarkResult("GRP_S_" + tag,    grpS.rowCount(),  grpS.elapsedMs(), grpS.elapsedNs()));

            // GROUP BY complexe : passenger_count (6 groupes), 4 agrégats
            BenchmarkService.BenchmarkResult grpC  = benchmarkService.benchmarkGroupBy(tableName,
                    List.of("passenger_count", "COUNT(VendorID)", "SUM(trip_distance)", "SUM(total_amount)", "SUM(tip_amount)"),
                    null, List.of("passenger_count"));
            ALL_RESULTS.add(new BenchmarkService.BenchmarkResult("GRP_C_" + tag,    grpC.rowCount(),  grpC.elapsedMs(), grpC.elapsedNs()));

            // TOP-N : ORDER BY fare_amount DESC LIMIT 100 (heap O(n log N))
            long tTop = System.nanoTime();
            List<Map<String, Object>> top100 = queryService.execute(
                    tableName, List.of("fare_amount"), null, null, "fare_amount", "DESC", 100);
            long topMs = (System.nanoTime() - tTop) / 1_000_000;
            ALL_RESULTS.add(new BenchmarkService.BenchmarkResult("TOP100_" + tag,   top100.size(),    topMs,           topMs * 1_000_000));

            System.out.printf("%-10d %7d %7d %8d %8d %8d %8d %8d%n",
                    milestone, cumulativeLoadMs, sel.elapsedMs(), whereS.elapsedMs(), whereC.elapsedMs(),
                    grpS.elapsedMs(), grpC.elapsedMs(), topMs);

            assertThat(sel.rowCount()).isEqualTo(milestone);
            assertThat(grpS.rowCount()).isEqualTo(2);
            assertThat(grpC.rowCount()).isEqualTo(6);
            assertThat(top100).hasSize(100);
        }

        dataStorage.deleteTable(tableName);
        System.gc();
    }

    /**
     * Ajoute {@code count} lignes au stockage colonnaire NYC Taxi 19 colonnes.
     * Reprend depuis table.getRowCount() existant — zéro objet Row, zéro GC.
     * reserveCapacity doit être appelé avant cette méthode (depuis le test).
     */
    private void fillDirectTaxiDelta(String tableName, int count) {
        Table table = dataStorage.getTable(tableName).orElseThrow();
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        String flagN = "N".intern();
        String flagY = "Y".intern();
        final long BASE_TS = 1_640_995_200L; // 2022-01-01 00:00:00 UTC
        final int BATCH = 100_000;
        for (int base = 0; base < count; ) {
            int actual = Math.min(BATCH, count - base);
            int start  = table.allocateBatch(actual);
            for (int j = 0; j < actual; j++) {
                int ri = start + j;
                // 0: VendorID (1 ou 2, alternés)
                table.setColumnValue(ri,  0, (ri % 2) + 1);
                // 1-2: timestamps (pickup aléatoire sur janvier 2022, dropoff = pickup + 5..60 min)
                long pickup = BASE_TS + rnd.nextLong(0, 2_678_400L);
                table.setColumnValue(ri,  1, pickup);
                table.setColumnValue(ri,  2, pickup + rnd.nextLong(300L, 3_600L));
                // 3-5: passenger_count, trip_distance, RatecodeID
                table.setColumnValue(ri,  3, (double) rnd.nextInt(1, 7));
                table.setColumnValue(ri,  4, Math.round(rnd.nextDouble(0.1, 30.0) * 10) / 10.0);
                table.setColumnValue(ri,  5, (double) rnd.nextInt(1, 7));
                // 6: store_and_fwd_flag ("N" 99%, "Y" 1%)
                table.setColumnValue(ri,  6, rnd.nextInt(100) < 1 ? flagY : flagN);
                // 7-9: PULocationID, DOLocationID, payment_type
                table.setColumnValue(ri,  7, rnd.nextInt(1, 266));
                table.setColumnValue(ri,  8, rnd.nextInt(1, 266));
                table.setColumnValue(ri,  9, rnd.nextInt(1, 5));
                // 10: fare_amount [2.5 .. 80.0]
                double fare = Math.round(rnd.nextDouble(2.5, 80.0) * 100) / 100.0;
                table.setColumnValue(ri, 10, fare);
                // 11: extra (0.0 / 0.5 / 1.0)
                double extra = EXTRA_VALUES[rnd.nextInt(3)];
                table.setColumnValue(ri, 11, extra);
                // 12: mta_tax (0.5 fixe)
                table.setColumnValue(ri, 12, 0.5);
                // 13: tip_amount [0.0 .. 15.0]
                double tip = Math.round(rnd.nextDouble(0.0, 15.0) * 100) / 100.0;
                table.setColumnValue(ri, 13, tip);
                // 14: tolls_amount (0 la plupart du temps)
                double tolls = rnd.nextInt(10) < 1 ? Math.round(rnd.nextDouble(1.0, 8.0) * 100) / 100.0 : 0.0;
                table.setColumnValue(ri, 14, tolls);
                // 15: improvement_surcharge (0.3 fixe)
                table.setColumnValue(ri, 15, 0.3);
                // 16: total_amount = somme des composantes
                table.setColumnValue(ri, 16, Math.round((fare + extra + 0.5 + tip + tolls + 0.3) * 100) / 100.0);
                // 17: congestion_surcharge (2.5 à 70%, 0.0 sinon)
                table.setColumnValue(ri, 17, rnd.nextInt(10) < 7 ? 2.5 : 0.0);
                // 18: airport_fee (1.25 à 5%, 0.0 sinon)
                table.setColumnValue(ri, 18, rnd.nextInt(20) < 1 ? 1.25 : 0.0);
            }
            base += actual;
        }
    }

    // -----------------------------------------------------------------------
    // 10. EXPORT CSV manuel
    // -----------------------------------------------------------------------

    @Test
    @Order(10)
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