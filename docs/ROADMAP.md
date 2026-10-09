# Roadmap

Implementation plan for [SPEC.md](SPEC.md). Each task is sized for **one session**.

**How to use**
- Start a session with: *"Do task X.Y from docs/ROADMAP.md"*.
- Each task lists what it depends on (**Depends**) and when it is done (**Done when**).
- When a task is finished, tick its box `[x]` in the same commit as the work, and add the date / PR link after it.
- Don't start a task before all of its dependencies are ticked.

Status legend: `[ ]` open · `[x]` done

---

## M0 — Foundation

Goal: the empty skeleton runs with one `docker compose up`.

- [x] **0.1 Backend skeleton** — 2026-10-09, [#1](https://github.com/pgatzka/tarkov-gunsmith/pull/1)
  - What: Spring Boot 3 / Java 21 / Gradle (Kotlin DSL) project in `backend/`. Includes Flyway, Spring Data JDBC or JPA, Postgres driver, Actuator and a health endpoint. Testcontainers is set up for integration tests.
  - Depends: —
  - Done when: `./gradlew test` passes, and `GET /actuator/health` returns UP against a local Postgres.
- [x] **0.2 Frontend skeleton** — 2026-10-09, [#2](https://github.com/pgatzka/tarkov-gunsmith/pull/2)
  - What: React + Vite + TypeScript in `frontend/`, with ESLint/Prettier, a router and an API client stub. The dev proxy points to the backend.
  - Depends: —
  - Done when: `npm run build` and `npm run lint` pass, and the dev server shows a placeholder page.
- [x] **0.3 Docker Compose** — 2026-10-09
  - What: `docker-compose.yml` with postgres, backend (Dockerfile), and frontend served by nginx with `/api` proxied to the backend. Adds `.env.example` and a README section explaining how to run it.
  - Depends: 0.1, 0.2
  - Done when: `docker compose up --build` serves the placeholder page, and `/api/actuator/health` returns UP through nginx.
- [ ] **0.4 CI**
  - What: GitHub Actions running backend tests, frontend lint and build, and docker build.
  - Depends: 0.3
  - Done when: CI is green on a PR.

## M1 — Game data

Goal: all weapons, mods, traders and offers from json.tarkov.dev are in Postgres and kept fresh.

- [ ] **1.1 json.tarkov.dev client**
  - What: an HTTP client for `/{mode}/items`, `/{mode}/items_en`, `/{mode}/traders` (modes `regular` and `pve`). Supports ETag/304 and brotli/gzip, and resolves translation keys. Typed DTOs for the fields listed in SPEC §2.
  - Depends: 0.1
  - Done when: unit tests pass against recorded fixture JSON (trimmed samples committed under test resources), and there is a manual smoke test against the live API.
- [ ] **1.2 Schema & item import**
  - What: Flyway migrations for `item`, `trader`, `offer` and `data_version`. Importer that stores items with stats, modifiers, slots and conflicts, and classifies weapons. Launchers and special weapons are excluded through a configurable list or category.
  - Depends: 1.1
  - Done when: an import from fixtures populates the tables, and the excluded weapons are absent.
- [ ] **1.3 Offers & prices**
  - What: imports trader cash offers that are not quest-locked (with `minTraderLevel`) and flea prices (`lastLowPrice`, falling back to `avg24hPrice`; `noFlea` items are skipped), per mode.
  - Depends: 1.2
  - Done when: an MP5 receiver has the expected trader and flea offers for both PvP and PvE in a test.
- [ ] **1.4 Scheduled sync**
  - What: `DataSync` job that runs every 10 minutes and on startup. It upserts items, offers and prices, and logs what changed.
  - Depends: 1.3
  - Done when: a second sync with unchanged data does no writes (304 / no-op), and changed prices are updated.

## M2 — Build engine

Goal: correct compatibility, conflict handling and stat computation, verified against the game's presets.

- [ ] **2.1 Compatibility graph**
  - What: in-memory graph of weapon → slots → allowed items → their slots (recursive). Handles `allowedCategories`/`excludedCategories` via the category tree, and `excludedItems`.
  - Depends: 1.2
  - Done when: tests show the MP5's slot tree and allowed items, and category-based filters resolve correctly.
- [ ] **2.2 Conflict rules**
  - What: implements `conflictingItems`, `conflictingSlotIds` and `conflictingCategories` (both directions).
  - Depends: 2.1
  - Done when: unit tests cover each conflict type with real item pairs.
- [ ] **2.3 Stat calculator**
  - What: computes ergo, vRecoil, hRecoil and weight per SPEC §3.
  - Depends: 2.1
  - Done when: computed stats match the `preset` items' stored stats for every weapon (tolerance documented), and any exceptions are listed and explained.
- [ ] **2.4 Random build generator (single build)**
  - What: random recursive walk. Every required slot is filled, optional slots are filled randomly, conflicting candidates are skipped, and the walk backtracks on dead ends. Produces the canonical parts hash.
  - Depends: 2.2, 2.3
  - Done when: a property test generating 10k builds per weapon finds every build valid (required slots filled, no conflicts, every item allowed in its slot). Weapons that can't produce any valid build are reported.

## M3 — Build store & background generation

Goal: builds accumulate continuously, respecting the cap and the patch wipes.

- [ ] **3.1 Build persistence**
  - What: migrations for `build` and `build_part` (with a unique `partsHash`), and a batch insert repository.
  - Depends: 2.4
  - Done when: duplicates are rejected, and batch insert throughput has been measured and noted in the PR.
- [ ] **3.2 Background generator workers**
  - What: configurable thread pool, round-robin over weapons that favours those with the fewest builds, batched writes. Config: `threads`, `batch-size`, `enabled`.
  - Depends: 3.1
  - Done when: the build count grows over time for every weapon in a running app, and the generator can be disabled from config.
- [ ] **3.3 Per-weapon cap & dominance replacement**
  - What: `cap-per-weapon` (default 200000, `-1` = unlimited). At the cap, a new build replaces a stored build that it dominates on ergo, vRecoil, hRecoil and weight; otherwise it is discarded.
  - Depends: 3.2
  - Done when: tests show the cap is never exceeded, dominated builds get replaced, and `-1` imposes no limit.
- [ ] **3.4 Patch detection & wipe**
  - What: structure hash per SPEC §4.2. When the hash changes during sync: pause the generator, truncate builds in a transaction, rebuild the graph, store the hash, and resume.
  - Depends: 1.4, 3.2
  - Done when: a test where only prices change leaves builds intact, and a test where stats or slots change wipes them and generation restarts.

## M4 — Query engine & API

Goal: fast filtered search with costs that depend on the user's purchase context.

- [ ] **4.1 In-memory columnar index**
  - What: per-weapon arrays of stats and part ids. Loaded from Postgres on startup, appended and replaced by the generator, cleared on wipe.
  - Depends: 3.3, 3.4
  - Done when: the index stays consistent with the DB after inserts, replacements and a wipe (tests).
- [ ] **4.2 Price context resolver**
  - What: takes mode, per-trader LL and the flea toggle, and returns `price[itemId]` (cheapest allowed source, or unavailable) along with the source used.
  - Depends: 1.3
  - Done when: unit tests cover LL gating, the flea toggle, `noFlea` items, and choosing the cheapest source.
- [ ] **4.3 Search**
  - What: `POST /api/builds/search`. Filters on stat ranges, include/exclude parts, availability and cost range. Sorts by ergo, vRecoil, hRecoil, cost or weight in either direction, with pagination.
  - Depends: 4.1, 4.2
  - Done when: integration tests cover each filter, and p95 latency on 200k builds is under 100 ms (benchmark noted in the PR).
- [ ] **4.4 Build detail**
  - What: `GET /api/builds/{id}` with the price context in the query string. Returns the nested parts tree with icons, and a shopping list (part → trader + LL or flea → price) with the total.
  - Depends: 4.2
  - Done when: an integration test checks the tree structure and the shopping list total.
- [ ] **4.5 Reference endpoints**
  - What: `GET /api/weapons` (with build counts and icons), `GET /api/items?weaponId=` (parts used for this weapon), `GET /api/traders?mode=`. Includes OpenAPI docs.
  - Depends: 4.1
  - Done when: endpoints are documented, tested and reachable through nginx.

## M5 — Frontend

Goal: a usable public UI on top of the API.

- [ ] **5.1 Weapon picker**
  - What: searchable grid or list with icons and build counts, plus a PvP/PvE toggle.
  - Depends: 0.2, 4.5
  - Done when: selecting a weapon navigates to its search page.
- [ ] **5.2 Filter panel**
  - What: min/max inputs for ergo, vRecoil, hRecoil and cost; LL selectors for each trader; flea toggle; include/exclude part pickers with icons. State is synced to the URL, and trader levels are remembered in `localStorage`.
  - Depends: 5.1
  - Done when: a reload keeps the filters, and a pasted URL restores them.
- [ ] **5.3 Results list**
  - What: sortable, paginated list showing stats, cost and part icons. Shows an empty state ("No builds found — try loosening filters") and loading/error states.
  - Depends: 5.2, 4.3
  - Done when: the full flow works end to end against the local stack.
- [ ] **5.4 Build detail page**
  - What: nested parts tree with icons and slot names, and a shopping list with sources, LL and prices, plus the total.
  - Depends: 5.3, 4.4
  - Done when: it matches the API data, and changing trader levels or flea updates the shopping list.
- [ ] **5.5 Polish & responsive**
  - What: dark Tarkov-style theme, mobile layout, data freshness indicator ("prices updated X min ago", build counts).
  - Depends: 5.4
  - Done when: it is usable at phone width with no horizontal scroll.

## M6 — Production readiness

Goal: running on a VPS, observable and safe for public traffic.

- [ ] **6.1 Production compose & config**
  - What: production compose override, env-based config, Postgres volume and backups, resource limits for the generator vs the API, TLS via Caddy or Traefik.
  - Depends: M5
  - Done when: a fresh VPS deploy following the README works.
- [ ] **6.2 Observability**
  - What: structured logs; metrics (builds/s, builds per weapon, sync status, search latency) through Actuator and Prometheus; admin status endpoint.
  - Depends: 6.1
  - Done when: metrics are visible and sync failures are logged clearly.
- [ ] **6.3 Hardening**
  - What: rate limiting on the search API, input validation and size limits, caching headers on reference endpoints, and a degraded mode when json.tarkov.dev is unreachable (serve the last known data).
  - Depends: 6.1
  - Done when: tests or manual checks for each item are documented in the PR.

---

## Milestone overview

| Milestone | Outcome | Tasks |
|---|---|---|
| M0 Foundation | Skeleton runs via Docker Compose | 0.1–0.4 |
| M1 Game data | Items, offers and prices synced | 1.1–1.4 |
| M2 Build engine | Valid random builds with correct stats | 2.1–2.4 |
| M3 Build store | Continuous generation, cap, wipe on patch | 3.1–3.4 |
| M4 Query & API | Fast filtered search and build detail | 4.1–4.5 |
| M5 Frontend | Public UI | 5.1–5.5 |
| M6 Production | Deployed and observable | 6.1–6.3 |

Tasks that can run in parallel: 0.1 ∥ 0.2; 2.2 ∥ 2.3; 4.2 can start right after 1.3; 4.4 ∥ 4.3; 4.5 ∥ 4.3.
