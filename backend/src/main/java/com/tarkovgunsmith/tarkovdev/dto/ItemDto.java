package com.tarkovgunsmith.tarkovdev.dto;

import java.util.List;

/**
 * An item from {@code /{mode}/items}, reduced to the fields the app uses.
 *
 * <p>{@code ergonomicsModifier} and {@code recoilModifier} (percent) are what a mod adds to a
 * weapon. A gun's own base stats are in {@link ItemPropertiesDto}; on a gun, {@code
 * ergonomicsModifier} just repeats its base ergonomics.
 */
public record ItemDto(
        String id,
        String name,
        String shortName,
        String normalizedName,
        List<String> types,
        List<String> categories,
        Double weight,
        Double ergonomicsModifier,
        Double recoilModifier,
        Double accuracyModifier,
        List<String> conflictingItems,
        List<String> conflictingSlotIds,
        List<String> conflictingCategories,
        List<TraderOfferDto> buyFromTrader,
        Integer lastLowPrice,
        Integer avg24hPrice,
        String iconLink,
        String gridImageLink,
        String baseImageLink,
        List<ContainedItemDto> containsItems,
        ItemPropertiesDto properties) {

    public ItemDto {
        types = copy(types);
        categories = copy(categories);
        conflictingItems = copy(conflictingItems);
        conflictingSlotIds = copy(conflictingSlotIds);
        conflictingCategories = copy(conflictingCategories);
        buyFromTrader = copy(buyFromTrader);
        containsItems = copy(containsItems);
    }

    public boolean isGun() {
        return types.contains("gun");
    }

    /** A gun with weapon properties (base stats and slots). */
    public boolean isWeapon() {
        return isGun() && properties != null && ItemPropertiesDto.WEAPON.equals(properties.propertiesType());
    }

    public boolean isPreset() {
        return types.contains("preset");
    }

    /** Can't be bought on the flea market. */
    public boolean isNoFlea() {
        return types.contains("noFlea");
    }

    /** Slots this item offers to mods; empty for items without any. */
    public List<SlotDto> slots() {
        return properties == null ? List.of() : properties.slots();
    }

    ItemDto translate(Translations t) {
        return new ItemDto(
                id,
                t.resolve(name),
                t.resolve(shortName),
                normalizedName,
                types,
                categories,
                weight,
                ergonomicsModifier,
                recoilModifier,
                accuracyModifier,
                conflictingItems,
                conflictingSlotIds,
                conflictingCategories,
                buyFromTrader,
                lastLowPrice,
                avg24hPrice,
                iconLink,
                gridImageLink,
                baseImageLink,
                containsItems,
                properties == null ? null : properties.translate(t));
    }

    static <T> List<T> copy(List<T> list) {
        return list == null ? List.of() : List.copyOf(list);
    }
}
