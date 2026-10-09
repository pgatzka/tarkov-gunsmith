package com.tarkovgunsmith.gamedata;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tarkovgunsmith.tarkovdev.GameMode;
import com.tarkovgunsmith.tarkovdev.dto.ItemDto;
import com.tarkovgunsmith.tarkovdev.dto.ItemsPayload;
import com.tarkovgunsmith.tarkovdev.dto.TradersPayload;
import com.tarkovgunsmith.tarkovdev.dto.Translations;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The recorded json.tarkov.dev fixtures under {@code tarkovdev/regular/}, translated. {@code
 * tarkovdev/pve/items.json} holds the same items with the PvE offers and flea prices.
 */
final class Fixtures {

    static final String MP5 = "5926bb2186f7744b1c6c6e60";
    static final String MP5_DEFAULT_PRESET = "59411aa786f7747aeb37f9a5";
    static final String MP5_UPPER_RECEIVER = "5926c0df86f77462f647f764";
    static final String MP5_MAGAZINE = "5926c3b286f774640d189b6b";
    static final String AR15_BARREL = "55d35ee94bdc2d61338b4568";
    // offered only by Jaeger, who is not in the traders fixture
    static final String JAEGER_ONLY_MOD = "5dff772da3651922b360bf91";
    // its only trader offer (Skier LL3) is quest-locked
    static final String QUEST_LOCKED_MOD = "56eabf3bd2720b75698b4569";
    static final String GPNVG = "5c0558060db834001b735271";
    static final String COMTAC_V = "66b5f69ea7f72d197e70bcdb";
    static final String RHINO_50DS = "61a4c8884f95bc3b2c5dc96f";
    static final String FN40GL = "5e81ebcd8e146c7080625e15";
    static final String FN40GL_DEFAULT_PRESET = "5f06d6e1475d472556679d16";
    static final String M32A1 = "6275303a9f372d6ea97f9ec7";
    static final String RSHG2 = "676bf44c5539167c3603e869";

    static final String PEACEKEEPER = "5935c25fb3acc3127c3d8cd9";
    static final String SKIER = "58330581ace78e27b8b10cee";
    static final String JAEGER = "5c0647fdd443bc2504c2d371";

    static final String GRENADE_LAUNCHER_CATEGORY = "5447bedf4bdc2d87278b4568";
    static final String ROCKET_LAUNCHER_CATEGORY = "67446d4f04141c10630604e7";

    private static final ObjectMapper MAPPER =
            new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private Fixtures() {}

    static ItemsPayload items() {
        return items(GameMode.REGULAR);
    }

    static ItemsPayload items(GameMode mode) {
        return read(mode, "items", ItemsPayload.class).translate(read(mode, "items_en", Translations.class));
    }

    static TradersPayload traders() {
        return read(GameMode.REGULAR, "traders", TradersPayload.class)
                .translate(read(GameMode.REGULAR, "traders_en", Translations.class));
    }

    /** The exclusion config from {@code application.yml}. */
    static GameDataProperties properties() {
        return new GameDataProperties(
                List.of(GRENADE_LAUNCHER_CATEGORY, ROCKET_LAUNCHER_CATEGORY), List.of(M32A1));
    }

    /** {@code item} with other flea prices. */
    static ItemDto withFleaPrices(ItemDto item, Integer lastLowPrice, Integer avg24hPrice) {
        return new ItemDto(
                item.id(),
                item.name(),
                item.shortName(),
                item.normalizedName(),
                item.types(),
                item.categories(),
                item.weight(),
                item.ergonomicsModifier(),
                item.recoilModifier(),
                item.accuracyModifier(),
                item.conflictingItems(),
                item.conflictingSlotIds(),
                item.conflictingCategories(),
                item.buyFromTrader(),
                lastLowPrice,
                avg24hPrice,
                item.iconLink(),
                item.gridImageLink(),
                item.baseImageLink(),
                item.containsItems(),
                item.properties());
    }

    /** {@code payload} with {@code item} replacing the item of the same id. */
    static ItemsPayload replace(ItemsPayload payload, ItemDto item) {
        Map<String, ItemDto> items = new LinkedHashMap<>(payload.items());
        items.put(item.id(), item);
        return new ItemsPayload(new ItemsPayload.Data(items, payload.itemCategories()));
    }

    private static <T> T read(GameMode mode, String endpoint, Class<T> type) {
        String path = "/tarkovdev/" + mode.path() + "/" + endpoint + ".json";
        try (InputStream in = Fixtures.class.getResourceAsStream(path)) {
            return MAPPER.readValue(in, type);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
