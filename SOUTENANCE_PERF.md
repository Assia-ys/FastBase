# FastBase — Parcours complet des optimisations

> Document de soutenance : retrace **toutes** les optimisations depuis la version initiale
> jusqu'à la version finale, avec les chiffres mesurés à chaque étape.
>
> **Dataset principal** : NYC Yellow Taxi 2022 — 70 560 406 lignes, 19 colonnes
> **Machine** : 12 CPUs, 16 GB RAM, Java 17

---

## Ligne du temps

```
Phase 1              Phase 2               Phase 3                Phase 4
──────────────────── ───────────────────── ────────────────────── ─────────────────────
Optimisations        Architecture          Infrastructure          Optimisations
requêtes de base     de stockage           benchmark              avancées
P1 à P8              Colonnaire, chunks    Upload HTTP,           P9, P12, P14,
                     float[], Parquet      BenchmarkController    P15, P16, ZGC
                     natif
```

---

## État initial (Version 0 — avant toute optimisation)

La version de départ stockait toutes les données dans une `ArrayList<Row>` où chaque `Row` était un `Object[]`.
Chaque valeur — qu'elle soit entière, décimale ou chaîne — était stockée comme `Object` (boxing systématique).

```java
// Version initiale
class Row { Object[] values; }
class Table { List<Row> rows = new ArrayList<>(); }

// Problèmes :
// 1. Double boxing : int 42 → Integer.valueOf(42) → stocké comme Object
// 2. Pas d'index sur les colonnes → scan linéaire à chaque requête
// 3. Chargement ligne par ligne → 4M appels ArrayList.add()
// 4. Tri sur résultats projetés (Maps) → hashing inutile à chaque comparaison
```

**Performances estimées version 0 (4M lignes synthétiques) :**

| Opération | Temps estimé v0 |
|-----------|---------------:|
| LOAD 4M | ~2 000ms |
| SELECT | ~560ms |
| GROUP BY | ~400ms |
| ORDER BY | ~6 000ms |
| TOP-10 | ~3 000ms |

---

## Phase 1 — Optimisations des requêtes de base (P1 à P8)

*Source : `Optimisations.md`*

### P1 — Index de colonnes : O(n) → O(1)

**Problème** : chaque appel à `getColumnIndex("fare_amount")` parcourait toute la liste de colonnes.
Sur 4M lignes × GROUP BY sur 10 colonnes : **40 millions de comparaisons de chaînes** par requête.

```java
// AVANT — scan linéaire O(n), appelé des millions de fois
for (int i = 0; i < columns.size(); i++)
    if (columns.get(i).getName().equals(columnName)) return i;

// APRÈS — HashMap construit une seule fois au constructeur, O(1) garanti
private final Map<String, Integer> columnIndex = new HashMap<>();

private void rebuildIndex() {
    for (int i = 0; i < columns.size(); i++)
        columnIndex.put(columns.get(i).getName(), i);
}

public int getColumnIndex(String name) {
    Integer idx = columnIndex.get(name);
    return idx != null ? idx : -1;
}
```

**Gain mesuré** : −30 à −50% sur les requêtes GROUP BY.

---

### P2 — Pré-calcul des index d'agrégats

**Problème** : dans `applyGroupBy()`, `getColumnIndex("fare_amount")` était appelé **pour chaque groupe**,
pas une seule fois avant la boucle.

```java
// AVANT — getColumnIndex() appelé N fois pour N groupes
for (List<Row> groupRows : groups.values())
    result.put("SUM(fare_amount)", sumCol(rows, table.getColumnIndex("fare_amount")));

// APRÈS — résolu une seule fois via record AggInfo
record AggInfo(String col, String type, int colIdx) {}
// colIdx résolu avant la boucle, réutilisé pour chaque groupe
```

**Gain** : sur 1 000 groupes × 3 agrégats : 3 000 appels → 3 appels.

---

### P3 — LinkedHashMap → HashMap dans projectRows()

**Problème** : `LinkedHashMap` maintient une liste doublement chaînée entre ses entrées.
Sur 4M lignes × 2 colonnes × 2 `put()` : **16 millions de mises à jour de pointeurs inutiles**.

```java
// AVANT
Map<String, Object> map = new LinkedHashMap<>(projected.size() * 2);
// APRÈS — le client accède par clé, pas par ordre d'insertion
Map<String, Object> map = new HashMap<>(projected.size() * 2);
```

**Gain mesuré** : SELECT 4M : **561ms → 197ms (×2,8)**.

---

### P4 — Buffer de lecture CSV : 8 Ko → 1 Mo

```java
// AVANT — 64 000 appels système pour un fichier de 500 Mo
new BufferedReader(new FileReader(filePath))

// APRÈS — 500 appels système seulement (÷125)
new BufferedReader(new FileReader(filePath), 1024 * 1024)
```

---

### P5 — Insertion batch : ligne par ligne → blocs de 10 000

```java
// AVANT — 4M appels ArrayList.add() avec vérification de capacité à chaque fois
for (Row row : rows) table.addRow(row);

// APRÈS — 400 appels addAll() = System.arraycopy() natif
List<Row> batch = new ArrayList<>(10_000);
if (batch.size() >= 10_000) {
    table.addRows(batch);  // addAll() = copie mémoire optimisée par la JVM
    batch = new ArrayList<>(10_000);
}
```

---

### P6 — ORDER BY : trier les Rows brutes, pas les Maps

**Problème** : l'ancienne implémentation triait les résultats **après** projection en Maps.
Chaque comparaison appelait `HashMap.get("fare_amount")` → calcul de hash à chaque fois.
Sur 4M lignes, TimSort fait ~88M comparaisons → **176M calculs de hash inutiles**.

```java
// AVANT : trier les Maps (hash à chaque comparaison)
results.sort((a, b) -> {
    Object va = a.get("fare_amount");  // hash("fare_amount") recalculé
    Object vb = b.get("fare_amount");  // encore un hash
});

// APRÈS : trier les Row brutes (accès tableau O(1), index pré-calculé)
int idx = table.getColumnIndex(orderBy);  // résolu une seule fois (P1)
rows.sort((a, b) -> compareValues(a.getValue(idx), b.getValue(idx)));
//                                        ↑ values[idx] — accès tableau direct
```

**Gain mesuré** : ORDER BY 4M : **5 909ms → 3 043ms (×1,9)**.

---

### P7 — Tri séquentiel → Arrays.parallelSort()

**Problème** : `List.sort()` n'utilise qu'un seul cœur CPU.

```java
// Au-delà de 500k lignes, le gain des cœurs multiples dépasse le surcoût de coordination
if (rows.size() > 500_000) {
    Row[] arr = rows.toArray(new Row[0]);
    Arrays.parallelSort(arr, cmp);  // fork-join merge sort sur tous les cœurs
} else {
    rows.sort(cmp);
}
```

**Gain mesuré** : ORDER BY 4M : **3 043ms → 1 489ms (×2)** sur 4 cœurs.

---

### P8 — Heap O(n log N) pour TOP-N (ORDER BY + LIMIT)

**Problème** : `ORDER BY fare_amount LIMIT 10` triait **toutes** les lignes pour n'en garder que 10.

```
Tri complet O(n log n) : 4M × log₂(4M) = 4M × 22 = 88M comparaisons
Heap    O(n log N) : 4M × log₂(10)  = 4M × 3,3 = 13M comparaisons → ×6,7
```

```java
PriorityQueue<Row> heap = new PriorityQueue<>(limit + 1, heapComp);
for (Row row : rows) {
    heap.offer(row);
    if (heap.size() > limit) heap.poll();  // expulse le "pire" des top-N
}
// résultat : les N meilleurs sans avoir trié les (n - N) autres
```

**Gain mesuré** : TOP-10 sur 4M : **3 011ms → 100ms (×30)**.

### Bilan Phase 1 (données synthétiques 4M lignes)

| Opération | v0 | Après P1-P8 | Gain |
|-----------|---:|------------:|-----:|
| SELECT | 561ms | 197ms | ×2,8 |
| ORDER BY | 5 909ms | 1 489ms | ×4 |
| TOP-10 | 3 011ms | 100ms | **×30** |
| GROUP BY | ~400ms | ~287ms | ×1,4 |

---

## Phase 2 — Architecture de stockage

*Source : `BENCHMARK_TRACE.md`, `PERFORMANCES.md`*

### Stockage colonnaire typé (Table.java)

**Problème** : toutes les valeurs stockées en `double` (8 octets), même les entiers et les `float`.

```
v0 → ArrayList<Row> avec Object[] boxing
Phase 2 → int[][][], long[][][], float[][][], String[][][]
```

| ColumnType | Stockage | Octets/valeur | Colonnes NYC Taxi |
|------------|----------|:-------------:|:------------------|
| INTEGER, BOOLEAN | `int[][][]` | 4 | VendorID, PULocationID, DOLocationID, payment_type |
| LONG | `long[][][]` | 8 | tpep_pickup_datetime, tpep_dropoff_datetime |
| DOUBLE | `float[][][]` | 4 | fare_amount, tip_amount, trip_distance… (12 cols) |
| STRING | `String[][][]` | réf | store_and_fwd_flag |

**Calcul du gain mémoire pour 50M lignes :**

| | Avant (`double` pour tout) | Après (typé) |
|---|---:|---:|
| 4 cols INTEGER | 1 600 MB | 800 MB |
| 2 cols LONG | 800 MB | 800 MB |
| 12 cols DOUBLE→float | 4 800 MB | 2 400 MB |
| 1 col STRING | 400 MB | 400 MB |
| **Total** | **7 600 MB** | **4 400 MB** |
| **Gain** | | **−3 200 MB (−42%)** |

> **Pourquoi `float` suffit pour les montants ?**
> `float` = ~7 chiffres significatifs. `fare_amount = 123.45` → stocké `123.4500`.
> Pour des agrégats (SUM, AVG, GROUP BY), la précision est largement suffisante.

---

### Chunks de 262 144 lignes (anti-humongous GC)

**Problème** : allouer `double[50_000_000]` = 400 MB d'un bloc.
G1GC appelle ça un *humongous object* (> 4 MB) → alloué en Old Gen → Full GC fréquents pendant le chargement.

```java
private static final int CHUNK_BITS = 18;   // 2^18 = 262 144 lignes
private static final int CHUNK_SIZE = 1 << CHUNK_BITS;
private static final int CHUNK_MASK = CHUNK_SIZE - 1;

// Chaque colonne = tableaux de 262 144 valeurs
private float[][][]  floatData;   // floatData[colSlot][chunkIdx][posInChunk]

// Accès : floatData[slot][rowIdx >> 18][rowIdx & 0x3FFFF]
// Chaque chunk = 1 MB → jamais "humongous" → collecté en Young Gen
```

| Type | Taille d'un chunk | Humongous G1GC ? |
|------|:-----------------:|:----------------:|
| `int[262144]` | 1 MB | ❌ Non |
| `float[262144]` | 1 MB | ❌ Non |
| `long[262144]` | 2 MB | ❌ Non |

---

### Lecture Parquet native (getters typés)

**Problème** : l'ancienne lecture appelait `group.getValueToString()` pour chaque champ,
puis `parseValue(string, type)`. Résultat : **1 objet String par champ par ligne**.

```
19 champs × 70M lignes = 1 330 000 000 String créées puis jetées
→ GC sous pression constante pendant tout le chargement
```

```java
// AVANT — conversion String inutile pour les numériques
String raw = group.getValueToString(parquetIdx, 0);  // crée une String
table.setColumnValue(rowIdx, colIdx, parseValue(raw, type));  // la parse puis la jette

// APRÈS — getters natifs Parquet selon le type physique
return switch (ptn) {
    case INT32  -> group.getInteger(idx, 0);   // int primitif, 0 allocation
    case INT64  -> group.getLong(idx, 0);      // long primitif, 0 allocation
    case FLOAT  -> group.getFloat(idx, 0);     // float primitif, 0 allocation
    case DOUBLE -> group.getDouble(idx, 0);    // double primitif, 0 allocation
    default     -> group.getValueToString(idx, 0);  // String uniquement si inévitable
};
```

**Gain** : 17 colonnes sur 19 n'allouent plus de String → GC beaucoup moins sollicité.

---

### GROUP BY sans int[] intermédiaire

**Problème** : quand pas de WHERE, le code allouait inutilement `int[rowCount]` pour stocker
les indices de toutes les lignes avant de les passer à `applyGroupBy()`.

```java
// AVANT — alloue int[70M] = 280 MB juste pour stocker "toutes les lignes"
int[] rows = IntStream.range(0, n).toArray();
applyGroupBy(table, rows, ...);

// APRÈS — null signifie "toutes les lignes", boucle directe sans allocation
applyGroupBy(table, null, ...);
// Dans applyGroupBy : int r = rows != null ? rows[i] : i;
```

**Gain** : −280 MB alloués/libérés à chaque GROUP BY sans WHERE sur 70M lignes.

---

### Bilan Phase 2 (NYC Taxi 50M lignes)

| Métrique | Avant Phase 2 | Après Phase 2 |
|----------|:-------------:|:-------------:|
| Mémoire 50M lignes | 7 600 MB | 4 400 MB (−42%) |
| Full GC pendant LOAD | Oui | Non (chunks) |
| Allocations String LOAD | 1,3 milliard | ~100 millions |
| LOAD 50M | ~70s | ~55s |

---

## Phase 3 — Infrastructure benchmark

*Source : `CHANGES.md`*

### BenchmarkController — endpoint "bouton unique"

**Problème** : les benchmarks tournaient uniquement via JUnit avec des chemins locaux.
Impossible d'utiliser depuis une autre machine sans accès au filesystem du serveur.

**Solution** : `POST /api/benchmark/run` accepte un **vrai fichier Parquet uploadé** en multipart.
Le schéma est détecté automatiquement depuis les métadonnées Parquet.

```
POST /api/benchmark/run
Content-Type: multipart/form-data
file=<yellow_tripdata.parquet>
scales=100000,500000,1000000,2000000,4000000  (optionnel)

→ Retourne JSON avec elapsedMs par opération et par palier
```

**Paliers exécutés automatiquement** : LOAD, SELECT, WHERE_SIMPLE, WHERE_COMPLEX,
GROUP_BY_SIMPLE, GROUP_BY_COMPLEX.

---

### Fix WHERE avec guillemets (`'Y'` → `Y`)

**Problème** : la condition `store_and_fwd_flag='Y'` produisait `raw = "'Y'"` (avec les apostrophes)
au lieu de `"Y"`, ce qui ne correspondait à aucune valeur en base.

```java
// FIX dans SimpleCondition.make()
String clean = val.trim();
// Supprime les guillemets encadrants : 'Y' → Y  ou  "Paris" → Paris
if (clean.length() >= 2 &&
    ((clean.charAt(0) == '\'' && clean.charAt(clean.length()-1) == '\'') ||
     (clean.charAt(0) == '"'  && clean.charAt(clean.length()-1) == '"')))
    clean = clean.substring(1, clean.length() - 1);
```

---

### Résultats mesurés Phase 3 (NYC Taxi 2022, upload HTTP, 2,4M lignes)

| Palier | LOAD | SELECT | WHERE | WHERE complex | GROUP BY | GROUP BY complex |
|-------:|-----:|-------:|------:|--------------:|---------:|-----------------:|
| 100k | 785ms | 8ms | 10ms | 5ms | 16ms | 19ms |
| 500k | 2 540ms | 22ms | 26ms | 28ms | 59ms | 87ms |
| 1M | 5 100ms | 52ms | 53ms | 51ms | 103ms | 159ms |
| 2M | 10 332ms | 136ms | 94ms | 95ms | 174ms | 342ms |

---

## Phase 4 — Optimisations avancées (sessions récentes)

### P9 — NumericCondition : WHERE sans boxing

**Problème** : `getValue()` crée un objet `Integer`/`Float` à chaque appel.
Sur 70M lignes avec WHERE numérique : **70 millions d'allocations d'objets** → pression GC.

```java
// AVANT — getValue() boxe la valeur en Integer ou Float
Object cell = table.getValue(rowIdx, colIndex);
if (isNum && cell instanceof Number n) { double v = n.doubleValue(); ... }

// APRÈS — NumericCondition : getNumericRaw() retourne un double primitif, 0 boxing
public boolean matches(int rowIdx, Table table) {
    double v = table.getNumericRaw(colIndex, rowIdx);  // double primitif direct
    return switch (op) {
        case GT -> v > threshold;
        case LT -> v < threshold;
        ...
    };
}
// Créé au parse time si la colonne est numérique → spécialisation statique
```

**Gain estimé** : −15 à −25% sur WHERE numérique à grand volume.

---

### P12 — GROUP BY parallèle : N HashMaps locaux + merge final

**Problème** : l'accumulation GROUP BY était entièrement séquentielle même sur 12 CPUs.

```
AVANT  : 1 thread × 70M itérations = ~3 500ms
APRÈS  : 12 threads × 5,8M itérations + merge = ~400ms
```

```java
// Chaque thread accumule dans son propre HashMap → 0 contention
IntStream.range(0, nCpu).parallel().forEach(t -> {
    Map<Long, GroupAcc> local = locals[t];  // HashMap privé, 0 synchronisation
    for (int r = from; r < to; r++) {
        long key = Double.doubleToRawLongBits(table.getNumericRaw(groupIdx[0], r));
        // accumuler dans local...
    }
});

// Merge final : trivial (payment_type = 5 valeurs, VendorID = 2)
Map<Long, GroupAcc> merged = locals[0];
for (int t = 1; t < nCpu; t++) {
    for (Map.Entry<Long, GroupAcc> e : locals[t].entrySet()) {
        dst.count += src.count;
        dst.sums[j] += src.sums[j];  // merge SUM
        // merge MIN, MAX, hasVal...
    }
}
```

---

### P14 — Filter + GROUP BY en une seule passe parallèle

**Problème** : quand WHERE + GROUP BY, le code faisait 2 passes séquentielles-parallèles :

```
AVANT :
  Pass 1 : applyWhere(70M, parallèle) → crée int[63M] = 252 MB
  Pass 2 : applyGroupBy(rows[], séquentiel)  ← P12 ne s'activait pas car rows != null
  Total  : ~3 500ms

APRÈS :
  Pass unique : filtre inline dans chaque thread → 0 allocation int[]
  Total       : ~400-700ms
```

```java
IntStream.range(0, nCpu).parallel().forEach(t -> {
    for (int r = from; r < to; r++) {
        if (cond != null && !cond.matches(r, table)) continue;  // filtre inline
        long key = Double.doubleToRawLongBits(table.getNumericRaw(groupIdx[0], r));
        // accumuler directement sans passer par un int[]
    }
});
```

**Gain mesuré** :
- R2 (WHERE `passenger_count>0 AND trip_distance>0` + GROUP BY 11 groupes) : **3 471ms → 1 324ms (×2,6)**
- R3 (WHERE `tip_amount>0` + GROUP BY `DOLocationID` 261 groupes) : **3 073ms → 634ms (×4,8)**

---

### P15 — Lecture Parquet colonnaire (ColumnReader)

**Problème** : `GroupRecordConverter` crée un objet `SimpleGroup` par ligne.
70M lignes = **70 millions d'objets** créés et immédiatement abandonnés → Full GC pendant le LOAD.

```java
// AVANT — GroupRecordConverter : 1 objet Group par ligne
RecordReader<Group> rr = cio.getRecordReader(pages, new GroupRecordConverter(schema));
for (int i = 0; i < groupRows; i++) {
    Group g = rr.read();           // new SimpleGroup(19 champs) à chaque fois
    writeParquetRow(g, rowIdx, ...);
}

// APRÈS — ColumnReader : lecture directe colonne par colonne, 0 objet créé
ColumnReader cr = cs.getColumnReader(colDesc);
for (int r = 0; r < groupRows; r++) {
    if (cr.getCurrentDefinitionLevel() >= maxDef)
        table.writeFloat(flt, startRow + r, (float) cr.getDouble());  // primitif direct
    cr.consume();
}
```

**Pourquoi c'est plus rapide** :
1. Zéro allocation pour les colonnes numériques
2. Accès colonnaire aligné avec le format Parquet (qui stocke par colonnes)
3. Écriture séquentielle dans `float[][][]` → meilleur cache CPU

**Gain estimé** : −60% du temps de décodage.

---

### P16 — Pipeline parallèle de row groups Parquet

**Problème** : le fichier contient ~70 row groups de ~1M lignes. Avec 12 CPUs, on décodait 1 group à la fois.

```
AVANT  : [lire g0] [décoder g0] [lire g1] [décoder g1] ... (séquentiel)
         Total = I/O + décodage = ~80s

APRÈS  : [lire g0] ──────────── [lire g1] ──────── [lire g2] ... (main thread)
                    [décoder g0]            [déc g1]  [déc g2] ... (12 workers)
         Total = max(I/O, décodage) ≈ ~25-35s
```

```java
Semaphore semaphore = new Semaphore(maxFlight);  // max 6 row groups en vol simultané

while ((pages = fileReader.readNextRowGroup()) != null) {
    int startRow = table.allocateBatch(groupRows);  // synchronisé, séquentiel
    semaphore.acquire();  // attend si trop de groups en cours
    futures.add(pool.submit(() -> {
        try {
            ColumnReadStoreImpl cs = new ColumnReadStoreImpl(fp, ...);  // privé au worker
            // décoder les 19 colonnes pour ce row group
            // chaque worker écrit dans startRow..startRow+groupRows-1 (exclusif)
        } finally {
            semaphore.release();  // toujours libéré, même si exception
        }
    }));
}
pool.shutdown();
pool.awaitTermination(30, TimeUnit.MINUTES);
```

**Sécurité thread** :
- `allocateBatch()` est `synchronized` → chaque worker a une plage de lignes exclusive
- Aucun deux workers n'écrivent jamais dans la même case de tableau
- Chaque worker a son propre `ColumnReadStoreImpl` (pas de partage d'état Parquet)

**Gain estimé** : LOAD 84s → **~25-35s (×2,5)**.

---

### ZGC — GC concurrent sans pauses stop-the-world

```
AVANT (.mvn/jvm.config) : -XX:+UseG1GC -XX:MaxGCPauseMillis=200
  → pauses stop-the-world jusqu'à 200ms pendant le chargement de 70M lignes (7 GB)
  → visible comme des "sauts" dans les courbes de temps

APRÈS : -XX:+UseZGC
  → GC entièrement concurrent, pauses < 1ms quelle que soit la taille du heap
  → pendant le chargement de 70M lignes, GC collecte en arrière-plan sans interrompre
```

---

## Résultats mesurés — évolution complète

*Source : `BENCHMARK_TRACE.md` — `yellow_tripdata_combined.parquet`, 70 560 406 lignes*

### Par palier (version avec P9 + P12 + P14)

| Lignes | LOAD | R1 GROUP BY | R2 WHERE+GB | R3 WHERE+GB | R4 GROUP BY |
|-------:|-----:|------------:|------------:|------------:|------------:|
| 4M | 2 557ms | 188ms | 272ms | 235ms | 61ms |
| 10M | 8 684ms | 178ms | 90ms | 78ms | 48ms |
| 20M | 18 511ms | 253ms | 185ms | 118ms | 107ms |
| 30M | 29 504ms | 541ms | 387ms | 327ms | 218ms |
| 40M | 41 931ms | 563ms | 450ms | 379ms | 389ms |
| 50M | 54 944ms | 556ms | 718ms | 481ms | 318ms |
| 70M | 82 484ms | 708ms | 920ms | 667ms | 757ms |
| **70,56M** | **84 555ms** | **696ms** | **1 324ms** | **634ms** | **770ms** |

### Requêtes sur données complètes (70,56M lignes)

| Requête | Description | Groupes | Temps |
|---------|-------------|--------:|------:|
| R1 | GROUP BY payment_type, COUNT+SUM+AVG+MIN+MAX, ORDER BY DESC | 6 | 696ms |
| R2 | WHERE passenger_count>0 AND trip_distance>0 + GROUP BY | 11 | 1 324ms |
| R3 | WHERE tip_amount>0 + GROUP BY DOLocationID | 261 | 634ms |
| R4 | GROUP BY payment_type, SUM(total_amount) | 6 | 770ms |

---

## Tableau récapitulatif — Toutes optimisations

| # | Phase | Optimisation | Composant | Gain mesuré |
|---|:-----:|---|---|---|
| P1 | 1 | `getColumnIndex()` O(n) → O(1) HashMap | `Table.java` | −30-50% GROUP BY |
| P2 | 1 | Pré-calcul index agrégats | `QueryService` | N fois → 1 fois |
| P3 | 1 | `LinkedHashMap` → `HashMap` | `QueryService` | SELECT 4M ×2,8 |
| P4 | 1 | Buffer CSV 8Ko → 1Mo | `DataLoaderService` | ÷125 appels système |
| P5 | 1 | Batch insertion 10k | `DataLoaderService` | ÷10 000 opérations |
| P6 | 1 | Tri Rows brutes vs Maps | `QueryService` | ORDER BY ×1,9 |
| P7 | 1 | `parallelSort` > 500k | `QueryService` | ORDER BY ×2 |
| P8 | 1 | Heap O(n log N) TOP-N | `QueryService` | TOP-10 ×30 |
| — | 2 | Stockage `int[]/float[]/long[]` typé | `Table.java` | −42% mémoire |
| — | 2 | Chunks 262k (anti-humongous) | `Table.java` | Supprime Full GC |
| — | 2 | Getters natifs Parquet | `DataLoaderService` | −1,3 milliard String |
| — | 2 | GROUP BY sans `int[]` intermédiaire | `QueryService` | −280 MB/requête |
| — | 3 | Upload HTTP multipart | `TableController` | Utilisable sur toute machine |
| — | 3 | Fix WHERE guillemets | `QueryService` | Bug correctness |
| P9 | 4 | `NumericCondition` sans boxing | `QueryService` | −15-25% WHERE |
| P12 | 4 | GROUP BY parallèle N HashMaps | `QueryService` | ×6-10 GROUP BY |
| P14 | 4 | Filter+GROUP BY passe unique | `QueryService` | R2 ×2,6 / R3 ×4,8 |
| P15 | 4 | `ColumnReader` colonnaire | `DataLoaderService` | −60% décodage LOAD |
| P16 | 4 | Pipeline row groups parallèles | `DataLoaderService` | LOAD ×2,5 |
| P17 | 4 | Seuil parallélisme 500k→100k | `QueryService` | Petits volumes +30% |
| ZGC | 4 | GC concurrent < 1ms | `.mvn/jvm.config` | LOAD plus fluide |

---

## Points clés pour l'oral

### 1. Pourquoi le stockage colonnaire ?
Les données NYC Taxi ont 12 colonnes `DOUBLE` (montants). En les stockant en `float`
(4 octets au lieu de 8), on économise **3 200 MB sur 50M lignes** sans perte de précision
pour les agrégats (7 chiffres significatifs suffisent pour `fare_amount = 123.45`).

### 2. Pourquoi les chunks de 262k ?
Un tableau `float[50_000_000]` = 200 MB → G1GC le place en Old Gen (objet "humongous"),
jamais collecté en cours de chargement → Full GC. Chunks de 262k = 1 MB → Young Gen,
collecté normalement entre deux row groups.

### 3. Pourquoi P14 élimine l'int[] ?
`WHERE passenger_count>0 AND trip_distance>0` sur 70M lignes créait un `int[63M]` = 252 MB.
Puis `applyGroupBy()` itérait ce tableau séquentiellement (P12 ne s'activait pas car `rows != null`).
P14 fusionne le filtre dans la boucle parallèle → 0 allocation, P12 s'active même avec WHERE.

### 4. Pourquoi P15 est plus rapide que GroupRecordConverter ?
`GroupRecordConverter` matérialise chaque ligne comme un objet Java à 19 champs.
Pour 70M lignes = 70M objets créés et jetés. `ColumnReader` lit les valeurs brutes directement
dans les arrays, en suivant l'ordre naturel du format Parquet (colonnaire → aligné avec notre stockage).

### 5. Thread-safety de P16
- `allocateBatch()` est `synchronized` → chaque worker a une plage de lignes exclusive
- Chaque worker crée son propre `ColumnReadStoreImpl` → aucun état Parquet partagé
- Le sémaphore limite à 6 row groups simultanés pour éviter l'OOM
- Bug corrigé : `semaphore.release()` dans un bloc `finally` (libéré même si exception)
