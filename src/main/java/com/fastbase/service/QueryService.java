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

        Table table = dataStorage.getTable(tableName)
                .orElseThrow(() -> new TableNotFoundException(tableName));

        List<Row> rows = applyWhere(table, whereCondition);

        if (groupByCols != null && !groupByCols.isEmpty())
            return applyGroupBy(table, rows, selectCols, groupByCols);

        return projectRows(rows, resolveColumns(table, selectCols), table.getColumns());
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

        Map<String, List<Row>> groups = new LinkedHashMap<>();
        for (Row row : rows)
            groups.computeIfAbsent(buildGroupKey(row, groupIdx), k -> new ArrayList<>()).add(row);

        List<Map<String, Object>> results = new ArrayList<>(groups.size());
        for (List<Row> groupRows : groups.values()) {
            Map<String, Object> result = new LinkedHashMap<>();
            Row first = groupRows.get(0);

            for (int i = 0; i < groupByCols.size(); i++)
                result.put(groupByCols.get(i), first.getValue(groupIdx[i]));

            if (selectCols != null) {
                for (String col : selectCols) {
                    String up = col.trim().toUpperCase();
                    if      (up.startsWith("COUNT(")) result.put(col, (long) groupRows.size());
                    else if (up.startsWith("SUM("))   result.put(col, sumCol(groupRows, table.getColumnIndex(extractArg(col))));
                    else if (up.startsWith("AVG("))   result.put(col, avgCol(groupRows, table.getColumnIndex(extractArg(col))));
                    else if (up.startsWith("MIN("))   result.put(col, minCol(groupRows, table.getColumnIndex(extractArg(col))));
                    else if (up.startsWith("MAX("))   result.put(col, maxCol(groupRows, table.getColumnIndex(extractArg(col))));
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

    private List<Map<String, Object>> projectRows(List<Row> rows, List<Column> projected, List<Column> all) {
        int[] indices = new int[projected.size()];
        for (int i = 0; i < projected.size(); i++)
            indices[i] = all.indexOf(projected.get(i));

        List<Map<String, Object>> result = new ArrayList<>(rows.size());
        for (Row row : rows) {
            Map<String, Object> map = new LinkedHashMap<>(projected.size() * 2);
            for (int i = 0; i < projected.size(); i++)
                if (indices[i] >= 0) map.put(projected.get(i).getName(), row.getValue(indices[i]));
            result.add(map);
        }
        return result;
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
        double s = 0;
        for (Row r : rows) { Object v = r.getValue(idx); if (v instanceof Number n) s += n.doubleValue(); }
        return s;
    }

    private double avgCol(List<Row> rows, int idx) {
        return rows.isEmpty() ? 0 : sumCol(rows, idx) / rows.size();
    }

    private Object minCol(List<Row> rows, int idx) {
        double m = Double.MAX_VALUE;
        for (Row r : rows) { Object v = r.getValue(idx); if (v instanceof Number n) m = Math.min(m, n.doubleValue()); }
        return m == Double.MAX_VALUE ? null : m;
    }

    private Object maxCol(List<Row> rows, int idx) {
        double m = -Double.MAX_VALUE;
        for (Row r : rows) { Object v = r.getValue(idx); if (v instanceof Number n) m = Math.max(m, n.doubleValue()); }
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