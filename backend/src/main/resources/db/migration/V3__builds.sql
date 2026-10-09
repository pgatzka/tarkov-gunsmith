-- Generated builds (SPEC §4.1, §4.3). Shared between PvP and PvE: only cost and availability
-- depend on the mode, and those are resolved per request.
-- No foreign keys: builds are wiped as a whole when the stat-relevant game data changes (SPEC §4.2),
-- and BuildRepository writes a build and its parts together. The per-row FK checks would make the
-- generator's part inserts about 3x slower.
CREATE TABLE build (
    id                bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    weapon_id         text             NOT NULL,
    -- canonical key: SHA-256 (hex) over the sorted (slotPath, itemId) pairs, see Build.partsHash
    parts_hash        text             NOT NULL,
    ergonomics        double precision NOT NULL,
    recoil_vertical   double precision NOT NULL,
    recoil_horizontal double precision NOT NULL,
    weight            double precision NOT NULL,
    created_at        timestamptz      NOT NULL DEFAULT now(),
    CONSTRAINT build_parts_hash_key UNIQUE (parts_hash)
);

CREATE INDEX build_weapon_idx ON build (weapon_id);

-- Every part of a build, the weapon included (slot_path '' and no parent).
CREATE TABLE build_part (
    build_id    bigint NOT NULL,
    -- slot nameIds from the weapon down, joined by '/', e.g. 'mod_reciever/mod_handguard'
    slot_path   text   NOT NULL,
    item_id     text   NOT NULL,
    parent_path text,
    PRIMARY KEY (build_id, slot_path)
);
