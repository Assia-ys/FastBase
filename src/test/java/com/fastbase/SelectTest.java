package com.fastbase;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SelectTest extends AbstractFastBaseTest {

    @Test
    void selectToutRetourne10Lignes() {
        List<Map<String, Object>> results = queryService.execute(TABLE, List.of("*"), null, null);
        assertEquals(10, results.size());
    }

    @Test
    void selectContientLesColonnes() {
        List<Map<String, Object>> results = queryService.execute(TABLE, List.of("*"), null, null);
        assertTrue(results.get(0).containsKey("nom"));
        assertTrue(results.get(0).containsKey("age"));
        assertTrue(results.get(0).containsKey("ville"));
        assertTrue(results.get(0).containsKey("salaire"));
    }

    @Test
    void selectProjectionNePrendQueLesBonnesColonnes() {
        List<Map<String, Object>> results = queryService.execute(TABLE, List.of("nom", "ville"), null, null);
        assertEquals(10, results.size());
        assertTrue(results.get(0).containsKey("nom"));
        assertTrue(results.get(0).containsKey("ville"));
        assertFalse(results.get(0).containsKey("age"));
        assertFalse(results.get(0).containsKey("salaire"));
    }

    @Test
    void selectValeursPasnull() {
        List<Map<String, Object>> results = queryService.execute(TABLE, List.of("nom", "age"), null, null);
        results.forEach(row -> {
            assertNotNull(row.get("nom"));
            assertNotNull(row.get("age"));
        });
    }
}
