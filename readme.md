# 🚀 NaviQ CLI

> A SQL autocomplete engine that asks the parser instead of guessing.

Most SQL autocomplete tools work by prefix-matching against a list of known names. That's fast to build and mostly
works — until the query gets a subquery, a CTE that references an earlier CTE, or an alias three levels deep, and
suddenly the tool is just guessing again.

**NaviQ CLI** takes a different approach: it asks ANTLR's own grammar what's valid at the cursor, and asks a real
scope-resolution pass what an alias actually points to — the same two questions a database engine would ask, just
answered early enough to suggest something useful mid-keystroke.

---
![img.png](img.png)

## ✨ Highlights

* **Two independent analyses, not one heuristic** — a syntax layer (what token is grammatically valid here) and a
  semantic layer (what does this alias actually resolve to)
* **Deterministic, not fuzzy** — suggestions come from ATN traversal, not prefix scoring
* **Understands real SQL structure** — nested subqueries, CTEs referencing earlier CTEs, JOINs, alias shadowing
* **Survives half-typed input** — `SELECT id.` and `SELECT | FROM t` resolve correctly instead of confusing the
  parser
* **A typo doesn't take down the query** — one bad table name doesn't break suggestions for the rest of the
  statement
* **Custom terminal UI (JLine)**

---

## ⚙️ How It Works

Every keystroke runs two analyses in parallel and combines their answers. Neither one can replace the other — they
literally answer different questions.

```
                     input SQL + cursor position
                                 │
                 ┌───────────────┴───────────────┐
                 ▼                                ▼
      SYNTACTIC LAYER                   SEMANTIC LAYER
      "what's grammatically             "what does this name
       valid right here?"                actually point to?"

      ATN traversal (DFS/BFS)            tolerant grammar +
      stops exactly at the caret         tree walk over scopes

      → candidate token types            → alias → table / CTE /
      → candidate grammar rules            subquery resolution
        (columnName, tableName…)         → real columns a derived
                                            table projects
                 │                                │
                 └───────────────┬────────────────┘
                                  ▼
                 "columnName expected here" +
                 "this alias resolves to orders"
                     → suggest orders' real columns
                                  │
                                  ▼
                    schema lookup (real DB columns/types)
                                  │
                                  ▼
                            terminal UI (JLine)
```

---

## 🧠 The Core Idea

Naive autocomplete asks one question: *what starts with what the user just typed?* That's a string-matching
problem, and it treats SQL as flat text.

NaviQ CLI asks two narrower questions instead, and only combines the answers at the end:

**Is this grammatically valid?** ANTLR's ATN (Augmented Transition Network) already encodes every legal path
through the grammar. At the caret's token index, walking that network directly yields every token and rule that
could legally appear next — not a guess, a fact derived from the grammar itself.

**What does that name actually mean?** Knowing that a `columnName` is grammatically valid at the cursor is useless
without knowing *which table*. A separate pass walks the parse tree and resolves aliases through nested subqueries,
CTEs, and JOINs the way a real query planner would — including cases like a CTE that references an earlier CTE, or
an alias that legitimately shadows an outer one with the same name.

Put together: every suggestion is both syntactically valid *and* semantically correct, not just a plausible-looking
string.

---

## 🩹 The Hard Part: Input That Isn't Finished Yet

Autocomplete has to work on SQL mid-sentence. `SELECT id.` isn't valid — the dot has nothing after it. Parse that
naively and ANTLR's default error recovery starts guessing about what to do with the broken token, and that
guessing can go wrong in ways that are easy to miss: during development, a trailing alias in an otherwise unrelated
part of the query got silently misattributed as a column alias, because recovery guessed the error was somewhere it
wasn't.

Two fixes, borrowed from how DBeaver's SQL engine handles the same problem:

**Make the grammar tolerant instead of recovering from an error.** A dangling `identifier.` is accepted as valid
grammar (`qualifiedName: identifier (DOT identifier)* DOT??` — non-greedy, so it doesn't swallow `table.*` by
mistake). There's no error to recover from, so there's nothing to guess wrong about.

**Give the parser something to anchor to when there's nothing there at all.** `SELECT | FROM t` has no token at the
cursor for the grammar to hang onto. A synthetic identifier gets inserted at the cursor before parsing — but only
when the cursor isn't already right after a dot, so it never interferes with the fix above. Without this, error
recovery can delete a real keyword like `FROM` while trying to make sense of the gap.

A third safeguard, `isUnreliable()`, cross-checks the parse tree against the error listener's reported token
positions, so even a genuine typo (`123abc` as a table name) stays contained to that one table instead of poisoning
scope resolution for the rest of the query.

---

## 🎯 Examples

**Basic alias resolution**

```sql
select u.from users u
```

```
u.id
u.email
u.created_at
```

**CTE-aware completion**

```sql
WITH t AS (SELECT id, email FROM users)
SELECT t.FROM t
```

```
t.id
t.email
```

Resolved from the CTE's own `SELECT` list — not from re-scanning `users` — so a CTE with a `WHERE` or renamed
columns still suggests exactly what it actually projects.

**Subquery with alias**

```sql
SELECT *
FROM (SELECT id AS uid, name FROM users) u
WHERE u.
```

```
u.uid
u.name
```

**A typo elsewhere doesn't take the query down**

```sql
SELECT *
FROM 999bad bad
         JOIN users good ON true
WHERE good.
```

`999bad` is flagged as invalid — but `good.`, a perfectly valid alias in the same query, still resolves normally:

```
good.id
good.email
```

---

## 🔍 Why Not pgcli?

pgcli is a great tool, and this isn't trying to replace it. Its autocomplete leans on heuristics and metadata rather
than grammar traversal, which is fast and usually good enough — but it doesn't resolve scope through nested
subqueries or CTEs the way a real parser does, so complex queries eventually outrun it.

NaviQ CLI exists because I wanted to understand *why* that gap exists, and to see how far a two-layer,
grammar-driven approach could close it. It's a side project for learning, not a pgcli replacement — though on
deeply nested queries, it does noticeably better.

---

## 🛠 Tech Stack

Java · ANTLR4 · JLine · PostgreSQL

---

## 🎯 Goals

* Understand how parsers actually work, at both the syntax (ATN) and semantic (scope resolution) level
* Build autocomplete that strictly follows grammar rules while staying usable on input that isn't finished yet
* Build a foundation extensible to other languages, DSLs, or configuration formats

---

## 🚧 Status

_Experimental / Side Project_

- [x] Basic completion
- [x] Alias resolution
- [x] CTEs, including a CTE referencing an earlier CTE
- [x] Subqueries, including nested and JOIN-position subqueries
- [x] Tolerant of incomplete input (`alias.`, empty cursor positions)
- [x] Error isolation — a typo in one table doesn't break the rest of the query
- [ ] Ranking improvements
- [ ] Performance — the common case (cursor right after a dot) parses once; the "empty position" case still parses
  twice, since syntax and semantics can't yet safely share one tree there
- [ ] Multi-database support