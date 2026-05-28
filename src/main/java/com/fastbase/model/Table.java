package com.fastbase.model;

import com.fastbase.model.enums.ColumnType;
import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.*;

/**
 * Stockage colonnaire typé en chunks.
 *
 * Type Java par ColumnType :
 *   INTEGER / BOOLEAN → int[][][]   (4 octets)
 *   LONG              → long[][][]  (8 octets, précis sur 64 bits)
 *   DOUBLE            → float[][][] (4 octets, ~7 chiffres significatifs)
 *   STRING            → String[][][] (intern() pour les valeurs répétées)
 *
 * Empreinte mémoire NYC Taxi 19 cols / 50M lignes :
 *   4 × INTEGER × 4 =  800 MB
 *   2 × LONG    × 8 =  800 MB
 *  12 × DOUBLE  × 4 = 2 400 MB
 *   1 × STRING  × 8 =  400 MB
 *  Total ≈ 4,4 GB  (vs 7,6 GB avec double pour tout)
 */
public class Table {

    private static final int CHUNK_BITS = 18;            // 2^18 = 262 144 lignes/chunk
    private static final int CHUNK_SIZE = 1 << CHUNK_BITS;
    private static final int CHUNK_MASK = CHUNK_SIZE - 1;

    private String       name;
    private List<Column> columns;

    // Schéma — un seul slot actif parmi les quatre pour chaque colonne i
    private int[]        colIntIdx;   // col i → slot intData,    -1 sinon
    private int[]        colLongIdx;  // col i → slot longData,   -1 sinon
    private int[]        colFltIdx;   // col i → slot floatData,  -1 sinon
    private int[]        colStrIdx;   // col i → slot stringData, -1 sinon
    private ColumnType[] colTypes;
    private int          intCount, longCount, fltCount, strCount;

    // Stockage [colSlot][chunkIdx][posInChunk]
    private int[][][]    intData;
    private long[][][]   longData;
    private float[][][]  floatData;
    private String[][][] stringData;

    private int rowCount  = 0;
    private int numChunks = 0;

    private final Map<String, Integer> columnIndex = new HashMap<>();

    // ── Constructeurs ──────────────────────────────────────────────

    public Table() {
        this.columns = new ArrayList<>();
        rebuildIndex();
    }

    public Table(String name, List<Column> columns) {
        this.name    = name;
        this.columns = columns != null ? columns : new ArrayList<>();
        rebuildIndex();
    }

    // ── Schéma ────────────────────────────────────────────────────

    private void rebuildIndex() {
        columnIndex.clear();
        int n = columns.size();
        colIntIdx  = new int[n]; Arrays.fill(colIntIdx,  -1);
        colLongIdx = new int[n]; Arrays.fill(colLongIdx, -1);
        colFltIdx  = new int[n]; Arrays.fill(colFltIdx,  -1);
        colStrIdx  = new int[n]; Arrays.fill(colStrIdx,  -1);
        colTypes   = new ColumnType[n];
        int ic = 0, lc = 0, fc = 0, sc = 0;
        for (int i = 0; i < n; i++) {
            Column col = columns.get(i);
            columnIndex.put(col.getName(), i);
            colTypes[i] = col.getType();
            switch (col.getType()) {
                case INTEGER, BOOLEAN -> colIntIdx[i]  = ic++;
                case LONG             -> colLongIdx[i] = lc++;
                case DOUBLE           -> colFltIdx[i]  = fc++;
                default               -> colStrIdx[i]  = sc++;
            }
        }
        intCount  = ic; longCount = lc; fltCount = fc; strCount = sc;
        intData    = new int[intCount][][];
        longData   = new long[longCount][][];
        floatData  = new float[fltCount][][];
        stringData = new String[strCount][][];
        for (int i = 0; i < intCount;  i++) intData[i]    = new int[0][];
        for (int i = 0; i < longCount; i++) longData[i]   = new long[0][];
        for (int i = 0; i < fltCount;  i++) floatData[i]  = new float[0][];
        for (int i = 0; i < strCount;  i++) stringData[i] = new String[0][];
        numChunks = 0; rowCount = 0;
    }

    // ── Capacité par chunks ───────────────────────────────────────

    public synchronized void ensureCapacity(int needed) {
        int currentCap = numChunks * CHUNK_SIZE;
        while (currentCap < needed) {
            int ci = numChunks;
            for (int s = 0; s < intCount; s++) {
                int[][] neo = Arrays.copyOf(intData[s], ci + 1);
                neo[ci] = new int[CHUNK_SIZE];
                intData[s] = neo;
            }
            for (int s = 0; s < longCount; s++) {
                long[][] neo = Arrays.copyOf(longData[s], ci + 1);
                neo[ci] = new long[CHUNK_SIZE];
                longData[s] = neo;
            }
            for (int s = 0; s < fltCount; s++) {
                float[][] neo = Arrays.copyOf(floatData[s], ci + 1);
                neo[ci] = new float[CHUNK_SIZE];
                floatData[s] = neo;
            }
            for (int s = 0; s < strCount; s++) {
                String[][] neo = Arrays.copyOf(stringData[s], ci + 1);
                neo[ci] = new String[CHUNK_SIZE];
                stringData[s] = neo;
            }
            numChunks++;
            currentCap += CHUNK_SIZE;
        }
    }

    public void reserveCapacity(int cap) { ensureCapacity(cap); }

    // ── Écriture directe (DataLoaderService) ─────────────────────

    public void setColumnValue(int rowIdx, int colIdx, Object value) {
        int chunk = rowIdx >> CHUNK_BITS, pos = rowIdx & CHUNK_MASK;
        int ii = colIntIdx[colIdx];
        if (ii >= 0) {
            intData[ii][chunk][pos] = value == null ? 0 : ((Number) value).intValue();
            return;
        }
        int li = colLongIdx[colIdx];
        if (li >= 0) {
            longData[li][chunk][pos] = value == null ? 0L : ((Number) value).longValue();
            return;
        }
        int fi = colFltIdx[colIdx];
        if (fi >= 0) {
            floatData[fi][chunk][pos] = value == null ? 0f : ((Number) value).floatValue();
            return;
        }
        int si = colStrIdx[colIdx];
        if (si >= 0)
            stringData[si][chunk][pos] = value instanceof String s ? s.intern()
                : value != null ? value.toString() : null;
    }

    public synchronized int allocateBatch(int count) {
        ensureCapacity(rowCount + count);
        int start = rowCount;
        rowCount += count;
        return start;
    }

    public synchronized void trimRowCount(int actual) {
        if (actual >= 0 && actual < rowCount) rowCount = actual;
    }

    // ── Rétro-compatibilité tests (Row legacy) ────────────────────

    public void addRows(List<Row> batch) {
        if (batch.isEmpty()) return;
        int start = allocateBatch(batch.size());
        for (int j = 0; j < batch.size(); j++) {
            Row row = batch.get(j);
            int ri = start + j;
            for (int ci = 0; ci < colIntIdx.length; ci++)
                setColumnValue(ri, ci, row.getValue(ci));
        }
    }

    public Row createRow() { return new Row(columns.size()); }

    // ── Lecture (QueryService) ────────────────────────────────────

    public Object getValue(int rowIdx, int colIdx) {
        int chunk = rowIdx >> CHUNK_BITS, pos = rowIdx & CHUNK_MASK;
        int ii = colIntIdx[colIdx];
        if (ii >= 0) return colTypes[colIdx] == ColumnType.BOOLEAN
                ? intData[ii][chunk][pos] != 0
                : intData[ii][chunk][pos];
        int li = colLongIdx[colIdx];
        if (li >= 0) return longData[li][chunk][pos];
        int fi = colFltIdx[colIdx];
        if (fi >= 0) return (double) floatData[fi][chunk][pos];
        int si = colStrIdx[colIdx];
        return si >= 0 ? stringData[si][chunk][pos] : null;
    }

    /** Valeur numérique brute sans boxing — utilisé par GROUP BY pour éviter Integer/Long/Double. */
    public double getNumericRaw(int colIdx, int rowIdx) {
        int chunk = rowIdx >> CHUNK_BITS, pos = rowIdx & CHUNK_MASK;
        int ii = colIntIdx[colIdx];
        if (ii >= 0) return intData[ii][chunk][pos];
        int li = colLongIdx[colIdx];
        if (li >= 0) return longData[li][chunk][pos];
        int fi = colFltIdx[colIdx];
        if (fi >= 0) return floatData[fi][chunk][pos];
        return 0.0;
    }

    public boolean isNumericColumn(int colIdx) {
        return colIdx >= 0
            && (colIntIdx[colIdx] >= 0 || colLongIdx[colIdx] >= 0 || colFltIdx[colIdx] >= 0);
    }

    // ── Accès direct aux slots + écriture typée (DataLoaderService optimisé) ──────────
    // Évite le dispatch de setColumnValue() dans la boucle chaude de chargement Parquet.

    public int getFltSlot(int colIdx)  { return colIdx >= 0 && colIdx < colFltIdx.length  ? colFltIdx[colIdx]  : -1; }
    public int getIntSlot(int colIdx)  { return colIdx >= 0 && colIdx < colIntIdx.length  ? colIntIdx[colIdx]  : -1; }
    public int getLongSlot(int colIdx) { return colIdx >= 0 && colIdx < colLongIdx.length ? colLongIdx[colIdx] : -1; }
    public int getStrSlot(int colIdx)  { return colIdx >= 0 && colIdx < colStrIdx.length  ? colStrIdx[colIdx]  : -1; }

    public void writeFloat(int slot, int rowIdx, float v) {
        floatData[slot][rowIdx >> CHUNK_BITS][rowIdx & CHUNK_MASK] = v;
    }
    public void writeInt(int slot, int rowIdx, int v) {
        intData[slot][rowIdx >> CHUNK_BITS][rowIdx & CHUNK_MASK] = v;
    }
    public void writeLong(int slot, int rowIdx, long v) {
        longData[slot][rowIdx >> CHUNK_BITS][rowIdx & CHUNK_MASK] = v;
    }
    public void writeString(int slot, int rowIdx, String v) {
        stringData[slot][rowIdx >> CHUNK_BITS][rowIdx & CHUNK_MASK] = v != null ? v.intern() : null;
    }

    // ── Getters / Setters ─────────────────────────────────────────

    public int          getRowCount()    { return rowCount; }
    public String       getName()        { return name; }
    public void         setName(String n){ this.name = n; }
    public List<Column> getColumns()     { return columns; }

    public void setColumns(List<Column> cols) {
        this.columns = cols;
        rebuildIndex();
    }

    public int getColumnIndex(String columnName) {
        Integer idx = columnIndex.get(columnName);
        return idx != null ? idx : -1;
    }

    public Column getColumn(String columnName) {
        int idx = getColumnIndex(columnName);
        return idx >= 0 ? columns.get(idx) : null;
    }

    @JsonIgnore
    public List<Row> getRows() { return Collections.emptyList(); }

    @Override
    public String toString() {
        return "Table{name='" + name + "', columns=" + columns.size() + ", rows=" + rowCount + "}";
    }
}