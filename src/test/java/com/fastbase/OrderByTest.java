package com.fastbase;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class OrderByTest extends AbstractFastBaseTest {

    @Test
    void orderByAgeAsc() {
        List<Map<String, Object>> results = queryService.execute(
                TABLE, List.of("nom", "age"), null, null, "age", "ASC", null);

        assertEquals(10, results.size());
        for (int i = 0; i < results.size() - 1; i++) {
            int current = (Integer) results.get(i).get("age");
            int next    = (Integer) results.get(i + 1).get("age");
            assertTrue(current <= next, "Ordre ASC non respecté : " + current + " > " + next);
        }
    }

    @Test
    void orderByAgeDesc() {
        List<Map<String, Object>> results = queryService.execute(
                TABLE, List.of("nom", "age"), null, null, "age", "DESC", null);

        assertEquals(10, results.size());
        for (int i = 0; i < results.size() - 1; i++) {
            int current = (Integer) results.get(i).get("age");
            int next    = (Integer) results.get(i + 1).get("age");
            assertTrue(current >= next, "Ordre DESC non respecté : " + current + " < " + next);
        }
    }

    @Test
    void orderByNomAsc() {
        List<Map<String, Object>> results = queryService.execute(
                TABLE, List.of("nom"), null, null, "nom", "ASC", null);

        assertEquals(10, results.size());
        for (int i = 0; i < results.size() - 1; i++) {
            String current = results.get(i).get("nom").toString();
            String next    = results.get(i + 1).get("nom").toString();
            assertTrue(current.compareTo(next) <= 0, "Ordre alphabétique ASC non respecté");
        }
    }

    @Test
    void
    orderByAvecLimit() {
        List<Map<String, Object>> results = queryService.execute(
                TABLE, List.of("nom", "salaire"), null, null, "salaire", "DESC", 3);

        assertEquals(3, results.size());
        // Les 3 premiers doivent avoir les salaires les plus élevés
        for (int i = 0; i < results.size() - 1; i++) {
            int current = (Integer) results.get(i).get("salaire");
            int next    = (Integer) results.get(i + 1).get("salaire");
            assertTrue(current >= next, "Top 3 salaires : ordre DESC non respecté");
        }
    }

    @Test
    void orderBySurGroupBy() {
        List<Map<String, Object>> results = queryService.execute(
                TABLE,
                List.of("ville", "COUNT(id)"),
                null,
                List.of("ville"),
                "COUNT(id)", "DESC", null);

        assertEquals(3, results.size());
        // La ville avec le plus de personnes en premier
        for (int i = 0; i < results.size() - 1; i++) {
            long current = (Long) results.get(i).get("COUNT(id)");
            long next    = (Long) results.get(i + 1).get("COUNT(id)");
            assertTrue(current >= next, "ORDER BY sur COUNT DESC non respecté");
        }
    }

    @Test
    void limitSansOrderBy() {
        List<Map<String, Object>> results = queryService.execute(
                TABLE, List.of("*"), null, null, null, null, 5);

        assertEquals(5, results.size());
    }

    @Test
    void orderByColonneInexistante() {
        // Une colonne inexistante → les nulls sont en dernier, pas de crash
        List<Map<String, Object>> results = queryService.execute(
                TABLE, List.of("nom"), null, null, "colonne_inexistante", "ASC", null);

        assertEquals(10, results.size()); // retourne quand même les résultats
    }
}
