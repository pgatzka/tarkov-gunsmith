package com.tarkovgunsmith.engine;

import com.tarkovgunsmith.gamedata.ItemKind;
import java.util.List;
import java.util.Set;

/**
 * A weapon or mod as the build engine sees it: its categories, stats, slots and conflicts.
 *
 * @param categories the item's categories as stored; ancestors are added from the category tree
 * @param ergonomics base ergonomics of a weapon; {@code null} for mods
 * @param recoilVertical base vertical recoil of a weapon; {@code null} for mods
 * @param recoilHorizontal base horizontal recoil of a weapon; {@code null} for mods
 * @param recoilModifier what a mod adds to the weapon's recoil, in percent
 */
public record GraphItem(
        String id,
        ItemKind kind,
        List<String> categories,
        double weight,
        Double ergonomics,
        Double recoilVertical,
        Double recoilHorizontal,
        double ergonomicsModifier,
        double recoilModifier,
        List<SlotDefinition> slots,
        Set<String> conflictingItems,
        Set<String> conflictingSlotIds,
        Set<String> conflictingCategories) {

    public GraphItem {
        categories = List.copyOf(categories);
        slots = List.copyOf(slots);
        conflictingItems = Set.copyOf(conflictingItems);
        conflictingSlotIds = Set.copyOf(conflictingSlotIds);
        conflictingCategories = Set.copyOf(conflictingCategories);
    }

    /**
     * A slot as defined in the game data.
     *
     * @param id what {@code conflictingSlotIds} refers to; unique per item
     * @param nameId stable across items, e.g. {@code mod_magazine}
     */
    public record SlotDefinition(String id, String nameId, String name, boolean required, SlotFilter filter) {}
}
