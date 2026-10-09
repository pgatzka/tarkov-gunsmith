package com.tarkovgunsmith.build;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tarkovgunsmith.TestcontainersConfiguration;
import com.tarkovgunsmith.engine.CompatibilityGraph.Part;
import com.tarkovgunsmith.engine.CompatibilityGraphLoader;
import com.tarkovgunsmith.gamedata.GameDataImporter;
import com.tarkovgunsmith.tarkovdev.dto.ItemsPayload;
import com.tarkovgunsmith.tarkovdev.dto.Translations;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.List;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/** Background generation on every weapon of {@code tarkovdev/all-guns}. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class BuildGenerationIT {

    @Autowired
    GameDataImporter importer;

    @Autowired
    CompatibilityGraphLoader loader;

    @Autowired
    BuildRepository repository;

    @Autowired
    BuildGeneration configured;

    @Autowired
    JdbcTemplate jdbc;

    BuildGeneration generation;

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE item, item_category, trader, offer, data_version, build, build_part");
    }

    @AfterEach
    void tearDown() {
        if (generation != null) {
            generation.stop();
        }
    }

    @Test
    void theBuildCountGrowsForEveryWeapon() {
        importer.importItems(allGuns());
        List<String> weapons = loader.load().weapons().stream().map(Part::id).toList();
        generation = new BuildGeneration(loader, repository, properties(true));

        generation.start();

        assertThat(generation.isRunning()).isTrue();
        await().atMost(Duration.ofMinutes(2)).untilAsserted(() -> assertThat(repository.countByWeapon())
                .containsOnlyKeys(weapons)
                .allSatisfy((weapon, count) -> assertThat(count).as(weapon).isPositive()));
        long total = repository.count();
        await().atMost(Duration.ofMinutes(1)).until(() -> repository.count() > total);

        generation.stop();
        assertThat(generation.inserted()).isEqualTo(repository.count());
        assertThat(generation.generated()).isGreaterThanOrEqualTo(generation.inserted());
    }

    @Test
    void stopHaltsTheWorkers() {
        importer.importItems(allGuns());
        generation = new BuildGeneration(loader, repository, properties(true));
        generation.start();
        await().atMost(Duration.ofMinutes(1)).until(() -> repository.count() > 0);

        generation.stop();

        assertThat(generation.isRunning()).isFalse();
        long stored = repository.count();
        sleep(Duration.ofMillis(500));
        assertThat(repository.count()).isEqualTo(stored);
    }

    @Test
    void waitsForGameDataAndPicksItUpOnceImported() {
        generation = new BuildGeneration(loader, repository, properties(true));
        generation.start();
        sleep(Duration.ofMillis(300));
        assertThat(repository.count()).isZero();

        importer.importItems(allGuns());

        // the workers check for weapons every idle delay and then load the graph
        await().atMost(Duration.ofMinutes(1)).until(() -> repository.count() > 0);
    }

    @Test
    void canBeDisabledFromConfig() {
        // src/test/resources/config/application.yml sets gunsmith.generator.enabled=false
        importer.importItems(allGuns());

        assertThat(configured.isAutoStartup()).isFalse();
        assertThat(configured.isRunning()).isFalse();
        sleep(Duration.ofMillis(500));
        assertThat(repository.count()).isZero();

        assertThat(new BuildGeneration(loader, repository, properties(true)).isAutoStartup()).isTrue();
    }

    private static BuildGenerationProperties properties(boolean enabled) {
        return new BuildGenerationProperties(enabled, 2, 50, Duration.ofMillis(200));
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    static ItemsPayload allGuns() {
        ObjectMapper mapper = new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        try (InputStream items = new GZIPInputStream(resource("items.json.gz"));
                InputStream translations = resource("items_en.json")) {
            return mapper.readValue(items, ItemsPayload.class).translate(mapper.readValue(translations, Translations.class));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static InputStream resource(String name) {
        return BuildGenerationIT.class.getResourceAsStream("/tarkovdev/all-guns/" + name);
    }
}
