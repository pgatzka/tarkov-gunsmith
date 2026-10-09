package com.tarkovgunsmith.tarkovdev.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * The type-specific {@code properties} of an item. Only the fields relevant to builds are mapped;
 * which ones are set depends on {@code propertiesType}.
 *
 * <ul>
 *   <li>{@code ItemPropertiesWeapon}: base {@code ergonomics}, {@code recoilVertical}, {@code
 *       recoilHorizontal}, {@code slots}, {@code defaultPreset}, {@code presets}
 *   <li>{@code ItemPropertiesPreset}: the assembled weapon's {@code ergonomics} and recoil, {@code
 *       baseItem}, {@code isDefault}
 *   <li>mods ({@code ItemPropertiesWeaponMod}, {@code ItemPropertiesBarrel}, ...): {@code slots}
 * </ul>
 */
public record ItemPropertiesDto(
        String propertiesType,
        Double ergonomics,
        Double recoilVertical,
        Double recoilHorizontal,
        List<SlotDto> slots,
        String defaultPreset,
        List<String> presets,
        String baseItem,
        @JsonProperty("default") Boolean isDefault) {

    public static final String WEAPON = "ItemPropertiesWeapon";
    public static final String PRESET = "ItemPropertiesPreset";

    public ItemPropertiesDto {
        slots = ItemDto.copy(slots);
        presets = ItemDto.copy(presets);
    }

    ItemPropertiesDto translate(Translations t) {
        List<SlotDto> translated = slots.stream().map(slot -> slot.translate(t)).toList();
        return new ItemPropertiesDto(
                propertiesType,
                ergonomics,
                recoilVertical,
                recoilHorizontal,
                translated,
                defaultPreset,
                presets,
                baseItem,
                isDefault);
    }
}
