package com.tarkovgunsmith.engine;

import com.tarkovgunsmith.gamedata.ItemKind;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Which items fit where (SPEC §2): weapon → slots → allowed mods → their slots, and so on.
 *
 * <p>Every weapon and mod is one {@link Part}, shared by all slots that accept it, so the graph is
 * built once and walked recursively from any weapon. A slot's candidates are its {@code
 * allowedItems} plus every mod in one of its {@code allowedCategories} (or a descendant category),
 * minus its {@code excludedItems} and mods in an {@code excludedCategories} category. Only mods are
 * candidates; ids that aren't a stored mod (presets, gear that was not imported, ...) are ignored.
 *
 * <p>The graph is immutable and safe to share between threads.
 */
public final class CompatibilityGraph {

    private final Map<String, Part> parts;
    private final List<Part> weapons;

    private CompatibilityGraph(Map<String, Part> parts) {
        this.parts = Collections.unmodifiableMap(parts);
        this.weapons = parts.values().stream().filter(Part::isWeapon).toList();
    }

    /** Builds the graph from all weapons and mods; other kinds are skipped. */
    public static CompatibilityGraph build(CategoryTree categories, Collection<GraphItem> items) {
        Map<String, Part> parts = new LinkedHashMap<>();
        Map<String, List<Part>> modsByCategory = new HashMap<>();
        for (GraphItem item : items) {
            if (item.kind() != ItemKind.WEAPON && item.kind() != ItemKind.MOD) {
                continue;
            }
            Part part = new Part(item, categories.withAncestors(item.categories()));
            parts.put(item.id(), part);
            if (part.isMod()) {
                part.categories().forEach(c -> modsByCategory.computeIfAbsent(c, k -> new ArrayList<>()).add(part));
            }
        }
        for (Part part : parts.values()) {
            part.slots = part.item.slots().stream()
                    .map(definition -> new Slot(part, definition, candidates(definition.filter(), parts, modsByCategory)))
                    .toList();
        }
        return new CompatibilityGraph(parts);
    }

    private static List<Part> candidates(SlotFilter filter, Map<String, Part> parts, Map<String, List<Part>> modsByCategory) {
        Set<Part> candidates = new LinkedHashSet<>();
        for (String id : filter.allowedItems()) {
            Part part = parts.get(id);
            if (part != null && part.isMod()) {
                candidates.add(part);
            }
        }
        for (String category : filter.allowedCategories()) {
            candidates.addAll(modsByCategory.getOrDefault(category, List.of()));
        }
        candidates.removeIf(part -> filter.excludes(part.id(), part.categories()));
        return List.copyOf(candidates);
    }

    public Optional<Part> part(String id) {
        return Optional.ofNullable(parts.get(id));
    }

    /** The weapon with this id, if it is a stored (not excluded) weapon. */
    public Optional<Part> weapon(String id) {
        return part(id).filter(Part::isWeapon);
    }

    public List<Part> weapons() {
        return weapons;
    }

    /** Number of weapons and mods. */
    public int size() {
        return parts.size();
    }

    /** {@code root} and every mod that can end up somewhere below it. */
    public static Set<Part> reachable(Part root) {
        Set<Part> seen = new LinkedHashSet<>();
        Deque<Part> todo = new ArrayDeque<>(List.of(root));
        while (!todo.isEmpty()) {
            Part part = todo.poll();
            if (seen.add(part)) {
                part.slots().forEach(slot -> todo.addAll(slot.candidates()));
            }
        }
        return seen;
    }

    /** A weapon or mod in the graph. Identity is the item id. */
    public static final class Part {

        private final GraphItem item;
        private final Set<String> categories;
        private List<Slot> slots = List.of();

        private Part(GraphItem item, Set<String> categories) {
            this.item = item;
            this.categories = Collections.unmodifiableSet(categories);
        }

        public String id() {
            return item.id();
        }

        /** Stats, slot definitions and conflicts as stored. */
        public GraphItem item() {
            return item;
        }

        /** The item's categories and all of their ancestors. */
        public Set<String> categories() {
            return categories;
        }

        public List<Slot> slots() {
            return slots;
        }

        public boolean isWeapon() {
            return item.kind() == ItemKind.WEAPON;
        }

        public boolean isMod() {
            return item.kind() == ItemKind.MOD;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Part other && id().equals(other.id());
        }

        @Override
        public int hashCode() {
            return id().hashCode();
        }

        @Override
        public String toString() {
            return "Part[" + id() + "]";
        }
    }

    /** A slot of a {@link Part} with the mods that fit into it. */
    public static final class Slot {

        private final Part owner;
        private final GraphItem.SlotDefinition definition;
        private final List<Part> candidates;

        private Slot(Part owner, GraphItem.SlotDefinition definition, List<Part> candidates) {
            this.owner = owner;
            this.definition = definition;
            this.candidates = candidates;
        }

        /** The weapon or mod this slot belongs to. */
        public Part owner() {
            return owner;
        }

        /** What {@code conflictingSlotIds} refers to. */
        public String id() {
            return definition.id();
        }

        /** E.g. {@code mod_magazine}. */
        public String nameId() {
            return definition.nameId();
        }

        public String name() {
            return definition.name();
        }

        /** Must be filled in a valid build. */
        public boolean required() {
            return definition.required();
        }

        /** The mods that fit, in the order the data lists them. */
        public List<Part> candidates() {
            return candidates;
        }

        public boolean accepts(Part part) {
            return candidates.contains(part);
        }

        @Override
        public String toString() {
            return "Slot[" + owner.id() + "/" + nameId() + "]";
        }
    }
}
