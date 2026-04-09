package com.fastbase.controller;

import com.fastbase.dto.QueryRequest;
import com.fastbase.service.QueryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/query")
@RequiredArgsConstructor
public class QueryController {

    private final QueryService queryService;

    @PostMapping("/select")
    public Map<String, Object> executeQuery(@Valid @RequestBody QueryRequest request) {
        long start = System.currentTimeMillis();
        List<Map<String, Object>> results = queryService.execute(
                request.getTableName(),
                request.getSelectColumns(),
                request.getWhereCondition(),
                request.getGroupByColumns()
        );
        return Map.of("rowCount", results.size(), "executionMs", System.currentTimeMillis() - start, "data", results);
    }
}