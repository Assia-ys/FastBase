# FastBase — Guide de compréhension du code

Moteur de données haute performance en Java (Spring Boot), 100% in-memory, sans base de données externe.
Projet pédagogique — Licence Informatique, Sorbonne Université.

---

## Architecture globale — comment les classes s'articulent

```
Client (Postman / curl / BenchmarkDemo)
         │
         ▼ HTTP
┌─────────────────────────────────┐
│  TableController                │  ← Créer/supprimer tables, charger fichiers
│  QueryController                │  ← Exécuter requêtes SELECT / GROUP BY / WHERE
│  BenchmarkController            │  ← Lancer les benchmarks via upload Parquet
└────────────┬────────────────────┘
             │ appelle
┌────────────▼────────────────────┐
│  TableService                   │  ← Logique métier tables (créer, supprimer)
│  QueryService                   │  ← Moteur de requêtes (SELECT, WHERE, GROUP BY)
│  DataLoaderService              │  ← Chargement CSV et Parquet
└────────────┬────────────────────┘
             │ lit/écrit
┌────────────▼────────────────────┐
│  InMemoryStorage                │  ← ConcurrentHashMap<String, Table>
│  Table                          │  ← Stockage colonnaire en mémoire
└─────────────────────────────────┘
```

**Flux de base :**
1. Client crée une table → `TableController` → `TableService` → `InMemoryStorage`
2. Client charge un fichier → `TableController` → `DataLoaderService` → `Table`
3. Client exécute une requête → `QueryController` → `QueryService` → `Table`

---

## 1. Table.java — Le stockage en mémoire

**Rôle** : stocker toutes les données d'une table en mémoire, de façon optimisée.

### Comment les données sont organisées

La `Table` ne stocke **pas** les données dans une liste de lignes (`List<Row>`).
Elle les stocke **par colonnes**, dans des tableaux typés :

```
Table "taxi" avec 3 colonnes :
  VendorID (INTEGER)   → intData[0]   = int[][][]
  fare_amount (DOUBLE) → floatData[0] = float[][][]
  store_fwd (STRING)   → stringData[0]= String[][][]
```

Chaque array est découpé en **chunks de 262 144 lignes** :

```
floatData[0]        → tableau de chunks pour la colonne "fare_amount"
  [chunk 0]         → float[262144]  = lignes 0 à 262 143
  [chunk 1]         → float[262144]  = lignes 262 144 à 524 287
  [chunk 2]         → float[262144]  = lignes 524 288 à 786 431
  ...
```

**Accès à la ligne 300 000, colonne "fare_amount" :**
```java
int chunk = 300_000 >> 18;        // = 1  (chunk numéro 1)
int pos   = 300_000 & 0x3FFFF;   // = 37 856 (position dans le chunk)
float val = floatData[0][1][37856];
```

### Pourquoi 4 types de tableaux séparés ?

```java
private int[][][]    intData;    // INTEGER, BOOLEAN → 4 octets
private long[][][]   longData;   // LONG (timestamps) → 8 octets
private float[][][]  floatData;  // DOUBLE → stocké en float (4 octets, −42% mémoire)
private String[][][] stringData; // STRING
```

**Pour 50M lignes, NYC Taxi (19 colonnes) :**
- Avec `double` pour tout : 7 600 MB
- Avec types adaptés : 4 400 MB → **−42%**

### Comment savoir quel array utiliser pour une colonne ?

```java
// colIntIdx[i]  = slot dans intData   pour la colonne i, ou -1 si pas entière
// colFltIdx[i]  = slot dans floatData pour la colonne i, ou -1 si pas décimale
// colLongIdx[i] = slot dans longData  pour la colonne i, ou -1 si pas long
// colStrIdx[i]  = slot dans stringData pour la colonne i, ou -1 si pas string

// Exemple pour Table "taxi" avec colonnes : VendorID(INT), fare_amount(DBL), store_fwd(STR)
// colIntIdx  = [0, -1, -1]  → VendorID est au slot 0 de intData
// colFltIdx  = [-1, 0, -1]  → fare_amount est au slot 0 de floatData
// colStrIdx  = [-1, -1, 0]  → store_fwd est au slot 0 de stringData
```

### Les méthodes importantes

```java
// Lire une valeur (retourne Object boxé : Integer, Double, String...)
table.getValue(rowIdx, colIdx)

// Lire une valeur numérique SANS boxing (retourne double primitif)
// Utilisé par GROUP BY et WHERE pour éviter les allocations
table.getNumericRaw(colIdx, rowIdx)

// Écrire directement dans le bon array (utilisé par DataLoaderService)
table.writeFloat(fltSlot, rowIdx, value)
table.writeInt(intSlot, rowIdx, value)

// Trouver l'index d'une colonne par son nom en O(1) via HashMap
table.getColumnIndex("fare_amount")  // → 1

// Allouer N lignes d'un coup (synchronized)
int startRow = table.allocateBatch(1_000_000)
// → réserve les lignes startRow à startRow+999999, retourne startRow
```

---

## 2. DataLoaderService.java — Charger les données

**Rôle** : lire un fichier CSV ou Parquet et écrire les valeurs dans une `Table`.

### Chargement CSV

```java
// Exemple : charger "yellow_tripdata.csv" dans la table "taxi"
dataLoaderService.loadCsvData("taxi", "/chemin/vers/fichier.csv");
```

**Ce que ça fait ligne par ligne :**
```
1. Lire l'en-tête CSV → construire le mapping "nom colonne CSV → index colonne Table"
2. Pour chaque ligne :
   - Découper les champs (gère les guillemets RFC 4180)
   - Convertir chaque valeur selon le type de la colonne (Integer.parseInt, etc.)
   - Écrire directement dans Table via setColumnValue()
3. Insertion par batch de 10 000 lignes (évite 4M appels ArrayList.add())
```

**Buffer de 1 Mo** pour réduire les appels système de 64 000 à ~500 (÷125).

### Chargement Parquet (optimisé)

Le format Parquet stocke les données **par colonnes** dans le fichier.
On lit directement les valeurs typées sans créer d'objet intermédiaire.

```java
// Avant l'optimisation (GroupRecordConverter) :
Group g = recordReader.read();  // crée un objet SimpleGroup → 70M allocations pour 70M lignes

// Après l'optimisation (ColumnReader) :
ColumnReader cr = cs.getColumnReader(colDesc);
table.writeFloat(fltSlot, startRow + r, (float) cr.getDouble());  // double primitif, 0 objet créé
```

**Pipeline parallèle (P16) :**
```
Main thread (I/O) :    [lire group0] ──────── [lire group1] ──────── [lire group2]
Worker thread 1 :               [décoder group0]
Worker thread 2 :                               [décoder group1]
Worker thread 3 :                                               [décoder group2]

→ I/O et décodage se chevauchent, pas séquentiels
```

**Sécurité thread** :
- `allocateBatch()` est `synchronized` → chaque worker écrit dans sa propre plage de lignes
- Chaque worker crée son propre `ColumnReadStoreImpl` → aucun état partagé
- Sémaphore limite à 6 row groups en mémoire simultanément (évite l'OOM)

---

## 3. QueryService.java — Exécuter les requêtes

**Rôle** : implémenter SELECT, WHERE, GROUP BY, ORDER BY, LIMIT.

### La méthode execute() — le chef d'orchestre

```java
public List<Map<String, Object>> execute(
    String tableName,
    List<String> selectCols,    // ex: ["fare_amount", "COUNT(trip_distance)"]
    String whereCondition,      // ex: "fare_amount > 10"
    List<String> groupByCols,   // ex: ["VendorID"]
    String orderBy,             // ex: "SUM(total_amount)"
    String orderDir,            // "ASC" ou "DESC"
    Integer limit               // ex: 100
)
```

**Les 3 chemins d'exécution :**

```
Si groupByCols non vide  → applyGroupBy()     [GROUP BY avec filtre inline]
Si orderBy non vide      → sortRows() ou topNRowsDirect()  [ORDER BY / LIMIT]
Sinon                    → filterAndProject()  [SELECT avec WHERE simple]
```

### filterAndProject() — SELECT sans GROUP BY

```java
// Parallélisation automatique au-delà de 100k lignes
if (n > 100_000) {
    return IntStream.range(0, n).parallel()   // 12 threads
        .filter(r -> cond == null || cond.matches(r, table))
        .mapToObj(r -> buildMap(r, colIndices, names, table))
        .collect(Collectors.toList());
}
// Séquentiel pour les petits volumes (surcoût fork-join > gain)
```

### applyGroupBy() — GROUP BY avec filtre inline (P12 + P14)

C'est la méthode la plus complexe. Elle gère tout : filter + group + aggregate.

**Clé numérique (chemin rapide)** : pour les colonnes INTEGER/DOUBLE comme `payment_type`, `VendorID`

```java
// Zéro allocation String — la clé est un Long (bits du double)
long key = Double.doubleToRawLongBits(table.getNumericRaw(groupIdx[0], r));
//         ↑ transforme le double en Long sans perte, utilisé comme clé HashMap

// Chaque thread a son propre HashMap → 0 contention
Map<Long, GroupAcc>[] locals = new HashMap[nCpu];

// Filtre inline dans la boucle (P14) — évite le tableau int[] intermédiaire
for (int r = from; r < to; r++) {
    if (cond != null && !cond.matches(r, table)) continue;  // filtre ici
    long key = Double.doubleToRawLongBits(table.getNumericRaw(groupIdx[0], r));
    // accumuler dans locals[t]...
}

// Merge final — trivial pour peu de groupes (payment_type = 5 valeurs)
for (Map.Entry<Long, GroupAcc> e : locals[t].entrySet()) {
    dst.count += src.count;
    dst.sums[j] += src.sums[j];
    // merge MIN, MAX, hasVal...
}
```

**L'accumulateur GroupAcc** : stocke les agrégats pour un groupe

```java
class GroupAcc {
    long   count;       // COUNT(*)
    double[] sums;      // SUM par agrégat
    double[] mins;      // MIN par agrégat (initialisé à Double.MAX_VALUE)
    double[] maxs;      // MAX par agrégat (initialisé à -Double.MAX_VALUE)
    boolean[] hasVal;   // true si au moins une valeur non-null reçue
}
// AVG = sums[j] / count  (calculé à la fin, pas accumulé)
```

### Le système WHERE — Condition

```java
// Une condition peut être simple ou composée :
// "fare_amount > 10"                     → SimpleCondition ou NumericCondition
// "fare_amount > 10 AND VendorID = 1"   → AndCondition([...])
// "fare_amount > 10 OR VendorID = 2"    → OrCondition([...])

// Parse la string en arbre de conditions
Condition cond = Condition.parse("fare_amount > 10 AND VendorID = 1", table);
// → AndCondition([NumericCondition(fare_amount, GT, 10.0),
//                 NumericCondition(VendorID, EQ, 1.0)])

// Évaluation sur la ligne 42 :
cond.matches(42, table);
// → NumericCondition: table.getNumericRaw(fare_idx, 42) > 10.0 (double primitif, 0 boxing)
//    && NumericCondition: table.getNumericRaw(vendor_idx, 42) == 1.0
```

**NumericCondition (P9)** : pour les colonnes numériques, utilise `getNumericRaw()` au lieu de
`getValue()`. Évite la création d'objets `Integer`/`Float` → 70M allocations en moins sur 70M lignes.

### topNRowsDirect() — ORDER BY + LIMIT sans trier tout

```java
// LIMIT 10, ORDER BY fare_amount DESC sur 70M lignes
// Min-heap de taille 10 : la racine est le "pire" des top-10

PriorityQueue<Integer> heap = new PriorityQueue<>(11, heapComp);
for (int r = 0; r < n; r++) {
    heap.offer(r);             // ajouter la ligne r
    if (heap.size() > limit)   // si heap trop grand
        heap.poll();           // expulser le "pire" (le plus petit pour DESC)
}
// Résultat : les 10 lignes avec les plus grands fare_amount
// Sans avoir trié les 70 000 000 autres lignes
```

---

## 4. Les contrôleurs REST — L'API exposée

### TableController.java — Gérer les tables

| Méthode | URL | Action |
|---------|-----|--------|
| POST | `/api/tables` | Créer une table avec son schéma |
| GET | `/api/tables` | Lister toutes les tables |
| GET | `/api/tables/{name}` | Détails d'une table |
| DELETE | `/api/tables/{name}` | Supprimer une table |
| POST | `/api/tables/load` | Charger un fichier CSV ou Parquet (multipart) |

**Exemple de création :**
```json
POST /api/tables
{
  "tableName": "taxi",
  "columns": [
    {"name": "VendorID",    "type": "INTEGER"},
    {"name": "fare_amount", "type": "DOUBLE"},
    {"name": "total_amount","type": "DOUBLE"}
  ]
}
```

**Chargement de fichier (multipart — fonctionne depuis n'importe quelle machine) :**
```bash
curl -X POST http://localhost:8080/api/tables/load \
  -F "tableName=taxi" \
  -F "format=PARQUET" \
  -F "file=@yellow_tripdata.parquet"
```

### QueryController.java — Exécuter une requête

Un seul endpoint :

```json
POST /api/query/select
{
  "tableName":      "taxi",
  "selectColumns":  ["VendorID", "COUNT(trip_distance)", "SUM(total_amount)"],
  "whereCondition": "fare_amount > 10",
  "groupByColumns": ["VendorID"],
  "orderBy":        "SUM(total_amount)",
  "orderDir":       "DESC",
  "limit":          100
}
```

**Réponse :**
```json
{
  "rowCount":    6,
  "executionMs": 420,
  "data": [
    {"VendorID": 2, "COUNT(trip_distance)": 31200000, "SUM(total_amount)": 450000000.0},
    {"VendorID": 1, "COUNT(trip_distance)": 26000000, "SUM(total_amount)": 380000000.0}
  ]
}
```

### InMemoryStorage.java — Le registre des tables

```java
// Simplement un ConcurrentHashMap thread-safe
private final ConcurrentHashMap<String, Table> tables = new ConcurrentHashMap<>();

// Opérations :
tables.put(name, table)         // créer
tables.get(name)                // lire
tables.remove(name)             // supprimer
tables.containsKey(name)        // vérifier existence
```

---

## 5. BenchmarkDemo.java — La démonstration

**Rôle** : charger le dataset NYC Taxi, mesurer les temps de LOAD et des 4 requêtes à
chaque palier (4M, 10M, 20M, 30M, 40M, 50M, 70M lignes), exporter les résultats en CSV.

**Les 4 requêtes benchmarkées :**

```
R1 — GROUP BY payment_type (6 groupes)
     + COUNT, SUM, AVG, MIN, MAX + ORDER BY SUM DESC
     → Mesure l'agrégation multi-fonctions + tri sur résultats

R2 — WHERE passenger_count > 0 AND trip_distance > 0
     + GROUP BY passenger_count (11 groupes)
     → Mesure le filtre composé AND + agrégation

R3 — WHERE tip_amount > 0
     + GROUP BY DOLocationID (261 groupes — haute cardinalité)
     → Mesure GROUP BY sur colonne à forte cardinalité

R4 — GROUP BY payment_type (6 groupes)
     + SUM(total_amount) uniquement
     → Mesure la version simple de R1 pour comparaison
```

**Lancer la démo** (depuis IntelliJ, bouton Run sur BenchmarkDemo) :
```
→ Télécharge automatiquement le fichier Parquet si absent (via HTTPS)
→ Affiche les résultats dans la console
→ Exporte target/demo/benchmark.csv
→ Génère target/demo/requete1_resultats.csv ... requete4_resultats.csv
```

---

## Réponses aux questions fréquentes du prof

### "Pourquoi pas de base de données ?"
Le sujet l'interdit explicitement. On a tout implémenté à la main :
le stockage (arrays colonnaires), le parsing des requêtes (Condition.parse()),
et les algorithmes d'agrégation (GroupAcc accumulator).

### "Comment les données sont stockées ?"
Dans des arrays Java typés, découpés en chunks de 262 144 lignes.
Chaque type de colonne a son propre array : `int[][][]` pour INTEGER,
`float[][][]` pour DOUBLE, `long[][][]` pour LONG, `String[][][]` pour STRING.
L'accès se fait par `floatData[colSlot][rowIdx >> 18][rowIdx & 0x3FFFF]`.

### "Pourquoi float et pas double ?"
`float` = 4 octets, `double` = 8 octets. Pour 12 colonnes DOUBLE × 50M lignes :
économie de 2 400 MB. La précision float (~7 chiffres) est suffisante pour
des montants comme `fare_amount = 123.45`.

### "Pourquoi les chunks de 262 144 ?"
2^18 = 262 144 lignes × 4 octets = 1 MB par chunk.
G1GC traite les objets > 4 MB comme "humongous" → alloués en Old Gen → Full GC fréquents.
Les chunks de 1 MB restent en Young Gen et sont collectés normalement.

### "Comment fonctionne le GROUP BY parallèle ?"
12 threads, chacun traite 1/12 des lignes, accumule dans son propre `HashMap<Long, GroupAcc>`.
Zéro contention pendant l'accumulation. Merge final trivial (quelques valeurs distinctes).
Le filtre WHERE est appliqué inline dans la même boucle pour éviter un tableau intermédiaire.

### "C'est quoi ColumnReader ?"
Au lieu de créer un objet Java (`Group`) par ligne lue depuis Parquet,
on utilise `ColumnReader` qui retourne directement des primitives (`cr.getDouble()`, `cr.getInteger()`).
Pour 70M lignes, ça élimine 70M allocations d'objets → beaucoup moins de GC.

### "Qu'est-ce que le semaphore dans le chargement Parquet ?"
Il limite à 6 le nombre de row groups Parquet décodés simultanément.
Chaque row group ~1M lignes × 19 colonnes = ~100 MB de données temporaires.
Sans limite, 70 row groups en parallèle = 7 GB de mémoire temporaire → OOM.

### "Comment le WHERE est parsé ?"
La string `"fare_amount > 10 AND VendorID = 1"` est découpée en arbre :
- Split sur `OR` → `OrCondition`
- Split sur `AND` → `AndCondition`
- Condition simple → `NumericCondition` (si colonne numérique) ou `SimpleCondition`
Une fois parsé, l'arbre est évalué sur chaque ligne par `cond.matches(rowIdx, table)`.

---

## Structure des fichiers

```
src/main/java/com/fastbase/
  controller/
    TableController.java    ← API tables (CRUD + chargement)
    QueryController.java    ← API requêtes (SELECT)
    BenchmarkController.java← API benchmark (upload + mesure)
  service/
    TableService.java       ← Logique création/suppression tables
    QueryService.java       ← Moteur de requêtes (SELECT/WHERE/GROUP BY)
    DataLoaderService.java  ← Chargement CSV et Parquet
  model/
    Table.java              ← Stockage colonnaire (le cœur du projet)
    Column.java             ← Nom + type d'une colonne
    Row.java                ← Object[] (rétro-compatibilité tests)
    enums/ColumnType.java   ← INTEGER, LONG, DOUBLE, BOOLEAN, STRING
  storage/
    DataStorage.java        ← Interface
    InMemoryStorage.java    ← ConcurrentHashMap<String, Table>
  dto/
    CreateTableRequestDTO.java  ← Body POST /api/tables
    LoadDataRequestDTO.java     ← Body POST /api/tables/load
    QueryRequestDTO.java        ← Body POST /api/query/select
  demo/
    BenchmarkDemo.java      ← Point d'entrée démo soutenance

src/test/java/com/fastbase/
  WhereTest.java            ← Tests unitaires WHERE
  GroupByTest.java          ← Tests unitaires GROUP BY
  SelectTest.java           ← Tests unitaires SELECT
  OrderByTest.java          ← Tests unitaires ORDER BY + LIMIT
  RealDataBenchmarkTest.java← Benchmark sur NYC Taxi réel
  BenchmarkServiceTest.java ← Benchmark sur données synthétiques
```

---

## Commandes utiles

```bash
# Lancer l'application
mvn spring-boot:run

# Lancer les tests unitaires
mvn test -Dtest=WhereTest,GroupByTest,SelectTest

# Lancer le benchmark réel (NYC Taxi)
mvn test -Dtest=RealDataBenchmarkTest

# Générer les graphiques après benchmark
python target/demo/benchmark.csv  # ou
python target/plot_benchmark.py
```
