package com.tarkovgunsmith.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.tarkovgunsmith.engine.CompatibilityGraph.Part;
import com.tarkovgunsmith.engine.CompatibilityGraph.Slot;
import com.tarkovgunsmith.engine.GraphItem.SlotDefinition;
import com.tarkovgunsmith.gamedata.ItemKind;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The MP5 default preset ("HK MP5 9x19 submachine gun (Navy 3 Round Burst) Default", stored as
 * ergonomics 72, recoil 43 / 209, weight 2.71 kg) with the stats recorded from {@code
 * /regular/items} (2026-10-09). The slot tree is cut down to one slot per part.
 */
class StatCalculatorTest {

    static final String MP5 = "5926bb2186f7744b1c6c6e60";
    static final String MAGAZINE = "5926c3b286f774640d189b6b";
    static final String UPPER_RECEIVER = "5926c0df86f77462f647f764";
    static final String HANDGUARD = "5926c36d86f77467a92a8629";
    static final String REAR_SIGHT = "5926d2be86f774134d668e4e";
    static final String STOCK = "5926d3c686f77410de68ebc8";
    static final String MUZZLE = "5926e16e86f7742f5a0f7ecb";
    static final String CHARGING_HANDLE = "5926c32286f774616e42de99";

    static final List<String> MODS = List.of(MAGAZINE, UPPER_RECEIVER, HANDGUARD, REAR_SIGHT, STOCK, MUZZLE, CHARGING_HANDLE);

    final CompatibilityGraph graph = CompatibilityGraph.build(new CategoryTree(Map.of()), List.of(
            new GraphItem(MP5, ItemKind.WEAPON, List.of(), 1.21, 50.0, 56.0, 275.0, 0, 0,
                    MODS.stream().map(StatCalculatorTest::slotFor).toList(), Set.of(), Set.of(), Set.of()),
            mod(MAGAZINE, 0.17, -1, 0),
            mod(UPPER_RECEIVER, 0.544, 5, 0),
            mod(HANDGUARD, 0.17, 10, 0),
            mod(REAR_SIGHT, 0.04, 0, 0),
            mod(STOCK, 0.37, 6, -24),
            mod(MUZZLE, 0.08, 2, 0),
            mod(CHARGING_HANDLE, 0.126, 0, 0)));

    @Test
    void matchesTheMp5DefaultPreset() {
        BuildStats stats = StatCalculator.compute(part(MP5), MODS.stream().map(this::part).toList());

        assertThat(stats.ergonomics()).isCloseTo(72, within(1e-9));
        // 56 × (1 − 24 / 100) = 42.56 and 275 × 0.76 = 209; the preset stores them rounded
        assertThat(stats.recoilVertical()).isCloseTo(42.56, within(1e-9));
        assertThat(stats.recoilHorizontal()).isCloseTo(209, within(1e-9));
        assertThat(stats.weight()).isCloseTo(2.71, within(1e-9));
    }

    @Test
    void bareWeaponHasItsBaseStats() {
        assertThat(StatCalculator.compute(part(MP5), List.of())).isEqualTo(new BuildStats(50, 56, 275, 1.21));
    }

    @Test
    void aModInTwoSlotsCountsTwice() {
        BuildStats once = StatCalculator.compute(part(MP5), List.of(part(STOCK)));
        BuildStats twice = StatCalculator.compute(part(MP5), List.of(part(STOCK), part(STOCK)));

        assertThat(twice.ergonomics() - once.ergonomics()).isCloseTo(6, within(1e-9));
        assertThat(twice.recoilVertical()).isCloseTo(56 * (1 - 0.48), within(1e-9));
        assertThat(twice.weight() - once.weight()).isCloseTo(0.37, within(1e-9));
    }

    @Test
    void computesABuildOfPlacements() {
        Part mp5 = part(MP5);
        List<Placement> build = new ArrayList<>(List.of(Placement.root(mp5)));
        for (int i = 0; i < MODS.size(); i++) {
            build.add(new Placement(mp5.slots().get(i), part(MODS.get(i))));
        }

        assertThat(StatCalculator.compute(build))
                .isEqualTo(StatCalculator.compute(mp5, MODS.stream().map(this::part).toList()));
    }

    @Test
    void aBuildNeedsExactlyOneRoot() {
        Part mp5 = part(MP5);
        Slot stockSlot = mp5.slots().get(MODS.indexOf(STOCK));

        assertThatThrownBy(() -> StatCalculator.compute(List.of(new Placement(stockSlot, part(STOCK)))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StatCalculator.compute(List.of(Placement.root(mp5), Placement.root(mp5))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StatCalculator.compute(part(STOCK), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private Part part(String id) {
        return graph.part(id).orElseThrow();
    }

    private static SlotDefinition slotFor(String mod) {
        return new SlotDefinition("slot-" + mod, "slot-" + mod, "slot", false,
                new SlotFilter(List.of(mod), List.of(), List.of(), List.of()));
    }

    private static GraphItem mod(String id, double weight, double ergonomicsModifier, double recoilModifier) {
        return new GraphItem(id, ItemKind.MOD, List.of(), weight, null, null, null, ergonomicsModifier, recoilModifier,
                List.of(), Set.of(), Set.of(), Set.of());
    }
}
