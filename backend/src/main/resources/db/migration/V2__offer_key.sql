-- One row per purchase source, so offer imports can upsert prices in place (SPEC §1.3).
-- Flea rows have no trader and level; NULLS NOT DISTINCT keeps them unique per item and mode.
CREATE UNIQUE INDEX offer_source_key ON offer (item_id, mode, source, trader_id, min_level) NULLS NOT DISTINCT;

DROP INDEX offer_item_mode_idx;
