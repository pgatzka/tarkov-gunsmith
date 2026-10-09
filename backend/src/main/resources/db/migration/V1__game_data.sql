-- Game data imported from json.tarkov.dev (SPEC §2, §4.3).
-- Ids are the game's own 24-char hex ids.

-- Category tree (data.itemCategories); slot filters refer to these ids.
CREATE TABLE item_category (
    id              text PRIMARY KEY,
    name            text NOT NULL,
    normalized_name text NOT NULL,
    parent_id       text,
    updated_at      timestamptz NOT NULL DEFAULT now()
);

-- Weapons, mods and weapon presets. Stats never differ between modes, so items are mode-independent.
CREATE TABLE item (
    id                     text PRIMARY KEY,
    kind                   text             NOT NULL CHECK (kind IN ('WEAPON', 'MOD', 'PRESET')),
    -- WEAPON / PRESET: normalized name of the weapon's category, e.g. 'assault-rifle'
    weapon_class           text,
    name                   text             NOT NULL,
    short_name             text             NOT NULL,
    normalized_name        text             NOT NULL,
    types                  text[]           NOT NULL,
    -- the item's category and all its ancestors, most specific first
    categories             text[]           NOT NULL,
    no_flea                boolean          NOT NULL,
    weight                 double precision NOT NULL,
    -- WEAPON: base stats; PRESET: stats of the assembled weapon
    ergonomics             double precision,
    recoil_vertical        double precision,
    recoil_horizontal      double precision,
    -- MOD: what the mod adds to a weapon (recoil in percent)
    ergonomics_modifier    double precision NOT NULL,
    recoil_modifier        double precision NOT NULL,
    accuracy_modifier      double precision NOT NULL,
    -- [{id, nameId, name, required, filters{allowedItems, allowedCategories, excludedItems, excludedCategories}}]
    slots                  jsonb            NOT NULL,
    conflicting_items      text[]           NOT NULL,
    conflicting_slot_ids   text[]           NOT NULL,
    conflicting_categories text[]           NOT NULL,
    -- WEAPON: its default preset
    default_preset_id      text,
    -- PRESET: the weapon it is built on, and its parts [{item, count}]
    base_item_id           text,
    contains_items         jsonb            NOT NULL,
    icon_link              text,
    grid_image_link        text,
    base_image_link        text,
    updated_at             timestamptz      NOT NULL DEFAULT now()
);

CREATE INDEX item_kind_idx ON item (kind);

CREATE TABLE trader (
    id              text PRIMARY KEY,
    name            text        NOT NULL,
    normalized_name text        NOT NULL,
    image_link      text,
    max_level       int         NOT NULL,
    updated_at      timestamptz NOT NULL DEFAULT now()
);

-- Where an item can be bought, per game mode. Trader offers carry the trader and its required loyalty level.
CREATE TABLE offer (
    id         bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    item_id    text        NOT NULL REFERENCES item (id) ON DELETE CASCADE,
    mode       text        NOT NULL CHECK (mode IN ('REGULAR', 'PVE')),
    source     text        NOT NULL CHECK (source IN ('TRADER', 'FLEA')),
    trader_id  text REFERENCES trader (id) ON DELETE CASCADE,
    min_level  int,
    price_rub  int         NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    CHECK ((source = 'TRADER') = (trader_id IS NOT NULL AND min_level IS NOT NULL))
);

CREATE INDEX offer_item_mode_idx ON offer (item_id, mode);

-- Small key/value store for sync state: ETags of the last fetched payloads, the structure hash (SPEC §4.2), ...
CREATE TABLE data_version (
    key        text PRIMARY KEY,
    value      text        NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now()
);
