package com.fastbase;

import com.fastbase.model.Column;
import com.fastbase.model.enums.ColumnType;
import com.fastbase.service.DataLoaderService;
import com.fastbase.service.QueryService;
import com.fastbase.service.TableService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.util.List;

@SpringBootTest
public abstract class AbstractFastBaseTest {

    @Autowired protected TableService      tableService;
    @Autowired protected DataLoaderService dataLoaderService;
    @Autowired protected QueryService      queryService;

    protected static final String CSV_PATH = "src/test/ressources/users.csv";
    protected static final String TABLE    = "users_test";

    @BeforeEach
    void setup() throws IOException {
        tableService.deleteTable(TABLE);
        tableService.createTable(TABLE, List.of(
            new Column("id",      ColumnType.INTEGER),
            new Column("nom",     ColumnType.STRING),
            new Column("age",     ColumnType.INTEGER),
            new Column("ville",   ColumnType.STRING),
            new Column("salaire", ColumnType.INTEGER)
        ));
        dataLoaderService.loadCsvData(TABLE, CSV_PATH);
    }

    @AfterAll
    static void teardown(@Autowired TableService tableService) {
        tableService.deleteTable(TABLE);
    }
}
