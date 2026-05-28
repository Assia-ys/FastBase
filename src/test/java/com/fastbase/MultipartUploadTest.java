package com.fastbase;

import com.fastbase.model.Column;
import com.fastbase.model.enums.ColumnType;
import com.fastbase.service.TableService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class MultipartUploadTest {

    private static final String TABLE = "users_upload_test";

    @Autowired private MockMvc mockMvc;
    @Autowired private TableService tableService;

    @BeforeEach
    void setup() {
        tableService.deleteTable(TABLE);
        tableService.createTable(TABLE, List.of(
                new Column("id",      ColumnType.INTEGER),
                new Column("nom",     ColumnType.STRING),
                new Column("age",     ColumnType.INTEGER),
                new Column("ville",   ColumnType.STRING),
                new Column("salaire", ColumnType.INTEGER)
        ));
    }

    @Test
    void uploadCsvMultipartChargeLesDonnees() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "users.csv",
                "text/csv",
                Files.readAllBytes(Path.of("src/test/ressources/users.csv"))
        );

        mockMvc.perform(multipart("/api/tables/load")
                        .file(file)
                        .param("tableName", TABLE)
                        .param("format", "CSV"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message", is("Données chargées")))
                .andExpect(jsonPath("$.rowsLoaded", is(10)));
    }
}
