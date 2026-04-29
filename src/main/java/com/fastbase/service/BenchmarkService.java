package com.fastbase.service;

import com.fastbase.model.Table;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.*;

@Service
public class BenchmarkService {

    private final QueryService queryService;
    private final DataLoaderService dataLoaderService;

    public BenchmarkService(QueryService queryService, DataLoaderService dataLoaderService) {
        this.queryService      = queryService;
        this.dataLoaderService = dataLoaderService;
    }

    public record BenchmarkResult(
            String operation,
            long rowCount,
            long elapsedMs,
            long elapsedNs
    ) {
        public String toCsvLine() {
            return operation + "," + rowCount + "," + elapsedMs + "," + elapsedNs;
        }

        @Override
        public String toString() {
            return String.format("[%s] rows=%d | %d ms (%,d ns)",
                    operation, rowCount, elapsedMs, elapsedNs);
        }
    }

    public BenchmarkResult benchmarkCsvLoad(String tableName, String filePath, int maxRows) {
        try {
            long t0       = System.nanoTime();
            int rowCount  = dataLoaderService.loadCsvData(tableName, filePath, maxRows);
            long elapsed  = System.nanoTime() - t0;
            return new BenchmarkResult("LOAD", rowCount, elapsed / 1_000_000, elapsed);
        } catch (IOException e) {
            throw new RuntimeException("Erreur benchmark LOAD : " + e.getMessage(), e);
        }
    }

    public BenchmarkResult benchmarkLoad(Table table, List<com.fastbase.model.Row> rows) {
        long t0      = System.nanoTime();
        table.addRows(rows);
        long elapsed = System.nanoTime() - t0;
        return new BenchmarkResult("LOAD", rows.size(), elapsed / 1_000_000, elapsed);
    }

    public BenchmarkResult benchmarkSelect(String tableName, List<String> selectColumns, String whereCondition) {
        long t0 = System.nanoTime();
        List<Map<String, Object>> results = queryService.execute(tableName, selectColumns, whereCondition, null);
        long elapsed = System.nanoTime() - t0;
        return new BenchmarkResult("SELECT", results.size(), elapsed / 1_000_000, elapsed);
    }

    public BenchmarkResult benchmarkGroupBy(String tableName, List<String> selectColumns,
                                            String whereCondition, List<String> groupByColumns) {
        long t0 = System.nanoTime();
        List<Map<String, Object>> results = queryService.execute(tableName, selectColumns, whereCondition, groupByColumns);
        long elapsed = System.nanoTime() - t0;
        return new BenchmarkResult("GROUP_BY", results.size(), elapsed / 1_000_000, elapsed);
    }

    public String exportToCsv(List<BenchmarkResult> results) {
        StringBuilder sb = new StringBuilder("operation,rowCount,elapsedMs,elapsedNs\n");
        for (BenchmarkResult r : results) sb.append(r.toCsvLine()).append("\n");
        return sb.toString();
    }

    public String exportToCsv(BenchmarkResult result) {
        return exportToCsv(List.of(result));
    }
}
