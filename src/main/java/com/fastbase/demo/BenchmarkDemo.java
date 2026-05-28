package com.fastbase.demo;

import com.fastbase.model.Column;
import com.fastbase.model.enums.ColumnType;
import com.fastbase.service.DataLoaderService;
import com.fastbase.service.QueryService;
import com.fastbase.service.TableService;
import com.fastbase.storage.DataStorage;
import com.fastbase.storage.InMemoryStorage;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/**
 * Point d'entrée pour la démonstration FastBase.
 * Lancer depuis IntelliJ (bouton Run) ou via :
 *   mvnw.cmd compile exec:java -Dexec.mainClass=com.fastbase.demo.BenchmarkDemo
 *
 * Produit dans target/demo/ :
 *   benchmark.csv           — temps LOAD + 4 requêtes par palier
 *   requete1_resultats.csv  — résultats Requête 1 sur données complètes
 *   requete2_resultats.csv  — résultats Requête 2
 *   requete3_resultats.csv  — résultats Requête 3
 *   requete4_resultats.csv  — résultats Requête 4
 */
public class BenchmarkDemo {

    private static final String DATA_PATH =
            System.getProperty("fastbase.demo.path", "../data_NYC/yellow_tripdata_2016-01.parquet");

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

    // ── Requête 1 ────────────────────────────────────────────────────────
    // SELECT payment_type,
    //        COUNT(trip_distance) AS nb_courses,
    //        SUM(total_amount)    AS revenu_total,
    //        AVG(fare_amount)     AS tarif_base_moyen,
    //        MIN(trip_distance)   AS distance_min,
    //        MAX(trip_distance)   AS distance_max
    // FROM trip_data
    // GROUP BY payment_type
    // ORDER BY revenu_total DESC
    private static final List<String> R1_COLS    = List.of(
            "payment_type", "COUNT(trip_distance)", "SUM(total_amount)",
            "AVG(fare_amount)", "MIN(trip_distance)", "MAX(trip_distance)");
    private static final List<String> R1_GROUPBY = List.of("payment_type");
    private static final String       R1_ORDERBY = "SUM(total_amount)";
    private static final String       R1_DIR     = "DESC";
    private static final Map<String, String> R1_ALIASES = aliases(
            "payment_type",         "payment_type",
            "COUNT(trip_distance)", "nb_courses",
            "SUM(total_amount)",    "revenu_total",
            "AVG(fare_amount)",     "tarif_base_moyen",
            "MIN(trip_distance)",   "distance_min",
            "MAX(trip_distance)",   "distance_max");

    // ── Requête 2 ────────────────────────────────────────────────────────
    // SELECT passenger_count        AS nb_passagers,
    //        COUNT(trip_distance)   AS nb_courses,
    //        AVG(trip_distance)     AS distance_moyenne,
    //        AVG(tip_amount)        AS pourboire_moyen,
    //        SUM(total_amount)      AS revenu_total
    // FROM trip_data
    // WHERE passenger_count > 0 AND trip_distance > 0
    // GROUP BY passenger_count
    // ORDER BY revenu_total DESC
    private static final List<String> R2_COLS    = List.of(
            "passenger_count", "COUNT(trip_distance)", "AVG(trip_distance)",
            "AVG(tip_amount)", "SUM(total_amount)");
    private static final String       R2_WHERE   = "passenger_count > 0 AND trip_distance > 0";
    private static final List<String> R2_GROUPBY = List.of("passenger_count");
    private static final String       R2_ORDERBY = "SUM(total_amount)";
    private static final String       R2_DIR     = "DESC";
    private static final Map<String, String> R2_ALIASES = aliases(
            "passenger_count",      "nb_passagers",
            "COUNT(trip_distance)", "nb_courses",
            "AVG(trip_distance)",   "distance_moyenne",
            "AVG(tip_amount)",      "pourboire_moyen",
            "SUM(total_amount)",    "revenu_total");

    // ── Requête 3 ────────────────────────────────────────────────────────
    // SELECT DOLocationID,
    //        COUNT(trip_distance)   AS nb_courses,
    //        SUM(tip_amount)        AS total_pourboires,
    //        AVG(tip_amount)        AS pourboire_moyen,
    //        MAX(tip_amount)        AS pourboire_max
    // FROM trip_data
    // WHERE tip_amount > 0
    // GROUP BY DOLocationID
    // ORDER BY total_pourboires DESC
    private static final List<String> R3_COLS    = List.of(
            "DOLocationID", "COUNT(trip_distance)", "SUM(tip_amount)",
            "AVG(tip_amount)", "MAX(tip_amount)");
    private static final String       R3_WHERE   = "tip_amount > 0";
    private static final List<String> R3_GROUPBY = List.of("DOLocationID");
    private static final String       R3_ORDERBY = "SUM(tip_amount)";
    private static final String       R3_DIR     = "DESC";
    private static final Map<String, String> R3_ALIASES = aliases(
            "DOLocationID",         "DOLocationID",
            "COUNT(trip_distance)", "nb_courses",
            "SUM(tip_amount)",      "total_pourboires",
            "AVG(tip_amount)",      "pourboire_moyen",
            "MAX(tip_amount)",      "pourboire_max");

    // ── Requête 4 ────────────────────────────────────────────────────────
    // SELECT payment_type,
    //        SUM(total_amount) AS revenu_total
    // FROM trip_data
    // GROUP BY payment_type
    // ORDER BY revenu_total DESC
    private static final List<String> R4_COLS    = List.of("payment_type", "SUM(total_amount)");
    private static final List<String> R4_GROUPBY = List.of("payment_type");
    private static final String       R4_ORDERBY = "SUM(total_amount)";
    private static final String       R4_DIR     = "DESC";
    private static final Map<String, String> R4_ALIASES = aliases(
            "payment_type",      "payment_type",
            "SUM(total_amount)", "revenu_total");

    // ── Requête 5 ────────────────────────────────────────────────────────
    // SELECT payment_type, passenger_count,
    //        COUNT(trip_distance) AS nb_courses, SUM(total_amount) AS revenu_total,
    //        AVG(fare_amount) AS tarif_moyen, AVG(tip_amount) AS pourboire_moyen,
    //        AVG(trip_distance) AS distance_moyenne, SUM(tip_amount) AS pourboire_total,
    //        MIN(trip_distance) AS distance_min, MAX(trip_distance) AS distance_max,
    //        MIN(total_amount) AS montant_min, MAX(total_amount) AS montant_max,
    //        AVG(total_amount) AS montant_moyen, SUM(fare_amount) AS tarif_total,
    //        SUM(tolls_amount) AS peages_total, AVG(tolls_amount) AS peages_moyen,
    //        SUM(mta_tax) AS mta_tax_total, SUM(improvement_surcharge) AS surcharge_total,
    //        SUM(extra) AS extra_total, COUNT(tip_amount) AS nb_avec_tip
    // FROM trip_data
    // GROUP BY payment_type, passenger_count
    // ORDER BY revenu_total DESC
    private static final List<String> R5_COLS    = List.of(
            "payment_type", "passenger_count",
            "COUNT(trip_distance)", "SUM(total_amount)",
            "AVG(fare_amount)", "AVG(tip_amount)", "AVG(trip_distance)",
            "SUM(tip_amount)", "MIN(trip_distance)", "MAX(trip_distance)",
            "MIN(total_amount)", "MAX(total_amount)", "AVG(total_amount)",
            "SUM(fare_amount)", "SUM(tolls_amount)", "AVG(tolls_amount)",
            "SUM(mta_tax)", "SUM(improvement_surcharge)", "SUM(extra)", "COUNT(tip_amount)");
    private static final List<String> R5_GROUPBY = List.of("payment_type", "passenger_count");
    private static final String       R5_ORDERBY = "SUM(total_amount)";
    private static final String       R5_DIR     = "DESC";
    // Toutes les colonnes — utilisées pour l'export CSV
    private static final Map<String, String> R5_ALIASES = aliases(
            "payment_type",              "payment_type",
            "passenger_count",           "passenger_count",
            "COUNT(trip_distance)",      "nb_courses",
            "SUM(total_amount)",         "revenu_total",
            "AVG(fare_amount)",          "tarif_moyen",
            "AVG(tip_amount)",           "pourboire_moyen",
            "AVG(trip_distance)",        "distance_moyenne",
            "SUM(tip_amount)",           "pourboire_total",
            "MIN(trip_distance)",        "distance_min",
            "MAX(trip_distance)",        "distance_max",
            "MIN(total_amount)",         "montant_min",
            "MAX(total_amount)",         "montant_max",
            "AVG(total_amount)",         "montant_moyen",
            "SUM(fare_amount)",          "tarif_total",
            "SUM(tolls_amount)",         "peages_total",
            "AVG(tolls_amount)",         "peages_moyen",
            "SUM(mta_tax)",              "mta_tax_total",
            "SUM(improvement_surcharge)","surcharge_total",
            "SUM(extra)",                "extra_total",
            "COUNT(tip_amount)",         "nb_avec_tip");
    // Colonnes clés pour l'affichage console (20 colonnes = trop large)
    private static final Map<String, String> R5_DISPLAY = aliases(
            "payment_type",         "payment_type",
            "passenger_count",      "passenger_count",
            "COUNT(trip_distance)", "nb_courses",
            "SUM(total_amount)",    "revenu_total",
            "AVG(fare_amount)",     "tarif_moyen",
            "AVG(tip_amount)",      "pourboire_moyen",
            "AVG(trip_distance)",   "distance_moyenne");

    // ── Paliers : 1M → 2M → 4M → +2M jusqu'à la fin ─────────────────────
    private static List<Integer> buildScales(long totalRows) {
        List<Integer> s = new ArrayList<>(List.of(1_000_000, 2_000_000, 4_000_000));
        int next = 6_000_000;
        while (next < totalRows) { s.add(next); next += 2_000_000; }
        if (s.get(s.size() - 1) < totalRows) s.add((int) totalRows);
        return s;
    }

    // ═════════════════════════════════════════════════════════════════════
    public static void main(String[] args) throws IOException {

        banner();

        Path dataFile = Path.of(DATA_PATH);
        if (!Files.exists(dataFile)) {
            System.out.println("  ERREUR : fichier introuvable -> " + dataFile.toAbsolutePath());
            System.out.println("  Lancez d'abord : mvnw.cmd test -Dtest=RealDataBenchmarkTest");
            System.out.println("  (téléchargement automatique ~130 Mo)");
            return;
        }

        DataStorage       storage = new InMemoryStorage();
        DataLoaderService loader  = new DataLoaderService(storage);
        QueryService      query   = new QueryService(storage);
        TableService      tables  = new TableService(storage);

        long          totalRows = loader.countParquetRows(DATA_PATH);
        List<Integer> scales    = buildScales(totalRows);

        System.out.printf("  Fichier : %s%n", dataFile.toAbsolutePath());
        System.out.printf("  Lignes  : %,d%n%n", totalRows);

        // ── CHARGEMENT INCRÉMENTAL + REQUÊTES ────────────────────────────
        sep('═', "CHARGEMENT + EXÉCUTION DES REQUÊTES");
        System.out.printf("  %-14s  %-10s  %-9s  %-9s  %-9s  %-9s  %s%n",
                "Lignes total", "LOAD", "R1 (ms)", "R2 (ms)", "R3 (ms)", "R4 (ms)", "R5 (ms)");
        System.out.println("  " + "─".repeat(83));

        String tableName = "demo_trip_data";
        tables.createTable(tableName, new ArrayList<>(SCHEMA));

        List<String> benchLines = new ArrayList<>();
        benchLines.add("lignes,LOAD_ms,R1_ms,R2_ms,R3_ms,R4_ms,R5_ms");

        int  prevScale = 0;
        long r1Ms = 0, r2Ms = 0, r3Ms = 0, r4Ms = 0, r5Ms = 0;
        List<Map<String, Object>> r1 = Collections.emptyList();
        List<Map<String, Object>> r2 = Collections.emptyList();
        List<Map<String, Object>> r3 = Collections.emptyList();
        List<Map<String, Object>> r4 = Collections.emptyList();
        List<Map<String, Object>> r5 = Collections.emptyList();

        for (int scale : scales) {
            int delta = scale - prevScale;

            long t0    = System.nanoTime();
            int  added = loader.loadParquetData(tableName, DATA_PATH, prevScale, delta);
            long loadMs = (System.nanoTime() - t0) / 1_000_000;

            int     actual = prevScale + added;
            boolean eof    = added < delta;

            long tq;
            tq = System.nanoTime(); r1 = query.execute(tableName, R1_COLS, null,    R1_GROUPBY, R1_ORDERBY, R1_DIR, null); r1Ms = (System.nanoTime()-tq)/1_000_000;
            tq = System.nanoTime(); r2 = query.execute(tableName, R2_COLS, R2_WHERE, R2_GROUPBY, R2_ORDERBY, R2_DIR, null); r2Ms = (System.nanoTime()-tq)/1_000_000;
            tq = System.nanoTime(); r3 = query.execute(tableName, R3_COLS, R3_WHERE, R3_GROUPBY, R3_ORDERBY, R3_DIR, null); r3Ms = (System.nanoTime()-tq)/1_000_000;
            tq = System.nanoTime(); r4 = query.execute(tableName, R4_COLS, null,    R4_GROUPBY, R4_ORDERBY, R4_DIR, null); r4Ms = (System.nanoTime()-tq)/1_000_000;
            tq = System.nanoTime(); r5 = query.execute(tableName, R5_COLS, null,    R5_GROUPBY, R5_ORDERBY, R5_DIR, null); r5Ms = (System.nanoTime()-tq)/1_000_000;

            System.out.printf("  %,14d  %7d ms  %6d     %6d     %6d     %6d     %6d%s%n",
                    actual, loadMs, r1Ms, r2Ms, r3Ms, r4Ms, r5Ms, eof ? "  ← FIN DU FICHIER" : "");

            benchLines.add(actual + "," + loadMs + "," + r1Ms + "," + r2Ms + "," + r3Ms + "," + r4Ms + "," + r5Ms);
            prevScale = actual;
            if (eof) break;
        }

        // ── RÉSULTATS SUR DONNÉES COMPLÈTES ──────────────────────────────
        showQuery(1,
                "SELECT payment_type, COUNT(trip_distance) AS nb_courses, SUM(total_amount) AS revenu_total,\n" +
                "         AVG(fare_amount) AS tarif_base_moyen, MIN(trip_distance) AS distance_min,\n" +
                "         MAX(trip_distance) AS distance_max\n" +
                "  FROM trip_data\n" +
                "  GROUP BY payment_type  ORDER BY revenu_total DESC",
                r1, R1_ALIASES, r1Ms, null);

        showQuery(2,
                "SELECT passenger_count AS nb_passagers, COUNT(trip_distance) AS nb_courses,\n" +
                "         AVG(trip_distance) AS distance_moyenne, AVG(tip_amount) AS pourboire_moyen,\n" +
                "         SUM(total_amount) AS revenu_total\n" +
                "  FROM trip_data\n" +
                "  WHERE passenger_count > 0 AND trip_distance > 0\n" +
                "  GROUP BY passenger_count  ORDER BY revenu_total DESC",
                r2, R2_ALIASES, r2Ms, null);

        showQuery(3,
                "SELECT DOLocationID, COUNT(trip_distance) AS nb_courses,\n" +
                "         SUM(tip_amount) AS total_pourboires, AVG(tip_amount) AS pourboire_moyen,\n" +
                "         MAX(tip_amount) AS pourboire_max\n" +
                "  FROM trip_data\n" +
                "  WHERE tip_amount > 0\n" +
                "  GROUP BY DOLocationID  ORDER BY total_pourboires DESC",
                r3, R3_ALIASES, r3Ms, 10);

        showQuery(4,
                "SELECT payment_type, SUM(total_amount) AS revenu_total\n" +
                "  FROM trip_data\n" +
                "  GROUP BY payment_type  ORDER BY revenu_total DESC",
                r4, R4_ALIASES, r4Ms, null);

        showQuery(5,
                "SELECT payment_type, passenger_count, COUNT(trip_distance) AS nb_courses,\n" +
                "         SUM(total_amount) AS revenu_total, AVG(fare_amount) AS tarif_moyen,\n" +
                "         AVG(tip_amount) AS pourboire_moyen, AVG(trip_distance) AS distance_moyenne,\n" +
                "         [+ 13 autres agrégats  →  voir requete5_resultats.csv]\n" +
                "  FROM trip_data\n" +
                "  GROUP BY payment_type, passenger_count  ORDER BY revenu_total DESC",
                r5, R5_DISPLAY, r5Ms, 15);

        // ── EXPORT ───────────────────────────────────────────────────────
        Path demoDir = Path.of("target/demo");
        Files.createDirectories(demoDir);

        Files.writeString(demoDir.resolve("benchmark.csv"),
                String.join("\n", benchLines) + "\n",
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        writeCsv(demoDir.resolve("requete1_resultats.csv"), r1, R1_ALIASES);
        writeCsv(demoDir.resolve("requete2_resultats.csv"), r2, R2_ALIASES);
        writeCsv(demoDir.resolve("requete3_resultats.csv"), r3, R3_ALIASES);
        writeCsv(demoDir.resolve("requete4_resultats.csv"), r4, R4_ALIASES);
        writeCsv(demoDir.resolve("requete5_resultats.csv"), r5, R5_ALIASES);

        System.out.println();
        sep('═', "EXPORT  →  target/demo/");
        System.out.printf("  benchmark.csv           (%d paliers)%n", scales.size());
        System.out.println("  requete1_resultats.csv");
        System.out.println("  requete2_resultats.csv");
        System.out.printf("  requete3_resultats.csv  (%d zones au total)%n", r3.size());
        System.out.println("  requete4_resultats.csv");

        System.out.println();
        System.out.println("  ╔══════════════════════════════════════════╗");
        System.out.println("  ║        DÉMONSTRATION TERMINÉE            ║");
        System.out.println("  ╚══════════════════════════════════════════╝");
        System.out.println();

        storage.deleteTable(tableName);
    }

    // ── Affichage ─────────────────────────────────────────────────────────

    private static void showQuery(int num, String sql,
                                   List<Map<String, Object>> rows,
                                   Map<String, String> aliases,
                                   long ms, Integer displayLimit) {
        System.out.println();
        sep('═', "REQUÊTE " + num);
        System.out.println();
        for (String line : sql.split("\n"))
            System.out.println("  " + line);
        System.out.println();
        List<Map<String, Object>> display = displayLimit == null
                ? rows
                : rows.subList(0, Math.min(displayLimit, rows.size()));
        printResultTable(display, aliases);
        System.out.printf("%n  Temps : %d ms  |  %d groupe(s)%s%n",
                ms, rows.size(),
                displayLimit != null && rows.size() > displayLimit
                        ? "  (top " + displayLimit + " affiché — voir CSV pour tout)" : "");
    }

    private static void banner() {
        System.out.println();
        System.out.println("  ╔══════════════════════════════════════════════════════════════╗");
        System.out.println("  ║              FASTBASE  —  DÉMONSTRATION                     ║");
        System.out.println("  ║        Moteur de données haute performance In-Memory         ║");
        System.out.println("  ║        Dataset : NYC Yellow Taxi  2016-01                   ║");
        System.out.println("  ║        Sorbonne Université  —  Licence Informatique          ║");
        System.out.println("  ╚══════════════════════════════════════════════════════════════╝");
        System.out.println();
    }

    private static void sep(char c, String title) {
        System.out.println("  " + String.valueOf(c).repeat(2) + " " + title + " "
                + String.valueOf(c).repeat(Math.max(0, 66 - title.length())));
    }

    private static void printResultTable(List<Map<String, Object>> rows,
                                          Map<String, String> aliases) {
        if (rows.isEmpty()) { System.out.println("  (aucun résultat)"); return; }
        List<String> techKeys = new ArrayList<>(aliases.keySet());
        List<String> headers  = new ArrayList<>(aliases.values());
        int[] w = new int[headers.size()];
        for (int i = 0; i < headers.size(); i++) w[i] = headers.get(i).length();
        for (Map<String, Object> row : rows)
            for (int i = 0; i < techKeys.size(); i++)
                w[i] = Math.max(w[i], String.valueOf(row.get(techKeys.get(i))).length());
        String fmt = buildFmt(w);
        String sep = buildSep(w);
        System.out.println("  " + sep);
        System.out.printf("  " + fmt + "%n", headers.toArray());
        System.out.println("  " + sep);
        for (Map<String, Object> row : rows) {
            Object[] vals = techKeys.stream().map(k -> formatVal(row.get(k))).toArray();
            System.out.printf("  " + fmt + "%n", vals);
        }
        System.out.println("  " + sep);
    }

    private static String buildFmt(int[] w) {
        StringBuilder sb = new StringBuilder("│");
        for (int wi : w) sb.append(" %-").append(wi).append("s │");
        return sb.toString();
    }

    private static String buildSep(int[] w) {
        StringBuilder sb = new StringBuilder("├");
        for (int i = 0; i < w.length; i++) {
            sb.append("─".repeat(w[i] + 2));
            sb.append(i < w.length - 1 ? "┼" : "┤");
        }
        return sb.toString();
    }

    private static String formatVal(Object v) {
        if (v == null) return "";
        if (v instanceof Double d) return String.format("%.2f", d);
        return v.toString();
    }

    // ── Export CSV ────────────────────────────────────────────────────────

    private static void writeCsv(Path file, List<Map<String, Object>> rows,
                                  Map<String, String> aliases) throws IOException {
        if (rows.isEmpty()) {
            Files.writeString(file, "(aucun résultat)\n",
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            return;
        }
        StringBuilder sb = new StringBuilder(String.join(",", aliases.values())).append("\n");
        for (Map<String, Object> row : rows) {
            StringJoiner sj = new StringJoiner(",");
            for (String key : aliases.keySet()) {
                String v = formatVal(row.get(key));
                if (v.contains(",") || v.contains("\""))
                    v = "\"" + v.replace("\"", "\"\"") + "\"";
                sj.add(v);
            }
            sb.append(sj).append("\n");
        }
        Files.writeString(file, sb.toString(),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    private static Map<String, String> aliases(String... pairs) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) m.put(pairs[i], pairs[i + 1]);
        return m;
    }
}
