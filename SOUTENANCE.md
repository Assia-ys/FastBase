# FastBase - Guide soutenance

## Couverture du cahier des charges

- API REST Spring Boot.
- Creation, lecture et suppression de tables.
- Chargement CSV et Parquet.
- Upload de fichiers via `multipart/form-data`, utilisable sur machine distante.
- Stockage interne implemente a la main en memoire.
- Requetes `SELECT`, `WHERE`, `GROUP BY`.
- Agregations bonus : `COUNT`, `SUM`, `AVG`, `MIN`, `MAX`.
- Bonus : `ORDER BY`, `LIMIT`, conditions `AND` / `OR`.
- Benchmarks jusqu'a 4 millions de lignes.
- Export CSV et script Python pour graphique.

## Charger un fichier Parquet via API

Avant, le chargement utilisait un chemin local serveur. Maintenant le client envoie directement le fichier.

```powershell
curl -X POST http://localhost:8080/api/tables/load `
  -F "tableName=taxi" `
  -F "format=PARQUET" `
  -F "file=@C:\chemin\vers\yellow_tripdata_2016-01.parquet"
```

Pour CSV :

```powershell
curl -X POST http://localhost:8080/api/tables/load `
  -F "tableName=taxi" `
  -F "format=CSV" `
  -F "file=@C:\chemin\vers\yellow_tripdata_2016-01.csv"
```

## Exemple de requete

```powershell
curl -X POST http://localhost:8080/api/query/select `
  -H "Content-Type: application/json" `
  -d '{
    "tableName": "taxi",
    "selectColumns": ["VendorID", "fare_amount", "total_amount"],
    "whereCondition": "fare_amount>10",
    "orderBy": "total_amount",
    "orderDir": "DESC",
    "limit": 10
  }'
```

## Benchmark Parquet 4M

Par defaut, le benchmark reel utilise :

```text
../data_NYC/yellow_tripdata_2016-01.parquet
```

Commande :

```powershell
.\mvnw.cmd "-Dtest=RealDataBenchmarkTest" test
```

Avec un autre fichier :

```powershell
.\mvnw.cmd "-Dtest=RealDataBenchmarkTest" `
  "-Dfastbase.benchmark.format=PARQUET" `
  "-Dfastbase.benchmark.path=C:\chemin\vers\dataset.parquet" `
  test
```

Pour revenir au CSV :

```powershell
.\mvnw.cmd "-Dtest=RealDataBenchmarkTest" `
  "-Dfastbase.benchmark.format=CSV" `
  "-Dfastbase.benchmark.path=C:\chemin\vers\dataset.csv" `
  test
```

## Sorties de benchmark

Les resultats sont ecrits ici :

```text
target/benchmark-real.csv
target/plot_benchmark.py
```

Pour generer le graphique :

```powershell
python target\plot_benchmark.py
```

## Justification technique Parquet

Le projet n'utilise pas de base de donnees, pas d'ORM et pas de moteur de requetes externe. La lecture Parquet utilise uniquement la librairie officielle Apache Parquet pour parser le format de fichier, puis les lignes sont converties dans les structures internes FastBase (`Table`, `Row`, `Column`). Le stockage, le filtrage, les aggregations et les optimisations restent implementes dans le projet.
