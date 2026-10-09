package com.tarkovgunsmith.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tarkovgunsmith.TestcontainersConfiguration;
import com.tarkovgunsmith.engine.CompatibilityGraph.Part;
import com.tarkovgunsmith.engine.CompatibilityGraph.Slot;
import com.tarkovgunsmith.gamedata.GameDataImporter;
import com.tarkovgunsmith.tarkovdev.dto.ItemDto;
import com.tarkovgunsmith.tarkovdev.dto.ItemPropertiesDto;
import com.tarkovgunsmith.tarkovdev.dto.ItemsPayload;
import com.tarkovgunsmith.tarkovdev.dto.SlotDto;
import com.tarkovgunsmith.tarkovdev.dto.SlotFiltersDto;
import com.tarkovgunsmith.tarkovdev.dto.Translations;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The graph of the MP5 loaded from the database, using {@code tarkovdev/mp5-tree}: the MP5, every
 * mod that fits on it (recursively) and its presets, recorded from the live {@code /regular/items}.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class CompatibilityGraphLoaderIT {

    static final String MP5 = "5926bb2186f7744b1c6c6e60";
    static final String MP5_DEFAULT_PRESET = "59411aa786f7747aeb37f9a5";
    static final String MP5_UPPER_RECEIVER = "5926c0df86f77462f647f764";
    static final String MP5SD_UPPER_RECEIVER = "5926f2e086f7745aae644231";
    static final String MP5SD_HANDGUARD = "5926f34786f77469195bfe92";
    static final String HANDGUARD_CATEGORY = "55818a104bdc2db9688b4569";
    static final String ESSENTIAL_MOD_CATEGORY = "55802f4a4bdc2ddb688b4569";

    @Autowired
    GameDataImporter importer;

    @Autowired
    CompatibilityGraphLoader loader;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE item, item_category, trader, offer, data_version");
    }

    @Test
    void mp5SlotTree() {
        importer.importItems(mp5Tree());

        Part mp5 = loader.load().weapon(MP5).orElseThrow();

        assertThat(mp5.slots()).extracting(Slot::nameId).containsExactly("mod_magazine", "mod_reciever", "mod_charge");
        assertThat(mp5.slots()).extracting(Slot::required).containsExactly(false, true, true);
        assertThat(ids(slot(mp5, "mod_magazine").candidates()))
                .containsExactly("5d2f213448f0355009199284", "5926c3b286f774640d189b6b", "5a351711c4a282000b1521a4");
        assertThat(ids(slot(mp5, "mod_reciever").candidates())).containsExactly(MP5SD_UPPER_RECEIVER, MP5_UPPER_RECEIVER);
        assertThat(ids(slot(mp5, "mod_charge").candidates())).containsExactly("5926c32286f774616e42de99");

        // one level down: the upper receiver's own slots
        Part receiver = slot(mp5, "mod_reciever").candidates().get(1);
        assertThat(receiver.slots())
                .extracting(Slot::nameId)
                .containsExactly("mod_handguard", "mod_sight_rear", "mod_stock", "mod_muzzle", "mod_mount");
        assertThat(receiver.slots()).extracting(Slot::required).containsExactly(true, false, true, true, false);
        assertThat(ids(slot(receiver, "mod_handguard").candidates()))
                .containsExactly(
                        "5a9548c9159bd400133e97b3",
                        "5d010d1cd7ad1a59283b1ce7",
                        "5926c36d86f77467a92a8629",
                        "5d19cd96d7ad1a4a992c9f52");
        assertThat(ids(slot(receiver, "mod_stock").candidates()))
                .containsExactly("5926d3c686f77410de68ebc8", "5926d40686f7740f152b6b7e", "5c07c9660db834001a66b588");
    }

    @Test
    void everyFixtureModIsReachableFromTheMp5() {
        importer.importItems(mp5Tree());

        CompatibilityGraph graph = loader.load();

        assertThat(graph.weapons()).extracting(Part::id).containsExactly(MP5);
        // the fixture is exactly the MP5 and the mods reachable from it (plus presets, which aren't parts)
        assertThat(CompatibilityGraph.reachable(graph.weapon(MP5).orElseThrow())).hasSize(234);
        assertThat(graph.size()).isEqualTo(234);
        assertThat(graph.part(MP5_DEFAULT_PRESET)).isEmpty();
        // every candidate passes its slot's filter
        for (Part part : CompatibilityGraph.reachable(graph.weapon(MP5).orElseThrow())) {
            for (Slot slot : part.slots()) {
                GraphItem.SlotDefinition definition = part.item().slots().stream()
                        .filter(d -> d.id().equals(slot.id()))
                        .findFirst()
                        .orElseThrow();
                assertThat(slot.candidates())
                        .allSatisfy(c -> assertThat(definition.filter().allows(c.id(), c.categories())).isTrue());
            }
        }
    }

    @Test
    void loadsStatsAndConflicts() {
        importer.importItems(mp5Tree());

        CompatibilityGraph graph = loader.load();

        GraphItem mp5 = graph.weapon(MP5).orElseThrow().item();
        assertThat(mp5.ergonomics()).isEqualTo(50.0);
        assertThat(mp5.recoilVertical()).isEqualTo(56.0);
        assertThat(mp5.recoilHorizontal()).isEqualTo(275.0);
        assertThat(mp5.weight()).isEqualTo(1.21);
        assertThat(graph.part(MP5_UPPER_RECEIVER).orElseThrow().item().ergonomics()).isNull();
        assertThat(graph.part(MP5_UPPER_RECEIVER).orElseThrow().categories())
                .containsSubsequence(ESSENTIAL_MOD_CATEGORY, "5448fe124bdc2da5018b4567");
    }

    @Test
    void resolvesAllowedCategoriesThroughTheCategoryTree() {
        // the MP5SD receiver's handguard slot, rewritten to allow the handguard category except the MP5SD's own
        importer.importItems(withSlotFilter(
                mp5Tree(), MP5SD_UPPER_RECEIVER, "mod_handguard",
                new SlotFiltersDto(List.of(), List.of(HANDGUARD_CATEGORY), List.of(MP5SD_HANDGUARD), List.of())));

        Part receiver = loader.load().part(MP5SD_UPPER_RECEIVER).orElseThrow();

        // the loader reads items ordered by id
        assertThat(ids(slot(receiver, "mod_handguard").candidates()))
                .containsExactly(
                        "5926c36d86f77467a92a8629",
                        "5a9548c9159bd400133e97b3",
                        "5d010d1cd7ad1a59283b1ce7",
                        "5d19cd96d7ad1a4a992c9f52");
    }

    @Test
    void resolvesExcludedCategoriesThroughTheCategoryTree() {
        // essential mods (handguards, receivers, barrels, ...) except handguards
        importer.importItems(withSlotFilter(
                mp5Tree(), MP5SD_UPPER_RECEIVER, "mod_handguard",
                new SlotFiltersDto(List.of(), List.of(ESSENTIAL_MOD_CATEGORY), List.of(), List.of(HANDGUARD_CATEGORY))));

        Part receiver = loader.load().part(MP5SD_UPPER_RECEIVER).orElseThrow();

        List<Part> candidates = slot(receiver, "mod_handguard").candidates();
        assertThat(ids(candidates)).contains(MP5_UPPER_RECEIVER, MP5SD_UPPER_RECEIVER);
        assertThat(candidates).allSatisfy(part -> {
            assertThat(part.categories()).contains(ESSENTIAL_MOD_CATEGORY);
            assertThat(part.categories()).doesNotContain(HANDGUARD_CATEGORY);
        });
    }

    private static Slot slot(Part part, String nameId) {
        return part.slots().stream().filter(s -> s.nameId().equals(nameId)).findFirst().orElseThrow();
    }

    private static List<String> ids(Collection<Part> parts) {
        return parts.stream().map(Part::id).toList();
    }

    /** {@code payload} with the filters of one slot of one item replaced. */
    private static ItemsPayload withSlotFilter(ItemsPayload payload, String itemId, String nameId, SlotFiltersDto filters) {
        ItemDto item = payload.items().get(itemId);
        ItemPropertiesDto p = item.properties();
        List<SlotDto> slots = p.slots().stream()
                .map(s -> s.nameId().equals(nameId) ? new SlotDto(s.id(), s.nameId(), s.name(), s.required(), filters) : s)
                .toList();
        ItemPropertiesDto properties = new ItemPropertiesDto(
                p.propertiesType(),
                p.ergonomics(),
                p.recoilVertical(),
                p.recoilHorizontal(),
                slots,
                p.defaultPreset(),
                p.presets(),
                p.baseItem(),
                p.isDefault());
        Map<String, ItemDto> items = new LinkedHashMap<>(payload.items());
        items.put(itemId, new ItemDto(
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
                item.lastLowPrice(),
                item.avg24hPrice(),
                item.iconLink(),
                item.gridImageLink(),
                item.baseImageLink(),
                item.containsItems(),
                properties));
        return new ItemsPayload(new ItemsPayload.Data(items, payload.itemCategories()));
    }

    static ItemsPayload mp5Tree() {
        return read("items", ItemsPayload.class).translate(read("items_en", Translations.class));
    }

    private static <T> T read(String endpoint, Class<T> type) {
        ObjectMapper mapper = new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        try (InputStream in = CompatibilityGraphLoaderIT.class.getResourceAsStream("/tarkovdev/mp5-tree/" + endpoint + ".json")) {
            return mapper.readValue(in, type);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
