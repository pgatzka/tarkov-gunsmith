package com.tarkovgunsmith.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tarkovgunsmith.TestcontainersConfiguration;
import com.tarkovgunsmith.engine.CompatibilityGraph.Part;
import com.tarkovgunsmith.engine.PresetCheck.Result;
import com.tarkovgunsmith.gamedata.GameDataImporter;
import com.tarkovgunsmith.tarkovdev.dto.ItemsPayload;
import com.tarkovgunsmith.tarkovdev.dto.Translations;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@link StatCalculator} against every weapon preset, using {@code tarkovdev/presets}: all guns,
 * all their presets and every item the presets contain, recorded from the live {@code
 * /regular/items} (2026-10-09; stat fields only, no slots, conflicts or offers).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class PresetStatsIT {

    @Autowired
    GameDataImporter importer;

    @Autowired
    CompatibilityGraphLoader loader;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ObjectMapper objectMapper;

    @BeforeEach
    void clean() {
        jdbcTemplate.execute("TRUNCATE item, item_category, trader, offer, data_version");
    }

    @Test
    void computedStatsMatchEveryPreset() {
        importer.importItems(presets());
        CompatibilityGraph graph = loader.load();

        List<Result> results = PresetCheck.run(jdbc, objectMapper, graph);

        // every imported weapon (launchers excluded) has at least one preset, so every weapon is checked
        assertThat(graph.weapons()).hasSize(161);
        assertThat(results).hasSize(395);
        assertThat(results.stream().map(Result::weaponId).collect(Collectors.toSet()))
                .containsExactlyInAnyOrderElementsOf(graph.weapons().stream().map(Part::id).toList());

        // 35 presets list a mod twice (count 2: two rail panels, flashlights, ...). Their stored weight
        // counts both copies, but their stored ergonomics count only one, a tarkov.dev quirk: the game
        // counts every attached mod, and so does the engine. That makes 26 of them differ (the other
        // 9 duplicate a mod without ergonomics), e.g. the SIG MPX Default with 2× "GEN1 2\"" rail
        // covers (−0.1): stored 78.25, computed 78.15; Saiga-12K "NERFGUN" with 2× Klesch-2U (−2):
        // stored 10, computed 8.
        assertThat(results.stream().filter(r -> !r.matches())).hasSize(26);
        assertThat(results).allSatisfy(r -> {
            assertThat(r.recoilMatches()).as("recoil of %s", r).isTrue();
            assertThat(r.weightMatches()).as("weight of %s", r).isTrue();
            if (!r.ergonomicsMatch()) {
                assertThat(r.ergonomicsOffByDuplicates()).as("ergonomics of %s", r).isTrue();
            }
        });
        // presets with ammo in the magazine: ergonomics and recoil checked, weight not
        assertThat(results.stream().filter(r -> !r.weightChecked())).hasSize(21);
    }

    static ItemsPayload presets() {
        return read("items", ItemsPayload.class).translate(read("items_en", Translations.class));
    }

    private static <T> T read(String endpoint, Class<T> type) {
        ObjectMapper mapper = new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        try (InputStream in = PresetStatsIT.class.getResourceAsStream("/tarkovdev/presets/" + endpoint + ".json")) {
            return mapper.readValue(in, type);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
