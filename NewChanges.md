# Modifications apportées

Hello Girls, Ce fichier liste tous les changements majeurs effectués sur le projet depuis la mini soutenance. Il est écrit pour que vous compreniez exactement ce qui a changé, ce qui a été ajouté, et pourquoi.

---

## BUGS CORRIGÉS

### Crash du serveur sur SUM/AVG/MIN/MAX avec une mauvaise colonne
**Fichier :** `QueryService.java`

Avant, si on faisait `SUM(colonne_qui_nexiste_pas)`, le serveur crashait avec une `ArrayIndexOutOfBoundsException`. Maintenant il retourne un message clair : `"Colonne d'agrégat introuvable : colonne_qui_nexiste_pas"`.

---

### Impossible de charger plus de 5 colonnes du fichier NYC Taxi
**Fichier :** `RealDataBenchmarkTest.java`

Le fichier NYC Taxi a 19 colonnes. L'ancien code chargeait par position dans le CSV → valeurs fausses + crash au-delà de 5 colonnes. Le benchmark utilise maintenant `DataLoaderService` qui mappe par **nom de colonne**. Les 19 colonnes se chargent correctement.

---

## FONCTIONNALITÉS AJOUTÉES

### ORDER BY et LIMIT
**Fichiers :** `QueryService.java`, `QueryRequestDTO.java`, `QueryController.java`

```json
POST /api/query/select
{
  "tableName": "taxi",
  "selectColumns": ["VendorID", "fare_amount"],
  "orderBy": "fare_amount",
  "orderDir": "DESC",
  "limit": 10
}
```

Retourne les 10 courses les plus chères. Fonctionne aussi après GROUP BY.

---

### WHERE avec AND / OR
**Fichier :** `QueryService.java`

Avant, le WHERE ne gérait qu'une seule condition. Maintenant :

```json
{ "whereCondition": "fare_amount > 10 AND passenger_count = 1" }
{ "whereCondition": "ville = Paris OR ville = Lyon" }
{ "whereCondition": "age > 18 AND ville = Paris OR salaire > 70000" }
```

AND a la priorité sur OR (comme en SQL standard).

---

### Chargement d'un nombre précis de lignes
**Fichier :** `DataLoaderService.java`

```java
dataLoaderService.loadCsvData("taxi", "yellow_tripdata.csv", 500_000);
// charge exactement 500 000 lignes
```

---

## OPTIMISATIONS DE PERFORMANCE

### Accès aux colonnes O(n) → O(1)
**Fichier :** `Table.java`

`getColumnIndex()` parcourait toute la liste à chaque appel. Sur 4M lignes avec GROUP BY = 40M comparaisons inutiles. Ajout d'un `HashMap` construit une seule fois. Gain : **−30 à −50% sur GROUP BY**.

---

### ORDER BY : de 5909ms à ~1000ms (×6x)
**Fichier :** `QueryService.java`

Quatre améliorations successives :

1. **Tri sur Rows brutes** (accès tableau O(1) au lieu de HashMap.get) → 5909ms → 3043ms
2. **parallelSort multi-cœurs** (tous les CPU cores) → 3043ms → 1489ms
3. **Arrays.asList O(1)** (évite 4M appels set() pour recopier) → 1489ms → ~1000ms
4. **Heap O(n log N) pour TOP-N** → TOP-10 : 3011ms → **37ms (×81x)**

---

### Pipeline filter+project en une seule passe
**Fichier :** `QueryService.java`

Avant : filtrer → créer List<Row> → projeter en Maps (2 passes, 1 liste intermédiaire).
Après : filtrer ET projeter en même temps avec `parallelStream` → une seule passe, pas de liste intermédiaire.

Gain WHERE 4M : **480ms → 224ms (×2.1x)**

---

### Filtrage et projection parallèles
**Fichier :** `QueryService.java`

Pour les tables > 500k lignes, le filtrage WHERE et la projection SELECT utilisent `parallelStream()` — plusieurs cœurs CPU travaillent en parallèle.

- SELECT 4M : 561ms → ~320ms
- WHERE 4M : 480ms → ~224ms

---

### GROUP BY : accumulateurs inline (une seule passe)
**Fichier :** `QueryService.java`

Avant : stocker les 4M lignes dans des ArrayList par groupe, puis itérer pour calculer COUNT/SUM/AVG/MIN/MAX → 2 passes + 32Mo d'allocations.

Après : une seule passe avec des accumulateurs `double[]` — on accumule directement sans stocker les lignes. Zéro allocation de Row objects.

Gain GROUP BY 4M : **~342ms → ~292ms**

---

### TOP-N parallèle
**Fichier :** `QueryService.java`

Le heap de taille N est maintenant calculé en parallèle : chaque thread maintient son propre mini-heap, puis fusion à la fin. Zéro contention (contrairement au GROUP BY parallèle qui avait été abandonné car peu de groupes = haute contention).

TOP-10 sur 4M : **106ms → 37ms (×2.9x)**

---

## NOUVEAUX TESTS

### OrderByTest.java
Tests ORDER BY ASC/DESC, alphabétique, ORDER BY + LIMIT, ORDER BY sur GROUP BY, LIMIT seul.

### WhereTest.java — étendu
Ajout de tests AND, AND avec 3 conditions, OR, AND + OR mixte.

### BenchmarkServiceTest.java — amélioré
- Warmup JVM avant chaque benchmark
- Nettoyage mémoire entre paliers
- Benchmark WHERE AND/OR (simple vs AND vs AND+OR)
- Benchmark ORDER BY (100k / 1M / 4M)
- Benchmark TOP-N (LIMIT 1, 10, 100, 1000 sur 4M lignes)

---

## DOCUMENTATION

| Fichier | Contenu |
|---|---|
| `CHANGELOG.md` | Ce fichier, version longue |
| `PERFORMANCES.md` | Détail des optimisations avec code avant/après |
| `FAQ_SOUTENANCE.md` | Questions probables du prof + réponses préparées |
| `CLAUDE.md` | Guide de développement |

---

## RÉSULTATS FINAUX (4 millions de lignes)

| Opération | Temps | Lignes/seconde |
|---|---|---|
| LOAD | ~245ms | **16M/s** |
| SELECT | ~320ms | **12M/s** |
| WHERE | ~224ms | **18M/s** |
| GROUP BY | ~292ms | **14M/s** |
| ORDER BY complet | ~1000ms | **4M/s** |
| TOP-10 (heap) | **37ms** | **108M/s** |

**Gain le plus impressionnant : TOP-10 de 3011ms à 37ms = ×81x plus rapide**
