package com.tarkovgunsmith.gamedata;

import com.tarkovgunsmith.tarkovdev.dto.ItemCategoryDto;
import com.tarkovgunsmith.tarkovdev.dto.ItemDto;
import com.tarkovgunsmith.tarkovdev.dto.ItemsPayload;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Decides which items of an items payload are imported and as what.
 *
 * <ul>
 *   <li>{@link ItemKind#WEAPON}: guns with weapon properties, minus the configured exclusions
 *   <li>{@link ItemKind#MOD}: items of type {@code mods}
 *   <li>{@link ItemKind#PRESET}: presets of an imported weapon
 * </ul>
 *
 * Everything else (gear, ammo, presets of excluded weapons or of armor, ...) is skipped.
 */
@Component
public class ItemClassifier {

    private final Set<String> excludedCategories;
    private final Set<String> excludedWeapons;

    public ItemClassifier(GameDataProperties properties) {
        this.excludedCategories = Set.copyOf(properties.excludedWeaponCategories());
        this.excludedWeapons = Set.copyOf(properties.excludedWeapons());
    }

    public Classification classify(ItemsPayload payload) {
        Map<String, ItemKind> kinds = new LinkedHashMap<>();
        Set<String> excluded = new LinkedHashSet<>();
        for (ItemDto item : payload.items().values()) {
            if (item.isWeapon()) {
                if (isExcluded(item)) {
                    excluded.add(item.id());
                } else {
                    kinds.put(item.id(), ItemKind.WEAPON);
                }
            } else if (item.types().contains("mods") && !item.isGun() && !item.isPreset()) {
                kinds.put(item.id(), ItemKind.MOD);
            }
        }
        Set<String> weapons = new HashSet<>(kinds.keySet());
        for (ItemDto item : payload.items().values()) {
            if (item.isPreset() && item.properties() != null && weapons.contains(item.properties().baseItem())) {
                kinds.put(item.id(), ItemKind.PRESET);
            }
        }
        return new Classification(Collections.unmodifiableMap(kinds), Collections.unmodifiableSet(excluded));
    }

    /**
     * The weapon class of a weapon or preset: the normalized name of its most specific category,
     * e.g. {@code smg}; {@code null} if the category is unknown.
     */
    public static String weaponClass(ItemDto item, Map<String, ItemCategoryDto> categories) {
        if (item.categories().isEmpty()) {
            return null;
        }
        ItemCategoryDto category = categories.get(item.categories().getFirst());
        return category == null ? null : category.normalizedName();
    }

    private boolean isExcluded(ItemDto weapon) {
        // categories lists the item's category and all of its ancestors
        return excludedWeapons.contains(weapon.id()) || weapon.categories().stream().anyMatch(excludedCategories::contains);
    }

    /**
     * @param kinds the items to import, by id
     * @param excludedWeapons ids of guns left out by the exclusion config
     */
    public record Classification(Map<String, ItemKind> kinds, Set<String> excludedWeapons) {

        public long count(ItemKind kind) {
            return kinds.values().stream().filter(kind::equals).count();
        }
    }
}
