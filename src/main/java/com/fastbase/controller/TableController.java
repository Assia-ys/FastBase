package com.fastbase.controller;

import com.fastbase.dto.CreateTableRequestDTO;
import com.fastbase.dto.LoadDataRequestDTO;
import com.fastbase.dto.LoadFromUrlRequestDTO;
import com.fastbase.model.Table;
import com.fastbase.service.DataLoaderService;
import com.fastbase.service.TableService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.io.InputStream;
import java.net.URI;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
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

    @PostMapping("/load-url")
    public Map<String, Object> loadFromUrl(@Valid @RequestBody LoadFromUrlRequestDTO request) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30))
                .build();

        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(request.getUrl()))
                .timeout(Duration.ofMinutes(30))
                .GET()
                .build();

        long start = System.currentTimeMillis();
        HttpResponse<InputStream> response = client.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());

        if (response.statusCode() != 200) {
            throw new IllegalArgumentException("Echec du téléchargement — HTTP " + response.statusCode());
        }

        int rows;
        try (InputStream body = response.body()) {
            rows = switch (request.getFormat()) {
                case CSV     -> dataLoaderService.loadCsvData(request.getTableName(), body);
                case PARQUET -> dataLoaderService.loadParquetData(request.getTableName(), body, request.getMaxRows());
            };
        }

        return Map.of(
                "message",    "Données chargées depuis " + request.getUrl(),
                "rowsLoaded", rows,
                "loadingMs",  System.currentTimeMillis() - start
        );
    }
}
