package com.tarkovgunsmith.tarkovdev.dto;

/**
 * A mod slot. {@code id} is what {@code conflictingSlotIds} refers to; {@code nameId} (e.g. {@code
 * mod_magazine}) is stable across items, and {@code name} is a translation key.
 */
public record SlotDto(String id, String nameId, String name, boolean required, SlotFiltersDto filters) {

    public SlotDto {
        filters = filters == null ? SlotFiltersDto.EMPTY : filters;
    }

    SlotDto translate(Translations t) {
        return new SlotDto(id, nameId, t.resolve(name), required, filters);
    }
}
