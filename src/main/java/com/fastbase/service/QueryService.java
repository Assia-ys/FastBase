package com.fastbase.service;

import com.fastbase.exception.InvalidQueryException;
import com.fastbase.exception.TableNotFoundException;
import com.fastbase.model.Column;
import com.fastbase.model.Row;
import com.fastbase.model.Table;
import com.fastbase.storage.DataStorage;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class QueryService {

    private final DataStorage dataStorage;

    public QueryService(DataStorage dataStorage) {
        this.dataStorage = dataStorage;
    }

    public List<Map<String, Object>> execute(
            String tableName,
            List<String> selectCols,
            String whereCondition,
            List<String> groupByCols) {
        return execute(tableName, selectCols, whereCondition, groupByCols, null, null, null);
    }

    public List<Map<String, Object>> execute(
            String tableName,
            List<String> selectCols,
            String whereCondition,
            List<String> groupByCols,
            String orderBy,
            String orderDir,
            Integer limit) {

        Table table = dataStorage.getTable(tableName)
                .orElseThrow(() -> new TableNotFoundException(tableName));

        List<Map<String, Object>> results;
        if (groupByCols != null && !groupByCols.isEmpty()) {
            List<Row> rows = applyWhere(table, whereCondition);
            results = applyGroupBy(table, rows, selectCols, groupByCols);
            if (orderBy != null && !orderBy.isBlank())
                applyOrderByMaps(results, orderBy, orderDir);
            if (limit != null && limit > 0 && results.size() > limit)
                results = results.subList(0, limit);
        } else if (orderBy != null && !orderBy.isBlank()) {
            List<Row> rows = applyWhere(table, whereCondition);
            boolean useHeap = limit != null && limit > 0 && limit < rows.size();
            if (useHeap)
                rows = topNRows(rows, table, orderBy, orderDir, limit);
            else
                rows = sortRows(rows, table, orderBy, orderDir);
            results = projectRows(rows, resolveColumns(table, selectCols), table);
        } else {
            // Pas d'ORDER BY : pipeline filter+project en une seule passe, sans liste intermédiaire
            List<Column> projected = resolveColumns(table, selectCols);
            int[] indices = buildIndices(projected, table);
            String[] names  = projected.stream().map(Column::getName).toArray(String[]::new);
            results = filterAndProject(table, whereCondition, indices, names);
            if (limit != null && limit > 0 && results.size() > limit)
                results = results.subList(0, limit);
        }

        return results;
    }

    // Une seule passe : filtre ET projette sans List<Row> intermédiaire
    private List<Map<String, Object>> filterAndProject(
            Table table, String whereCondition, int[] indices, String[] names) {

        List<Row> rows = table.getRows();

        if (whereCondition == null || whereCondition.isBlank()) {
            // Pas de filtre : projection pure en parallèle
            if (rows.size() > 500_000)
                return rows.parallelStream()
                        .map(row -> buildMap(row, indices, names))
                        .collect(Collectors.toList());
            List<Map<String, Object>> result = new ArrayList<>(rows.size());
            for (Row row : rows) result.add(buildMap(row, indices, names));
            return result;
        }

        Condition cond = Condition.parse(whereCondition, table);
        if (rows.size() > 500_000)
            return rows.parallelStream()
                    .filter(cond::matches)
                    .map(row -> buildMap(row, indices, names))
                    .collect(Collectors.toList());

        List<Map<String, Object>> result = new ArrayList<>();
        for (Row row : rows)
            if (cond.matches(row)) result.add(buildMap(row, indices, names));
        return result;
    }

    private Map<String, Object> buildMap(Row row, int[] indices, String[] names) {
        Map<String, Object> map = new HashMap<>(indices.length * 2);
        for (int i = 0; i < indices.length; i++)
            if (indices[i] >= 0) map.put(names[i], row.getValue(indices[i]));
        return map;
    }

    private int[] buildIndices(List<Column> projected, Table table) {
        int[] indices = new int[projected.size()];
        for (int i = 0; i < projected.size(); i++)
            indices[i] = table.getColumnIndex(projected.get(i).getName());
        return indices;
    }

    private List<Row> applyWhere(Table table, String whereCondition) {
        if (whereCondition == null || whereCondition.isBlank())
            return table.getRows();

        Condition cond = Condition.parse(whereCondition, table);
        List<Row> rows = table.getRows();

        if (rows.size() > 500_000)
            return rows.parallelStream().filter(cond::matches).collect(Collectors.toList());

        List<Row> filtered = new ArrayList<>();
        for (Row row : rows)
            if (cond.matches(row)) filtered.add(row);
        return filtered;
    }

    private List<Map<String, Object>> applyGroupBy(
            Table table, List<Row> rows, List<String> selectCols, List<String> groupByCols) {

        int[] groupIdx = new int[groupByCols.size()];
        for (int i = 0; i < groupByCols.size(); i++) {
            groupIdx[i] = table.getColumnIndex(groupByCols.get(i));
            if (groupIdx[i] < 0)
                throw new InvalidQueryException("Colonne GROUP BY introuvable : " + groupByCols.get(i));
        }

        // Prépare les infos sur chaque agrégat : expression, type, index colonne
        record AggInfo(String col, String type, int colIdx) {}
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
                    if (idx < 0) throw new InvalidQueryException("Colonne d'agrégat introuvable : " + extractArg(col));
                    aggs.add(new AggInfo(col, type, idx));
                }
            }
        }
        int nAcc = (int) aggs.stream().filter(a -> !a.type().equals("COUNT")).count();

        // Numérotation des slots accumulateur (COUNT n'a pas de slot — utilise .count)
        int[] slot = new int[aggs.size()];
        int s = 0;
        for (int i = 0; i < aggs.size(); i++)
            slot[i] = aggs.get(i).type().equals("COUNT") ? -1 : s++;

        // PASSE UNIQUE : accumulation directe sans stocker les rows
        Map<String, GroupAcc> groups = new HashMap<>();
        for (Row row : rows) {
            String key = buildGroupKey(row, groupIdx);
            GroupAcc acc = groups.get(key);
            if (acc == null) {
                Object[] gv = new Object[groupIdx.length];
                for (int i = 0; i < groupIdx.length; i++) gv[i] = row.getValue(groupIdx[i]);
                acc = new GroupAcc(gv, nAcc);
                groups.put(key, acc);
            }
            acc.count++;
            for (int i = 0; i < aggs.size(); i++) {
                if (slot[i] < 0) continue;
                Object v = row.getValue(aggs.get(i).colIdx());
                if (v instanceof Number n) {
                    double d = n.doubleValue();
                    acc.sums[slot[i]] += d;
                    if (d < acc.mins[slot[i]]) acc.mins[slot[i]] = d;
                    if (d > acc.maxs[slot[i]]) acc.maxs[slot[i]] = d;
                    acc.hasVal[slot[i]] = true;
                }
            }
        }

        // Construction des résultats depuis les accumulateurs
        List<Map<String, Object>> results = new ArrayList<>(groups.size());
        for (GroupAcc acc : groups.values()) {
            Map<String, Object> result = new HashMap<>();
            for (int i = 0; i < groupByCols.size(); i++)
                result.put(groupByCols.get(i), acc.groupVals[i]);
            for (int i = 0; i < aggs.size(); i++) {
                AggInfo ai = aggs.get(i);
                int si = slot[i];
                switch (ai.type()) {
                    case "COUNT" -> result.put(ai.col(), acc.count);
                    case "SUM"   -> result.put(ai.col(), acc.hasVal[si] ? acc.sums[si] : 0.0);
                    case "AVG"   -> result.put(ai.col(), acc.count > 0 ? acc.sums[si] / acc.count : 0.0);
                    case "MIN"   -> result.put(ai.col(), acc.hasVal[si] ? acc.mins[si] : null);
                    case "MAX"   -> result.put(ai.col(), acc.hasVal[si] ? acc.maxs[si] : null);
                }
            }
            results.add(result);
        }
        return results;
    }

    private static class GroupAcc {
        final Object[] groupVals;
        long count = 0;
        final double[] sums;
        final double[] mins;
        final double[] maxs;
        final boolean[] hasVal;

        GroupAcc(Object[] groupVals, int nAcc) {
            this.groupVals = groupVals;
            this.sums   = new double[nAcc];
            this.mins   = new double[nAcc];
            this.maxs   = new double[nAcc];
            this.hasVal = new boolean[nAcc];
            Arrays.fill(mins, Double.MAX_VALUE);
            Arrays.fill(maxs, -Double.MAX_VALUE);
        }
    }

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

    private List<Map<String, Object>> projectRows(List<Row> rows, List<Column> projected, Table table) {
        int[] indices = buildIndices(projected, table);
        String[] names = projected.stream().map(Column::getName).toArray(String[]::new);
        if (rows.size() > 500_000)
            return rows.parallelStream().map(row -> buildMap(row, indices, names)).collect(Collectors.toList());
        List<Map<String, Object>> result = new ArrayList<>(rows.size());
        for (Row row : rows) result.add(buildMap(row, indices, names));
        return result;
    }

    // heap de taille N évite de trier n éléments pour n'en garder que limit
    private List<Row> topNRows(List<Row> rows, Table table, String orderBy, String orderDir, int limit) {
        int idx = table.getColumnIndex(orderBy);
        if (idx < 0) return rows.subList(0, Math.min(limit, rows.size()));

        boolean desc = "DESC".equalsIgnoreCase(orderDir);
        Comparator<Row> heapComp = (a, b) -> {
            int cmp = compareValues(a.getValue(idx), b.getValue(idx));
            return desc ? cmp : -cmp;
        };

        PriorityQueue<Row> heap;

        if (rows.size() > 500_000) {
            // Chaque thread maintient son propre heap — zéro contention, merge final trivial
            heap = rows.parallelStream().collect(
                () -> new PriorityQueue<>(limit + 1, heapComp),
                (h, row) -> { h.offer(row); if (h.size() > limit) h.poll(); },
                (h1, h2) -> { for (Row r : h2) { h1.offer(r); if (h1.size() > limit) h1.poll(); } }
            );
        } else {
            heap = new PriorityQueue<>(limit + 1, heapComp);
            for (Row row : rows) { heap.offer(row); if (heap.size() > limit) heap.poll(); }
        }

        List<Row> result = new ArrayList<>(heap);
        result.sort(heapComp.reversed());
        return result;
    }

    // parallelSort au-delà de 500k car le surcoût fork-join dépasse le gain pour les petits volumes
    private List<Row> sortRows(List<Row> rows, Table table, String orderBy, String orderDir) {
        int idx = table.getColumnIndex(orderBy);
        if (idx < 0) return rows;
        boolean desc = "DESC".equalsIgnoreCase(orderDir);

        Comparator<Row> cmp = (a, b) -> {
            int c = compareValues(a.getValue(idx), b.getValue(idx));
            return desc ? -c : c;
        };

        if (rows.size() > 500_000) {
            Row[] arr = rows.toArray(new Row[0]);
            Arrays.parallelSort(arr, cmp);
            return Arrays.asList(arr); // O(1) — enveloppe le tableau sans copie
        }

        rows.sort(cmp);
        return rows;
    }

    @SuppressWarnings("unchecked")
    private int compareValues(Object va, Object vb) {
        if (va == null && vb == null) return 0;
        if (va == null) return 1;
        if (vb == null) return -1;
        if (va instanceof Number na && vb instanceof Number nb)
            return Double.compare(na.doubleValue(), nb.doubleValue());
        if (va instanceof Comparable ca)
            return ca.compareTo(vb);
        return va.toString().compareTo(vb.toString());
    }

    private void applyOrderByMaps(List<Map<String, Object>> results, String orderBy, String orderDir) {
        boolean desc = "DESC".equalsIgnoreCase(orderDir);
        results.sort((a, b) -> {
            int cmp = compareValues(a.get(orderBy), b.get(orderBy));
            return desc ? -cmp : cmp;
        });
    }

    private String buildGroupKey(Row row, int[] idx) {
        StringBuilder sb = new StringBuilder();
        for (int i : idx) sb.append(row.getValue(i)).append('|');
        return sb.toString();
    }

    private String extractArg(String expr) {
        return expr.substring(expr.indexOf('(') + 1, expr.indexOf(')')).trim();
    }

    private double sumCol(List<Row> rows, int idx) {
        if (idx < 0) return 0;
        double s = 0;
        for (Row r : rows) {
            Object v = r.getValue(idx);
            if (v instanceof Number n) s += n.doubleValue();
        }
        return s;
    }

    private double avgCol(List<Row> rows, int idx) {
        if (idx < 0 || rows.isEmpty()) return 0;
        return sumCol(rows, idx) / rows.size();
    }

    private Object minCol(List<Row> rows, int idx) {
        if (idx < 0) return null;
        double m = Double.MAX_VALUE;
        for (Row r : rows) {
            Object v = r.getValue(idx);
            if (v instanceof Number n) m = Math.min(m, n.doubleValue());
        }
        return m == Double.MAX_VALUE ? null : m;
    }

    private Object maxCol(List<Row> rows, int idx) {
        if (idx < 0) return null;
        double m = -Double.MAX_VALUE;
        for (Row r : rows) {
            Object v = r.getValue(idx);
            if (v instanceof Number n) m = Math.max(m, n.doubleValue());
        }
        return m == -Double.MAX_VALUE ? null : m;
    }

    // ---------------------------------------------------------------
    // Système de conditions WHERE : Condition, AndCondition, OrCondition, SimpleCondition
    // Parsé une fois, évalué des millions de fois sans allocation
    // AND a la priorité sur OR (standard SQL)
    // ---------------------------------------------------------------

    private interface Condition {
        boolean matches(Row row);

        static Condition parse(String expr, Table table) {
            // Split sur OR en premier (priorité basse)
            String[] orParts = expr.split("(?i)\\s+OR\\s+");
            if (orParts.length > 1) {
                Condition[] conds = new Condition[orParts.length];
                for (int i = 0; i < orParts.length; i++)
                    conds[i] = parseAnd(orParts[i], table);
                return new OrCondition(conds);
            }
            return parseAnd(expr, table);
        }

        static Condition parseAnd(String expr, Table table) {
            // Split sur AND (priorité haute)
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

    // Court-circuit : s'arrête dès qu'une condition est fausse
    private record AndCondition(Condition[] conds) implements Condition {
        public boolean matches(Row row) {
            for (Condition c : conds) if (!c.matches(row)) return false;
            return true;
        }
    }

    // Court-circuit : s'arrête dès qu'une condition est vraie
    private record OrCondition(Condition[] conds) implements Condition {
        public boolean matches(Row row) {
            for (Condition c : conds) if (c.matches(row)) return true;
            return false;
        }
    }

    private static class SimpleCondition implements Condition {

        enum Op { EQ, NEQ, LT, LTE, GT, GTE, LIKE }

        final int colIndex;
        final Op op;
        final String raw;
        final double num;
        final boolean isNum;

        SimpleCondition(int colIndex, Op op, String raw) {
            this.colIndex = colIndex;
            this.op       = op;
            this.raw      = raw;
            double d = 0; boolean b = false;
            try { d = Double.parseDouble(raw); b = true; } catch (NumberFormatException ignored) {}
            this.num = d; this.isNum = b;
        }

        static SimpleCondition parse(String condition, Table table) {
            String trimmed = condition.trim();
            String upper   = trimmed.toUpperCase();

            int likePos = upper.indexOf(" LIKE ");
            if (likePos >= 0)
                return make(trimmed.substring(0, likePos), Op.LIKE, trimmed.substring(likePos + 6), table);

            String[][] ops = {{"<=", "LTE"}, {">=", "GTE"}, {"!=", "NEQ"}, {"<", "LT"}, {">", "GT"}, {"=", "EQ"}};
            for (String[] pair : ops) {
                int pos = trimmed.indexOf(pair[0]);
                if (pos > 0)
                    return make(trimmed.substring(0, pos), Op.valueOf(pair[1]), trimmed.substring(pos + pair[0].length()), table);
            }
            throw new InvalidQueryException("Condition WHERE non reconnue : " + condition);
        }

        private static SimpleCondition make(String col, Op op, String val, Table table) {
            int idx = table.getColumnIndex(col.trim());
            if (idx < 0) throw new InvalidQueryException("Colonne WHERE introuvable : " + col.trim());
            return new SimpleCondition(idx, op, val.trim());
        }

        public boolean matches(Row row) {
            Object cell = row.getValue(colIndex);
            if (cell == null) return false;

            if (op == Op.LIKE) {
                String s = cell.toString().toLowerCase();
                String p = raw.toLowerCase();
                if (p.startsWith("%") && p.endsWith("%")) return s.contains(p.substring(1, p.length() - 1));
                if (p.startsWith("%")) return s.endsWith(p.substring(1));
                if (p.endsWith("%"))   return s.startsWith(p.substring(0, p.length() - 1));
                return s.equals(p);
            }

            if (isNum && cell instanceof Number n) {
                double v = n.doubleValue();
                return switch (op) {
                    case EQ  -> v == num;
                    case NEQ -> v != num;
                    case LT  -> v <  num;
                    case LTE -> v <= num;
                    case GT  -> v >  num;
                    case GTE -> v >= num;
                    default  -> false;
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
