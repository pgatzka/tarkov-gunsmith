package com.tarkovgunsmith.tarkovdev.dto;

import java.util.LinkedHashMap;
import java.util.Map;

/** {@code /{mode}/items}. Both maps are keyed by id. */
public record ItemsPayload(Data data) {

    public record Data(Map<String, ItemDto> items, Map<String, ItemCategoryDto> itemCategories) {

        public Data {
            items = items == null ? Map.of() : items;
            itemCategories = itemCategories == null ? Map.of() : itemCategories;
        }
    }

    public Map<String, ItemDto> items() {
        return data.items();
    }

    public Map<String, ItemCategoryDto> itemCategories() {
        return data.itemCategories();
    }

    /** A copy with item, slot and category names resolved through {@code translations}. */
    public ItemsPayload translate(Translations translations) {
        Map<String, ItemDto> items = new LinkedHashMap<>();
        data.items().forEach((id, item) -> items.put(id, item.translate(translations)));
        Map<String, ItemCategoryDto> categories = new LinkedHashMap<>();
        data.itemCategories().forEach((id, category) -> categories.put(id, category.translate(translations)));
        return new ItemsPayload(new Data(items, categories));
    }
}
