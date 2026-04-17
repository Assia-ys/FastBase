package com.fastbase;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DataLoaderTest extends AbstractFastBaseTest {

    @Test
    void chargementCsvRetourne10Lignes() {
        assertEquals(10, tableService.getTable(TABLE).getRowCount());
    }

    @Test
    void colonnesPresentes() {
        var columns = tableService.getTable(TABLE).getColumns();
        assertEquals("id",      columns.get(0).getName());
        assertEquals("nom",     columns.get(1).getName());
        assertEquals("age",     columns.get(2).getName());
        assertEquals("ville",   columns.get(3).getName());
        assertEquals("salaire", columns.get(4).getName());
    }
}
