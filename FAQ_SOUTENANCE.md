# FAQ Soutenance — Questions probables et réponses

Ce fichier liste les questions que le professeur peut poser pendant la soutenance, avec des réponses claires et argumentées.

---

## Questions sur l'architecture

**Pourquoi avez-vous choisi un stockage in-memory ?**
> L'énoncé laisse le choix libre. Le stockage in-memory offre les accès les plus rapides car la RAM est ~1000x plus rapide que le disque. Pour 4M lignes de données, la mémoire est suffisante (~500 Mo). L'inconvénient est que les données sont perdues au redémarrage, mais ce n'est pas une exigence du projet.

**Pourquoi avoir utilisé `Object[]` dans Row plutôt qu'une `Map<String, Object>` ?**
> Un tableau `Object[]` est 4x moins gourmand en mémoire qu'une HashMap. L'accès `row.getValue(2)` est un accès direct au tableau sans calcul de hash. Sur 4 millions de lignes, cela représente des centaines de Mo économisés. Les noms de colonnes sont stockés une seule fois dans la `Table`, pas dans chaque ligne.

**Pourquoi un `ConcurrentHashMap` pour le stockage des tables ?**
> Spring Boot gère des requêtes HTTP en parallèle sur plusieurs threads. `ConcurrentHashMap` garantit que deux requêtes simultanées ne corrompent pas le stockage. C'est le choix naturel pour une Map partagée en environnement concurrent.


---

## Questions sur le chargement CSV

**Comment gérez-vous les fichiers avec des colonnes dans un ordre différent du schéma ?**
> On lit l'en-tête du CSV et on construit un mapping `nom CSV → index table` via la méthode `buildColumnMapping()`. Peu importe l'ordre des colonnes dans le fichier, chaque valeur va au bon endroit dans la Row. Les colonnes présentes dans le CSV mais absentes du schéma sont simplement ignorées.

**Que se passe-t-il si une ligne CSV est mal formée ?**
> Notre parseur `parseCsvLine()` respecte le standard RFC 4180 : il gère les guillemets, les virgules dans les champs, et les guillemets doublés. Si une valeur ne peut pas être convertie dans le type attendu (ex: "abc" pour un INTEGER), on stocke `null` à la place sans faire planter le chargement.

**Pourquoi un batch de 10 000 lignes pour l'insertion ?**
> Insérer 4 millions de lignes une par une = 4 millions d'appels à `ArrayList.add()`. En batch de 10k, c'est 400 appels à `ArrayList.addAll()` qui utilise `System.arraycopy()` en interne — une opération native JVM beaucoup plus rapide.

**Pourquoi un buffer de 1 Mo dans BufferedReader ?**
> Par défaut `BufferedReader` utilise 8 Ko. Pour un fichier de 500 Mo, cela représente ~64 000 appels système vers l'OS. Avec 1 Mo de buffer, c'est 500 appels. Moins d'interruptions = lecture plus rapide.

---

## Questions sur les requêtes

**Comment fonctionne le WHERE ?**
> La condition est parsée une seule fois avant de parcourir les lignes. On supporte `=`, `!=`, `<`, `<=`, `>`, `>=` et `LIKE` (avec `%` en préfixe, suffixe ou les deux). Pour les comparaisons numériques on utilise `Double.compare()` pour éviter les problèmes d'arrondi.

**Comment fonctionne le GROUP BY ?**
> On construit une `HashMap<clé_de_groupe, List<Row>>` en parcourant toutes les lignes. La clé est la concaténation des valeurs des colonnes GROUP BY. Ensuite pour chaque groupe on calcule les agrégats (COUNT, SUM, AVG, MIN, MAX). Les index des colonnes d'agrégat sont pré-calculés une seule fois avant la boucle pour éviter de les recalculer pour chaque groupe.

**Pourquoi GROUP BY est-il parfois plus rapide que SELECT sur 4M lignes ?**
> GROUP BY retourne 5 groupes (5 Maps) au lieu de 4 millions de Maps. Même si GROUP BY fait plus de calculs (hashing des clés, agrégation), il évite la création de 4M objets Map et la pression GC qui en découle.

**Quelle est la limite du WHERE actuel ?**
> Le WHERE gère une seule condition simple. On ne supporte pas `AND` ni `OR` (`age > 18 AND ville = Paris` ne fonctionnerait pas). C'est une limitation connue qu'on pourrait implémenter avec un parseur récursif.

---

## Questions sur ORDER BY

**Comment avez-vous optimisé ORDER BY ?**
> Trois étapes successives :
> 1. On trie les lignes brutes (`Row` avec `Object[]`) avant de les projeter en Maps — accès tableau direct O(1) au lieu de `HashMap.get()` avec calcul de hash
> 2. On utilise `Arrays.parallelSort()` au-delà de 500k lignes pour exploiter tous les cœurs CPU
> 3. Pour ORDER BY + LIMIT N, on utilise un min-heap de taille N au lieu de trier tout — O(n log N) au lieu de O(n log n)
>
> Résultat : de 6 secondes à 1.5 secondes pour ORDER BY complet, et TOP-10 passe de 3 secondes à 100ms (×30x).

**Pourquoi un heap pour ORDER BY + LIMIT ?**
> Pour `ORDER BY fare_amount DESC LIMIT 10` sur 4M lignes, trier 4M éléments pour n'en garder que 10 est absurde. Un min-heap de taille 10 parcourt les 4M lignes en une seule passe en gardant les 10 plus grandes valeurs. Complexité : O(n log N) = O(4M × log 10) = 13M comparaisons au lieu de O(4M × log 4M) = 88M.

**Pourquoi le seuil de 500k lignes pour parallelSort ?**
> En dessous de 500k éléments, le surcoût de coordination des threads (création du fork-join pool, synchronisation) dépasse le gain apporté par le parallélisme. Au-delà, le gain est systématiquement positif.

---

## Questions sur les benchmarks

**Pourquoi les résultats varient-ils d'un run à l'autre ?**
> Les benchmarks JVM sans framework dédié (JMH) ont une variance naturelle due à trois facteurs : le JIT compiler (la JVM compile le code en natif progressivement), le Garbage Collector (peut se déclencher pendant une mesure), et le scheduler OS (autres processus). C'est pourquoi on utilise un warmup de plusieurs passes avant de mesurer.

**Comment mesurer le JIT warmup ?**
> On exécute 3 passes "à vide" avec un petit jeu de données (50k lignes) avant de commencer les vraies mesures. Après ces passes, les méthodes critiques sont compilées en code natif par la JVM et les mesures suivantes sont stables.

**Pourquoi le LOAD est-il si rapide (200ms pour 4M lignes) ?**
> Notre benchmark LOAD mesure la génération des données + l'insertion en mémoire. L'insertion est essentiellement un `System.arraycopy()` sur des références d'objets — une opération native JVM extrêmement rapide. En production, le vrai goulot serait la lecture du fichier CSV (I/O disque).

**Quel est le principal goulot d'étranglement identifié ?**
> Pour SELECT et WHERE sur grands volumes : la création d'un objet `HashMap` par ligne retournée. Sur 4M lignes, c'est 4M allocations d'objets qui provoquent une pression GC et un ralentissement non-linéaire. L'optimisation radicale serait de retourner `List<Object[]>` + noms de colonnes au lieu de `List<Map>`, mais cela changerait l'interface de l'API REST.

---

## Questions sur le code

**Pourquoi avez-vous utilisé HashMap et pas LinkedHashMap dans les résultats ?**
> `LinkedHashMap` maintient une liste doublement chaînée pour préserver l'ordre d'insertion. Sur 4M lignes × 2 colonnes, c'est 16M mises à jour de pointeurs inutiles. On utilise `HashMap` car le client accède aux valeurs par clé (`row.get("fare_amount")`), pas par position. L'ordre des colonnes dans le JSON n'est pas requis.

**Pourquoi Spring Boot et pas Quarkus ?**
> L'énoncé propose les deux. Spring Boot est le framework que l'équipe maîtrisait le mieux, avec une documentation plus abondante et un meilleur support IntelliJ.

**Avez-vous respecté toutes les contraintes de l'énoncé ?**
> Oui : pas de base de données externe, pas d'ORM, pas de moteur de requêtes existant. Tout le stockage, le requêtage et les optimisations sont implémentés manuellement. Les seules librairies utilisées sont Spring Boot, Apache Commons (autorisé), Parquet-Hadoop (autorisé), et Lombok.

**Que feriez-vous différemment avec plus de temps ?**
> Implémenter le support Parquet pour le chargement de fichiers `.parquet`, ajouter le support `AND`/`OR` dans le WHERE, et remplacer `List<Map>` par un format tabulaire `List<Object[]>` pour éliminer la pression GC sur les grands SELECT.
