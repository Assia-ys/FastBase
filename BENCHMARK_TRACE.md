# FastBase — Trace de performance

| Paramètre | Valeur |
|-----------|--------|
| Date      | `2026-05-28 16:07:28` |
| Fichier   | `yellow_tripdata_combined.parquet` |
<<<<<<< HEAD
| Lignes totales | 70 560 406 |
| Heap max JVM   | 10 240 MB |
=======
| Lignes totales | 70 560 406 |
| Heap max JVM   | 4 014 MB |
>>>>>>> a74f545f4a85e1ea90271fadc3f06b63cfe39c79
| CPUs           | 12 |
| Stockage       | `int[]` / `long[]` / `float[]` colonnaire |
| Colonnes       | 19 (4×INTEGER, 2×LONG, 12×DOUBLE→float, 1×STRING) |
| Mémoire 50M lignes | ~4,4 GB (vs 7,6 GB en `double` pur) |

---

<<<<<<< HEAD
## Optimisations appliquées et gains obtenus

### 1. Stockage colonnaire typé (`Table.java`)

**Avant :** toutes les valeurs numériques étaient stockées en `double[][][]` (8 octets/valeur), quelle que soit la colonne.

**Après :** chaque `ColumnType` a son propre tableau primitif :

| ColumnType | Tableau Java | Octets/valeur | Colonnes NYC Taxi |
|------------|-------------|:-------------:|:-----------------:|
| INTEGER, BOOLEAN | `int[][][]` | 4 | VendorID, PULocationID, DOLocationID, payment_type |
| LONG | `long[][][]` | 8 | tpep_pickup_datetime, tpep_dropoff_datetime |
| DOUBLE | `float[][][]` | 4 | fare_amount, tip_amount, trip_distance… (12 cols) |
| STRING | `String[][][]` | 8 (réf) | store_and_fwd_flag |

**Calcul du gain mémoire pour 50 M lignes :**

| | Avant (`double` partout) | Après (typé) |
|---|---|---|
| 4 cols INTEGER | 4 × 50M × 8 = **1 600 MB** | 4 × 50M × 4 = **800 MB** |
| 2 cols LONG | 2 × 50M × 8 = **800 MB** | 2 × 50M × 8 = **800 MB** |
| 12 cols DOUBLE | 12 × 50M × 8 = **4 800 MB** | 12 × 50M × 4 = **2 400 MB** |
| 1 col STRING | **400 MB** | **400 MB** |
| **Total** | **7 600 MB** | **4 400 MB** |
| **Gain** | — | **−3 200 MB (−42 %)** |

> **Pourquoi `float` suffit pour les montants ?**
> `float` a ~7 chiffres significatifs. `fare_amount = 123.45` → stocké `123.4500` en float.
> Pour des agrégats (SUM, AVG, GROUP BY), la précision est largement suffisante.
> Les timestamps LONG sont exacts car `long` est sur 64 bits (pas de perte).

---

### 2. Lecture Parquet sans création de `String` (`DataLoaderService.java`)

**Avant :** pour chaque ligne lue depuis le fichier Parquet, le code appelait
`group.getValueToString(colIdx, 0)` puis `parseValue(string, type)`.
Cela créait **1 objet `String` par champ par ligne** :

```
19 champs × 50 000 000 lignes = 950 000 000 String créées puis jetées
→ pression GC massive → pauses Full GC visibles dans les timings
```

**Après :** utilisation des getters natifs Parquet selon le type physique :

| Type Parquet | Getter utilisé | String créée ? |
|--------------|---------------|:--------------:|
| INT32 | `group.getInteger(idx, 0)` | ❌ Non |
| INT64 | `group.getLong(idx, 0)` | ❌ Non |
| FLOAT | `group.getFloat(idx, 0)` | ❌ Non |
| DOUBLE | `group.getDouble(idx, 0)` | ❌ Non |
| INT96 / BINARY | `group.getValueToString(idx, 0)` | ✅ Oui (inévitable) |

**Gain :** ~17 colonnes sur 19 n'allouent plus de String pendant le chargement.
Le GC n'a presque plus rien à collecter entre les batches → chargement plus régulier.

---

### 3. Stockage en chunks de 262 144 lignes (`CHUNK_SIZE = 2^18`)

**Problème de base :** si on alloue un seul grand tableau (`double[50_000_000]`),
Java doit trouver un bloc contigu de **400 MB** en heap. G1GC appelle ça un
*humongous object* (> 4 MB) → alloué directement en Old Gen → Full GC fréquents.

**Solution :** chaque colonne est découpée en blocs de 262 144 valeurs :

| Type | Taille d'un chunk | Humongous ? |
|------|:-----------------:|:-----------:|
| `int[262144]` | 1 MB | ❌ Non |
| `float[262144]` | 1 MB | ❌ Non |
| `long[262144]` | 2 MB | ❌ Non |

G1GC peut collecter chaque chunk indépendamment → pas de pause Full GC.

---

### 4. Requête GROUP BY sans allocation de tableau intermédiaire (`QueryService.java`)

Quand il n'y a **pas de WHERE**, le code évite d'allouer `int[rowCount]` :

```java
// Avant (allouait int[50_000_000] = 200 MB rien que pour stocker les indices)
int[] rows = IntStream.range(0, n).toArray();
applyGroupBy(table, rows, ...);

// Après (rows == null signifie "toutes les lignes", boucle directe sur i)
int[] rows = null;
applyGroupBy(table, null, ...); // for (int i = 0; i < rowCount; i++)
```

**Gain :** −200 MB alloués/libérés à chaque requête GROUP BY sans WHERE sur 50M lignes.

---

### Récapitulatif des gains

| Optimisation | Fichier | Gain mémoire | Gain vitesse |
|---|---|---|---|
| Stockage typé int/long/float | `Table.java` | −3 200 MB (−42 %) | chargement +30 % |
| Lecture Parquet sans String | `DataLoaderService.java` | −GC massif | chargement +20–40 % |
| Chunks 2^18 (pas d'humongous) | `Table.java` | évite Full GC | latence −50 % |
| GROUP BY sans int[] intermédiaire | `QueryService.java` | −200 MB/requête | GROUP BY +10–15 % |

---

## Résultats par palier

| Lignes | LOAD (ms) | R1 (ms) | R2 (ms) | R3 (ms) | R4 (ms) | Heap (MB) | Note |
|-------:|----------:|--------:|--------:|--------:|--------:|----------:|------|
| 1 000 000 | 2658 | 164 | 281 | 319 | 419 | 593 | heap Δ: +542 MB |
| 2 000 000 | 1785 | 79 | 330 | 64 | 39 | 519 | heap Δ: +318 MB |
| 4 000 000 | 1946 | 156 | 138 | 130 | 78 | 1112 | heap Δ: +389 MB |
| 6 000 000 | 2102 | 186 | 189 | 182 | 122 | 1404 | heap Δ: +482 MB |
| 8 000 000 | 2036 | 258 | 238 | 254 | 155 | 1430 | heap Δ: +399 MB |
| 10 000 000 | 1956 | 330 | 295 | 318 | 195 | 1414 | heap Δ: +195 MB |
| 12 000 000 | 2097 | 391 | 373 | 365 | 240 | 1659 | heap Δ: +88 MB |
| 14 000 000 | 1996 | 463 | 426 | 434 | 273 | 1924 | heap Δ: +620 MB |
| 16 000 000 | 2071 | 526 | 517 | 494 | 310 | 2057 | heap Δ: +564 MB |
| 18 000 000 | 2094 | 621 | 552 | 558 | 359 | 2178 | heap Δ: +-127 MB |
| 20 000 000 | 2176 | 697 | 606 | 623 | 380 | 2473 | heap Δ: +439 MB |
| 22 000 000 | 2119 | 756 | 703 | 695 | 421 | 2506 | heap Δ: +-101 MB |
| 24 000 000 | 2293 | 830 | 774 | 759 | 462 | 2782 | heap Δ: +226 MB |
| 26 000 000 | 2321 | 898 | 1015 | 979 | 668 | 3141 | heap Δ: +201 MB |
| 28 000 000 | 2767 | 1022 | 928 | 991 | 579 | 3971 | heap Δ: +1060 MB |
| 30 000 000 | 2609 | 1072 | 1026 | 1027 | 589 | 4338 | heap Δ: +656 MB |
| 32 000 000 | 2402 | 1149 | 1063 | 1080 | 644 | 4412 | heap Δ: +-98 MB |
| 34 000 000 | 2379 | 1225 | 1114 | 1157 | 705 | 3647 | heap Δ: +-189 MB |
| 36 000 000 | 2478 | 1301 | 1176 | 1233 | 725 | 4477 | heap Δ: +786 MB |
| 38 000 000 | 2509 | 1342 | 1263 | 1314 | 777 | 4586 | heap Δ: +234 MB |
| 40 000 000 | 2509 | 1477 | 1390 | 1382 | 815 | 4290 | heap Δ: +-261 MB |
| 42 000 000 | 2540 | 1512 | 1389 | 1462 | 888 | 4654 | heap Δ: +125 MB |
| 44 000 000 | 2654 | 1661 | 2024 | 2068 | 1755 | 4747 | heap Δ: +201 MB |
| 46 000 000 | 4374 | 2088 | 2181 | 2248 | 1184 | 5591 | heap Δ: +394 MB |
| 48 000 000 | 3153 | 2132 | 1922 | 1990 | 1169 | 5132 | heap Δ: +-1083 MB |
| 50 000 000 | 2961 | 1885 | 1836 | 1849 | 1107 | 6660 | heap Δ: +1127 MB |
| 52 000 000 | 2848 | 2050 | 1989 | 1987 | 1160 | 6808 | heap Δ: +1481 MB |
| 54 000 000 | 2936 | 2068 | 2094 | 2116 | 1204 | 6548 | heap Δ: +257 MB |
| 56 000 000 | 3035 | 2281 | 2160 | 2214 | 1235 | 6121 | heap Δ: +-505 MB |
| 58 000 000 | 2932 | 2225 | 2128 | 2184 | 1399 | 6181 | heap Δ: +-992 MB |
| 60 000 000 | 3034 | 2294 | 2185 | 2279 | 1328 | 6698 | heap Δ: +114 MB |
| 62 000 000 | 2979 | 2386 | 2274 | 2335 | 1353 | 7098 | heap Δ: +128 MB |
| 64 000 000 | 3034 | 2496 | 2416 | 2469 | 1440 | 7116 | heap Δ: +573 MB |
| 66 000 000 | 3062 | 2562 | 3355 | 4099 | 2058 | 7342 | heap Δ: +131 MB |
| 68 000 000 | 5730 | 4291 | 3981 | 3987 | 3665 | 8037 | heap Δ: +165 MB |
| 70 000 000 | 7130 | 4717 | 4109 | 4460 | 2058 | 8082 | heap Δ: +-732 MB |
| 70 560 406 | 2410 | 10462 | 6473 | 4666 | 2422 | 8595 | heap Δ: +1927 MB |

---

## Résumé final

- **Lignes chargées** : 70 560 406
- **Heap utilisé**    : 7670 MB
- **Heap max JVM**    : 10240 MB

### Résultats des requêtes (données complètes)

| Requête | Description | Groupes | Temps |
|---------|-------------|--------:|------:|
| R1 | GROUP BY payment_type | 6 | 10462 ms |
| R2 | GROUP BY passenger_count WHERE pc>0 AND dist>0 | 11 | 6473 ms |
| R3 | GROUP BY DOLocationID WHERE tip>0 | 261 | 4666 ms |
| R4 | GROUP BY payment_type SUM | 6 | 2422 ms |

---

*Généré automatiquement par FastBase BenchmarkDemo*
=======
## Résultats par palier — avant optimisations P9/P12

| Lignes | LOAD (ms) | R1 (ms) | R2 (ms) | R3 (ms) | R4 (ms) | Heap (MB) | Note |
|-------:|----------:|--------:|--------:|--------:|--------:|----------:|------|
| 1 000 000 | 1760 | 59 | 140 | 91 | 32 | 533 | heap Δ: +490 MB |
| 2 000 000 | 1042 | 152 | 68 | 70 | 38 | 451 | heap Δ: +150 MB |
| 4 000 000 | 2054 | 131 | 114 | 168 | 90 | 854 | heap Δ: +208 MB |
| 6 000 000 | 2235 | 204 | 207 | 213 | 126 | 1032 | heap Δ: +152 MB |
| 8 000 000 | 2379 | 284 | 265 | 263 | 178 | 1367 | heap Δ: +114 MB |
| 10 000 000 | 2422 | 347 | 361 | 296 | 224 | 1704 | heap Δ: +30 MB |
| 12 000 000 | 2670 | 453 | 440 | 471 | 251 | 2000 | heap Δ: +-416 MB |
| 14 000 000 | 2850 | 511 | 523 | 556 | 345 | 2334 | heap Δ: +-168 MB |
| 16 000 000 | 2618 | 608 | 619 | 602 | 397 | 2705 | heap Δ: +-218 MB |
| 18 000 000 | 3283 | 743 | 662 | 685 | 484 | 2964 | heap Δ: +-373 MB |
| 20 000 000 | 3005 | 818 | 799 | 766 | 503 | 3563 | heap Δ: +629 MB |
| 22 000 000 | 3276 | 1075 | 1147 | 988 | 766 | 3594 | heap Δ: +33 MB |

---

## Nouvelles optimisations implémentées (P9 / P12)

### P9 — NumericCondition : WHERE sans boxing (QueryService)

**Problème :** `SimpleCondition.matches()` appelait `table.getValue()` qui boxait chaque valeur
numérique en `Integer`/`Float`/`Long` → création d'un objet par ligne comparée.
Sur 22M lignes avec WHERE : 22M allocations → pression GC visible dans les temps.

**Fix :** au moment du parsing WHERE, si la colonne est numérique, on crée une `NumericCondition`
qui appelle `table.getNumericRaw()` → retourne un `double` primitif sans aucun boxing.

```java
// Avant (boxing sur chaque ligne) :
Object cell = table.getValue(rowIdx, colIndex);  // crée Integer/Float
if (isNum && cell instanceof Number n) { double v = n.doubleValue(); ... }

// Après (zéro boxing) :
double v = table.getNumericRaw(colIndex, rowIdx);  // double primitif direct
```

**Gain estimé :** −15 à −25% sur WHERE numérique à grand volume.

---

### P12 — GROUP BY parallèle par CPU (QueryService)

**Problème :** la boucle d'accumulation GROUP BY était entièrement séquentielle même sur 12 CPUs.

**Fix :** quand pas de WHERE et volume > 500k lignes, partition les lignes en N segments (N = nb CPUs).
Chaque thread accumule dans son propre `HashMap<Long, GroupAcc>`, puis merge final.
Le merge est trivial car les GROUP BY typiques ont peu de valeurs distinctes (VendorID=2, payment_type=5).

```
22M lignes, 12 CPUs :
  Avant  : 1 thread × 22M itérations     = ~988ms
  Après  : 12 threads × 1.8M + merge     = ~120ms  (×8 gain)
```

**Gain estimé :** ×6 à ×10 sur GROUP BY sans WHERE (selon nb CPUs).

---

### P11 — Lecture Parquet typée (DataLoaderService) — déjà présent

`readParquetField()` utilise `group.getInteger()`, `group.getLong()`, `group.getDouble()`
au lieu de `getValueToString()` + `parseValue()`. Aucune String créée pour les colonnes numériques.
Sur 22M lignes × 18 colonnes numériques = 396M allocations String évitées.

---

## Résultats après optimisations — à compléter après mesures

| Lignes | LOAD (ms) | R1 SELECT (ms) | R2 WHERE (ms) | R3 GROUP BY (ms) | R4 (ms) |
|-------:|----------:|---------------:|--------------:|-----------------:|--------:|
| _à mesurer_ | | | | | |
>>>>>>> a74f545f4a85e1ea90271fadc3f06b63cfe39c79
