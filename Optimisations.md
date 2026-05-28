# FastBase — Optimisations de performance

Ce document détaille chaque optimisation implémentée : le problème de départ, ce qui a été changé, pourquoi, et le gain mesuré. Il est conçu pour être présenté à la soutenance.

---

## Résumé des gains

| # | Optimisation | Composant | Gain mesuré |
|---|---|---|---|
| P1 | Lookup O(n) → O(1) via HashMap | `Table.getColumnIndex()` | −30 à −50% GROUP BY |
| P2 | Pré-calcul des index d'agrégats | `QueryService.applyGroupBy()` | Proportionnel au nb de groupes |
| P3 | `LinkedHashMap` → `HashMap` | `QueryService.projectRows()` | SELECT 4M : 561ms → 197ms |
| P4 | Buffer lecture 8Ko → 1Mo | `DataLoaderService` | −125x appels système |
| P5 | Insertion ligne par ligne → batch | `DataLoaderService` | 4M appels → 400 appels |
| P6 | Tri sur Maps → tri sur Rows brutes | `QueryService.sortRows()` | ORDER BY 4M : 5909ms → 3043ms |
| P7 | Tri séquentiel → parallelSort | `QueryService.sortRows()` | ORDER BY 4M : 3043ms → 1489ms |
| P8 | Tri complet → heap O(n log N) | `QueryService.topNRows()` | TOP-10 4M : 3011ms → 100ms |

---

## P1 — Table.getColumnIndex() : O(n) → O(1)

### Problème

Chaque appel à `getColumnIndex("fare_amount")` parcourait toute la liste de colonnes :

```java
// AVANT — scan linéaire O(n)
for (int i = 0; i < columns.size(); i++) {
    if (columns.get(i).getName().equals(columnName)) return i;
}
```

`getColumnIndex()` est appelé :
- une fois par ligne lors d'un GROUP BY → 4M appels sur 4M lignes
- une fois par colonne projetée dans `projectRows()`
- une fois par comparaison WHERE

Sur une table de 10 colonnes et 4M lignes avec GROUP BY : **40 millions de comparaisons de chaînes** rien que pour trouver les index.

### Solution

Ajout d'un `HashMap<String, Integer>` construit une seule fois à la création de la table :

```java
// Dans Table.java
private final Map<String, Integer> columnIndex = new HashMap<>();

private void rebuildIndex() {
    columnIndex.clear();
    for (int i = 0; i < columns.size(); i++)
        columnIndex.put(columns.get(i).getName(), i);
}

// APRÈS — O(1) garanti
public int getColumnIndex(String columnName) {
    Integer idx = columnIndex.get(columnName);
    return idx != null ? idx : -1;
}
```

`rebuildIndex()` est appelé uniquement dans le constructeur et dans `setColumns()` — une seule fois par table, pas à chaque requête.

### Gain mesuré
**−30 à −50% sur les requêtes GROUP BY** à grand volume.

---

## P2 — Pré-calcul des index d'agrégats

### Problème

Dans `applyGroupBy()`, les index des colonnes d'agrégat étaient recalculés pour **chaque groupe** :

```java
// AVANT — getColumnIndex() appelé à chaque groupe
for (List<Row> groupRows : groups.values()) {
    result.put("SUM(fare_amount)", sumCol(rows, table.getColumnIndex("fare_amount")));
    // → getColumnIndex() appelé N fois pour N groupes
}
```

### Solution

Pré-calcul en dehors de la boucle :

```java
// APRÈS — calculé une seule fois avant la boucle
Map<String, Integer> aggColCache = new HashMap<>();
for (String col : selectCols) {
    if (col.startsWith("SUM(") || ...) {
        aggColCache.put(col, table.getColumnIndex(extractArg(col)));
    }
}

for (List<Row> groupRows : groups.values()) {
    result.put("SUM(fare_amount)", sumCol(rows, aggColCache.get("SUM(fare_amount)")));
    // → simple lookup O(1) dans aggColCache
}
```

### Gain mesuré
Sur 1000 groupes avec 3 agrégats : 3000 appels → 3 appels. Proportionnel au nombre de groupes.

---

## P3 — LinkedHashMap → HashMap dans projectRows()

### Problème

`projectRows()` créait un `LinkedHashMap` pour chaque ligne retournée. Sur 4M lignes, c'est 4M objets `LinkedHashMap`. Le problème spécifique à `LinkedHashMap` : il maintient une **liste doublement chaînée** entre tous ses éléments pour préserver l'ordre d'insertion.

```
LinkedHashMap interne :
  Entry("fare_amount", 10.5) ←before/after→ Entry("VendorID", 1) ←before/after→ ...
                              ↑ 2 pointeurs mis à jour à chaque put()
```

Sur 4M lignes × 2 colonnes × 2 `put()` : **16 millions de mises à jour de pointeurs inutiles**.

### Solution

```java
// AVANT
Map<String, Object> map = new LinkedHashMap<>(projected.size() * 2);

// APRÈS
Map<String, Object> map = new HashMap<>(projected.size() * 2);
```

L'ordre des colonnes dans le JSON n'est pas requis fonctionnellement — le client accède aux valeurs par clé (`row.get("fare_amount")`), pas par position.

### Gain mesuré
**SELECT 4M : 561ms → 197ms (×2.8x)**

---

## P4 — Buffer de lecture CSV : 8Ko → 1Mo

### Problème

`BufferedReader` par défaut utilise un buffer de 8 Ko. Chaque fois que le buffer est vide, la JVM fait un appel système `read()` vers l'OS.

Pour un fichier de 500 Mo (4M lignes) :
- Buffer 8Ko : ~64 000 appels système
- Buffer 1Mo : ~500 appels système

### Solution

```java
// AVANT
new BufferedReader(new FileReader(filePath))

// APRÈS
new BufferedReader(new FileReader(filePath), 1024 * 1024)  // buffer 1 Mo
```

### Gain mesuré
Réduction de 125x du nombre d'appels système. Impact surtout sur disques lents (HDD) — moins visible sur SSD.

---

## P5 — Insertion par batch : ligne par ligne → blocs de 10 000

### Problème

Ajouter 4 millions de lignes une par une :

```java
// AVANT (conceptuel) — 4 millions d'appels
for (Row row : rows) table.addRow(row);  // 4M appels à ArrayList.add()
```

Chaque appel à `ArrayList.add()` vérifie la capacité et peut déclencher une réallocation.

### Solution

```java
// APRÈS — 400 appels avec addAll() qui utilise System.arraycopy()
List<Row> batch = new ArrayList<>(10_000);
while (...) {
    batch.add(row);
    if (batch.size() >= 10_000) {
        table.addRows(batch);  // ArrayList.addAll() = System.arraycopy() natif
        batch = new ArrayList<>(10_000);
    }
}
```

`System.arraycopy()` est une opération native JVM ultra-optimisée, bien plus rapide que 10 000 appels individuels.

### Gain mesuré
Réduction de 4M à 400 opérations d'insertion. Impact majeur sur le temps de chargement.

---

## P6 — Tri sur Maps → tri sur Rows brutes

### Problème

La première implémentation de ORDER BY triait les résultats **après** projection en Maps :

```
applyWhere() → projectRows() (4M Maps) → sort(4M Maps)
```

Chaque comparaison dans le tri appelait `HashMap.get("fare_amount")` deux fois :

```java
// AVANT — HashMap.get() = calcul de hash à chaque comparaison
results.sort((a, b) -> {
    Object va = a.get("fare_amount");  // hash("fare_amount") à chaque fois
    Object vb = b.get("fare_amount");  // encore un hash
    ...
});
```

Sur 4M éléments, TimSort fait ~88M comparaisons → **176M calculs de hash inutiles**.

### Solution

Trier les `Row` brutes **avant** de les projeter en Maps :

```
applyWhere() → sortRows() (Object[] bruts) → projectRows()
```

```java
// APRÈS — accès tableau direct O(1), zéro hashing
private void sortRows(List<Row> rows, Table table, String orderBy, String orderDir) {
    int idx = table.getColumnIndex(orderBy);  // O(1) grâce à P1
    rows.sort((a, b) -> compareValues(a.getValue(idx), b.getValue(idx)));
    //                                   ↑ row.values[idx] — accès tableau direct
}
```

### Gain mesuré
**ORDER BY 4M : 5909ms → 3043ms (×1.9x)**

---

## P7 — Tri séquentiel → Arrays.parallelSort()

### Problème

Le tri séquentiel `List.sort()` n'utilise qu'un seul cœur CPU, quelle que soit la puissance de la machine.

### Solution

Au-delà de 500k lignes, on utilise `Arrays.parallelSort()` qui divise le travail entre tous les cœurs disponibles (algorithme fork-join merge sort) :

```java
if (rows.size() > 500_000) {
    Row[] arr = rows.toArray(new Row[0]);
    Arrays.parallelSort(arr, cmp);  // utilise tous les cœurs CPU
    for (int i = 0; i < arr.length; i++) rows.set(i, arr[i]);
} else {
    rows.sort(cmp);  // séquentiel pour les petits volumes
}
```

**Pourquoi le seuil de 500k ?** En dessous de 500k éléments, le surcoût de coordination des threads (création du fork-join pool, synchronisation) dépasse le gain apporté par le parallélisme.

### Gain mesuré
**ORDER BY 4M : 3043ms → 1489ms (×2x)** sur machine 4 cœurs.

---

## P8 — Tri complet → Heap O(n log N) pour TOP-N

### Problème

Pour `ORDER BY fare_amount DESC LIMIT 10` sur 4M lignes, l'ancienne implémentation triait les **4 millions de lignes** pour n'en garder que 10. C'est un énorme gaspillage.

**Complexité du tri complet :** O(n log n) = 4M × log₂(4M) = 4M × 22 = **88 millions de comparaisons**

### Solution

Un **min-heap de taille N** parcourt les 4M lignes en une seule passe et garde automatiquement les N meilleures :

```java
// heap de taille N — garde les N plus grands (pour DESC)
PriorityQueue<Row> heap = new PriorityQueue<>(limit + 1, heapComp);
for (Row row : rows) {
    heap.offer(row);
    if (heap.size() > limit) heap.poll();  // expulse le plus petit
}
```

**Complexité du heap :** O(n log N) = 4M × log₂(10) = 4M × 3.3 = **13 millions de comparaisons**

**Pourquoi ×6.7 moins de comparaisons ?** Parce que log₂(10) = 3.3 au lieu de log₂(4M) = 22.

L'algorithme n'est activé que quand `LIMIT` est spécifié et que N < nombre total de lignes. Pour ORDER BY sans LIMIT, on utilise P7 (tri complet parallèle).

### Progression complète de ORDER BY

```
Étape 0 — tri sur Maps         : ORDER BY 4M = 5909ms  |  TOP-10 = 3011ms
Étape 1 — tri sur Rows (P6)    : ORDER BY 4M = 3043ms  |  TOP-10 =  721ms
Étape 2 — parallelSort (P7)    : ORDER BY 4M = 1489ms  |  TOP-10 =  721ms
Étape 3 — heap O(n log N) (P8) : ORDER BY 4M = 1489ms  |  TOP-10 =  100ms
──────────────────────────────────────────────────────────────────────────────
Gain total                     :           ×4x          |           ×30x
```

### Gain mesuré
**TOP-10 sur 4M lignes : 3011ms → 100ms (×30x)**

---

## Résultats finaux benchmarks

### Données synthétiques (4 colonnes, seed fixe)

| Opération | 100k | 1M | 4M | Lignes/sec |
|---|---|---|---|---|
| LOAD | 10ms | 47ms | 200ms | **20M/s** |
| SELECT (2 col.) | 18ms | 83ms | 265ms | **15M/s** |
| WHERE (50% résultats) | 11ms | 48ms | 249ms | **16M/s** |
| GROUP BY (5 groupes) | 32ms | 75ms | 287ms | **14M/s** |
| ORDER BY complet | 60ms | 563ms | 1489ms | **2.7M/s** |
| TOP-10 (heap) | — | — | 100ms | **40M/s** |

### Note sur la variance des benchmarks

Les benchmarks JVM sans framework JMH ont une variance naturelle de ±20% due à :
1. **JIT compilation** — la JVM compile le code natif progressivement (d'où le warmup de 3 passes)
2. **Garbage Collector** — les GC pauses peuvent survenir pendant une mesure
3. **Scheduler OS** — d'autres processus peuvent interrompre le thread

Les tendances et ordres de grandeur sont fiables. Les valeurs absolues peuvent varier d'un run à l'autre.
