package com.fastbase.controller;

import com.fastbase.dto.CreateTableRequest;
import com.fastbase.dto.LoadDataRequest;
import com.fastbase.model.Table;
import com.fastbase.service.DataLoaderService;
import com.fastbase.service.TableService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Controller REST pour la gestion des tables
 */
@RestController
@RequestMapping("/api/tables")
public class TableController {

    private final TableService tableService;
    private final DataLoaderService dataLoaderService;

    public TableController(TableService tableService, DataLoaderService dataLoaderService) {
        this.tableService = tableService;
        this.dataLoaderService = dataLoaderService;
    }

    /**
     * POST /api/tables - Créer une nouvelle table
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> createTable(@RequestBody CreateTableRequest request) {
        try {
            Table table = tableService.createTable(request.getTableName(), request.getColumns());

            Map<String, Object> response = new HashMap<>();
            response.put("message", "Table créée avec succès");
            response.put("tableName", table.getName());
            response.put("columns", table.getColumns().size());

            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } catch (IllegalArgumentException | IllegalStateException e) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(error);
        }
    }

    /**
     * GET /api/tables - Récupérer toutes les tables
     */
    @GetMapping
    public ResponseEntity<List<Table>> getAllTables() {
        List<Table> tables = tableService.getAllTables();
        return ResponseEntity.ok(tables);
    }

    /**
     * GET /api/tables/{name} - Récupérer une table par son nom
     */
    @GetMapping("/{name}")
    public ResponseEntity<Table> getTable(@PathVariable String name) {
        try {
            Table table = tableService.getTable(name);
            return ResponseEntity.ok(table);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * DELETE /api/tables/{name} - Supprimer une table
     */
    @DeleteMapping("/{name}")
    public ResponseEntity<Map<String, Object>> deleteTable(@PathVariable String name) {
        boolean deleted = tableService.deleteTable(name);

        Map<String, Object> response = new HashMap<>();
        if (deleted) {
            response.put("message", "Table supprimée avec succès");
            return ResponseEntity.ok(response);
        } else {
            response.put("error", "Table non trouvée");
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * POST /api/tables/load - Charger des données dans une table
     */
    @PostMapping("/load")
    public ResponseEntity<Map<String, Object>> loadData(@RequestBody LoadDataRequest request) {
        try {
            int rowCount;
            if (request.getFormat() == LoadDataRequest.FileFormat.CSV) {
                rowCount = dataLoaderService.loadCsvData(request.getTableName(), request.getFilePath());
            } else {
                rowCount = dataLoaderService.loadParquetData(request.getTableName(), request.getFilePath());
            }

            Map<String, Object> response = new HashMap<>();
            response.put("message", "Données chargées avec succès");
            response.put("rowsLoaded", rowCount);

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }
}
