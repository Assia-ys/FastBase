package com.fastbase;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WhereTest extends AbstractFastBaseTest {

    @Test
    void whereAgeSuperieur18() {
        List<Map<String, Object>> results = queryService.execute(TABLE, List.of("*"), "age > 18", null);
        assertEquals(8, results.size());
        results.forEach(row -> assertTrue((Integer) row.get("age") > 18));
    }

    @Test
    void whereAgeInferieurOuEgal20() {
        List<Map<String, Object>> results = queryService.execute(TABLE, List.of("*"), "age <= 20", null);
        assertFalse(results.isEmpty());
        results.forEach(row -> assertTrue((Integer) row.get("age") <= 20));
    }

    @Test
    void whereAgeEgal25() {
        List<Map<String, Object>> results = queryService.execute(TABLE, List.of("*"), "age = 25", null);
        assertEquals(1, results.size());
        assertEquals(25, results.get(0).get("age"));
    }

    @Test
    void whereAgeDifferent25() {
        List<Map<String, Object>> results = queryService.execute(TABLE, List.of("*"), "age != 25", null);
        assertEquals(9, results.size());
        results.forEach(row -> assertNotEquals(25, row.get("age")));
    }

    @Test
    void whereVilleEgalParis() {
        List<Map<String, Object>> results = queryService.execute(TABLE, List.of("nom", "ville"), "ville = Paris", null);
        assertEquals(4, results.size());
        results.forEach(row -> assertEquals("Paris", row.get("ville")));
    }

    @Test
    void whereVilleEgalLyon() {
        List<Map<String, Object>> results = queryService.execute(TABLE, List.of("nom", "ville"), "ville = Lyon", null);
        assertEquals(3, results.size());
        results.forEach(row -> assertEquals("Lyon", row.get("ville")));
    }

    @Test
    void whereLikeNomContientE() {
        List<Map<String, Object>> results = queryService.execute(TABLE, List.of("nom"), "nom LIKE %e%", null);
        assertFalse(results.isEmpty());
        results.forEach(row ->
            assertTrue(row.get("nom").toString().toLowerCase().contains("e"))
        );
    }

    @Test
    void whereLikeNomCommenceParA() {
        List<Map<String, Object>> results = queryService.execute(TABLE, List.of("nom"), "nom LIKE A%", null);
        assertFalse(results.isEmpty());
        results.forEach(row ->
            assertTrue(row.get("nom").toString().startsWith("A"))
        );
    }

    @Test
    void whereSalaireSuperieur50000() {
        List<Map<String, Object>> results = queryService.execute(TABLE, List.of("*"), "salaire > 50000", null);
        assertFalse(results.isEmpty());
        results.forEach(row -> assertTrue((Integer) row.get("salaire") > 50000));
    }
}
