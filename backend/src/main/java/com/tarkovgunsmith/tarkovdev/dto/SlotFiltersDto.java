package com.tarkovgunsmith.tarkovdev.dto;

import java.util.List;

/** What may go into a slot: listed items and items in allowed categories, minus the excluded ones. */
public record SlotFiltersDto(
        List<String> allowedItems,
        List<String> allowedCategories,
        List<String> excludedItems,
        List<String> excludedCategories) {

    static final SlotFiltersDto EMPTY = new SlotFiltersDto(null, null, null, null);

    public SlotFiltersDto {
        allowedItems = ItemDto.copy(allowedItems);
        allowedCategories = ItemDto.copy(allowedCategories);
        excludedItems = ItemDto.copy(excludedItems);
        excludedCategories = ItemDto.copy(excludedCategories);
    }
}
