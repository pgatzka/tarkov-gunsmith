package com.tarkovgunsmith.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tarkovgunsmith.TestcontainersConfiguration;
import com.tarkovgunsmith.engine.PresetCheck.Result;
import com.tarkovgunsmith.gamedata.GameDataImporter;
import com.tarkovgunsmith.tarkovdev.FetchResult;
import com.tarkovgunsmith.tarkovdev.GameMode;
import com.tarkovgunsmith.tarkovdev.TarkovDevClient;
import com.tarkovgunsmith.tarkovdev.dto.ItemsPayload;
import com.tarkovgunsmith.tarkovdev.dto.Translations;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@link PresetStatsIT} against the current live {@code /regular/items}, to re-check the stat model
 * after a game patch. Not part of {@code ./gradlew test}; run with {@code ./gradlew liveTest}.
 */
@Tag("live")
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class PresetStatsLiveTest {

    @Autowired
    TarkovDevClient client;

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

    @Test
    void computedStatsMatchEveryLivePreset() {
        jdbcTemplate.execute("TRUNCATE item, item_category, trader, offer, data_version");
        ItemsPayload items = modified(client.fetchItems(GameMode.REGULAR, null))
                .translate(modified(client.fetchItemTranslations(GameMode.REGULAR, null)));
        importer.importItems(items);
        CompatibilityGraph graph = loader.load();

        List<Result> results = PresetCheck.run(jdbc, objectMapper, graph);

        assertThat(results).hasSizeGreaterThan(300);
        assertThat(results).allSatisfy(r -> {
            assertThat(r.recoilMatches()).as("recoil of %s", r).isTrue();
            assertThat(r.weightMatches()).as("weight of %s", r).isTrue();
            // the only known deviation: tarkov.dev counts a part listed twice once in the preset's ergonomics
            assertThat(r.ergonomicsMatch() || r.ergonomicsOffByDuplicates()).as("ergonomics of %s", r).isTrue();
        });
    }

    private static <T> T modified(FetchResult<T> result) {
        assertThat(result).isInstanceOf(FetchResult.Modified.class);
        return ((FetchResult.Modified<T>) result).body();
    }
}
