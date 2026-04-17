package com.fastbase.service;

import com.fastbase.exception.TableNotFoundException;
import com.fastbase.model.Column;
import com.fastbase.model.Row;
import com.fastbase.model.Table;
import com.fastbase.model.enums.ColumnType;
import com.fastbase.storage.DataStorage;
import jakarta.validation.constraints.NotBlank;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@Service
@Validated
public class DataLoaderService {

    // Nombre de lignes accumulées avant insertion en bloc dans la table
    private static final int BATCH_SIZE  = 10_000;

    // Taille du buffer de lecture : 1 Mo pour réduire les accès disque
    private static final int BUFFER_SIZE = 1024 * 1024;

    private final DataStorage dataStorage;

    public DataLoaderService(DataStorage dataStorage) {
        this.dataStorage = dataStorage;
    }

    /**
     * Charge un fichier CSV dans une table existante.
     *
     * Étapes :
     * 1. Récupère la table cible
     * 2. Lit l'en-tête CSV et construit le mapping colonnes CSV → colonnes table
     * 3. Lit les lignes par batch de BATCH_SIZE et les insère en bloc
     * 4. Retourne le nombre total de lignes chargées
     */
    public int loadCsvData(
            @NotBlank(message = "Le nom de la table ne peut pas être vide") String tableName,
            @NotBlank(message = "Le chemin du fichier ne peut pas être vide") String filePath)
            throws IOException {

        // Étape 1 : récupération de la table — lance TableNotFoundException si absente
        Table table = dataStorage.getTable(tableName)
                .orElseThrow(() -> new TableNotFoundException(tableName));

        List<Column> columns = table.getColumns();
        int totalRows = 0;

        // BufferedReader avec buffer 1 Mo : réduit le nombre d'accès disque
        try (BufferedReader reader = new BufferedReader(new FileReader(filePath), BUFFER_SIZE)) {

            // Étape 2 : lecture de l'en-tête (première ligne du CSV)
            String headerLine = reader.readLine();
            if (headerLine == null) throw new IOException("Le fichier CSV est vide");

            String[] csvHeaders    = parseCsvLine(headerLine);

            // Mapping : pour chaque colonne CSV, quel est son index dans la table ?
            // Permet d'ignorer les colonnes CSV absentes du schéma de la table
            int[]    columnMapping = buildColumnMapping(csvHeaders, columns);

            // Étape 3 : lecture et insertion par batch
            List<Row> batch = new ArrayList<>(BATCH_SIZE);
            String line;

            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue; // ignore les lignes vides

                String[] rawValues = parseCsvLine(line);

                // Création d'une Row avec autant de cases que de colonnes dans la table
                Row row = new Row(columns.size());

                // Remplissage de la Row en utilisant le mapping CSV → table
                for (int csvIdx = 0; csvIdx < csvHeaders.length; csvIdx++) {
                    int colIdx = columnMapping[csvIdx];
                    if (colIdx < 0) continue; // colonne CSV inconnue du schéma → ignorée
                    String raw = csvIdx < rawValues.length ? rawValues[csvIdx] : null;
                    row.setValue(colIdx, parseValue(raw, columns.get(colIdx).getType()));
                }

                batch.add(row);

                // Insertion en bloc quand le batch est plein
                // addAll() est bien plus rapide que 10 000 appels individuels à addRow()
                if (batch.size() >= BATCH_SIZE) {
                    table.addRows(batch);
                    totalRows += batch.size();
                    batch = new ArrayList<>(BATCH_SIZE); // réinitialise le batch
                }
            }

            // Étape 4 : flush du dernier batch (lignes restantes < BATCH_SIZE)
            if (!batch.isEmpty()) {
                table.addRows(batch);
                totalRows += batch.size();
            }
        }

        return totalRows;
    }

    public int loadParquetData(
            @NotBlank(message = "Le nom de la table ne peut pas être vide") String tableName,
            @NotBlank(message = "Le chemin du fichier ne peut pas être vide") String filePath) {
        throw new UnsupportedOperationException("Le chargement Parquet n'est pas encore implémenté");
    }

    /**
     * Convertit une valeur brute String en objet Java typé selon le type de la colonne.
     * Retourne null si la valeur est vide ou si le parsing échoue.
     */
    private Object parseValue(String value, ColumnType type) {
        if (value == null || value.isBlank()) return null;
        return switch (type) {
            case INTEGER -> { try { yield Integer.parseInt(value.trim());   } catch (NumberFormatException e) { yield null; } }
            case LONG    -> { try { yield Long.parseLong(value.trim());     } catch (NumberFormatException e) { yield null; } }
            case DOUBLE  -> { try { yield Double.parseDouble(value.trim()); } catch (NumberFormatException e) { yield null; } }
            case BOOLEAN -> Boolean.parseBoolean(value.trim());
            default      -> value.trim(); // STRING, VARCHAR, DATE → conservé tel quel
        };
    }

    /**
     * Construit le tableau de correspondance : index CSV → index colonne table.
     * Retourne -1 pour les colonnes CSV absentes du schéma de la table.
     *
     * Exemple :
     *   CSV headers  : [id, nom, age, inconnu]
     *   Table columns: [id, nom, age]
     *   Résultat     : [0, 1, 2, -1]  ← "inconnu" ignoré
     */
    private int[] buildColumnMapping(String[] csvHeaders, List<Column> columns) {
        int[] mapping = new int[csvHeaders.length];
        for (int i = 0; i < csvHeaders.length; i++) {
            mapping[i] = -1; // par défaut
            String header = csvHeaders[i].trim();
            for (int j = 0; j < columns.size(); j++) {
                if (columns.get(j).getName().equalsIgnoreCase(header)) {
                    mapping[i] = j;
                    break;
                }
            }
        }
        return mapping;
    }

    /**
     * Parse une ligne CSV en respectant les guillemets.
     * Gère les cas suivants :
     *   - champs avec virgules : "Paris, France"
     *   - guillemets doublés   : "il a dit ""bonjour"""
     */
    private String[] parseCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                // Guillemet doublé à l'intérieur d'un champ → un seul guillemet
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"'); i++;
                } else {
                    inQuotes = !inQuotes; // bascule mode guillemets on/off
                }
            } else if (c == ',' && !inQuotes) {
                // Virgule hors guillemets → séparateur de champ
                fields.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        fields.add(current.toString().trim()); // dernier champ
        return fields.toArray(new String[0]);
    }
}