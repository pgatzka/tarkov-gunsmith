package com.tarkovgunsmith.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.tarkovgunsmith.engine.CompatibilityGraph.Part;
import com.tarkovgunsmith.engine.CompatibilityGraph.Slot;
import com.tarkovgunsmith.engine.GraphItem.SlotDefinition;
import com.tarkovgunsmith.gamedata.ItemKind;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Slot resolution on a small synthetic graph. The live data lists every allowed item explicitly, so
 * category filters are only exercised here and in {@link CompatibilityGraphLoaderIT}.
 */
class CompatibilityGraphTest {

    // item <- mod <- sight <- reflex
    //             <- muzzle
    static final CategoryTree CATEGORIES = new CategoryTree(categories(
            "item", null,
            "mod", "item",
            "sight", "mod",
            "reflex", "sight",
            "muzzle", "mod"));

    // mods carry only their most specific category; ancestors come from the tree
    static final GraphItem RED_DOT = mod("red-dot", "reflex");
    static final GraphItem HOLO = mod("holo", "reflex");
    static final GraphItem SCOPE = mod("scope", "sight");
    static final GraphItem BRAKE = mod("brake", "muzzle", slot("thread", false, filter(List.of("brake"), List.of(), List.of(), List.of())));
    static final GraphItem PRESET = new GraphItem(
            "preset", ItemKind.PRESET, List.of("sight"), 0, null, null, null, 0, 0, List.of(), Set.of(), Set.of(), Set.of());
    static final GraphItem OTHER_GUN = weapon("other-gun");
    static final GraphItem GUN = weapon(
            "gun",
            slot("sight", false, filter(List.of(), List.of("sight"), List.of("holo"), List.of())),
            slot("muzzle", true, filter(List.of("brake", "other-gun", "preset", "unknown"), List.of(), List.of(), List.of())),
            slot("mount", false, filter(List.of(), List.of("mod"), List.of(), List.of("reflex"))));

    final CompatibilityGraph graph =
            CompatibilityGraph.build(CATEGORIES, List.of(RED_DOT, HOLO, SCOPE, BRAKE, PRESET, OTHER_GUN, GUN));

    @Test
    void allowedCategoriesIncludeDescendantCategories() {
        assertThat(ids(slot("gun", "sight").candidates())).containsExactly("red-dot", "scope");
    }

    @Test
    void excludedCategoriesRemoveDescendantCategories() {
        assertThat(ids(slot("gun", "mount").candidates())).containsExactly("scope", "brake");
    }

    @Test
    void excludedItemsAreRemoved() {
        assertThat(ids(slot("gun", "sight").candidates())).doesNotContain("holo");
    }

    @Test
    void allowedItemsResolveToModsOnly() {
        // weapons, presets and ids that aren't stored are not candidates
        assertThat(ids(slot("gun", "muzzle").candidates())).containsExactly("brake");
    }

    @Test
    void partsKnowTheirCategoryAncestors() {
        assertThat(graph.part("red-dot").orElseThrow().categories()).containsExactly("reflex", "sight", "mod", "item");
    }

    @Test
    void slotsKeepTheirDefinition() {
        Slot muzzle = slot("gun", "muzzle");

        assertThat(muzzle.required()).isTrue();
        assertThat(muzzle.id()).isEqualTo("gun-muzzle");
        assertThat(muzzle.owner().id()).isEqualTo("gun");
        assertThat(slot("gun", "sight").required()).isFalse();
        assertThat(muzzle.accepts(graph.part("brake").orElseThrow())).isTrue();
        assertThat(muzzle.accepts(graph.part("scope").orElseThrow())).isFalse();
    }

    @Test
    void holdsWeaponsAndModsButNotPresets() {
        assertThat(ids(graph.weapons())).containsExactly("other-gun", "gun");
        assertThat(graph.size()).isEqualTo(6);
        assertThat(graph.part("preset")).isEmpty();
        assertThat(graph.weapon("gun")).isPresent();
        assertThat(graph.weapon("scope")).isEmpty();
    }

    @Test
    void reachableWalksSlotsRecursivelyAndSurvivesCycles() {
        // the brake's thread slot accepts the brake itself
        Part brake = graph.part("brake").orElseThrow();
        assertThat(brake.slots().getFirst().candidates()).containsExactly(brake);

        assertThat(ids(CompatibilityGraph.reachable(graph.weapon("gun").orElseThrow())))
                .containsExactlyInAnyOrder("gun", "red-dot", "scope", "brake");
    }

    @Test
    void exclusionWinsOverAllowance() {
        SlotFilter filter = filter(List.of("a"), List.of("sight"), List.of("a"), List.of("reflex"));

        assertThat(filter.allows("a", Set.of("mod"))).isFalse();
        assertThat(filter.allows("b", Set.of("reflex", "sight"))).isFalse();
        assertThat(filter.allows("b", Set.of("sight"))).isTrue();
        assertThat(filter.allows("b", Set.of("muzzle"))).isFalse();
    }

    @Test
    void categoryTreeToleratesCycles() {
        var tree = new CategoryTree(categories("a", "b", "b", "a"));

        assertThat(tree.withAncestors(List.of("a"))).containsExactly("a", "b");
    }

    private Slot slot(String partId, String nameId) {
        return graph.part(partId).orElseThrow().slots().stream()
                .filter(slot -> slot.nameId().equals(nameId))
                .findFirst()
                .orElseThrow();
    }

    private static List<String> ids(Collection<Part> parts) {
        return parts.stream().map(Part::id).toList();
    }

    private static GraphItem weapon(String id, SlotDefinition... slots) {
        return new GraphItem(
                id, ItemKind.WEAPON, List.of("item"), 1, 50.0, 100.0, 200.0, 0, 0, List.of(slots), Set.of(), Set.of(), Set.of());
    }

    private static GraphItem mod(String id, String category, SlotDefinition... slots) {
        return new GraphItem(
                id, ItemKind.MOD, List.of(category), 0.1, null, null, null, 1, -1, List.of(slots), Set.of(), Set.of(), Set.of());
    }

    // slot ids are unique per item in the game data; the tests only look at the gun's
    private static SlotDefinition slot(String nameId, boolean required, SlotFilter filter) {
        return new SlotDefinition("gun-" + nameId, nameId, nameId, required, filter);
    }

    private static SlotFilter filter(
            List<String> allowedItems, List<String> allowedCategories, List<String> excludedItems, List<String> excludedCategories) {
        return new SlotFilter(allowedItems, allowedCategories, excludedItems, excludedCategories);
    }

    private static Map<String, String> categories(String... idsAndParents) {
        Map<String, String> parents = new HashMap<>();
        for (int i = 0; i < idsAndParents.length; i += 2) {
            parents.put(idsAndParents[i], idsAndParents[i + 1]);
        }
        return parents;
    }
}
