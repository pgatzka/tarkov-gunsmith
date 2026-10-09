package com.tarkovgunsmith.tarkovdev.dto;

import java.util.List;

/** Node of the item category tree ({@code data.itemCategories}); the root has an empty parent. */
public record ItemCategoryDto(String id, String name, String normalizedName, String parent, List<String> children) {

    public ItemCategoryDto {
        parent = parent == null || parent.isEmpty() ? null : parent;
        children = children == null ? List.of() : List.copyOf(children);
    }

    ItemCategoryDto translate(Translations translations) {
        return new ItemCategoryDto(id, translations.resolve(name), normalizedName, parent, children);
    }
}
