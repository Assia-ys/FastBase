# Journal des modifications — FastBase

> Ce fichier retrace chaque changement apporté au projet, le fichier concerné, et la raison.
> À lire par toute l'équipe avant de reprendre le travail.

---

## Session du 15/05/2026 — Retours prof + préparation 50M lignes

### Contexte
Le prof a signalé deux problèmes principaux :
1. Le chargement de données passe par un **chemin de fichier local** au lieu d'un **vrai upload HTTP** dans les benchmarks.
2. Il manque un **endpoint "bouton unique"** pour déclencher tous les benchmarks depuis une interface.

Objectif supplémentaire : dépasser le groupe qui a chargé **50M lignes**.

---

### 1. NOUVEAU FICHIER — `BenchmarkController.java`
**Chemin** : `src/main/java/com/fastbase/controller/BenchmarkController.java`

**Pourquoi** : Manquait complètement (FEAT 4 du CLAUDE.md). Les benchmarks ne tournaient que via JUnit avec des chemins de fichiers locaux — inutilisable depuis une interface web.

**Ce que ça fait** :
- `POST /api/benchmark/run` (multipart) — accepte un **vrai fichier Parquet uploadé**, pas un chemin
- Détecte automatiquement le schéma depuis les métadonnées Parquet (pas besoin de déclarer le schéma manuellement)
- Exécute les 6 opérations benchmark à chaque palier :
  - `LOAD` — temps de chargement
  - `SELECT` — scan `fare_amount, total_amount` sans filtre
  - `WHERE_SIMPLE` — `fare_amount > 10`
  - `WHERE_COMPLEX` — `payment_type = 1` (filtre entier)
  - `GROUP_BY_SIMPLE` — `GROUP BY VendorID, SUM(total_amount)`
  - `GROUP_BY_COMPLEX` — `GROUP BY passenger_count` avec 4 agrégats
- Paliers par défaut : 100k, 500k, 1M, 2M, 4M (paramètre `scales` configurable)
- Retourne un JSON complet avec `rowCount` et `elapsedMs` par opération et par palier
- Ignore les paliers supérieurs au nombre de lignes réel dans le fichier

**Exemple d'appel** :
```
POST /api/benchmark/run
Content-Type: multipart/form-data

file=<yellow_tripdata_2022-01.parquet>
scales=100000,500000,1000000,2000000,4000000   (optionnel)
```

---

### 2. MODIFIÉ — `Table.java`
**Chemin** : `src/main/java/com/fastbase/model/Table.java`

**Pourquoi** : Pour atteindre 50M+ lignes sans crash ni lenteur, il faut éviter que l'`ArrayList` interne se redimensionne ~26 fois (doublement à chaque fois). Sur 50M lignes ça représente des centaines de millions d'allocations inutiles.

**Changement** : Ajout de la méthode `reserveCapacity(int capacity)` qui appelle `ArrayList.ensureCapacity()` — pré-alloue directement la bonne taille si on la connaît à l'avance.

---

### 3. MODIFIÉ — `DataLoaderService.java`
**Chemin** : `src/main/java/com/fastbase/service/DataLoaderService.java`

**Pourquoi** : La méthode `loadParquetData(InputStream)` (utilisée par l'upload HTTP) ne pré-allouait pas l'ArrayList. Sur 50M lignes, le coût des resize était significatif.

**Changement** : Avant de lire le fichier Parquet, on lit d'abord le **row count depuis les métadonnées** (lecture instantanée, sans charger les données), puis on appelle `table.reserveCapacity(rowCount)`. L'ArrayList est pré-allouée à la bonne taille dès le départ.

---

### 4. MODIFIÉ — `QueryService.java`
**Chemin** : `src/main/java/com/fastbase/service/QueryService.java`

**Pourquoi** : Bug de parsing WHERE pour les valeurs string entourées de guillemets. La condition `store_and_fwd_flag='Y'` produisait `raw = "'Y'"` (avec guillemets) au lieu de `"Y"`, ce qui ne matchait jamais aucune valeur en base.

**Changement** : Dans `SimpleCondition.make()`, on supprime maintenant les guillemets simples `'...'` ou doubles `"..."` encadrant la valeur avant de créer la condition. Les conditions numériques ne sont pas affectées.

---

### 5. MODIFIÉ — `RealDataBenchmarkTest.java`
**Chemin** : `src/test/java/com/fastbase/RealDataBenchmarkTest.java`

**Pourquoi** : Le prof demande d'utiliser les données **NYC Taxi 2022**, pas 2016. Le schéma a changé entre les deux versions.

**Changements** :
- Chemin par défaut → `../data_NYC/yellow_tripdata_2022-01.parquet`
- Schéma mis à jour pour 2022 (19 colonnes) :
  - Supprimé : `pickup_longitude`, `pickup_latitude`, `dropoff_longitude`, `dropoff_latitude`
  - Ajouté : `PULocationID`, `DOLocationID`, `congestion_surcharge`, `airport_fee`
  - `passenger_count` passe de `INTEGER` à `DOUBLE` (nullable dans les fichiers 2022)
  - `RatecodeID` passe de `INTEGER` à `DOUBLE`
- WHERE_COMPLEX change de `store_and_fwd_flag='Y'` à `payment_type=1` (plus représentatif, filtre entier)

---

## Récapitulatif rapide

| Fichier | Type | Raison principale |
|---|---|---|
| `BenchmarkController.java` | **NOUVEAU** | Endpoint "bouton unique" — upload Parquet + benchmarks en JSON |
| `Table.java` | modifié | `reserveCapacity()` pour 50M+ lignes sans resize |
| `DataLoaderService.java` | modifié | Pré-allocation automatique à l'upload Parquet |
| `QueryService.java` | modifié | Fix WHERE string avec guillemets (`'Y'` → `Y`) |
| `RealDataBenchmarkTest.java` | modifié | Schéma + données NYC Taxi 2022 |

---

## API après ces changements

```
POST /api/benchmark/run          ← NOUVEAU — bouton unique benchmarks
POST /api/tables                 (inchangé)
GET  /api/tables                 (inchangé)
GET  /api/tables/{name}          (inchangé)
DELETE /api/tables/{name}        (inchangé)
POST /api/tables/load            (inchangé)
POST /api/query/select           (inchangé)
```

---

## Résultats benchmark validés — `yellow_tripdata_2022-01.parquet`

**Conditions** : upload multipart via `POST /api/benchmark/run`, schéma auto-détecté (19 colonnes), 2 463 931 lignes dans le fichier.

| Palier | LOAD | SELECT | WHERE_SIMPLE (`fare_amount>10`) | WHERE_COMPLEX (`payment_type=1`) | GROUP_BY_SIMPLE | GROUP_BY_COMPLEX |
|---|---|---|---|---|---|---|
| 100 000 | 785 ms | 8 ms | 10 ms | 5 ms | 16 ms | 19 ms |
| 500 000 | 2 540 ms | 22 ms | 26 ms | 28 ms | 59 ms | 87 ms |
| 1 000 000 | 5 100 ms | 52 ms | 53 ms | 51 ms | 103 ms | 159 ms |
| 2 000 000 | 10 332 ms | 136 ms | 94 ms | 95 ms | 174 ms | 342 ms |

**Observations** :
- SELECT et WHERE sont quasi-linéaires avec le volume (bon signe — pas de régression)
- GROUP BY reste rapide même à 2M lignes grâce à l'accumulateur en passe unique
- Le LOAD Parquet est le goulot d'étranglement principal (~5ms/1k lignes) — normal, c'est du décompression + parsing

---

## Prochaine étape — Objectif 50M lignes (2021 + 2022 complets)

Téléchargement de 24 fichiers Parquet (2021 × 12 mois + 2022 × 12 mois) puis concaténation en un seul fichier via pyarrow. Estimation : ~55–60M lignes au total.

La JVM Spring Boot sera relancée avec `-Xmx20g` pour absorber 50M+ lignes × 19 colonnes en mémoire.