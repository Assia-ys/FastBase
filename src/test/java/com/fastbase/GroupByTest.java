package com.fastbase;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GroupByTest extends AbstractFastBaseTest {

    @Test
    void groupByVilleCount() {
        List<Map<String, Object>> results = queryService.execute(TABLE, List.of("COUNT(id)"), null, List.of("ville"));
        assertEquals(3, results.size());
        results.forEach(row -> assertNotNull(row.get("COUNT(id)")));
    }

    @Test
    void groupByVilleSumSalaire() {
        List<Map<String, Object>> results = queryService.execute(TABLE, List.of("SUM(salaire)"), null, List.of("ville"));
        assertEquals(3, results.size());
        results.forEach(row -> assertNotNull(row.get("SUM(salaire)")));
    }

    @Test
    void groupByVilleAvgAge() {
        List<Map<String, Object>> results = queryService.execute(TABLE, List.of("AVG(age)"), null, List.of("ville"));
        assertEquals(3, results.size());
        results.forEach(row -> assertNotNull(row.get("AVG(age)")));
    }

    @Test
    void groupByVilleMinSalaire() {
        List<Map<String, Object>> results = queryService.execute(TABLE, List.of("MIN(salaire)"), null, List.of("ville"));
        assertEquals(3, results.size());
        results.forEach(row -> assertNotNull(row.get("MIN(salaire)")));
    }

    @Test
    void groupByVilleMaxSalaire() {
        List<Map<String, Object>> results = queryService.execute(TABLE, List.of("MAX(salaire)"), null, List.of("ville"));
        assertEquals(3, results.size());
        results.forEach(row -> assertNotNull(row.get("MAX(salaire)")));
    }

    @Test
    void groupByVilleContientLesVilles() {
        List<Map<String, Object>> results = queryService.execute(TABLE, List.of("COUNT(id)"), null, List.of("ville"));
        List<String> villes = results.stream().map(r -> r.get("ville").toString()).toList();
        assertTrue(villes.contains("Paris"));
        assertTrue(villes.contains("Lyon"));
        assertTrue(villes.contains("Marseille"));
    }
}
