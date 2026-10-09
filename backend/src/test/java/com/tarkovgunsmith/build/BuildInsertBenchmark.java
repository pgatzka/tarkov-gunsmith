package com.tarkovgunsmith.build;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tarkovgunsmith.TestcontainersConfiguration;
import com.tarkovgunsmith.engine.Build;
import com.tarkovgunsmith.engine.BuildGenerator;
import com.tarkovgunsmith.engine.CompatibilityGraph;
import com.tarkovgunsmith.engine.CompatibilityGraph.Part;
import com.tarkovgunsmith.engine.CompatibilityGraphLoader;
import com.tarkovgunsmith.gamedata.GameDataImporter;
import com.tarkovgunsmith.tarkovdev.dto.ItemsPayload;
import com.tarkovgunsmith.tarkovdev.dto.Translations;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Measures {@link BuildRepository#insertAll} throughput: generates builds for every weapon in
 * {@code tarkovdev/all-guns} up front, then inserts them in batches of each size into an empty
 * table and logs builds/s. Run with {@code ./gradlew benchmark}.
 */
@Tag("benchmark")
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class BuildInsertBenchmark {

    private static final Logger log = LoggerFactory.getLogger(BuildInsertBenchmark.class);

    static final int BUILDS_PER_WEAPON = 1_000;
    static final int[] BATCH_SIZES = {100, 1_000, 5_000};

    @Autowired
    GameDataImporter importer;

    @Autowired
    CompatibilityGraphLoader loader;

    @Autowired
    BuildRepository repository;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void insertThroughput() {
        jdbc.execute("TRUNCATE item, item_category, trader, offer, data_version, build, build_part");
        importer.importItems(allGuns());
        CompatibilityGraph graph = loader.load();
        BuildGenerator generator = new BuildGenerator();
        SplittableRandom random = new SplittableRandom(42);
        List<Build> builds = new ArrayList<>();
        for (Part weapon : graph.weapons()) {
            for (int i = 0; i < BUILDS_PER_WEAPON; i++) {
                generator.generate(weapon, random).ifPresent(builds::add);
            }
        }
        double partsPerBuild = builds.stream().mapToInt(b -> b.parts().size()).average().orElseThrow();
        log.info("Generated {} builds, {} parts per build on average", builds.size(), "%.1f".formatted(partsPerBuild));

        for (int batchSize : BATCH_SIZES) {
            jdbc.execute("TRUNCATE build, build_part");
            long inserted = 0;
            long start = System.nanoTime();
            for (int from = 0; from < builds.size(); from += batchSize) {
                inserted += repository.insertAll(builds.subList(from, Math.min(from + batchSize, builds.size())))
                        .size();
            }
            double seconds = (System.nanoTime() - start) / 1e9;
            log.info(
                    "Batch size {}: inserted {} builds in {} s = {} builds/s",
                    batchSize,
                    inserted,
                    "%.2f".formatted(seconds),
                    "%.0f".formatted(inserted / seconds));

            // inserting everything again only finds duplicates
            start = System.nanoTime();
            long again = 0;
            for (int from = 0; from < builds.size(); from += batchSize) {
                again += repository.insertAll(builds.subList(from, Math.min(from + batchSize, builds.size())))
                        .size();
            }
            seconds = (System.nanoTime() - start) / 1e9;
            log.info(
                    "Batch size {}: re-inserting all {} as duplicates took {} s = {} builds/s",
                    batchSize,
                    builds.size(),
                    "%.2f".formatted(seconds),
                    "%.0f".formatted(builds.size() / seconds));
            assertThat(again).isZero();
            assertThat(repository.count()).isEqualTo(inserted);
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
        return BuildInsertBenchmark.class.getResourceAsStream("/tarkovdev/all-guns/" + name);
    }
}
