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
 *   mvn compile exec:java -Dexec.mainClass=com.fastbase.demo.BenchmarkDemo
 *
 * Produit dans target/demo/ :
 *   benchmark.csv            — temps LOAD + Requête 1 par palier
 *   requete1_resultats.csv   — résultat complet de la Requête 1 (données réelles)
 */
public class BenchmarkDemo {

    // ── Chemin du fichier source ────────────────────────────────────────
    private static final String DATA_PATH =
            System.getProperty("fastbase.demo.path", "../data_NYC/yellow_tripdata_2016-01.parquet");

    // ── Schéma NYC Yellow Taxi 2016 ─────────────────────────────────────
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

    // ── Requête 1 ───────────────────────────────────────────────────────
    //   SELECT payment_type,
    //          COUNT(trip_distance)  AS nb_courses,
    //          SUM(total_amount)     AS revenu_total,
    //          AVG(fare_amount)      AS tarif_base_moyen,
    //          MIN(trip_distance)    AS distance_min,
    //          MAX(trip_distance)    AS distance_max
    //   FROM   trip_data
    //   GROUP  BY payment_type
    //   ORDER  BY revenu_total DESC
    private static final List<String> R1_COLS = List.of(
            "payment_type", "COUNT(trip_distance)", "SUM(total_amount)",
            "AVG(fare_amount)", "MIN(trip_distance)", "MAX(trip_distance)"
    );
    private static final List<String> R1_GROUPBY  = List.of("payment_type");
    private static final String       R1_ORDERBY  = "SUM(total_amount)";
    private static final String       R1_ORDERDIR = "DESC";
    // Clé technique → alias SQL (pour l'export CSV)
    private static final Map<String, String> R1_ALIASES = aliases(
            "payment_type",         "payment_type",
            "COUNT(trip_distance)", "nb_courses",
            "SUM(total_amount)",    "revenu_total",
            "AVG(fare_amount)",     "tarif_base_moyen",
            "MIN(trip_distance)",   "distance_min",
            "MAX(trip_distance)",   "distance_max"
    );

    // ── Paliers : 1M, 2M, 4M puis +2M jusqu'à la fin du fichier ─────────
    private static List<Integer> buildScales(long totalRows) {
        List<Integer> s = new ArrayList<>(List.of(1_000_000, 2_000_000, 4_000_000));
        int next = 6_000_000;
        while (next < totalRows) { s.add(next); next += 2_000_000; }
        if (s.get(s.size() - 1) < totalRows) s.add((int) totalRows);
        return s;
    }

    // ═══════════════════════════════════════════════════════════════════
    public static void main(String[] args) throws IOException {

        banner();

        // Vérification du fichier
        Path dataFile = Path.of(DATA_PATH);
        if (!Files.exists(dataFile)) {
            System.out.println("  ERREUR : fichier introuvable -> " + dataFile.toAbsolutePath());
            System.out.println("  Lancez d'abord : mvnw test -Dtest=RealDataBenchmarkTest");
            System.out.println("  (le fichier sera téléchargé automatiquement ~130 Mo)");
            return;
        }

        // Services
        DataStorage       storage  = new InMemoryStorage();
        DataLoaderService loader   = new DataLoaderService(storage);
        QueryService      query    = new QueryService(storage);
        TableService      tables   = new TableService(storage);

        // Métadonnées du fichier
        long totalRows = loader.countParquetRows(DATA_PATH);
        List<Integer> scales = buildScales(totalRows);

        System.out.printf("  Fichier  : %s%n", dataFile.toAbsolutePath());
        System.out.printf("  Lignes   : %,d%n", totalRows);
        System.out.printf("  Paliers  : %s%n%n", scales.stream()
                .map(n -> String.format("%,d", n)).reduce((a, b) -> a + " → " + b).orElse(""));

        // ── CHARGEMENT INCRÉMENTAL ──────────────────────────────────────
        sep('═', "CHARGEMENT INCRÉMENTAL");
        System.out.printf("  %-13s  %-14s  %-9s  %-12s  %s%n",
                "Lignes total", "Delta chargé", "Temps", "Débit", "");
        System.out.println("  " + "─".repeat(66));

        String tableName = "demo_trip_data";
        tables.createTable(tableName, new ArrayList<>(SCHEMA));

        List<String> benchLines   = new ArrayList<>();
        benchLines.add("lignes,LOAD_ms,Requete1_ms");

        int  prevScale  = 0;
        long lastR1Ms   = 0;
        List<Map<String, Object>> lastR1Result = Collections.emptyList();

        for (int scale : scales) {
            int delta = scale - prevScale;

            // LOAD delta
            long t0    = System.nanoTime();
            int  added = loader.loadParquetData(tableName, DATA_PATH, prevScale, delta);
            long loadMs = (System.nanoTime() - t0) / 1_000_000;

            int  actual    = prevScale + added;
            boolean eof    = added < delta;
            double  debit  = loadMs > 0 ? added / (double) loadMs : 0;
            String  marker = eof ? "  ← FIN DU FICHIER" : "";

            System.out.printf("  %,13d  %+,14d  %6d ms  %9.0f k/s%s%n",
                    actual, added, loadMs, debit, marker);

            // Requête 1 à chaque palier
            long tq1 = System.nanoTime();
            lastR1Result = query.execute(tableName, R1_COLS, null, R1_GROUPBY,
                    R1_ORDERBY, R1_ORDERDIR, null);
            lastR1Ms = (System.nanoTime() - tq1) / 1_000_000;

            benchLines.add(actual + "," + loadMs + "," + lastR1Ms);

            prevScale = actual;
            if (eof) break;
        }

        // ── REQUÊTE 1 — résultats sur données complètes ─────────────────
        System.out.println();
        sep('═', "REQUÊTE 1");
        System.out.println();
        System.out.println("  SELECT payment_type,");
        System.out.println("         COUNT(trip_distance)  AS nb_courses,");
        System.out.println("         SUM(total_amount)     AS revenu_total,");
        System.out.println("         AVG(fare_amount)      AS tarif_base_moyen,");
        System.out.println("         MIN(trip_distance)    AS distance_min,");
        System.out.println("         MAX(trip_distance)    AS distance_max");
        System.out.println("  FROM   trip_data");
        System.out.println("  GROUP  BY payment_type");
        System.out.println("  ORDER  BY revenu_total DESC");
        System.out.println();
        System.out.printf("  Exécution sur %,d lignes ...%n%n", prevScale);

        printResultTable(lastR1Result, R1_ALIASES);
        System.out.printf("%n  Temps d'exécution : %d ms  |  %d groupe(s) retourné(s)%n",
                lastR1Ms, lastR1Result.size());

        // ── EXPORT ──────────────────────────────────────────────────────
        Path demoDir = Path.of("target/demo");
        Files.createDirectories(demoDir);

        Path benchFile  = demoDir.resolve("benchmark.csv");
        Path resultFile = demoDir.resolve("requete1_resultats.csv");

        Files.writeString(benchFile, String.join("\n", benchLines) + "\n",
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        writeCsv(resultFile, lastR1Result, R1_ALIASES);

        System.out.println();
        sep('═', "EXPORT");
        System.out.printf("  %-40s  (%d paliers)%n",   benchFile.toAbsolutePath(),  scales.size());
        System.out.printf("  %-40s  (%d lignes)%n",    resultFile.toAbsolutePath(), lastR1Result.size());

        System.out.println();
        System.out.println("  ╔══════════════════════════════════════════╗");
        System.out.println("  ║        DÉMONSTRATION TERMINÉE            ║");
        System.out.println("  ╚══════════════════════════════════════════╝");
        System.out.println();

        storage.deleteTable(tableName);
    }

    // ── Affichage ────────────────────────────────────────────────────────

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

        // Largeurs de colonnes
        int[] w = new int[headers.size()];
        for (int i = 0; i < headers.size(); i++) w[i] = headers.get(i).length();
        for (Map<String, Object> row : rows)
            for (int i = 0; i < techKeys.size(); i++)
                w[i] = Math.max(w[i], String.valueOf(row.get(techKeys.get(i))).length());

        String fmt  = buildFmt(w);
        String sep  = buildSep(w);

        System.out.println("  " + sep);
        System.out.printf("  " + fmt + "%n", headers.toArray());
        System.out.println("  " + sep);
        for (Map<String, Object> row : rows) {
            Object[] vals = techKeys.stream()
                    .map(k -> formatVal(row.get(k))).toArray();
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
        // Remplace ├ par ┌ et ┤ par ┐ pour la première ligne — on utilise le même sep pour les trois
        return sb.toString();
    }

    private static String formatVal(Object v) {
        if (v == null) return "";
        if (v instanceof Double d) return String.format("%.2f", d);
        return v.toString();
    }

    // ── Export CSV ───────────────────────────────────────────────────────

    private static void writeCsv(Path file,
                                  List<Map<String, Object>> rows,
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

    // ── Utilitaires ──────────────────────────────────────────────────────

    private static Map<String, String> aliases(String... pairs) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) m.put(pairs[i], pairs[i + 1]);
        return m;
    }
}