package com.fastbase.controller;

import com.fastbase.dto.QueryRequest;
import com.fastbase.model.Row;
import com.fastbase.service.QueryService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Controller REST pour l'exécution de requêtes
 */
@RestController
@RequestMapping("/api/query")
public class QueryController {

    private final QueryService queryService;

    public QueryController(QueryService queryService) {
        this.queryService = queryService;
    }

    /**
     * POST /api/query/select - Exécuter une requête SELECT
     */
    @PostMapping("/select")
    public ResponseEntity<Map<String, Object>> executeQuery(@RequestBody QueryRequest request) {
        try {
            List<Row> results;

            // Déterminer quel type de requête exécuter
            if (request.getGroupByColumns() != null && !request.getGroupByColumns().isEmpty()) {
                // SELECT avec GROUP BY
                results = queryService.executeSelectWithGroupBy(
                        request.getTableName(),
                        request.getSelectColumns(),
                        request.getGroupByColumns()
                );
            } else if (request.getWhereCondition() != null && !request.getWhereCondition().isEmpty()) {
                // SELECT avec WHERE
                results = queryService.executeSelectWithWhere(
                        request.getTableName(),
                        request.getSelectColumns(),
                        request.getWhereCondition()
                );
            } else {
                // SELECT simple
                results = queryService.executeSelect(
                        request.getTableName(),
                        request.getSelectColumns()
                );
            }

            Map<String, Object> response = new HashMap<>();
            response.put("rowCount", results.size());
            response.put("data", results);

            return ResponseEntity.ok(response);

        } catch (IllegalArgumentException e) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(error);
        } catch (UnsupportedOperationException e) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", "Fonctionnalité non encore implémentée: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).body(error);
        }
    }
}
