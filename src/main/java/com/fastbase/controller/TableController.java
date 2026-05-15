package com.fastbase.controller;

import com.fastbase.dto.CreateTableRequestDTO;
import com.fastbase.dto.LoadDataRequestDTO;
import com.fastbase.model.Table;
import com.fastbase.service.DataLoaderService;
import com.fastbase.service.TableService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/tables")
public class TableController {

    private final TableService tableService;
    private final DataLoaderService dataLoaderService;

    public TableController(TableService tableService, DataLoaderService dataLoaderService) {
        this.tableService      = tableService;
        this.dataLoaderService = dataLoaderService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createTable(@Valid @RequestBody CreateTableRequestDTO request) {
        Table table = tableService.createTable(request.getTableName(), request.getColumns());
        return Map.of("message", "Table créée avec succès", "tableName", table.getName(), "columns", table.getColumns().size());
    }

    @GetMapping
    public List<Table> getAllTables() {
        return tableService.getAllTables();
    }

    @GetMapping("/{name}")
    public Table getTable(@PathVariable String name) {
        return tableService.getTable(name);
    }

    @DeleteMapping("/{name}")
    public Map<String, Object> deleteTable(@PathVariable String name) {
        tableService.deleteTable(name);
        return Map.of("message", "Table supprimée avec succès");
    }

    @PostMapping(value = "/load", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> loadData(@Valid @ModelAttribute LoadDataRequestDTO request) throws IOException {
        if (request.getFile().isEmpty()) {
            throw new IllegalArgumentException("Le fichier est vide");
        }

        long start = System.currentTimeMillis();
        int rows = switch (request.getFormat()) {
            case CSV     -> dataLoaderService.loadCsvData(request.getTableName(), request.getFile().getInputStream());
            case PARQUET -> dataLoaderService.loadParquetData(request.getTableName(), request.getFile().getInputStream());
        };
        return Map.of("message", "Données chargées", "rowsLoaded", rows, "loadingMs", System.currentTimeMillis() - start);
    }
}
