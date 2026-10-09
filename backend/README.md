# Backend

Spring Boot 3 / Java 21 / Gradle (Kotlin DSL), Postgres + Flyway, Spring Data JDBC, Actuator.

## Run

```sh
./gradlew test          # unit + integration tests (needs Docker for Testcontainers)
./gradlew bootRun       # against a local Postgres
./gradlew bootTestRun   # against a throwaway Postgres container
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
