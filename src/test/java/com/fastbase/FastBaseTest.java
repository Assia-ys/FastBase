package com.fastbase;

import com.fastbase.model.Column;
import com.fastbase.model.enums.ColumnType;
import com.fastbase.service.DataLoaderService;
import com.fastbase.service.QueryService;
import com.fastbase.service.TableService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FastBaseTest {

    @Autowired TableService     tableService;
    @Autowired DataLoaderService dataLoaderService;
    @Autowired QueryService     queryService;

    // Chemin vers le CSV — à adapter selon ton système
    private static final String CSV_PATH = "src/test/ressources/users.csv";
    private static final String TABLE    = "users";

    // -------------------------------------------------------------------------
    // 1. Création de la table
    // -------------------------------------------------------------------------


    @Test
    @Order(1)
    void creerTable() {
        // Supprime si déjà existante (re-run des tests)
        tableService.deleteTable(TABLE);

        tableService.createTable(TABLE, List.of(
            new Column("id",      ColumnType.INTEGER),
            new Column("nom",     ColumnType.STRING),
            new Column("age",     ColumnType.INTEGER),
            new Column("ville",   ColumnType.STRING),
            new Column("salaire", ColumnType.INTEGER)
        ));

        assertTrue(tableService.tableExists(TABLE));
        assertEquals(5, tableService.getTable(TABLE).getColumns().size());
    }

    // -------------------------------------------------------------------------
    // 2. Chargement CSV
    // -------------------------------------------------------------------------

    @Test
    @Order(2)
    void chargerCsv() throws IOException {
        int rows = dataLoaderService.loadCsvData(TABLE, CSV_PATH);
        assertEquals(10, rows);
        assertEquals(10, tableService.getTable(TABLE).getRowCount());
    }

    // -------------------------------------------------------------------------
    // 3. SELECT *
    // -------------------------------------------------------------------------

    @Test
    @Order(3)
    void selectTout() {
        List<Map<String, Object>> results = queryService.execute(TABLE, List.of("*"), null, null);
        assertEquals(10, results.size());
        // Vérifie que les colonnes sont présentes
        assertTrue(results.get(0).containsKey("nom"));
        assertTrue(results.get(0).containsKey("age"));
    }

    // -------------------------------------------------------------------------
    // 4. SELECT avec projection
    // -------------------------------------------------------------------------

    @Test
    @Order(4)
    void selectProjection() {
        List<Map<String, Object>> results = queryService.execute(TABLE, List.of("nom", "ville"), null, null);
        assertEquals(10, results.size());
        // Seulement nom et ville
        assertTrue(results.get(0).containsKey("nom"));
        assertTrue(results.get(0).containsKey("ville"));
        assertFalse(results.get(0).containsKey("age"));
    }

    // -------------------------------------------------------------------------
    // 5. WHERE age > 18
    // -------------------------------------------------------------------------

    @Test
    @Order(5)
    void whereAgeSup18() {
        List<Map<String, Object>> results = queryService.execute(TABLE, List.of("*"), "age > 18", null);
        // Charlie (17) et Jack (16) exclus → 8 résultats
        assertEquals(8, results.size());
        results.forEach(row ->
            assertTrue((Integer) row.get("age") > 18)
        );
    }

    // -------------------------------------------------------------------------
    // 6. WHERE ville = Paris
    // -------------------------------------------------------------------------

    @Test
    @Order(6)
    void whereVilleParis() {
        List<Map<String, Object>> results = queryService.execute(TABLE, List.of("nom", "ville"), "ville = Paris", null);
        assertEquals(4, results.size());
        results.forEach(row ->
            assertEquals("Paris", row.get("ville"))
        );
    }

    // -------------------------------------------------------------------------
    // 7. WHERE avec LIKE
    // -------------------------------------------------------------------------

    @Test
    @Order(7)
    void whereLike() {
        List<Map<String, Object>> results = queryService.execute(TABLE, List.of("nom"), "nom LIKE %e%", null);
        // Alice, Charlie, Grace, Eve, Iris → contient 'e'
        assertFalse(results.isEmpty());
    }

    // -------------------------------------------------------------------------
    // 8. GROUP BY ville + COUNT
    // -------------------------------------------------------------------------

    @Test
    @Order(8)
    void groupByVille() {
        List<Map<String, Object>> results = queryService.execute(
            TABLE,
            List.of("COUNT(id)"),
            null,
            List.of("ville")
        );
        // 3 villes : Paris, Lyon, Marseille
        assertEquals(3, results.size());
    }

    // -------------------------------------------------------------------------
    // 9. GROUP BY ville + SUM salaire
    // -------------------------------------------------------------------------

    @Test
    @Order(9)
    void groupBySumSalaire() {
        List<Map<String, Object>> results = queryService.execute(
            TABLE,
            List.of("SUM(salaire)"),
            null,
            List.of("ville")
        );
        assertEquals(3, results.size());
        results.forEach(row -> assertNotNull(row.get("SUM(salaire)")));
    }

    // -------------------------------------------------------------------------
    // 10. Table inexistante → exception
    // -------------------------------------------------------------------------

    @Test
    @Order(10)
    void tableInexistante() {
        assertThrows(Exception.class, () ->
            queryService.execute("inexistante", List.of("*"), null, null)
        );
    }

    // -------------------------------------------------------------------------
    // 11. Table déjà existante → exception
    // -------------------------------------------------------------------------

    @Test
    @Order(11)
    void tableDejaExistante() {
        assertThrows(Exception.class, () ->
            tableService.createTable(TABLE, List.of(new Column("id", ColumnType.INTEGER)))
        );
    }

    // -------------------------------------------------------------------------
    // 12. Suppression table
    // -------------------------------------------------------------------------

    @Test
    @Order(12)
    void supprimerTable() {
        tableService.deleteTable(TABLE);
        assertFalse(tableService.tableExists(TABLE));
    }
}
