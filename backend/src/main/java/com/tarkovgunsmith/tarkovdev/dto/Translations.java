package com.tarkovgunsmith.tarkovdev.dto;

import java.util.Map;

/**
 * Translation map from {@code /{mode}/items_en} or {@code /{mode}/traders_en}. Payload names such
 * as {@code "5926bb2186f7744b1c6c6e60 Name"} or {@code "MOD_MAGAZINE"} are keys into it.
 */
public record Translations(Map<String, String> data) {

    public Translations {
        data = data == null ? Map.of() : Map.copyOf(data);
    }

    /** The text for {@code key}, or {@code key} itself if it has no translation. */
    public String resolve(String key) {
        return key == null ? null : data.getOrDefault(key, key);
    }
}
