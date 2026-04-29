package com.fastbase.service;

import com.fastbase.exception.InvalidQueryException;
import com.fastbase.exception.TableNotFoundException;
import com.fastbase.model.Column;
import com.fastbase.model.Row;
import com.fastbase.model.Table;
import com.fastbase.storage.DataStorage;
import org.springframework.stereotype.Service;

import java.util.*;

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

        List<Row> rows = applyWhere(table, whereCondition);

        List<Map<String, Object>> results;
        if (groupByCols != null && !groupByCols.isEmpty()) {
            results = applyGroupBy(table, rows, selectCols, groupByCols);
            if (orderBy != null && !orderBy.isBlank())
                applyOrderByMaps(results, orderBy, orderDir);
            if (limit != null && limit > 0 && results.size() > limit)
                results = results.subList(0, limit);
        } else {
            if (orderBy != null && !orderBy.isBlank()) {
                boolean useHeap = limit != null && limit > 0 && limit < rows.size();
                if (useHeap)
                    rows = topNRows(rows, table, orderBy, orderDir, limit);
                else
                    sortRows(rows, table, orderBy, orderDir);
            }
            results = projectRows(rows, resolveColumns(table, selectCols), table);
            if (limit != null && limit > 0 && orderBy == null && results.size() > limit)
                results = results.subList(0, limit);
        }

        return results;
    }

    private List<Row> applyWhere(Table table, String whereCondition) {
        if (whereCondition == null || whereCondition.isBlank())
            return table.getRows();

        WhereCondition cond = WhereCondition.parse(whereCondition, table);
        List<Row> filtered = new ArrayList<>();
        for (Row row : table.getRows()) {
            if (cond.matches(row)) filtered.add(row);
        }
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

        Map<String, List<Row>> groups = new HashMap<>();
        for (Row row : rows)
            groups.computeIfAbsent(buildGroupKey(row, groupIdx), k -> new ArrayList<>()).add(row);

        // Indices des colonnes d'agrégat calculés une seule fois, pas à chaque itération de groupe
        Map<String, Integer> aggColCache = new HashMap<>();
        if (selectCols != null) {
            for (String col : selectCols) {
                String up = col.trim().toUpperCase();
                if (up.startsWith("SUM(") || up.startsWith("AVG(") ||
                    up.startsWith("MIN(") || up.startsWith("MAX(")) {
                    String arg = extractArg(col);
                    int idx = table.getColumnIndex(arg);
                    if (idx < 0)
                        throw new InvalidQueryException("Colonne d'agrégat introuvable : " + arg);
                    aggColCache.put(col, idx);
                }
            }
        }

        List<Map<String, Object>> results = new ArrayList<>(groups.size());
        for (List<Row> groupRows : groups.values()) {
            Map<String, Object> result = new HashMap<>();
            Row first = groupRows.get(0);

            for (int i = 0; i < groupByCols.size(); i++)
                result.put(groupByCols.get(i), first.getValue(groupIdx[i]));

            if (selectCols != null) {
                for (String col : selectCols) {
                    String up = col.trim().toUpperCase();
                    if      (up.startsWith("COUNT(")) result.put(col, (long) groupRows.size());
                    else if (up.startsWith("SUM("))   result.put(col, sumCol(groupRows, aggColCache.get(col)));
                    else if (up.startsWith("AVG("))   result.put(col, avgCol(groupRows, aggColCache.get(col)));
                    else if (up.startsWith("MIN("))   result.put(col, minCol(groupRows, aggColCache.get(col)));
                    else if (up.startsWith("MAX("))   result.put(col, maxCol(groupRows, aggColCache.get(col)));
                }
            }
            results.add(result);
        }
        return results;
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
        int[] indices = new int[projected.size()];
        for (int i = 0; i < projected.size(); i++)
            indices[i] = table.getColumnIndex(projected.get(i).getName());

        List<Map<String, Object>> result = new ArrayList<>(rows.size());
        for (Row row : rows) {
            Map<String, Object> map = new HashMap<>(projected.size() * 2);
            for (int i = 0; i < projected.size(); i++)
                if (indices[i] >= 0) map.put(projected.get(i).getName(), row.getValue(indices[i]));
            result.add(map);
        }
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

        PriorityQueue<Row> heap = new PriorityQueue<>(limit + 1, heapComp);
        for (Row row : rows) {
            heap.offer(row);
            if (heap.size() > limit) heap.poll();
        }

        List<Row> result = new ArrayList<>(heap);
        result.sort(desc ? heapComp.reversed() : heapComp.reversed().reversed());
        return result;
    }

    // parallelSort au-delà de 500k car le surcoût fork-join dépasse le gain pour les petits volumes
    private void sortRows(List<Row> rows, Table table, String orderBy, String orderDir) {
        int idx = table.getColumnIndex(orderBy);
        if (idx < 0) return;
        boolean desc = "DESC".equalsIgnoreCase(orderDir);

        Comparator<Row> cmp = (a, b) -> {
            int c = compareValues(a.getValue(idx), b.getValue(idx));
            return desc ? -c : c;
        };

        if (rows.size() > 500_000) {
            Row[] arr = rows.toArray(new Row[0]);
            Arrays.parallelSort(arr, cmp);
            for (int i = 0; i < arr.length; i++) rows.set(i, arr[i]);
        } else {
            rows.sort(cmp);
        }
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

    private static class WhereCondition {

        enum Op { EQ, NEQ, LT, LTE, GT, GTE, LIKE }

        final int colIndex;
        final Op op;
        final String raw;
        final double num;
        final boolean isNum;

        WhereCondition(int colIndex, Op op, String raw) {
            this.colIndex = colIndex;
            this.op       = op;
            this.raw      = raw;
            double d = 0; boolean b = false;
            try { d = Double.parseDouble(raw); b = true; } catch (NumberFormatException ignored) {}
            this.num = d; this.isNum = b;
        }

        static WhereCondition parse(String condition, Table table) {
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

        private static WhereCondition make(String col, Op op, String val, Table table) {
            int idx = table.getColumnIndex(col.trim());
            if (idx < 0) throw new InvalidQueryException("Colonne WHERE introuvable : " + col.trim());
            return new WhereCondition(idx, op, val.trim());
        }

        boolean matches(Row row) {
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
