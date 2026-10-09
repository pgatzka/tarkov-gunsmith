package com.tarkovgunsmith.engine;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** The item category tree ({@code item_category}): each category's parent, {@code null} for the root. */
public final class CategoryTree {

    private final Map<String, String> parents;

    /** @param parents parent id by category id; roots map to {@code null} or are left out */
    public CategoryTree(Map<String, String> parents) {
        Map<String, String> copy = new HashMap<>();
        parents.forEach((id, parent) -> {
            if (parent != null) {
                copy.put(id, parent);
            }
        });
        this.parents = Map.copyOf(copy);
    }

    /** {@code categories} and all of their ancestors, in the given order, each followed by its ancestors. */
    public Set<String> withAncestors(Collection<String> categories) {
        Set<String> result = new LinkedHashSet<>();
        for (String category : categories) {
            // stop at a category already seen: its ancestors are in, and a malformed cycle ends here
            for (String c = category; c != null && result.add(c); c = parents.get(c)) {}
        }
        return result;
    }
}
