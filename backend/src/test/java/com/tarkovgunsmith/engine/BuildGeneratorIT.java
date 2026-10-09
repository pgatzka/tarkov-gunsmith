package com.tarkovgunsmith.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tarkovgunsmith.TestcontainersConfiguration;
import com.tarkovgunsmith.engine.Build.BuildPart;
import com.tarkovgunsmith.engine.CompatibilityGraph.Part;
import com.tarkovgunsmith.gamedata.GameDataImporter;
import com.tarkovgunsmith.tarkovdev.dto.ItemsPayload;
import com.tarkovgunsmith.tarkovdev.dto.Translations;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Property test of {@link BuildGenerator} on every weapon, using {@code tarkovdev/all-guns}: every
 * gun and every mod, recorded from the live {@code /regular/items} (2026-10-09; stat, slot and
 * conflict fields only). Generates {@value #BUILDS_PER_WEAPON} builds per weapon and checks each
 * one with {@link BuildCheck}.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class BuildGeneratorIT {

    private static final Logger log = LoggerFactory.getLogger(BuildGeneratorIT.class);

    static final int BUILDS_PER_WEAPON = 10_000;

    @Autowired
    GameDataImporter importer;

    @Autowired
    CompatibilityGraphLoader loader;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void everyGeneratedBuildIsValid() {
        jdbc.execute("TRUNCATE item, item_category, trader, offer, data_version");
        importer.importItems(allGuns());
        CompatibilityGraph graph = loader.load();
        BuildGenerator generator = new BuildGenerator();

        long start = System.nanoTime();
        List<Result> results = graph.weapons().parallelStream()
                .map(weapon -> generate(generator, weapon, new SplittableRandom(weapon.id().hashCode())))
                .toList();
        long millis = (System.nanoTime() - start) / 1_000_000;

        List<Result> withoutBuilds = results.stream().filter(r -> r.builds() == 0).toList();
        log.info(
                "Generated {} builds for {} weapons in {} ms; weapons without a valid build: {}",
                results.stream().mapToInt(Result::builds).sum(),
                results.size(),
                millis,
                withoutBuilds.stream().map(Result::weaponId).toList());
        results.stream()
                .sorted((a, b) -> Integer.compare(a.distinct(), b.distinct()))
                .limit(10)
                .forEach(r -> log.info("Fewest distinct builds: {}", r));
        log.info("Builds with a part at MAX_DEPTH: {}", results.stream().mapToInt(Result::atMaxDepth).sum());

        // all guns except the excluded launchers (2 grenade launchers, 2 rocket launchers, 4 flare
        // pistols, the RSP/ROP cartridges and the M32A1)
        assertThat(results).hasSize(161);
        assertThat(results).allSatisfy(r -> assertThat(r.violations()).as("%s", r.weaponId()).isEmpty());
        assertThat(withoutBuilds).isEmpty();
        assertThat(results).allSatisfy(r -> assertThat(r.builds()).as("%s", r.weaponId()).isEqualTo(BUILDS_PER_WEAPON));
        // the depth limit only guards against endless nesting; real builds never get near it
        assertThat(results).allSatisfy(r -> assertThat(r.atMaxDepth()).as("%s", r.weaponId()).isZero());
    }

    /**
     * @param violations the first few problems found, with the build they were found in
     * @param distinct number of distinct parts hashes
     * @param atMaxDepth builds with a part {@link BuildGenerator#MAX_DEPTH} slots deep
     */
    record Result(String weaponId, int builds, int distinct, int maxParts, int atMaxDepth, Map<String, List<String>> violations) {

        @Override
        public String toString() {
            return "%s: %d builds, %d distinct, up to %d parts".formatted(weaponId, builds, distinct, maxParts);
        }
    }

    private static Result generate(BuildGenerator generator, Part weapon, SplittableRandom random) {
        int builds = 0;
        int maxParts = 0;
        int atMaxDepth = 0;
        Set<String> hashes = new HashSet<>();
        Map<String, List<String>> violations = new TreeMap<>();
        for (int i = 0; i < BUILDS_PER_WEAPON; i++) {
            Optional<Build> generated = generator.generate(weapon, random);
            if (generated.isEmpty()) {
                // the generator only gives up when no build exists or it ran out of attempts
                if (builds == 0) {
                    break;
                }
                continue;
            }
            Build build = generated.get();
            builds++;
            hashes.add(build.partsHash());
            maxParts = Math.max(maxParts, build.parts().size());
            if (build.parts().stream().anyMatch(p -> depth(p) == BuildGenerator.MAX_DEPTH)) {
                atMaxDepth++;
            }
            List<String> problems = BuildCheck.violations(build);
            if (!problems.isEmpty() && violations.size() < 5) {
                violations.put(build.parts().stream().map(BuildPart::toString).collect(Collectors.joining(", ")), problems);
            }
        }
        return new Result(weapon.id(), builds, hashes.size(), maxParts, atMaxDepth, violations);
    }

    private static int depth(BuildPart part) {
        return part.slotPath().isEmpty() ? 0 : part.slotPath().split("/").length;
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
        return BuildGeneratorIT.class.getResourceAsStream("/tarkovdev/all-guns/" + name);
    }
}
