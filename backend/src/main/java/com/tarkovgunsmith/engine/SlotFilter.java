package com.tarkovgunsmith.engine;

import java.util.Collection;
import java.util.List;

/**
 * What may go into a slot (SPEC §2): the listed items and items in an allowed category, minus the
 * excluded items and items in an excluded category. An exclusion always wins over an allowance.
 */
public record SlotFilter(
        List<String> allowedItems,
        List<String> allowedCategories,
        List<String> excludedItems,
        List<String> excludedCategories) {

    public SlotFilter {
        allowedItems = List.copyOf(allowedItems);
        allowedCategories = List.copyOf(allowedCategories);
        excludedItems = List.copyOf(excludedItems);
        excludedCategories = List.copyOf(excludedCategories);
    }

    /**
     * Whether an item fits.
     *
     * @param categories the item's category and all of its ancestors
     */
    public boolean allows(String itemId, Collection<String> categories) {
        boolean allowed = allowedItems.contains(itemId) || categories.stream().anyMatch(allowedCategories::contains);
        return allowed && !excludes(itemId, categories);
    }

    boolean excludes(String itemId, Collection<String> categories) {
        return excludedItems.contains(itemId) || categories.stream().anyMatch(excludedCategories::contains);
    }
}
