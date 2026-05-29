package com.fastbase.service;

import com.fastbase.exception.InvalidQueryException;
import com.fastbase.exception.TableNotFoundException;
import com.fastbase.model.Column;
import com.fastbase.model.Table;
import com.fastbase.storage.DataStorage;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Moteur de requêtes colonnaire.
 *
 * Itère par index de ligne (int r = 0..rowCount) sur les arrays double[][] / String[][]
 * de Table. Aucun objet Row créé pendant les requêtes → zéro GC pression sur 50M lignes.
 *
 * Optimisations :
 *  - WHERE / SELECT : parallelStream (IntStream.range) si rowCount > 500 000
 *  - GROUP BY : accumulateur passe unique (GroupAcc)
 *  - ORDER BY LIMIT : heap O(n log N) avec topN via PriorityQueue<Integer>
 *  - ORDER BY seul : Arrays.parallelSort sur int[] indices au-delà de 500 000 lignes
 */
@Service
public class QueryService {

    private final DataStorage dataStorage;

    public QueryService(DataStorage dataStorage) {
        this.dataStorage = dataStorage;
    }

    // ── Entrées publiques ──────────────────────────────────────────

    public List<Map<String, Object>> execute(
            String tableName, List<String> selectCols, String whereCondition,
            List<String> groupByCols) {
        return execute(tableName, selectCols, whereCondition, groupByCols, null, null, null);
    }

    public List<Map<String, Object>> execute(
            String tableName, List<String> selectCols, String whereCondition,
            List<String> groupByCols, String orderBy, String orderDir, Integer limit) {

        Table table = dataStorage.getTable(tableName)
                .orElseThrow(() -> new TableNotFoundException(tableName));

        List<Map<String, Object>> results;

        if (groupByCols != null && !groupByCols.isEmpty()) {
            // P14 : passe unique filter+group — évite int[rowCount] = 280 MB sur 70M lignes
            // et étend P12 (parallèle) aux requêtes avec WHERE
            results = applyGroupBy(table, whereCondition, selectCols, groupByCols);
            if (orderBy != null && !orderBy.isBlank())
                applyOrderByMaps(results, orderBy, orderDir);
            if (limit != null && limit > 0 && results.size() > limit)
                results = results.subList(0, limit);

        } else if (orderBy != null && !orderBy.isBlank()) {
            boolean hasWhere = whereCondition != null && !whereCondition.isBlank();
            boolean useHeap  = limit != null && limit > 0;
            if (useHeap && !hasWhere) {
                // Chemin optimal TOP-N sans WHERE : évite int[rowCount] = 240 MB sur 60M lignes
                int[] rows = topNRowsDirect(table, orderBy, orderDir, limit);
                results = projectRows(rows, resolveColumns(table, selectCols), table);
            } else {
                int[] rows = applyWhere(table, whereCondition);
                if (useHeap && limit < rows.length)
                    rows = topNRows(rows, table, orderBy, orderDir, limit);
                else
                    rows = sortRows(rows, table, orderBy, orderDir);
                results = projectRows(rows, resolveColumns(table, selectCols), table);
            }

        } else {
            // Chemin chaud : filter + project en une seule passe, sans liste intermédiaire
            List<Column> projected = resolveColumns(table, selectCols);
            int[] colIndices = projected.stream().mapToInt(c -> table.getColumnIndex(c.getName())).toArray();
            String[] names   = projected.stream().map(Column::getName).toArray(String[]::new);
            results = filterAndProject(table, whereCondition, colIndices, names);
            if (limit != null && limit > 0 && results.size() > limit)
                results = results.subList(0, limit);
        }
        return results;
    }

    /**
     * Scan sans matérialisation — pour les benchmarks SELECT (compte + checksum).
     */
    public long scanSelectCount(String tableName, List<String> selectCols, String whereCondition) {
        Table table = dataStorage.getTable(tableName)
                .orElseThrow(() -> new TableNotFoundException(tableName));

        List<Column> projected = resolveColumns(table, selectCols);
        int[] colIndices = projected.stream().mapToInt(c -> table.getColumnIndex(c.getName())).toArray();
        int n = table.getRowCount();
        Condition cond = parseCondition(whereCondition, table);
        long count = 0, checksum = 0;
        for (int r = 0; r < n; r++) {
            if (cond != null && !cond.matches(r, table)) continue;
            for (int ci : colIndices) {
                Object v = table.getValue(r, ci);
                if (v != null) checksum += v.hashCode();
            }
            count++;
        }
        if (checksum == Long.MIN_VALUE) System.out.print(""); // évite dead-code elimination
        return count;
    }

    // ── Filter + project en une passe ─────────────────────────────

    private List<Map<String, Object>> filterAndProject(
            Table table, String whereCondition, int[] colIndices, String[] names) {

        int n = table.getRowCount();
        Condition cond = parseCondition(whereCondition, table);

        if (n > 100_000) {
            return IntStream.range(0, n).parallel()
                    .filter(r -> cond == null || cond.matches(r, table))
                    .mapToObj(r -> buildMap(r, colIndices, names, table))
                    .collect(Collectors.toList());
        }

        List<Map<String, Object>> result = cond == null
                ? new ArrayList<>(n) : new ArrayList<>();
        for (int r = 0; r < n; r++) {
            if (cond == null || cond.matches(r, table))
                result.add(buildMap(r, colIndices, names, table));
        }
        return result;
    }

    private Map<String, Object> buildMap(int rowIdx, int[] colIndices, String[] names, Table table) {
        Map<String, Object> map = new HashMap<>(colIndices.length * 2);
        for (int i = 0; i < colIndices.length; i++)
            if (colIndices[i] >= 0) map.put(names[i], table.getValue(rowIdx, colIndices[i]));
        return map;
    }

    // ── WHERE ─────────────────────────────────────────────────────

    private int[] applyWhere(Table table, String whereCondition) {
        int n = table.getRowCount();
        Condition cond = parseCondition(whereCondition, table);
        if (cond == null) return IntStream.range(0, n).toArray();

        if (n > 500_000) {
            return IntStream.range(0, n).parallel()
                    .filter(r -> cond.matches(r, table))
                    .toArray();
        }
        int[] buf = new int[n];
        int count = 0;
        for (int r = 0; r < n; r++)
            if (cond.matches(r, table)) buf[count++] = r;
        return count == n ? buf : Arrays.copyOf(buf, count);
    }

    private static Condition parseCondition(String whereCondition, Table table) {
        if (whereCondition == null || whereCondition.isBlank()) return null;
        return Condition.parse(whereCondition, table);
    }

    // ── GROUP BY ──────────────────────────────────────────────────

    /**
     * P14 + P12 : GROUP BY avec filtre inline et parallélisme sur toutes les tailles.
     * Avant : applyWhere() créait int[70M] = 280 MB, puis applyGroupBy séquentiel.
     * Après : une seule passe parallèle — le filtre s'applique inline dans chaque thread.
     */
    private List<Map<String, Object>> applyGroupBy(
            Table table, String whereCondition, List<String> selectCols, List<String> groupByCols) {

        int[] groupIdx = new int[groupByCols.size()];
        for (int i = 0; i < groupByCols.size(); i++) {
            groupIdx[i] = table.getColumnIndex(groupByCols.get(i));
            if (groupIdx[i] < 0)
                throw new InvalidQueryException("Colonne GROUP BY introuvable : " + groupByCols.get(i));
        }

        List<AggInfo> aggs = new ArrayList<>();
        if (selectCols != null) {
            for (String col : selectCols) {
                String up = col.trim().toUpperCase();
                if (up.startsWith("COUNT(")) {
                    aggs.add(new AggInfo(col, "COUNT", -1));
                } else if (up.startsWith("SUM(") || up.startsWith("AVG(") ||
                           up.startsWith("MIN(") || up.startsWith("MAX(")) {
                    String type = up.substring(0, up.indexOf('('));
                    int idx = table.getColumnIndex(extractArg(col));
                    if (idx < 0) throw new InvalidQueryException("Colonne agrégat introuvable : " + extractArg(col));
                    aggs.add(new AggInfo(col, type, idx));
                }
            }
        }
        int nAcc = (int) aggs.stream().filter(a -> !a.type().equals("COUNT")).count();
        int[] slot = new int[aggs.size()];
        int s = 0;
        for (int i = 0; i < aggs.size(); i++) slot[i] = aggs.get(i).type().equals("COUNT") ? -1 : s++;

        // Filtre parsé une seule fois pour tous les threads
        Condition cond     = parseCondition(whereCondition, table);
        boolean   numericKey = groupIdx.length == 1 && table.isNumericColumn(groupIdx[0]);
        int       rowCount   = table.getRowCount();

        if (numericKey) {
            if (rowCount > 500_000) {
                // P12 + P14 : parallel, inline filter (fonctionne avec ET sans WHERE)
                int nCpu  = Runtime.getRuntime().availableProcessors();
                int chunk = (rowCount + nCpu - 1) / nCpu;
                @SuppressWarnings("unchecked")
                Map<Long, GroupAcc>[] locals = new HashMap[nCpu];
                for (int t = 0; t < nCpu; t++) locals[t] = new HashMap<>();

                final int[]         gIdx    = groupIdx;
                final List<AggInfo> aggList = aggs;
                final int[]         slotArr = slot;
                final int           nacc    = nAcc;

                IntStream.range(0, nCpu).parallel().forEach(t -> {
                    int from = t * chunk, to = Math.min(from + chunk, rowCount);
                    Map<Long, GroupAcc> local = locals[t];
                    for (int r = from; r < to; r++) {
                        if (cond != null && !cond.matches(r, table)) continue;
                        long key = Double.doubleToRawLongBits(table.getNumericRaw(gIdx[0], r));
                        GroupAcc acc = local.get(key);
                        if (acc == null) {
                            acc = new GroupAcc(new Object[]{ table.getValue(r, gIdx[0]) }, nacc);
                            local.put(key, acc);
                        }
                        acc.count++;
                        for (int j = 0; j < aggList.size(); j++) {
                            if (slotArr[j] < 0) continue;
                            double d = table.getNumericRaw(aggList.get(j).colIdx(), r);
                            if (!Double.isNaN(d)) {
                                acc.sums[slotArr[j]] += d;
                                if (d < acc.mins[slotArr[j]]) acc.mins[slotArr[j]] = d;
                                if (d > acc.maxs[slotArr[j]]) acc.maxs[slotArr[j]] = d;
                                acc.hasVal[slotArr[j]] = true;
                            }
                        }
                    }
                });

                Map<Long, GroupAcc> merged = locals[0];
                for (int t = 1; t < nCpu; t++) {
                    for (Map.Entry<Long, GroupAcc> e : locals[t].entrySet()) {
                        GroupAcc src = e.getValue(), dst = merged.get(e.getKey());
                        if (dst == null) { merged.put(e.getKey(), src); continue; }
                        dst.count += src.count;
                        for (int j = 0; j < nacc; j++) {
                            dst.sums[j] += src.sums[j];
                            if (src.mins[j] < dst.mins[j]) dst.mins[j] = src.mins[j];
                            if (src.maxs[j] > dst.maxs[j]) dst.maxs[j] = src.maxs[j];
                            if (src.hasVal[j]) dst.hasVal[j] = true;
                        }
                    }
                }
                return buildNumericResults(merged, groupByCols, aggs, slot);
            }

            // Séquentiel pour petites tables
            Map<Long, GroupAcc> groups = new HashMap<>();
            for (int r = 0; r < rowCount; r++) {
                if (cond != null && !cond.matches(r, table)) continue;
                long key = Double.doubleToRawLongBits(table.getNumericRaw(groupIdx[0], r));
                GroupAcc acc = groups.get(key);
                if (acc == null) {
                    acc = new GroupAcc(new Object[]{ table.getValue(r, groupIdx[0]) }, nAcc);
                    groups.put(key, acc);
                }
                acc.count++;
                for (int j = 0; j < aggs.size(); j++) {
                    if (slot[j] < 0) continue;
                    double d = table.getNumericRaw(aggs.get(j).colIdx(), r);
                    if (!Double.isNaN(d)) {
                        acc.sums[slot[j]] += d;
                        if (d < acc.mins[slot[j]]) acc.mins[slot[j]] = d;
                        if (d > acc.maxs[slot[j]]) acc.maxs[slot[j]] = d;
                        acc.hasVal[slot[j]] = true;
                    }
                }
            }
            return buildNumericResults(groups, groupByCols, aggs, slot);
        }

        // Chemin général : String key (GROUP BY texte ou multi-colonnes)
        // Même stratégie que le chemin numérique : maps thread-locales + fusion.
        final int[]         gIdx    = groupIdx;
        final List<AggInfo> aggList = aggs;
        final int[]         slotArr = slot;
        final int           nacc    = nAcc;

        if (rowCount > 500_000) {
            int nCpu  = Runtime.getRuntime().availableProcessors();
            int chunk = (rowCount + nCpu - 1) / nCpu;
            @SuppressWarnings("unchecked")
            Map<String, GroupAcc>[] locals = new HashMap[nCpu];
            for (int t = 0; t < nCpu; t++) locals[t] = new HashMap<>();

            IntStream.range(0, nCpu).parallel().forEach(t -> {
                int from = t * chunk, to = Math.min(from + chunk, rowCount);
                Map<String, GroupAcc> local = locals[t];
                for (int r = from; r < to; r++) {
                    if (cond != null && !cond.matches(r, table)) continue;
                    String key = buildGroupKey(r, table, gIdx);
                    GroupAcc acc = local.get(key);
                    if (acc == null) {
                        Object[] gv = new Object[gIdx.length];
                        for (int j = 0; j < gIdx.length; j++) gv[j] = table.getValue(r, gIdx[j]);
                        acc = new GroupAcc(gv, nacc);
                        local.put(key, acc);
                    }
                    acc.count++;
                    for (int j = 0; j < aggList.size(); j++) {
                        if (slotArr[j] < 0) continue;
                        double d = table.getNumericRaw(aggList.get(j).colIdx(), r);
                        if (!Double.isNaN(d)) {
                            acc.sums[slotArr[j]] += d;
                            if (d < acc.mins[slotArr[j]]) acc.mins[slotArr[j]] = d;
                            if (d > acc.maxs[slotArr[j]]) acc.maxs[slotArr[j]] = d;
                            acc.hasVal[slotArr[j]] = true;
                        }
                    }
                }
            });

            Map<String, GroupAcc> merged = locals[0];
            for (int t = 1; t < nCpu; t++) {
                for (Map.Entry<String, GroupAcc> e : locals[t].entrySet()) {
                    GroupAcc src = e.getValue(), dst = merged.get(e.getKey());
                    if (dst == null) { merged.put(e.getKey(), src); continue; }
                    dst.count += src.count;
                    for (int j = 0; j < nacc; j++) {
                        dst.sums[j] += src.sums[j];
                        if (src.mins[j] < dst.mins[j]) dst.mins[j] = src.mins[j];
                        if (src.maxs[j] > dst.maxs[j]) dst.maxs[j] = src.maxs[j];
                        if (src.hasVal[j]) dst.hasVal[j] = true;
                    }
                }
            }
            List<Map<String, Object>> results = new ArrayList<>(merged.size());
            for (GroupAcc acc : merged.values()) {
                Map<String, Object> result = new HashMap<>();
                for (int i = 0; i < groupByCols.size(); i++) result.put(groupByCols.get(i), acc.groupVals[i]);
                addAggResults(result, acc, aggList, slotArr);
                results.add(result);
            }
            return results;
        }

        // Séquentiel pour petites tables
        Map<String, GroupAcc> groups = new HashMap<>();
        for (int r = 0; r < rowCount; r++) {
            if (cond != null && !cond.matches(r, table)) continue;
            String key = buildGroupKey(r, table, gIdx);
            GroupAcc acc = groups.get(key);
            if (acc == null) {
                Object[] gv = new Object[gIdx.length];
                for (int j = 0; j < gIdx.length; j++) gv[j] = table.getValue(r, gIdx[j]);
                acc = new GroupAcc(gv, nacc);
                groups.put(key, acc);
            }
            acc.count++;
            for (int j = 0; j < aggList.size(); j++) {
                if (slotArr[j] < 0) continue;
                double d = table.getNumericRaw(aggList.get(j).colIdx(), r);
                if (!Double.isNaN(d)) {
                    acc.sums[slotArr[j]] += d;
                    if (d < acc.mins[slotArr[j]]) acc.mins[slotArr[j]] = d;
                    if (d > acc.maxs[slotArr[j]]) acc.maxs[slotArr[j]] = d;
                    acc.hasVal[slotArr[j]] = true;
                }
            }
        }
        List<Map<String, Object>> results = new ArrayList<>(groups.size());
        for (GroupAcc acc : groups.values()) {
            Map<String, Object> result = new HashMap<>();
            for (int i = 0; i < groupByCols.size(); i++) result.put(groupByCols.get(i), acc.groupVals[i]);
            addAggResults(result, acc, aggList, slotArr);
            results.add(result);
        }
        return results;
    }

    private List<Map<String, Object>> buildNumericResults(
            Map<Long, GroupAcc> groups, List<String> groupByCols,
            List<    AggInfo> aggs, int[] slot) {
        List<Map<String, Object>> results = new ArrayList<>(groups.size());
        for (GroupAcc acc : groups.values()) {
            Map<String, Object> result = new HashMap<>();
            result.put(groupByCols.get(0), acc.groupVals[0]);
            addAggResults(result, acc, aggs, slot);
            results.add(result);
        }
        return results;
    }

    private void addAggResults(Map<String, Object> result, GroupAcc acc,
                               List<AggInfo> aggs, int[] slot) {
        for (int i = 0; i < aggs.size(); i++) {
            AggInfo ai = aggs.get(i); int si = slot[i];
            switch (ai.type()) {
                case "COUNT" -> result.put(ai.col(), acc.count);
                case "SUM"   -> result.put(ai.col(), acc.hasVal[si] ? acc.sums[si] : 0.0);
                case "AVG"   -> result.put(ai.col(), acc.count > 0 ? acc.sums[si] / acc.count : 0.0);
                case "MIN"   -> result.put(ai.col(), acc.hasVal[si] ? acc.mins[si] : null);
                case "MAX"   -> result.put(ai.col(), acc.hasVal[si] ? acc.maxs[si] : null);
            }
        }
    }

    private record AggInfo(String col, String type, int colIdx) {}

    private static class GroupAcc {
        final Object[] groupVals; long count = 0;
        final double[] sums, mins, maxs; final boolean[] hasVal;
        GroupAcc(Object[] gv, int nAcc) {
            groupVals = gv; sums = new double[nAcc]; mins = new double[nAcc];
            maxs = new double[nAcc]; hasVal = new boolean[nAcc];
            Arrays.fill(mins, Double.MAX_VALUE); Arrays.fill(maxs, -Double.MAX_VALUE);
        }
    }

    // ── ORDER BY / TOP-N ──────────────────────────────────────────

    private int[] sortRows(int[] rows, Table table, String orderBy, String orderDir) {
        int idx = table.getColumnIndex(orderBy);
        if (idx < 0) return rows;
        boolean desc = "DESC".equalsIgnoreCase(orderDir);
        if (rows.length > 500_000) {
            Integer[] boxed = IntStream.of(rows).boxed().toArray(Integer[]::new);
            Arrays.parallelSort(boxed, (a, b) -> {
                int c = compareValues(table.getValue(a, idx), table.getValue(b, idx));
                return desc ? -c : c;
            });
            return Arrays.stream(boxed).mapToInt(Integer::intValue).toArray();
        }
        Integer[] boxed = IntStream.of(rows).boxed().toArray(Integer[]::new);
        Arrays.sort(boxed, (a, b) -> {
            int c = compareValues(table.getValue(a, idx), table.getValue(b, idx));
            return desc ? -c : c;
        });
        return Arrays.stream(boxed).mapToInt(Integer::intValue).toArray();
    }

    /**
     * TOP-N sans WHERE — zéro boxing sur colonnes numériques.
     * Seuil (threshold) mis à jour dès que le heap est plein : on rejette ~99,99 % des lignes
     * sans jamais créer d'Integer, éliminant la pression GC observée à grand volume.
     */
    private int[] topNRowsDirect(Table table, String orderBy, String orderDir, int limit) {
        int colIdx = table.getColumnIndex(orderBy);
        int n      = table.getRowCount();
        int actual = Math.min(limit, n);
        if (colIdx < 0 || n == 0) {
            int[] r = new int[actual]; for (int i = 0; i < actual; i++) r[i] = i; return r;
        }
        boolean desc    = "DESC".equalsIgnoreCase(orderDir);
        boolean numeric = table.isNumericColumn(colIdx);

        Comparator<Integer> heapComp = numeric
            ? (a, b) -> { int c = Double.compare(table.getNumericRaw(colIdx, a), table.getNumericRaw(colIdx, b));
                          return desc ? c : -c; }
            : (a, b) -> { int c = compareValues(table.getValue(a, colIdx), table.getValue(b, colIdx));
                          return desc ? c : -c; };

        PriorityQueue<Integer> heap = new PriorityQueue<>(limit + 1, heapComp);

        if (numeric) {
            // Chemin optimisé : seuil primitif → la grande majorité des lignes ne boxe jamais
            // threshold = valeur de la racine du heap (le "pire" des top-N actuels)
            double threshold = desc ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
            for (int r = 0; r < n; r++) {
                double v = table.getNumericRaw(colIdx, r);
                // Rejette sans boxing si la valeur ne peut pas améliorer le top-N
                if (heap.size() >= limit && (desc ? v <= threshold : v >= threshold)) continue;
                heap.offer(r);
                if (heap.size() > limit) heap.poll();
                // Met à jour le seuil = valeur de la racine (pire des top-N)
                threshold = table.getNumericRaw(colIdx, heap.peek());
            }
        } else {
            for (int r = 0; r < n; r++) {
                heap.offer(r);
                if (heap.size() > limit) heap.poll();
            }
        }

        List<Integer> result = new ArrayList<>(heap);
        result.sort(heapComp.reversed());
        return result.stream().mapToInt(Integer::intValue).toArray();
    }

    private int[] topNRows(int[] rows, Table table, String orderBy, String orderDir, int limit) {
        int idx = table.getColumnIndex(orderBy);
        if (idx < 0) return Arrays.copyOf(rows, Math.min(limit, rows.length));
        boolean desc = "DESC".equalsIgnoreCase(orderDir);
        Comparator<Integer> heapComp = (a, b) -> {
            int c = compareValues(table.getValue(a, idx), table.getValue(b, idx));
            return desc ? c : -c;
        };
        PriorityQueue<Integer> heap;
        if (rows.length > 500_000) {
            heap = IntStream.of(rows).parallel().boxed().collect(
                () -> new PriorityQueue<>(limit + 1, heapComp),
                (h, r) -> { h.offer(r); if (h.size() > limit) h.poll(); },
                (h1, h2) -> { for (int r : h2) { h1.offer(r); if (h1.size() > limit) h1.poll(); } });
        } else {
            heap = new PriorityQueue<>(limit + 1, heapComp);
            for (int r : rows) { heap.offer(r); if (heap.size() > limit) heap.poll(); }
        }
        List<Integer> result = new ArrayList<>(heap);
        result.sort(heapComp.reversed());
        return result.stream().mapToInt(Integer::intValue).toArray();
    }

    private List<Map<String, Object>> projectRows(int[] rows, List<Column> projected, Table table) {
        int[] colIndices = projected.stream().mapToInt(c -> table.getColumnIndex(c.getName())).toArray();
        String[] names   = projected.stream().map(Column::getName).toArray(String[]::new);
        if (rows.length > 500_000)
            return IntStream.of(rows).parallel()
                    .mapToObj(r -> buildMap(r, colIndices, names, table))
                    .collect(Collectors.toList());
        List<Map<String, Object>> result = new ArrayList<>(rows.length);
        for (int r : rows) result.add(buildMap(r, colIndices, names, table));
        return result;
    }

    private void applyOrderByMaps(List<Map<String, Object>> results, String orderBy, String orderDir) {
        boolean desc = "DESC".equalsIgnoreCase(orderDir);
        results.sort((a, b) -> {
            int c = compareValues(a.get(orderBy), b.get(orderBy));
            return desc ? -c : c;
        });
    }

    // ── Utilitaires ───────────────────────────────────────────────

    private List<Column> resolveColumns(Table table, List<String> selectCols) {
        if (selectCols == null || selectCols.isEmpty() || selectCols.contains("*"))
            return table.getColumns();
        List<Column> result = new ArrayList<>();
        for (String name : selectCols) {
            Column col = table.getColumn(name);
            if (col != null) result.add(col);
        }
        return result;
    }

    private String buildGroupKey(int rowIdx, Table table, int[] idx) {
        StringBuilder sb = new StringBuilder();
        for (int i : idx) sb.append(table.getValue(rowIdx, i)).append('|');
        return sb.toString();
    }

    private String extractArg(String expr) {
        return expr.substring(expr.indexOf('(') + 1, expr.indexOf(')')).trim();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private int compareValues(Object va, Object vb) {
        if (va == null && vb == null) return 0;
        if (va == null) return 1; if (vb == null) return -1;
        if (va instanceof Number na && vb instanceof Number nb)
            return Double.compare(na.doubleValue(), nb.doubleValue());
        if (va instanceof Comparable ca) return ca.compareTo(vb);
        return va.toString().compareTo(vb.toString());
    }

    // ── Conditions WHERE ──────────────────────────────────────────

    private interface Condition {
        boolean matches(int rowIdx, Table table);

        static Condition parse(String expr, Table table) {
            String[] orParts = expr.split("(?i)\\s+OR\\s+");
            if (orParts.length > 1) {
                Condition[] conds = new Condition[orParts.length];
                for (int i = 0; i < orParts.length; i++) conds[i] = parseAnd(orParts[i], table);
                return new OrCondition(conds);
            }
            return parseAnd(expr, table);
        }

        static Condition parseAnd(String expr, Table table) {
            String[] andParts = expr.split("(?i)\\s+AND\\s+");
            if (andParts.length > 1) {
                Condition[] conds = new Condition[andParts.length];
                for (int i = 0; i < andParts.length; i++)
                    conds[i] = SimpleCondition.parse(andParts[i].trim(), table);
                return new AndCondition(conds);
            }
            return SimpleCondition.parse(expr.trim(), table);
        }
    }

    private record AndCondition(Condition[] conds) implements Condition {
        public boolean matches(int r, Table t) {
            for (Condition c : conds) if (!c.matches(r, t)) return false;
            return true;
        }
    }

    private record OrCondition(Condition[] conds) implements Condition {
        public boolean matches(int r, Table t) {
            for (Condition c : conds) if (c.matches(r, t)) return true;
            return false;
        }
    }

    // P9 ── NumericCondition : WHERE sur colonne numérique sans boxing ─────────────
    // getValue() crée un Integer/Float/Long à chaque appel → GC pression sur 50M lignes.
    // getNumericRaw() retourne un double primitif directement depuis int[]/float[]/long[].
    private static final class NumericCondition implements Condition {
        final int colIndex; final SimpleCondition.Op op; final double threshold;
        NumericCondition(int colIndex, SimpleCondition.Op op, double threshold) {
            this.colIndex = colIndex; this.op = op; this.threshold = threshold;
        }
        @Override public boolean matches(int rowIdx, Table table) {
            double v = table.getNumericRaw(colIndex, rowIdx);
            return switch (op) {
                case EQ  -> v == threshold; case NEQ -> v != threshold;
                case LT  -> v <  threshold; case LTE -> v <= threshold;
                case GT  -> v >  threshold; case GTE -> v >= threshold;
                default  -> false;
            };
        }
    }

    private static class SimpleCondition implements Condition {
        enum Op { EQ, NEQ, LT, LTE, GT, GTE, LIKE }

        final int    colIndex;
        final Op     op;
        final String raw;
        final double num;
        final boolean isNum;

        SimpleCondition(int colIndex, Op op, String raw) {
            this.colIndex = colIndex; this.op = op; this.raw = raw;
            double d = 0; boolean b = false;
            try { d = Double.parseDouble(raw); b = true; } catch (NumberFormatException ignored) {}
            this.num = d; this.isNum = b;
        }

        static Condition parse(String condition, Table table) {
            String trimmed = condition.trim();
            String upper   = trimmed.toUpperCase();
            int likePos = upper.indexOf(" LIKE ");
            if (likePos >= 0)
                return make(trimmed.substring(0, likePos), Op.LIKE, trimmed.substring(likePos + 6), table);
            String[][] ops = {{"<=","LTE"},{">=","GTE"},{"!=","NEQ"},{"<","LT"},{">","GT"},{"=","EQ"}};
            for (String[] pair : ops) {
                int pos = trimmed.indexOf(pair[0]);
                if (pos > 0)
                    return make(trimmed.substring(0, pos), Op.valueOf(pair[1]),
                                trimmed.substring(pos + pair[0].length()), table);
            }
            throw new InvalidQueryException("Condition WHERE non reconnue : " + condition);
        }

    private static Condition make(String col, Op op, String val, Table table) {
            int idx = table.getColumnIndex(col.trim());
            if (idx < 0) throw new InvalidQueryException("Colonne WHERE introuvable : " + col.trim());
            String clean = val.trim();
            // Supprime les guillemets encadrants : 'Y' → Y
            if (clean.length() >= 2 &&
                ((clean.charAt(0) == '\'' && clean.charAt(clean.length()-1) == '\'') ||
                 (clean.charAt(0) == '"'  && clean.charAt(clean.length()-1) == '"')))
                clean = clean.substring(1, clean.length() - 1);
            // P9 : colonne numérique → NumericCondition (getNumericRaw, zéro boxing Integer/Float)
            if (op != Op.LIKE && table.isNumericColumn(idx)) {
                try { return new NumericCondition(idx, op, Double.parseDouble(clean)); }
                catch (NumberFormatException ignored) {}
            }
            return new SimpleCondition(idx, op, clean);
        }

        public boolean matches(int rowIdx, Table table) {
            Object cell = table.getValue(rowIdx, colIndex);
            if (cell == null) return false;
            if (op == Op.LIKE) {
                String s = cell.toString().toLowerCase();
                String p = raw.toLowerCase();
                if (p.startsWith("%") && p.endsWith("%")) return s.contains(p.substring(1, p.length()-1));
                if (p.startsWith("%")) return s.endsWith(p.substring(1));
                if (p.endsWith("%"))   return s.startsWith(p.substring(0, p.length()-1));
                return s.equals(p);
            }
            if (isNum && cell instanceof Number n) {
                double v = n.doubleValue();
                return switch (op) {
                    case EQ -> v == num; case NEQ -> v != num;
                    case LT -> v <  num; case LTE -> v <= num;
                    case GT -> v >  num; case GTE -> v >= num;
                    default -> false;
                };
            }
            String s = cell.toString();
            return switch (op) {
                case EQ  -> s.equalsIgnoreCase(raw);
                case NEQ -> !s.equalsIgnoreCase(raw);
                default  -> false;
            };
        }
    }

}