package com.fastbase;

import com.fastbase.model.Column;
import com.fastbase.model.enums.ColumnType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TableSchemaTest extends AbstractFastBaseTest {

    @Test
    void tableExiste() {
        assertTrue(tableService.tableExists(TABLE));
    }

    @Test
    void tableABonnesColonnes() {
        assertEquals(5, tableService.getTable(TABLE).getColumns().size());
    }

    @Test
    void tableDejaExistanteLanceException() {
        assertThrows(Exception.class, () ->
            tableService.createTable(TABLE, List.of(new Column("id", ColumnType.INTEGER)))
        );
    }

    @Test
    void tableInexistanteLanceException() {
        assertThrows(Exception.class, () ->
            queryService.execute("inexistante", List.of("*"), null, null)
        );
    }

    @Test
    void suppressionTable() {
        tableService.deleteTable(TABLE);
        assertFalse(tableService.tableExists(TABLE));
    }
}
