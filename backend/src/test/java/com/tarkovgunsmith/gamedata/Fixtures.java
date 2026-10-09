package com.tarkovgunsmith.gamedata;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tarkovgunsmith.tarkovdev.dto.ItemsPayload;
import com.tarkovgunsmith.tarkovdev.dto.TradersPayload;
import com.tarkovgunsmith.tarkovdev.dto.Translations;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;

/** The recorded json.tarkov.dev fixtures under {@code tarkovdev/regular/}, translated. */
final class Fixtures {

    static final String MP5 = "5926bb2186f7744b1c6c6e60";
    static final String MP5_DEFAULT_PRESET = "59411aa786f7747aeb37f9a5";
    static final String MP5_UPPER_RECEIVER = "5926c0df86f77462f647f764";
    static final String MP5_MAGAZINE = "5926c3b286f774640d189b6b";
    static final String AR15_BARREL = "55d35ee94bdc2d61338b4568";
    static final String GPNVG = "5c0558060db834001b735271";
    static final String COMTAC_V = "66b5f69ea7f72d197e70bcdb";
    static final String RHINO_50DS = "61a4c8884f95bc3b2c5dc96f";
    static final String FN40GL = "5e81ebcd8e146c7080625e15";
    static final String FN40GL_DEFAULT_PRESET = "5f06d6e1475d472556679d16";
    static final String M32A1 = "6275303a9f372d6ea97f9ec7";
    static final String RSHG2 = "676bf44c5539167c3603e869";

    static final String GRENADE_LAUNCHER_CATEGORY = "5447bedf4bdc2d87278b4568";
    static final String ROCKET_LAUNCHER_CATEGORY = "67446d4f04141c10630604e7";

    private static final ObjectMapper MAPPER =
            new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private Fixtures() {}

    static ItemsPayload items() {
        return read("items", ItemsPayload.class).translate(read("items_en", Translations.class));
    }

    static TradersPayload traders() {
        return read("traders", TradersPayload.class).translate(read("traders_en", Translations.class));
    }

    /** The exclusion config from {@code application.yml}. */
    static GameDataProperties properties() {
        return new GameDataProperties(
                List.of(GRENADE_LAUNCHER_CATEGORY, ROCKET_LAUNCHER_CATEGORY), List.of(M32A1));
    }

    private static <T> T read(String endpoint, Class<T> type) {
        try (InputStream in = Fixtures.class.getResourceAsStream("/tarkovdev/regular/" + endpoint + ".json")) {
            return MAPPER.readValue(in, type);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
