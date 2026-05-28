# FastBase — Trace de performance

| Paramètre | Valeur |
|-----------|--------|
| Date      | `2026-05-28 15:25:04` |
| Fichier   | `yellow_tripdata_combined.parquet` |
| Lignes totales | 70 560 406 |
| Heap max JVM   | 4 014 MB |
| CPUs           | 12 |
| Stockage       | `int[]` / `long[]` / `float[]` colonnaire |
| Colonnes       | 19 (4×INTEGER, 2×LONG, 12×DOUBLE→float, 1×STRING) |
| Mémoire 50M lignes | ~4,4 GB (vs 7,6 GB en `double` pur) |

---

## Résultats par palier

| Lignes | LOAD (ms) | R1 (ms) | R2 (ms) | R3 (ms) | R4 (ms) | Heap (MB) | Note |
|-------:|----------:|--------:|--------:|--------:|--------:|----------:|------|
| 1 000 000 | 1760 | 59 | 140 | 91 | 32 | 533 | heap Δ: +490 MB |
| 2 000 000 | 1042 | 152 | 68 | 70 | 38 | 451 | heap Δ: +150 MB |
| 4 000 000 | 2054 | 131 | 114 | 168 | 90 | 854 | heap Δ: +208 MB |
| 6 000 000 | 2235 | 204 | 207 | 213 | 126 | 1032 | heap Δ: +152 MB |
| 8 000 000 | 2379 | 284 | 265 | 263 | 178 | 1367 | heap Δ: +114 MB |
| 10 000 000 | 2422 | 347 | 361 | 296 | 224 | 1704 | heap Δ: +30 MB |
| 12 000 000 | 2670 | 453 | 440 | 471 | 251 | 2000 | heap Δ: +-416 MB |
| 14 000 000 | 2850 | 511 | 523 | 556 | 345 | 2334 | heap Δ: +-168 MB |
| 16 000 000 | 2618 | 608 | 619 | 602 | 397 | 2705 | heap Δ: +-218 MB |
| 18 000 000 | 3283 | 743 | 662 | 685 | 484 | 2964 | heap Δ: +-373 MB |
| 20 000 000 | 3005 | 818 | 799 | 766 | 503 | 3563 | heap Δ: +629 MB |
| 22 000 000 | 3276 | 1075 | 1147 | 988 | 766 | 3594 | heap Δ: +33 MB |
