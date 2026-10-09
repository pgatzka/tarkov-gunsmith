package com.tarkovgunsmith.tarkovdev.dto;

import java.util.List;

/** A trader; {@code name} is a translation key until translated. */
public record TraderDto(String id, String name, String normalizedName, String imageLink, List<TraderLevelDto> levels) {

    public TraderDto {
        levels = ItemDto.copy(levels);
    }

    TraderDto translate(Translations t) {
        return new TraderDto(id, t.resolve(name), normalizedName, imageLink, levels);
    }

    public record TraderLevelDto(int level, int requiredPlayerLevel) {}
}
