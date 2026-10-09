package com.tarkovgunsmith.build;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tarkovgunsmith.TestcontainersConfiguration;
import com.tarkovgunsmith.build.BuildRepository.StoredBuild;
import com.tarkovgunsmith.engine.Build;
import com.tarkovgunsmith.engine.BuildGenerator;
import com.tarkovgunsmith.engine.CompatibilityGraph.Part;
import com.tarkovgunsmith.engine.CompatibilityGraphLoader;
import com.tarkovgunsmith.gamedata.GameDataImporter;
import com.tarkovgunsmith.tarkovdev.dto.ItemsPayload;
import com.tarkovgunsmith.tarkovdev.dto.Translations;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/** Stores builds of the MP5 generated from {@code tarkovdev/mp5-tree}. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class BuildRepositoryIT {

    static final String MP5 = "5926bb2186f7744b1c6c6e60";

    @Autowired
    GameDataImporter importer;

    @Autowired
    CompatibilityGraphLoader loader;

    @Autowired
    BuildRepository repository;

    @Autowired
    JdbcTemplate jdbc;

    Part mp5;

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE item, item_category, trader, offer, data_version, build, build_part");
        importer.importItems(mp5Tree());
        mp5 = loader.load().weapon(MP5).orElseThrow();
    }

    @Test
    void storesBuildsWithTheirParts() {
        List<Build> builds = distinctBuilds(50, 1);

        List<StoredBuild> stored = repository.insertAll(builds);

        assertThat(stored).extracting(StoredBuild::build).containsExactlyElementsOf(builds);
        assertThat(repository.count()).isEqualTo(50);
        for (StoredBuild s : stored) {
            Build build = s.build();
            Map<String, Object> row = jdbc.queryForMap("SELECT * FROM build WHERE id = ?", s.id());
            assertThat(row)
                    .containsEntry("weapon_id", MP5)
                    .containsEntry("parts_hash", build.partsHash())
                    .containsEntry("ergonomics", build.stats().ergonomics())
                    .containsEntry("recoil_vertical", build.stats().recoilVertical())
                    .containsEntry("recoil_horizontal", build.stats().recoilHorizontal())
                    .containsEntry("weight", build.stats().weight());
            assertThat(row.get("created_at")).isNotNull();

            List<List<String>> parts = jdbc.query(
                    "SELECT slot_path, item_id, parent_path FROM build_part WHERE build_id = ? ORDER BY slot_path",
                    (rs, i) -> Arrays.asList(rs.getString(1), rs.getString(2), rs.getString(3)),
                    s.id());
            assertThat(parts)
                    .containsExactlyInAnyOrderElementsOf(build.parts().stream()
                            .map(p -> Arrays.asList(p.slotPath(), p.placement().part().id(), p.parentPath()))
                            .toList());
        }
        // the weapon is a part too, at the root
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM build_part WHERE slot_path = '' AND item_id = ? AND parent_path IS NULL",
                        Long.class,
                        MP5))
                .isEqualTo(50);
    }

    @Test
    void rejectsBuildsThatAreAlreadyStored() {
        List<Build> first = distinctBuilds(20, 1);
        repository.insertAll(first);
        long partRows = partRows();

        List<StoredBuild> again = repository.insertAll(first);

        assertThat(again).isEmpty();
        assertThat(repository.count()).isEqualTo(20);
        assertThat(partRows()).isEqualTo(partRows);
    }

    @Test
    void storesOnlyTheNewBuildsOfAMixedBatch() {
        List<Build> builds = distinctBuilds(30, 1);
        repository.insertAll(builds.subList(0, 10));

        List<StoredBuild> stored = repository.insertAll(builds);

        assertThat(stored).extracting(StoredBuild::build).containsExactlyElementsOf(builds.subList(10, 30));
        assertThat(repository.count()).isEqualTo(30);
        assertThat(jdbc.queryForObject("SELECT count(DISTINCT build_id) FROM build_part", Long.class))
                .isEqualTo(30);
    }

    @Test
    void storesADuplicateWithinABatchOnce() {
        Build build = distinctBuilds(1, 1).getFirst();
        // the same parts, assembled again: equal hash, different instance
        Build twin = Build.of(build.parts());

        List<StoredBuild> stored = repository.insertAll(List.of(build, twin, build));

        assertThat(stored).extracting(StoredBuild::build).containsExactly(build);
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void theDatabaseRejectsADuplicateHash() {
        Build build = distinctBuilds(1, 1).getFirst();
        repository.insertAll(List.of(build));

        assertThat(jdbc.update(
                        "INSERT INTO build (weapon_id, parts_hash, ergonomics, recoil_vertical, recoil_horizontal, weight)"
                                + " VALUES (?, ?, 0, 0, 0, 0) ON CONFLICT DO NOTHING",
                        MP5,
                        build.partsHash()))
                .isZero();
    }

    @Test
    void anEmptyBatchStoresNothing() {
        assertThat(repository.insertAll(List.of())).isEmpty();
        assertThat(repository.count()).isZero();
    }

    private long partRows() {
        return jdbc.queryForObject("SELECT count(*) FROM build_part", Long.class);
    }

    private List<Build> distinctBuilds(int count, long seed) {
        BuildGenerator generator = new BuildGenerator();
        SplittableRandom random = new SplittableRandom(seed);
        Map<String, Build> builds = new LinkedHashMap<>();
        while (builds.size() < count) {
            Build build = generator.generate(mp5, random).orElseThrow();
            builds.putIfAbsent(build.partsHash(), build);
        }
        return new ArrayList<>(builds.values());
    }

    static ItemsPayload mp5Tree() {
        ObjectMapper mapper = new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        try (InputStream items = resource("items.json");
                InputStream translations = resource("items_en.json")) {
            return mapper.readValue(items, ItemsPayload.class).translate(mapper.readValue(translations, Translations.class));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static InputStream resource(String name) {
        return BuildRepositoryIT.class.getResourceAsStream("/tarkovdev/mp5-tree/" + name);
    }
}
