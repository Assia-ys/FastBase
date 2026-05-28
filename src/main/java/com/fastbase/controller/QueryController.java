package com.fastbase.controller;

import com.fastbase.dto.QueryRequestDTO;
import com.fastbase.service.QueryService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/query")
public class QueryController {

    private final QueryService queryService;

    public QueryController(QueryService queryService) {
        this.queryService = queryService;
    }

    @PostMapping("/select")
    public Map<String, Object> executeQuery(@Valid @RequestBody QueryRequestDTO request) {
        long start = System.currentTimeMillis();
        List<Map<String, Object>> results = queryService.execute(
                request.getTableName(),
                request.getSelectColumns(),
                request.getWhereCondition(),
                request.getGroupByColumns(),
                request.getOrderBy(),
                request.getOrderDir(),
                request.getLimit()
        );
        return Map.of("rowCount", results.size(), "executionMs", System.currentTimeMillis() - start, "data", results);
    }
}