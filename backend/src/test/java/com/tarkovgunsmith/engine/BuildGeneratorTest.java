package com.tarkovgunsmith.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tarkovgunsmith.engine.Build.BuildPart;
import com.tarkovgunsmith.engine.CompatibilityGraph.Part;
import com.tarkovgunsmith.engine.GraphItem.SlotDefinition;
import com.tarkovgunsmith.gamedata.ItemKind;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** The random walk on small synthetic graphs, one rule per weapon. */
class BuildGeneratorTest {

    static final int BUILDS = 2_000;

    final BuildGenerator generator = new BuildGenerator();
    final Random random = new Random(42);

    @Test
    void fillsRequiredSlotsAndOptionalOnesSometimes() {
        CompatibilityGraph graph = graph(
                weapon("gun", slot("gun", "mod_receiver", true, "receiver"), slot("gun", "mod_sight", false, "sight-a", "sight-b")),
                mod("receiver", slot("receiver", "mod_barrel", true, "barrel")),
                mod("barrel"),
                mod("sight-a"),
                mod("sight-b"));

        List<Build> builds = generate(graph, "gun");

        assertThat(builds).allSatisfy(b -> assertThat(paths(b)).containsEntry("mod_receiver", "receiver")
                .containsEntry("mod_receiver/mod_barrel", "barrel"));
        // "empty", sight-a and sight-b are equally likely
        Map<String, Long> sights = builds.stream()
                .collect(Collectors.groupingBy(b -> paths(b).getOrDefault("mod_sight", "empty"), Collectors.counting()));
        assertThat(sights).containsOnlyKeys("empty", "sight-a", "sight-b");
        assertThat(sights.values()).allSatisfy(n -> assertThat(n).isBetween(BUILDS / 3 - 150L, BUILDS / 3 + 150L));
    }

    @Test
    void partsComeInTreeOrderWithTheirSlotPaths() {
        CompatibilityGraph graph = graph(
                weapon("gun", slot("gun", "mod_receiver", true, "receiver"), slot("gun", "mod_stock", true, "stock")),
                mod("receiver", slot("receiver", "mod_barrel", true, "barrel")),
                mod("barrel"),
                mod("stock"));

        Build build = generator.generate(weapon(graph, "gun"), random).orElseThrow();

        assertThat(build.parts()).extracting(BuildPart::slotPath)
                .containsExactly("", "mod_receiver", "mod_receiver/mod_barrel", "mod_stock");
        assertThat(build.parts()).extracting(BuildPart::parentPath)
                .containsExactly(null, "", "mod_receiver", "");
        assertThat(build.weapon().id()).isEqualTo("gun");
        assertThat(build.stats()).isEqualTo(StatCalculator.compute(build.placements()));
    }

    @Test
    void skipsConflictingCandidates() {
        // sight-a lists sight-b; the walk must never put both on
        CompatibilityGraph graph = graph(
                weapon("gun", slot("gun", "mod_sight", false, "sight-a"), slot("gun", "mod_sight_rear", false, "sight-b")),
                mod("sight-a", Set.of("sight-b"), Set.of()),
                mod("sight-b"));

        List<Build> builds = generate(graph, "gun");

        assertThat(builds).noneSatisfy(b -> assertThat(paths(b)).containsKeys("mod_sight", "mod_sight_rear"));
        assertThat(builds).anySatisfy(b -> assertThat(paths(b)).containsKey("mod_sight"));
        assertThat(builds).anySatisfy(b -> assertThat(paths(b)).containsKey("mod_sight_rear"));
    }

    @Test
    void keepsSlotsListedInConflictingSlotIdsEmpty() {
        // the handguard blocks the gun's mount slot, whichever is decided first
        CompatibilityGraph graph = graph(
                weapon("gun", slot("gun", "mod_mount", false, "mount"), slot("gun", "mod_handguard", false, "handguard")),
                mod("mount"),
                mod("handguard", Set.of(), Set.of("gun-mod_mount")));

        List<Build> builds = generate(graph, "gun");

        assertThat(builds).noneSatisfy(b -> assertThat(paths(b)).containsKeys("mod_mount", "mod_handguard"));
        assertThat(builds).anySatisfy(b -> assertThat(paths(b)).containsKey("mod_handguard"));
    }

    @Test
    void backtracksOutOfDeadEnds() {
        // receiver-a needs barrel-a, which conflicts with the only stock; the stock slot is decided
        // after the receiver's subtree, so every walk through receiver-a has to back out of it
        CompatibilityGraph graph = graph(
                weapon("gun", slot("gun", "mod_receiver", true, "receiver-a", "receiver-b"), slot("gun", "mod_stock", true, "stock")),
                mod("receiver-a", slot("receiver-a", "mod_barrel", true, "barrel-a")),
                mod("receiver-b"),
                mod("barrel-a", Set.of("stock"), Set.of()),
                mod("stock"));

        List<Build> builds = generate(graph, "gun");

        assertThat(builds).allSatisfy(b -> assertThat(paths(b)).containsEntry("mod_receiver", "receiver-b"));
    }

    @Test
    void reportsWeaponsWithoutAnyValidBuild() {
        CompatibilityGraph graph = graph(
                // the only receiver conflicts with the only stock
                weapon("gun", slot("gun", "mod_receiver", true, "receiver"), slot("gun", "mod_stock", true, "stock")),
                mod("receiver", Set.of("stock"), Set.of()),
                mod("stock"),
                // a required slot that nothing fits into
                weapon("empty-gun", slot("empty-gun", "mod_receiver", true)));

        assertThat(generator.generate(weapon(graph, "gun"), random)).isEmpty();
        assertThat(generator.generate(weapon(graph, "empty-gun"), random)).isEmpty();
    }

    @Test
    void stopsNestingAtMaxDepth() {
        // a rail takes another rail, without end
        CompatibilityGraph graph = graph(
                weapon("gun", slot("gun", "mod_mount", true, "rail")),
                mod("rail", slot("rail", "mod_mount", false, "rail")),
                weapon("required-gun", slot("required-gun", "mod_mount", true, "required-rail")),
                mod("required-rail", slot("required-rail", "mod_mount", true, "required-rail")));

        List<Build> builds = generate(graph, "gun");

        assertThat(builds).allSatisfy(b -> assertThat(b.parts()).hasSizeBetween(2, BuildGenerator.MAX_DEPTH + 1));
        assertThat(builds).anySatisfy(b -> assertThat(b.parts()).hasSize(BuildGenerator.MAX_DEPTH + 1));
        // every rail requires another one: no finite build
        assertThat(generator.generate(weapon(graph, "required-gun"), random)).isEmpty();
    }

    @Test
    void sameSeedSameBuild() {
        CompatibilityGraph graph = graph(
                weapon("gun", slot("gun", "mod_sight", false, "sight-a", "sight-b"), slot("gun", "mod_stock", false, "stock")),
                mod("sight-a"),
                mod("sight-b"),
                mod("stock"));

        Optional<Build> first = generator.generate(weapon(graph, "gun"), new Random(7));
        Optional<Build> second = generator.generate(weapon(graph, "gun"), new Random(7));

        assertThat(first).isPresent();
        assertThat(second.orElseThrow().partsHash()).isEqualTo(first.orElseThrow().partsHash());
    }

    @Test
    void partsHashIsCanonical() {
        CompatibilityGraph graph = graph(
                weapon("gun", slot("gun", "mod_sight", false, "sight"), slot("gun", "mod_stock", false, "stock")),
                mod("sight"),
                mod("stock"),
                weapon("other-gun", slot("other-gun", "mod_sight", false, "sight"), slot("other-gun", "mod_stock", false, "stock")));
        Part gun = weapon(graph, "gun");
        Part otherGun = weapon(graph, "other-gun");
        BuildPart sight = new BuildPart("mod_sight", new Placement(gun.slots().get(0), graph.part("sight").orElseThrow()));
        BuildPart stock = new BuildPart("mod_stock", new Placement(gun.slots().get(1), graph.part("stock").orElseThrow()));
        BuildPart root = new BuildPart("", Placement.root(gun));

        String hash = Build.partsHash(List.of(root, sight, stock));

        assertThat(hash).hasSize(64).isEqualTo(Build.partsHash(List.of(stock, root, sight)));
        assertThat(hash).isNotEqualTo(Build.partsHash(List.of(root, sight)));
        // the same part in another slot is another build
        assertThat(hash).isNotEqualTo(Build.partsHash(List.of(root, new BuildPart("mod_stock", sight.placement()), stock)));
        // and so is the same set of mods on another weapon
        assertThat(hash).isNotEqualTo(Build.partsHash(List.of(new BuildPart("", Placement.root(otherGun)), sight, stock)));
    }

    @Test
    void rejectsMods() {
        CompatibilityGraph graph = graph(mod("sight"));

        assertThatThrownBy(() -> generator.generate(graph.part("sight").orElseThrow(), random))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private List<Build> generate(CompatibilityGraph graph, String weaponId) {
        Part weapon = weapon(graph, weaponId);
        List<Build> builds = new ArrayList<>();
        for (int i = 0; i < BUILDS; i++) {
            Build build = generator.generate(weapon, random).orElseThrow();
            assertThat(BuildCheck.violations(build)).as("%s", build.parts()).isEmpty();
            builds.add(build);
        }
        return Collections.unmodifiableList(builds);
    }

    /** Item id by slot path, without the weapon. */
    private static Map<String, String> paths(Build build) {
        return build.parts().stream()
                .filter(p -> !p.slotPath().isEmpty())
                .collect(Collectors.toMap(BuildPart::slotPath, p -> p.placement().part().id()));
    }

    private static Part weapon(CompatibilityGraph graph, String id) {
        return graph.weapon(id).orElseThrow();
    }

    private static CompatibilityGraph graph(GraphItem... items) {
        return CompatibilityGraph.build(new CategoryTree(Map.of()), List.of(items));
    }

    private static GraphItem weapon(String id, SlotDefinition... slots) {
        return new GraphItem(
                id, ItemKind.WEAPON, List.of("weapon"), 3, 50.0, 100.0, 200.0, 0, 0, List.of(slots), Set.of(), Set.of(), Set.of());
    }

    private static GraphItem mod(String id, SlotDefinition... slots) {
        return mod(id, Set.of(), Set.of(), slots);
    }

    private static GraphItem mod(String id, Set<String> conflictingItems, Set<String> conflictingSlotIds, SlotDefinition... slots) {
        return new GraphItem(
                id, ItemKind.MOD, List.of("mod"), 0.1, null, null, null, 1, -1, List.of(slots), conflictingItems,
                conflictingSlotIds, Set.of());
    }

    /** A slot with id {@code <owner>-<nameId>} that accepts exactly {@code allowed}. */
    private static SlotDefinition slot(String owner, String nameId, boolean required, String... allowed) {
        return new SlotDefinition(
                owner + "-" + nameId, nameId, nameId, required, new SlotFilter(List.of(allowed), List.of(), List.of(), List.of()));
    }
}
