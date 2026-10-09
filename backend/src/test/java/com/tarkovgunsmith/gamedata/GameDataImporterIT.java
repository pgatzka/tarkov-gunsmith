package com.tarkovgunsmith.gamedata;

import static com.tarkovgunsmith.gamedata.Fixtures.AR15_BARREL;
import static com.tarkovgunsmith.gamedata.Fixtures.COMTAC_V;
import static com.tarkovgunsmith.gamedata.Fixtures.FN40GL;
import static com.tarkovgunsmith.gamedata.Fixtures.FN40GL_DEFAULT_PRESET;
import static com.tarkovgunsmith.gamedata.Fixtures.GPNVG;
import static com.tarkovgunsmith.gamedata.Fixtures.M32A1;
import static com.tarkovgunsmith.gamedata.Fixtures.MP5;
import static com.tarkovgunsmith.gamedata.Fixtures.MP5_DEFAULT_PRESET;
import static com.tarkovgunsmith.gamedata.Fixtures.MP5_MAGAZINE;
import static com.tarkovgunsmith.gamedata.Fixtures.MP5_UPPER_RECEIVER;
import static com.tarkovgunsmith.gamedata.Fixtures.RHINO_50DS;
import static com.tarkovgunsmith.gamedata.Fixtures.RSHG2;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tarkovgunsmith.TestcontainersConfiguration;
import com.tarkovgunsmith.tarkovdev.dto.ItemDto;
import com.tarkovgunsmith.tarkovdev.dto.ItemsPayload;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class GameDataImporterIT {

    @Autowired
    GameDataImporter importer;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    DataVersionRepository dataVersions;

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE item, item_category, trader, offer, data_version");
    }

    @Test
    void importsWeaponsModsAndPresetsWithoutExcludedWeapons() {
        var result = importer.importItems(Fixtures.items());

        assertThat(result.weapons()).isEqualTo(2);
        assertThat(result.presets()).isEqualTo(1);
        assertThat(result.mods()).isEqualTo(6);
        assertThat(result.changed()).isEqualTo(9);
        assertThat(result.excludedWeapons()).containsExactlyInAnyOrder(FN40GL, M32A1, RSHG2);

        assertThat(kinds())
                .containsOnlyKeys(MP5, RHINO_50DS, MP5_DEFAULT_PRESET, MP5_UPPER_RECEIVER, MP5_MAGAZINE, AR15_BARREL, GPNVG,
                        "5dff772da3651922b360bf91", "56eabf3bd2720b75698b4569")
                .containsEntry(MP5, "WEAPON")
                .containsEntry(MP5_DEFAULT_PRESET, "PRESET")
                .containsEntry(AR15_BARREL, "MOD");
        // launchers, a launcher's preset and non-mod gear are absent
        assertThat(kinds()).doesNotContainKeys(FN40GL, M32A1, RSHG2, FN40GL_DEFAULT_PRESET, COMTAC_V);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM item_category", Integer.class))
                .isEqualTo(Fixtures.items().itemCategories().size());
    }

    @Test
    void storesWeaponStatsAndSlots() throws Exception {
        importer.importItems(Fixtures.items());

        Map<String, Object> mp5 = jdbc.queryForMap("SELECT * FROM item WHERE id = ?", MP5);
        assertThat(mp5)
                .containsEntry("name", "HK MP5 9x19 submachine gun (Navy 3 Round Burst)")
                .containsEntry("short_name", "MP5")
                .containsEntry("weapon_class", "smg")
                .containsEntry("weight", 1.21)
                .containsEntry("ergonomics", 50.0)
                .containsEntry("recoil_vertical", 56.0)
                .containsEntry("recoil_horizontal", 275.0)
                .containsEntry("ergonomics_modifier", 0.0)
                .containsEntry("default_preset_id", MP5_DEFAULT_PRESET)
                .containsEntry("no_flea", false)
                .containsEntry("icon_link", "https://assets.tarkov.dev/" + MP5 + "-icon.webp");
        assertThat(textArray("SELECT categories FROM item WHERE id = ?", MP5))
                .startsWith("5447b5e04bdc2d62278b4567", "5422acb9af1c889c16000029");

        JsonNode slots = objectMapper.readTree(jdbc.queryForObject("SELECT slots::text FROM item WHERE id = ?", String.class, MP5));
        assertThat(slots).hasSize(3);
        JsonNode receiver = slots.get(1);
        assertThat(receiver.get("id").asText()).isEqualTo("5926bb2186f7744b1c6c6e63");
        assertThat(receiver.get("nameId").asText()).isEqualTo("mod_reciever");
        assertThat(receiver.get("name").asText()).isEqualTo("Receiver");
        assertThat(receiver.get("required").asBoolean()).isTrue();
        assertThat(receiver.at("/filters/allowedItems").toString()).contains(MP5_UPPER_RECEIVER);
        assertThat(receiver.at("/filters/excludedCategories").isArray()).isTrue();

        assertThat(jdbc.queryForObject("SELECT weapon_class FROM item WHERE id = ?", String.class, RHINO_50DS))
                .isEqualTo("revolver");
    }

    @Test
    void storesModModifiersAndConflicts() {
        importer.importItems(Fixtures.items());

        Map<String, Object> barrel = jdbc.queryForMap("SELECT * FROM item WHERE id = ?", AR15_BARREL);
        assertThat(barrel)
                .containsEntry("weapon_class", null)
                .containsEntry("ergonomics", null)
                .containsEntry("ergonomics_modifier", -2.0)
                .containsEntry("recoil_modifier", -5.2)
                .containsEntry("accuracy_modifier", 0.0)
                .containsEntry("weight", 0.409);
        assertThat(textArray("SELECT conflicting_items FROM item WHERE id = ?", AR15_BARREL))
                .containsExactly("68a63cdac92ee33ffa01bf5f", "68a63cb3e1fb670852024664", "68a63cc0c92ee33ffa01bf5c");
        assertThat(textArray("SELECT conflicting_slot_ids FROM item WHERE id = ?", "5dff772da3651922b360bf91"))
                .containsExactly("5c7d55f52e221644f31bff6c");
        assertThat(jdbc.queryForObject("SELECT no_flea FROM item WHERE id = ?", Boolean.class, GPNVG)).isTrue();
    }

    @Test
    void storesPresetStatsAndParts() throws Exception {
        importer.importItems(Fixtures.items());

        Map<String, Object> preset = jdbc.queryForMap("SELECT * FROM item WHERE id = ?", MP5_DEFAULT_PRESET);
        assertThat(preset)
                .containsEntry("base_item_id", MP5)
                .containsEntry("weapon_class", "smg")
                .containsEntry("ergonomics", 72.0)
                .containsEntry("recoil_vertical", 43.0)
                .containsEntry("recoil_horizontal", 209.0);
        JsonNode parts = objectMapper.readTree(
                jdbc.queryForObject("SELECT contains_items::text FROM item WHERE id = ?", String.class, MP5_DEFAULT_PRESET));
        assertThat(parts).hasSize(8);
        assertThat(parts.findValuesAsText("item")).contains(MP5, MP5_UPPER_RECEIVER);
    }

    @Test
    void reimportingUnchangedDataWritesNothing() {
        importer.importItems(Fixtures.items());

        var again = importer.importItems(Fixtures.items());

        assertThat(again.changed()).isZero();
        assertThat(again.removed()).isZero();
    }

    @Test
    void reimportUpdatesChangedAndRemovesMissingItems() {
        importer.importItems(Fixtures.items());
        ItemsPayload items = Fixtures.items();
        Map<String, ItemDto> changed = new LinkedHashMap<>(items.items());
        changed.remove(GPNVG);
        ItemDto barrel = changed.get(AR15_BARREL);
        changed.put(AR15_BARREL, withRecoilModifier(barrel, -6.0));

        var result = importer.importItems(new ItemsPayload(new ItemsPayload.Data(changed, items.itemCategories())));

        assertThat(result.changed()).isEqualTo(1);
        assertThat(result.removed()).isEqualTo(1);
        assertThat(kinds()).doesNotContainKey(GPNVG);
        assertThat(jdbc.queryForObject("SELECT recoil_modifier FROM item WHERE id = ?", Double.class, AR15_BARREL))
                .isEqualTo(-6.0);
    }

    @Test
    void importsTraders() {
        int changed = importer.importTraders(Fixtures.traders());

        assertThat(changed).isEqualTo(4);
        assertThat(jdbc.queryForMap("SELECT name, normalized_name, max_level FROM trader WHERE id = ?",
                        "5935c25fb3acc3127c3d8cd9"))
                .containsEntry("name", "Peacekeeper")
                .containsEntry("normalized_name", "peacekeeper")
                .containsEntry("max_level", 4);
        assertThat(importer.importTraders(Fixtures.traders())).isZero();
    }

    @Test
    void storesDataVersions() {
        assertThat(dataVersions.get("regular/items")).isEmpty();

        dataVersions.put("regular/items", "W/\"a\"");
        dataVersions.put("regular/items", "W/\"b\"");

        assertThat(dataVersions.get("regular/items")).contains("W/\"b\"");
    }

    private Map<String, String> kinds() {
        Map<String, String> kinds = new LinkedHashMap<>();
        jdbc.query("SELECT id, kind FROM item", rs -> {
            kinds.put(rs.getString("id"), rs.getString("kind"));
        });
        return kinds;
    }

    private List<String> textArray(String sql, String id) {
        return jdbc.queryForObject(sql, (rs, n) -> List.of((String[]) rs.getArray(1).getArray()), id);
    }

    private static ItemDto withRecoilModifier(ItemDto item, double recoilModifier) {
        return new ItemDto(
                item.id(),
                item.name(),
                item.shortName(),
                item.normalizedName(),
                item.types(),
                item.categories(),
                item.weight(),
                item.ergonomicsModifier(),
                recoilModifier,
                item.accuracyModifier(),
                item.conflictingItems(),
                item.conflictingSlotIds(),
                item.conflictingCategories(),
                item.buyFromTrader(),
                item.lastLowPrice(),
                item.avg24hPrice(),
                item.iconLink(),
                item.gridImageLink(),
                item.baseImageLink(),
                item.containsItems(),
                item.properties());
    }
}
