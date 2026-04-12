package com.fastbase.service;

import com.fastbase.model.Column;
import com.fastbase.model.Table;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Service de benchmark pour mesurer les performances de FastBase.
 * Mesure deux opérations clés :
 *   - LOAD  : chargement de données via DataLoaderService
 *   - QUERY : exécution de requêtes SELECT / GROUP BY
 */
@Service
public class BenchmarkService {

    private final QueryService queryService;

    public BenchmarkService(QueryService queryService) {
        this.queryService = queryService;
    }

    // -----------------------------------------------------------------------
    // Types de résultat
    // -----------------------------------------------------------------------

    public record BenchmarkResult(
            String operation,   // "LOAD" ou "SELECT" ou "GROUP_BY"
            long rowCount,      // nombre de lignes impliquées
            long elapsedMs,     // durée en millisecondes
            long elapsedNs      // durée en nanosecondes (précision maximale)
    ) {
        /** Retourne la ligne CSV correspondante (sans en-tête). */
        public String toCsvLine() {
            return operation + "," + rowCount + "," + elapsedMs + "," + elapsedNs;
        }

        @Override
        public String toString() {
            return String.format("[%s] rows=%d | %d ms (%,d ns)",
                    operation, rowCount, elapsedMs, elapsedNs);
        }
    }

    // -----------------------------------------------------------------------
    // Benchmark LOAD
    // -----------------------------------------------------------------------

    /**
     * Mesure le temps nécessaire pour insérer {@code rows} lignes dans une table.
     *
     * @param table      Table déjà créée dans le DataStorage (schéma défini)
     * @param rows       Lignes à insérer en une seule passe
     * @return           Résultat contenant le temps écoulé
     */
    public BenchmarkResult benchmarkLoad(Table table, List<com.fastbase.model.Row> rows) {
        long t0 = System.nanoTime();
        table.addRows(rows);
        long elapsed = System.nanoTime() - t0;
        return new BenchmarkResult("LOAD", rows.size(), elapsed / 1_000_000, elapsed);
    }

    // -----------------------------------------------------------------------
    // Benchmark SELECT (sans GROUP BY)
    // -----------------------------------------------------------------------

    /**
     * Mesure le temps d'exécution d'une requête SELECT simple.
     *
     * @param tableName      Nom de la table cible
     * @param selectColumns  Colonnes à projeter (null ou vide = SELECT *)
     * @param whereCondition Filtre WHERE (null ou vide = aucun filtre)
     * @return               Résultat contenant le nombre de lignes retournées et le temps
     */
    public BenchmarkResult benchmarkSelect(
            String tableName,
            List<String> selectColumns,
            String whereCondition) {

        long t0 = System.nanoTime();
        List<Map<String, Object>> results = queryService.execute(
                tableName, selectColumns, whereCondition, null);
        long elapsed = System.nanoTime() - t0;
        return new BenchmarkResult("SELECT", results.size(), elapsed / 1_000_000, elapsed);
    }

    // -----------------------------------------------------------------------
    // Benchmark GROUP BY
    // -----------------------------------------------------------------------

    /**
     * Mesure le temps d'exécution d'une requête SELECT avec GROUP BY.
     *
     * @param tableName      Nom de la table cible
     * @param selectColumns  Colonnes / agrégats à projeter
     * @param whereCondition Filtre WHERE (null ou vide = aucun filtre)
     * @param groupByColumns Colonnes de regroupement
     * @return               Résultat contenant le nombre de groupes et le temps
     */
    public BenchmarkResult benchmarkGroupBy(
            String tableName,
            List<String> selectColumns,
            String whereCondition,
            List<String> groupByColumns) {

        long t0 = System.nanoTime();
        List<Map<String, Object>> results = queryService.execute(
                tableName, selectColumns, whereCondition, groupByColumns);
        long elapsed = System.nanoTime() - t0;
        return new BenchmarkResult("GROUP_BY", results.size(), elapsed / 1_000_000, elapsed);
    }

    // -----------------------------------------------------------------------
    // Export CSV
    // -----------------------------------------------------------------------

    /**
     * Génère une chaîne CSV à partir d'une liste de résultats de benchmark.
     * Format : operation,rowCount,elapsedMs,elapsedNs
     *
     * @param results Liste de BenchmarkResult à exporter
     * @return        Contenu CSV complet (en-tête inclus) prêt à écrire dans un fichier
     */
    public String exportToCsv(List<BenchmarkResult> results) {
        StringBuilder sb = new StringBuilder();
        sb.append("operation,rowCount,elapsedMs,elapsedNs\n");
        for (BenchmarkResult r : results) {
            sb.append(r.toCsvLine()).append("\n");
        }
        return sb.toString();
    }

    /**
     * Surcharge pratique : exporte un seul résultat.
     */
    public String exportToCsv(BenchmarkResult result) {
        return exportToCsv(List.of(result));
    }
}