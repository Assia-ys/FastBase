package com.fastbase.model;

import com.fastbase.model.enums.ColumnType;
import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.*;

/**
 * Stockage colonnaire en chunks.
 *
 * Chaque colonne numérique est découpée en blocs de CHUNK_SIZE doubles (= 8 MB/bloc).
 * On n'alloue jamais plus de 8 MB à la fois → plus d'OOM sur 50M lignes même avec 12 GB heap.
 *
 * Architecture (17 colonnes NYC Taxi, 50M lignes) :
 *   - 17 colonnes × 50 chunks × 1M doubles = 6,8 GB
 *   - Allocation : 850 blocs de 8 MB (vs 17 allocations de 400 MB → OOM assuré)
 *   - Accès  : numericData[colSlot][rowIdx >> BITS][rowIdx & MASK]  (2 accès tableau)
 *   - GC    : peut collecter des blocs individuels (pas de 400 MB humongoing objects)
 */
public class Table {

    // 2^18 = 262 144 lignes par chunk → 2 MB par colonne numérique.
    // 2 MB < seuil humongous G1GC (4 MB pour heap 14 GB) → collecté normalement, pas en Full GC.
    private static final int CHUNK_BITS = 18;
    private static final int CHUNK_SIZE = 1 << CHUNK_BITS;
    private static final int CHUNK_MASK = CHUNK_SIZE - 1;

    private String       name;
    private List<Column> columns;

    // Schéma : mappings colonne → slot dans les arrays colonnaires
    private int[]        colNumIdx;  // colonne i → slot dans numericData, -1 si texte
    private int[]        colStrIdx;  // colonne i → slot dans stringData, -1 si numérique
    private int          numCount;
    private int          strCount;
    private ColumnType[] colTypes;

    // numericData[colSlot][chunkIdx][posInChunk]
    // stringData [colSlot][chunkIdx][posInChunk]
    private double[][][]  numericData;
    private String[][][]  stringData;

    private int rowCount  = 0;
    private int numChunks = 0;  // chunks déjà alloués

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

    private static boolean isNumeric(ColumnType t) {
        return t == ColumnType.INTEGER || t == ColumnType.LONG
            || t == ColumnType.DOUBLE  || t == ColumnType.BOOLEAN;
    }

    private void rebuildIndex() {
        columnIndex.clear();
        int n = columns.size();
        colNumIdx = new int[n]; colStrIdx = new int[n]; colTypes = new ColumnType[n];
        int nc = 0, sc = 0;
        for (int i = 0; i < n; i++) {
            Column col = columns.get(i);
            columnIndex.put(col.getName(), i);
            colTypes[i] = col.getType();
            if (isNumeric(col.getType())) { colNumIdx[i] = nc++; colStrIdx[i] = -1; }
            else                          { colNumIdx[i] = -1;   colStrIdx[i] = sc++; }
        }
        numCount = nc; strCount = sc;
        numericData = new double[numCount][][];
        stringData  = new String[strCount][][];
        for (int i = 0; i < numCount; i++) numericData[i] = new double[0][];
        for (int i = 0; i < strCount;  i++) stringData[i]  = new String[0][];
        numChunks = 0; rowCount = 0;
    }

    // ── Capacité par chunks ───────────────────────────────────────

    /**
     * Alloue des chunks supplémentaires jusqu'à couvrir {@code needed} lignes.
     * Chaque chunk = 8 MB (CHUNK_SIZE doubles) → jamais d'objet "humongous" en G1GC.
     */
    public synchronized void ensureCapacity(int needed) {
        int currentCap = numChunks * CHUNK_SIZE;
        while (currentCap < needed) {
            int ci = numChunks;
            // Agrandit le tableau de chunks pour chaque colonne
            for (int s = 0; s < numCount; s++) {
                double[][] old = numericData[s];
                double[][] neo = Arrays.copyOf(old, ci + 1);
                neo[ci] = new double[CHUNK_SIZE]; // 8 MB, initialisé à 0.0 (pas de NaN)
                numericData[s] = neo;
            }
            for (int s = 0; s < strCount; s++) {
                String[][] old = stringData[s];
                String[][] neo = Arrays.copyOf(old, ci + 1);
                neo[ci] = new String[CHUNK_SIZE]; // 4 MB (refs null)
                stringData[s] = neo;
            }
            numChunks++;
            currentCap += CHUNK_SIZE;
        }
    }

    /** Alias pour la compatibilité avec le code existant. */
    public void reserveCapacity(int cap) { ensureCapacity(cap); }

    // ── Écriture directe (DataLoaderService) ─────────────────────

    /** Écrit une valeur dans le bon chunk, sans créer d'objet Row. */
    public void setColumnValue(int rowIdx, int colIdx, Object value) {
        int ni = colNumIdx[colIdx];
        if (ni >= 0) {
            numericData[ni][rowIdx >> CHUNK_BITS][rowIdx & CHUNK_MASK] =
                (value == null) ? 0.0 : ((Number) value).doubleValue();
            return;
        }
        int si = colStrIdx[colIdx];
        if (si >= 0)
            stringData[si][rowIdx >> CHUNK_BITS][rowIdx & CHUNK_MASK] =
                (value instanceof String s) ? s.intern()
                : (value != null ? value.toString() : null);
    }

    /** Alloue un bloc de {@code count} lignes et retourne l'index de départ. */
    public synchronized int allocateBatch(int count) {
        ensureCapacity(rowCount + count);
        int start = rowCount;
        rowCount += count;
        return start;
    }

    // ── Rétro-compatibilité (tests avec Row legacy) ───────────────
    /**
     * Insère un batch de Row legacy (BenchmarkServiceTest, etc.).
     * Les valeurs sont copiées dans le stockage colonnaire puis les Row sont jetés.
     */
    public void addRows(List<Row> batch) {
        if (batch.isEmpty()) return;
        int start = allocateBatch(batch.size());
        for (int j = 0; j < batch.size(); j++) {
            Row row = batch.get(j);
            int ri = start + j;
            for (int ci = 0; ci < colNumIdx.length; ci++)
                setColumnValue(ri, ci, row.getValue(ci));
        }
    }

    /** Rétro-compatibilité : crée une Row legacy (anciens tests). */
    public Row createRow() { return new Row(columns.size()); }

    // ── Lecture (QueryService) ────────────────────────────────────

    /** Retourne la valeur à (rowIdx, colIdx) avec le bon type Java. */
    public Object getValue(int rowIdx, int colIdx) {
        int ni = colNumIdx[colIdx];
        if (ni >= 0) {
            double v = numericData[ni][rowIdx >> CHUNK_BITS][rowIdx & CHUNK_MASK];
            return switch (colTypes[colIdx]) {
                case INTEGER -> (int) v;
                case LONG    -> (long) v;
                case BOOLEAN -> v != 0.0;
                default      -> v;
            };
        }
        int si = colStrIdx[colIdx];
        return si >= 0 ? stringData[si][rowIdx >> CHUNK_BITS][rowIdx & CHUNK_MASK] : null;
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

    /**
     * Accès direct à la valeur numérique brute, sans boxing.
     * Utilisé par QueryService pour GROUP BY sans créer de Long/Double/String.
     */
    public double getNumericRaw(int colIdx, int rowIdx) {
        int ni = colNumIdx[colIdx];
        return ni >= 0 ? numericData[ni][rowIdx >> CHUNK_BITS][rowIdx & CHUNK_MASK] : 0.0;
    }

    /** Retourne true si la colonne colIdx est numérique (stockée en double[]). */
    public boolean isNumericColumn(int colIdx) { return colIdx >= 0 && colNumIdx[colIdx] >= 0; }

    /** @deprecated Stockage colonnaire : utiliser getValue(rowIdx, colIdx). */
    @JsonIgnore
    public List<Row> getRows() { return Collections.emptyList(); }

    @Override
    public String toString() {
        return "Table{name='" + name + "', columns=" + columns.size() + ", rows=" + rowCount + "}";
    }
}