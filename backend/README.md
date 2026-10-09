# Backend

Spring Boot 3 / Java 21 / Gradle (Kotlin DSL), Postgres + Flyway, Spring Data JDBC, Actuator.

## Run

```sh
./gradlew test          # unit + integration tests (needs Docker for Testcontainers)
./gradlew bootRun       # against a local Postgres
./gradlew bootTestRun   # against a throwaway Postgres container, syncing from the live json.tarkov.dev
./gradlew liveTest      # smoke tests against the real json.tarkov.dev (network required)
```

All endpoints are served under the `/api` context path.

Health: `GET http://localhost:8080/api/actuator/health`

## Configuration

| Env var | Default |
|---|---|
| `DB_HOST` | `localhost` |
| `DB_PORT` | `5432` |
| `DB_NAME` | `gunsmith` |
| `DB_USER` | `gunsmith` |
| `DB_PASSWORD` | `gunsmith` |
| `GUNSMITH_TARKOVDEV_BASEURL` | `https://json.tarkov.dev` |
| `GUNSMITH_SYNC_ENABLED` | `true` |
| `GUNSMITH_SYNC_INTERVAL` | `10m` |

Flyway migrations live in `src/main/resources/db/migration`.

## Game data client

`com.tarkovgunsmith.tarkovdev.TarkovDevClient` fetches `/{mode}/items`, `items_en`, `traders` and
`traders_en` from json.tarkov.dev (`mode` = `regular` | `pve`). Each fetch is a conditional GET: pass
the previous ETag and get `FetchResult.NotModified` back when nothing changed. Responses are
requested brotli/gzip compressed. Names in the payloads are translation keys; resolve them with
`ItemsPayload.translate(...)` / `TradersPayload.translate(...)`.

Unit tests run against trimmed fixtures recorded from the live API in
`src/test/resources/tarkovdev/regular/` (MP5 with its default preset and a few mods chosen to cover
slots, each conflict type, trader offers incl. a quest-locked one, and a `noFlea` item).
`src/test/resources/tarkovdev/pve/items.json` holds the same items with the offers and flea prices
recorded from `/pve/items`.

## Game data import

Flyway migration `V1__game_data.sql` creates `item_category`, `item`, `trader`, `offer` and
`data_version`. `com.tarkovgunsmith.gamedata.GameDataImporter` stores translated payloads:

- `importItems(...)`: the category tree plus every weapon, mod and weapon preset with stats,
  modifiers, slots (JSONB) and conflicts. Weapons get a `weapon_class` (e.g. `smg`).
- `importTraders(...)`: trader names and max loyalty level.
- `importOffers(mode, ...)`: where the stored items can be bought in that mode (`REGULAR` = PvP, or
  `PVE`). One `offer` row per trader cash offer that is not quest-locked (`taskUnlock == null`),
  with its `minTraderLevel`, and one `FLEA` row with `lastLowPrice` (falling back to `avg24hPrice`)
  unless the item is `noFlea`. Run it after the item and trader import; offers for items or traders
  that aren't stored are skipped.

Each import upserts only rows whose data changed and deletes rows that are no longer in the payload.
`V2__offer_key.sql` makes offers unique per item, mode, source, trader and level for this.

Launchers and other special weapons are excluded (they and their presets are not stored) through
`gunsmith.game-data.excluded-weapon-categories` (category ids; children are included) and
`gunsmith.game-data.excluded-weapons` (item ids) in `application.yml`.

## Data sync

`com.tarkovgunsmith.gamedata.DataSync` keeps the database in step with json.tarkov.dev. It runs
right after startup and then `gunsmith.sync.interval` (default 10 min) after the previous run ended;
`gunsmith.sync.enabled=false` turns it off (tests do this in `src/test/resources/config/application.yml`).

Each run fetches `regular/items`, `items_en`, `traders`, `traders_en` and `pve/items` with the ETag
stored in `data_version` (`etag:<mode>/<endpoint>`) after the last successful import. Endpoints that
answer 304 are skipped, so a run with no new data downloads and writes nothing. A changed payload is
imported in full, and the importer writes only the rows that differ. A change to items or traders
also re-imports the offers of both modes. One log line per run says what changed.

All imports of a run and their new ETags are committed in one transaction. If a fetch or import
fails, the error is logged, the last imported data stays in place, and the next run retries.

## Compatibility graph

`com.tarkovgunsmith.engine.CompatibilityGraphLoader.load()` reads the stored weapons, mods and
category tree and builds a `CompatibilityGraph`: weapon → slots → mods that fit → their slots, and
so on. Each weapon and mod is one shared `Part`, so the graph can be walked recursively from any
weapon (`CompatibilityGraph.reachable(...)` collects everything that can end up in its builds).

A slot's candidates are its `allowedItems` plus every mod in an `allowedCategories` category or one
of its descendants, minus `excludedItems` and mods in an `excludedCategories` category (exclusion
wins). Ancestors come from `item_category`, so an item's stored categories don't have to list them.
Only mods are candidates; presets, weapons and ids that aren't stored are ignored. The graph is
immutable and safe to share between threads.

The live data currently lists every allowed item explicitly (no slot uses category filters), so
category resolution is covered by synthetic tests. `src/test/resources/tarkovdev/mp5-tree/` holds
the MP5, all 233 mods reachable from it and its 7 presets, recorded from `/regular/items` (stat,
slot and conflict fields only, no offers).

## Conflict rules

`com.tarkovgunsmith.engine.Conflicts` decides whether two parts of a build (each a `Placement`: a
part and the slot it sits in, or the weapon at the root) can coexist. They conflict when one lists
the other in `conflictingItems`, lists the other's slot in `conflictingSlotIds` (that slot must stay
empty), or lists one of the other's categories or their ancestors in `conflictingCategories`. The
game data usually records a conflict on one side only (scopes list the reflex sights they block,
not the other way round), so every rule is checked in both directions. Weapons take part too: the
RSh-12 lists two scopes.

`Conflicts.conflictsWithAny(candidate, chosen)` is the check for adding a part to a build in
progress, and `Conflicts.find(build)` lists every conflicting pair of a finished build. In the live
data only gear (headsets, masks, mandibles) uses `conflictingCategories`, so `ConflictsTest` covers
that rule with a real gear pair (MSA riot gas mask vs AN/PVS-14 night vision).

## Stat calculator

`com.tarkovgunsmith.engine.StatCalculator.compute(...)` computes a build's `BuildStats` (SPEC §3)
from its weapon and mods: ergonomics = weapon ergonomics + Σ `ergonomicsModifier`, recoil
(vertical and horizontal) = weapon recoil × (1 + Σ `recoilModifier` / 100), weight = Σ weight
including the weapon. A mod used in two slots counts twice. Nothing is rounded or clamped.

`PresetStatsIT` validates it against every weapon preset in `src/test/resources/tarkovdev/presets/`
(all guns, their presets and every item they contain, recorded from `/regular/items`, stat fields
only): 395 presets covering all 161 imported weapons. Tolerances:

| Stat | Tolerance | Why |
|---|---|---|
| Ergonomics | 1e-6 | stored unrounded (e.g. 54.5) |
| Recoil | ±0.5 | stored rounded to a whole number; ties go either way (KBP VSK-94 Default: 60.5 computed, 60 stored) |
| Weight | 1e-6 | checked only when every part is a weapon or mod; the 21 presets with a loaded magazine also list the ammo, which isn't imported |

Exceptions: 26 presets list a mod twice (`count: 2`, e.g. two rail covers or flashlights) and
their stored ergonomics count that mod only once, although their stored weight counts both
copies. This is how tarkov.dev computes preset ergonomics; the game counts every attached mod, so
the engine does too. The test checks that each of these differs by exactly the duplicate copies'
`ergonomicsModifier` (e.g. SIG MPX Default, 2× rail cover at −0.1: stored 78.25, computed 78.15).
Recoil and weight match for all 395.

`./gradlew liveTest` includes `PresetStatsLiveTest`, which runs the same check against the current
live data, e.g. after a game patch.

## Build generator

`com.tarkovgunsmith.engine.BuildGenerator.generate(weapon, random)` returns one random valid
`Build` of a weapon (SPEC §4.1). It walks the slot tree depth-first from the weapon. Each slot gets
a candidate picked uniformly, and an optional slot can also stay empty, which is one more equally
likely option. A candidate that conflicts with a part already chosen (`Conflicts`) is skipped. A
chosen part's own slots are decided right after it. When a required slot has nothing left to try,
the walk backtracks to the previous slot and tries its next option.

Two limits keep a walk finite. Parts sit at most `MAX_DEPTH` (10) slots below the weapon: mounts
that take mounts could otherwise nest forever, and real builds don't come close. A walk also gives
up after 10,000 slot decisions and starts over, up to 10 times. If a walk exhausts every option
without running out of steps, no valid build exists and the result is empty. The generator is
stateless and thread-safe; give each thread its own random.

A `Build` lists its parts in tree order, starting with the weapon. Each part has a slot path made
of the slot `nameId`s from the weapon down, joined by `/`
(e.g. `mod_reciever/mod_handguard`); the weapon's path is `""`. `partsHash` is the
canonical dedup key: SHA-256 (hex) over the sorted `(slotPath, itemId)` pairs, weapon included.
`stats` comes from `StatCalculator`.

`BuildGeneratorIT` generates 10,000 builds for each of the 161 weapons and checks every build
independently of the generator (`BuildCheck`). In each build:

- every required slot is filled, recursively;
- every part is a candidate of its slot, and the slot belongs to the parent part;
- slot paths are unique;
- there are no conflicts;
- the hash and stats match the parts.

It runs on `src/test/resources/tarkovdev/all-guns/`: every gun and mod recorded from
`/regular/items`, with stat, slot and conflict fields only. `items.json` is gzipped (3.3 MB raw).
Every weapon produces valid builds. The run takes about 12 s for 1.61M builds on 4 cores.

## Build store

`V3__builds.sql` creates `build` (weapon, `parts_hash`, ergonomics, vertical and horizontal recoil,
weight, `created_at`) and `build_part` (one row per part, the weapon included with slot path `""`:
`slot_path`, `item_id`, `parent_path`). `parts_hash` is unique, so a build is stored at most once.
There are no foreign keys: builds are wiped as a whole when the game data changes, and the per-row
FK checks made part inserts about 3x slower.

`com.tarkovgunsmith.build.BuildRepository.insertAll(builds)` stores a batch in one transaction with
two statements (one per table), passing each column as an array expanded with `unnest`. Builds whose
hash is already stored, or repeated within the batch, are skipped (`ON CONFLICT DO NOTHING`); it
returns the builds it inserted with their new ids.

`./gradlew benchmark` measures insert throughput against a Testcontainers Postgres (161,000 builds
of all weapons, 12.3 parts each on average; 134,102 distinct). On 4 cores:

| Batch size | New builds | Duplicates only |
|---|---|---|
| 100 | 5,300 builds/s | 29,000 builds/s |
| 1,000 | 6,700 builds/s | 50,000 builds/s |
| 5,000 | 7,400 builds/s | 50,000 builds/s |

Most of the time goes into the `build_part` rows (about 80,000 rows/s).
