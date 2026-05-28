# FastBase — Guide de développement

Moteur de données haute performance en Java (Spring Boot), accessible via API REST.
Projet pédagogique Licence Informatique — Sorbonne Université.

## Stack technique
- Java 17, Spring Boot 3.3.5, Maven
- Stockage 100% in-memory (pas de BDD externe, pas d'ORM — interdit par l'énoncé)
- Librairies autorisées : Apache Commons, parquet-hadoop, Lombok

## Structure du projet

```
src/main/java/com/fastbase/
  controller/     → TableController, QueryController (API REST)
  dto/            → CreateTableRequestDTO, LoadDataRequestDTO, QueryRequestDTO
  exception/      → TableNotFoundException, TableAlreadyExistsException, InvalidQueryException
  model/          → Table, Row (Object[]), Column
  model/enums/    → ColumnType, FileFormat
  service/        → TableService, DataLoaderService, QueryService, BenchmarkService
  storage/        → DataStorage (interface), InMemoryStorage (ConcurrentHashMap)

src/test/java/com/fastbase/
  FastBaseTest.java           → tests fonctionnels complets (CSV users.csv)
  WhereTest.java              → tests WHERE
  GroupByTest.java            → tests GROUP BY
  SelectTest.java             → tests SELECT
  BenchmarkServiceTest.java   → benchmarks sur données synthétiques (100k → 4M)
  RealDataBenchmarkTest.java  → benchmarks sur NYC Taxi dataset réel
  AbstractFastBaseTest.java   → setup partagé
```

## Données réelles pour les benchmarks
- Dataset : NYC Yellow Taxi 2016-01
- Chemin attendu : `../data_NYC/yellow_tripdata_2016-01.csv`
- Le fichier doit être placé **un niveau au-dessus** du projet
- Volume minimal requis par l'énoncé : **4 millions de lignes**

## Ce qui est implémenté ✅
- Création / suppression de tables avec schéma typé
- Chargement CSV avec batch 10k lignes et buffer 1 Mo
- SELECT avec projection de colonnes
- WHERE : `=`, `!=`, `<`, `<=`, `>`, `>=`, `LIKE` (simple condition)
- GROUP BY avec agrégats : `COUNT`, `SUM`, `AVG`, `MIN`, `MAX`
- BenchmarkService avec export CSV et script Python pour graphiques
- API REST : `/api/tables`, `/api/query/select`
- Suite de tests JUnit5

---

## Bugs connus à corriger en priorité

### 🔴 BUG 1 — Compile error dans DataLoaderService
**Fichier** : `DataLoaderService.java` lignes 63 et 67
**Problème** : variable `csvHeaders` déclarée deux fois → le projet ne compile pas
**Fix** : supprimer la ligne 67 (doublon exact de la ligne 63)

### 🔴 BUG 2 — RealDataBenchmarkTest ne charge que 5 colonnes sur 19
**Fichier** : `RealDataBenchmarkTest.java` ligne 29-35
**Problème** : le schéma ne déclare que 5 colonnes (`VendorID`, `passenger_count`,
`trip_distance`, `fare_amount`, `total_amount`) alors que le CSV NYC Taxi a 19 colonnes.
De plus, le parsing utilise `parts[c]` par position — si les colonnes du schéma ne sont pas
aux mêmes positions dans le CSV, les valeurs sont fausses.
**Fix** : utiliser `DataLoaderService.loadCsvData()` qui fait le mapping par nom de colonne,
ou étendre le schéma aux 19 colonnes réelles du CSV.

### 🟡 BUG 3 — SELECT * en benchmark : problème mémoire et perf
**Fichier** : `RealDataBenchmarkTest.java`, `BenchmarkServiceTest.java`
**Problème** : `benchmarkSelect(tableName, null, null)` fait un SELECT * qui matérialise
**toutes** les lignes en `List<Map<String, Object>>`. Sur 4M lignes × 19 colonnes, cela
consomme plusieurs Go de RAM et fausse les mesures (GC visible dans les temps).
**Règle** : ne jamais faire SELECT * sur les benchmarks de performance. Toujours projeter
un sous-ensemble de colonnes : ex. `List.of("fare_amount", "total_amount")`.

### 🟡 BUG 4 — Benchmark LOAD ne mesure pas le parsing CSV réel
**Fichier** : `BenchmarkServiceTest.java`, `RealDataBenchmarkTest.java`
**Problème** : `benchmarkLoad(table, rows)` reçoit des lignes déjà construites en mémoire.
Il mesure uniquement `table.addRows()`, pas le parsing du fichier.
**Fix** : créer `benchmarkCsvLoad(tableName, filePath)` dans `BenchmarkService` qui appelle
`DataLoaderService.loadCsvData()` et mesure le temps total parsing + insertion.

---

## Améliorations de performance à implémenter

### PERF 1 — Table.getColumnIndex() : O(n) → O(1)
**Fichier** : `Table.java`
**Problème** : la méthode fait un scan linéaire sur les colonnes. Appelée des millions de
fois lors de GROUP BY / WHERE sur 4M lignes.
**Fix** : ajouter un `HashMap<String, Integer> columnIndex` dans `Table` et le construire
une seule fois dans le constructeur via `rebuildIndex()`.
**Impact estimé** : −30 à −50% sur les requêtes GROUP BY à grand volume.

### PERF 2 — QueryService.projectRows() : indexOf O(n) → O(1)
**Fichier** : `QueryService.java`, méthode `projectRows()`
**Problème** : `all.indexOf(projected.get(i))` parcourt la liste pour chaque colonne projetée.
**Fix** : remplacer par `table.getColumnIndex(colName)` après l'application de PERF 1.

### PERF 3 — WHERE parallèle sur grands volumes (optionnel)
Pour les tables de +1M lignes, le filtrage WHERE peut être parallélisé avec
`table.getRows().parallelStream()`. Ajouter un seuil : parallèle si `rowCount > 500_000`.

---

## Fonctionnalités manquantes à implémenter

### FEAT 1 — ORDER BY / LIMIT (bonus énoncé)
Ajouter dans `QueryRequestDTO` :
```java
private String  orderBy;   // nom de colonne ou expression agrégat
private String  orderDir;  // "ASC" ou "DESC"
private Integer limit;     // max lignes retournées
```
Appliquer après `applyGroupBy()` / `projectRows()` dans `QueryService.execute()`.

### FEAT 2 — WHERE avec AND / OR
**Problème** : actuellement une seule condition.
**Fix** : parser qui split sur ` AND ` puis ` OR ` avant de créer des `WhereCondition`
combinées. Modèle : `CompositeCondition(left, op, right)`.

### FEAT 3 — Chargement Parquet
**Fichier** : `DataLoaderService.java`, méthode `loadParquetData()` actuellement vide.
La dépendance `parquet-hadoop` est déjà dans `pom.xml`.
Implémenter la lecture colonne par colonne avec `ParquetReader`.

### FEAT 4 — Endpoint benchmark REST
Exposer un endpoint `POST /api/benchmark/load` et `POST /api/benchmark/query` qui
utilisent `BenchmarkService` et retournent les métriques en JSON.

---

## Commandes utiles

```bash
# Compiler et tester
mvn clean test

# Lancer l'appli
mvn spring-boot:run

# Lancer uniquement les benchmarks
mvn test -Dtest=BenchmarkServiceTest
mvn test -Dtest=RealDataBenchmarkTest

# Générer les graphiques Python (après benchmark)
python target/plot_benchmark.py
```

## API REST — Rappel des endpoints

```
POST /api/tables                  Créer une table
GET  /api/tables                  Lister les tables
GET  /api/tables/{name}           Détail d'une table
DELETE /api/tables/{name}         Supprimer une table
POST /api/tables/load             Charger un CSV dans une table
POST /api/query/select            Exécuter une requête
```

### Exemple de requête SELECT avec GROUP BY
```json
POST /api/query/select
{
  "tableName": "taxi",
  "selectColumns": ["VendorID", "COUNT(trip_distance)", "SUM(fare_amount)"],
  "whereCondition": "fare_amount > 10",
  "groupByColumns": ["VendorID"]
}
```

## Règles de développement
- Ne jamais utiliser de base de données externe (PostgreSQL, H2, SQLite, etc.)
- Ne jamais utiliser d'ORM (JPA, Hibernate, etc.)
- Ne jamais utiliser de moteur de requêtes externe (Lucene, Elasticsearch, etc.)
- Tout le stockage et le requêtage est implémenté à la main
- Les benchmarks doivent couvrir les paliers : 100k, 500k, 1M, 2M, 4M lignes
- Les résultats benchmarks doivent montrer une évolution avant/après optimisation
