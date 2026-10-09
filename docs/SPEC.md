# Tarkov Gunsmith — Product & Technical Spec (v1)

## 1. Product

A public website for Escape from Tarkov players. The user does **not** assemble a weapon.
Instead, a background service continuously generates random valid weapon builds and stores
them. The user picks a weapon, sets filters, and gets a sorted list of matching builds.

Example: *MP5, ergo > 75, vertical recoil < 30, Mechanic LL2 / Peacekeeper LL1, no flea → sorted by cost.*

### 1.1 Scope (v1)

| Area | Decision |
|---|---|
| Audience | Public website, anonymous (no accounts). |
| Game modes | PvP (`regular`) and PvE (`pve`), toggled by the user. |
| Weapons | All guns except launchers / special weapons (grenade launchers, flare guns, etc.). |
| Generation | Pure random sampling of valid builds, running continuously in the background. |
| Valid build | Every slot marked `required` in the game data is filled (recursively), and there are no conflicts. Optional slots (including the magazine, if not marked required) are filled randomly. |
| Results | A paginated list sorted by one user-selected stat. |
| No match | Show "No builds found" and suggest loosening the filters. |
| Build view | Parts tree (slot → item, nested), shopping list (where to buy each part and the cost), item images. |
| Patch handling | Auto-detect game data changes, then wipe and regenerate. |
| Hosting | Docker Compose on a single VPS. |

### 1.2 Filters

- **Weapon** (required)
- **Game mode**: PvP / PvE
- **Ergonomics**: min / max
- **Vertical recoil**: min / max
- **Horizontal recoil**: min / max
- **Cost**: min / max (₽, computed for the user's purchase context, see 1.3)
- **Included parts**: the build must contain all of these items
- **Excluded parts**: the build must contain none of these items
- **Trader levels**: per trader (Prapor, Therapist, Skier, Peacekeeper, Mechanic, Ragman, Jaeger, Ref), each 0–4 (0 = no access)
- **Flea**: yes / no

Sort: ergo, vertical recoil, horizontal recoil, cost, weight — ascending or descending.

### 1.3 Purchase availability & cost

Each part's price is resolved against the user's purchase context:

- **Trader cash offers** count if `minTraderLevel ≤` the user's level for that trader **and** the offer is not quest-locked (`taskUnlock == null`). Barters and quest-locked offers are out of scope for v1.
- **Flea** (only when the flea toggle is on): `lastLowPrice` (falling back to `avg24hPrice`); items with type `noFlea` are never available on the flea.
- Part price = the cheapest available source. **A build matches only if every part is available.**
- Build cost = the sum of the part prices. The weapon's base receiver is a part too.
- Prices are refreshed regularly (every 10 min, the data source cadence), so cost is always current. Build stats never change between wipes.

## 2. Data source

`https://json.tarkov.dev` (no key, no documented rate limit; supports ETag / 304 revalidation).
The GraphQL API at `api.tarkov.dev/graphql` currently returns "GraphQL server unavailable", so it is not used.

| Endpoint | Used for |
|---|---|
| `/{mode}/items` | Weapons, mods, slots, conflicts, modifiers, weight, `buyFromTrader`, flea prices, `noFlea` |
| `/{mode}/items_en` | Translation map (names are translation keys in the base payload) |
| `/{mode}/traders` | Trader names and levels |

`mode` ∈ `regular`, `pve`. Relevant fields:

- Weapon: `types` contains `gun`, `properties.propertiesType == "ItemPropertiesWeapon"`; base `ergonomics`, `recoilVertical`, `recoilHorizontal`.
- Slots: `properties.slots[] {nameId, required, filters{allowedItems, allowedCategories, excludedItems, excludedCategories}}` on weapons and mods, walked recursively. Categories are resolved via the item `categories` and the `data.itemCategories` tree.
- Conflicts: `conflictingItems[]`, `conflictingSlotIds[]`, `conflictingCategories[]`.
- Modifiers: `ergonomicsModifier`, `recoilModifier` (percent), `accuracyModifier`, `weight`.
- Offers: `buyFromTrader[] {trader, priceRUB, minTraderLevel, taskUnlock}`; flea `lastLowPrice`, `avg24hPrice`.

## 3. Stat model

Final stats are computed the way the game (and tarkov.dev) does:

- `ergo = weapon.ergonomics + Σ part.ergonomicsModifier`
- `recoilV = weapon.recoilVertical × (1 + Σ part.recoilModifier / 100)`, and the same for horizontal
- `weight = Σ weight` of every item, including the weapon

The engine is validated against the `preset` items in the data: their stored `ergonomics` and `recoilVertical`/`recoilHorizontal` must match the stats the engine computes for the same parts.

## 4. Architecture

```
docker-compose
├── postgres          builds, parts, items, offers, data version
├── backend (Spring Boot 3, Java 21)
│   ├── DataSync      pulls json.tarkov.dev every 10 min (ETag); updates prices/offers;
│   │                 computes the "structure hash" and triggers a wipe when it changes
│   ├── Generator     background workers producing random valid builds
│   ├── QueryEngine   per-weapon in-memory columnar index for fast filtering and sorting
│   └── REST API      /api/weapons, /api/items, /api/builds/search, /api/builds/{id}
└── frontend (React + Vite + TypeScript), served by nginx
```

### 4.1 Generator

- Workers run round-robin over the enabled weapons, favouring weapons with the fewest builds.
- A random build is a recursive walk: for each slot, pick an item uniformly from the compatible items (or "empty" if the slot is optional), skipping candidates that conflict with parts already chosen. Required slots never get "empty"; the walk backtracks on a dead end.
- Dedup: the canonical key is a hash of the sorted `(slotPath, itemId)` list, with a unique constraint.
- **Per-weapon cap**, configurable (`gunsmith.generator.cap-per-weapon`, default 200000; `-1` = unlimited). Once a weapon is at its cap, a new build replaces a stored build that it dominates on ergo, vRecoil, hRecoil and weight (cost is excluded because it depends on the user's context). If it dominates nothing, it is discarded.
- Thread count and throughput can be configured so the generator doesn't starve the API.

### 4.2 Patch detection / wipe

On each sync, a SHA-256 is computed over the stat-relevant data of every weapon and mod (stats, modifiers, slots, filters, conflicts), using regular-mode data only, because stats are identical across modes.
If the hash differs from the stored one: in a single transaction, truncate the builds, store the new hash, rebuild the item graph, and restart the generator. Price and offer changes do not affect the hash.

### 4.3 Storage

- `item` (id, name, shortName, iconLink, stats/modifiers, slot JSON)
- `offer` (itemId, mode, source = TRADER|FLEA, traderId, minLevel, priceRub)
- `build` (id, weaponId, partsHash, ergo, recoilV, recoilH, weight, createdAt)
- `build_part` (buildId, slotPath, itemId, parentPath)

Builds are shared between PvP and PvE; only availability and cost differ by mode.

### 4.4 Query engine

Cost and availability depend on the user's trader levels and flea toggle, so they can't be precomputed per build. Instead:

1. For the request, build `price[itemId]` for the user's context in memory (about 5k items; cheapest allowed source, or "unavailable").
2. Scan the selected weapon's builds in an in-memory columnar index (stat arrays plus part-id arrays, loaded from Postgres and kept up to date by the generator). Apply the stat filters, then the include/exclude filters, then availability and cost.
3. Sort, then paginate.

Scanning about 200k builds × about 15 parts is a few milliseconds. With `cap = -1`, memory grows without bound; this caveat is documented.

### 4.5 API (sketch)

- `GET /api/weapons` — enabled weapons with build counts
- `GET /api/items?weaponId=` — parts that appear in builds for this weapon (for the include/exclude pickers)
- `GET /api/traders?mode=`
- `POST /api/builds/search` — `{weaponId, mode, ergo{min,max}, recoilV{..}, recoilH{..}, cost{..}, include[], exclude[], traderLevels{traderId: lvl}, flea, sort, dir, page, size}`
- `GET /api/builds/{id}?mode=&traderLevels=&flea=` — parts tree plus a shopping list resolved for that context

## 5. Frontend

- Weapon picker (searchable, with icons) → filter panel (sliders/inputs, trader level selectors, flea toggle, PvP/PvE toggle, part include/exclude multiselects) → result list (stats, cost, part icons).
- Build detail: a nested parts tree with icons, and a shopping list (part → trader + LL or flea → price), plus the total.
- Filters are kept in the URL query string. The last trader levels are remembered in `localStorage`.

## 6. Out of scope for v1

Accounts, saved builds, shareable build links, barters, quest-locked offers, Pareto view, targeted generation on demand, ammo selection, accuracy/MOA filters.
