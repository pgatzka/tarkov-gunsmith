package com.tarkovgunsmith.tarkovdev.dto;

import java.util.LinkedHashMap;
import java.util.Map;

/** {@code /{mode}/traders}, keyed by trader id. */
public record TradersPayload(Map<String, TraderDto> data) {

    public TradersPayload {
        data = data == null ? Map.of() : data;
    }

    public Map<String, TraderDto> traders() {
        return data;
    }

    /** A copy with trader names resolved through {@code translations} (from {@code traders_en}). */
    public TradersPayload translate(Translations translations) {
        Map<String, TraderDto> traders = new LinkedHashMap<>();
        data.forEach((id, trader) -> traders.put(id, trader.translate(translations)));
        return new TradersPayload(traders);
    }
}
