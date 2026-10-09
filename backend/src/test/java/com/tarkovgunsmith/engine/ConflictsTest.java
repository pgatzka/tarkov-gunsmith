package com.tarkovgunsmith.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.tarkovgunsmith.engine.CompatibilityGraph.Part;
import com.tarkovgunsmith.engine.CompatibilityGraph.Slot;
import com.tarkovgunsmith.engine.Conflicts.Conflict;
import com.tarkovgunsmith.engine.Conflicts.Type;
import com.tarkovgunsmith.engine.GraphItem.SlotDefinition;
import com.tarkovgunsmith.gamedata.ItemKind;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Conflict rules on real item pairs from {@code /regular/items} (2026-10-09): ids, categories and
 * conflict lists as recorded, cut down to what the rules look at. Only the host weapon is made up,
 * so that unrelated parts have slots to sit in.
 */
class ConflictsTest {

    // categories (real ids), each with its parent
    static final String ITEM = "54009119af1c881c07000029";
    static final String COMPOUND_ITEM = "566162e44bdc2d3f298b4573";
    static final String WEAPON = "5422acb9af1c889c16000029";
    static final String REVOLVER = "617f1ef5e8b54b0998387733";
    static final String WEAPON_MOD = "5448fe124bdc2da5018b4567";
    static final String FUNCTIONAL_MOD = "550aa4154bdc2dd8348b456b";
    static final String GEAR_MOD = "55802f3e4bdc2de7118b4584";
    static final String MOUNT = "55818b224bdc2dde698b456f";
    static final String SIGHTS = "5448fe7a4bdc2d6f028b456b";
    static final String SCOPE = "55818ae44bdc2dde698b456c";
    static final String REFLEX_SIGHT = "55818ad54bdc2ddc698b4569";
    static final String SPECIAL_SCOPE = "55818aeb4bdc2ddc698b456a";
    static final String NIGHT_VISION = "5a2c3a9486f774688b05e574";
    static final String EQUIPMENT = "543be5f84bdc2dd4348b456a";
    static final String FACE_COVER = "5a341c4686f77469e155819e";

    static final CategoryTree CATEGORIES = new CategoryTree(categories(
            ITEM, null,
            COMPOUND_ITEM, ITEM,
            WEAPON, COMPOUND_ITEM,
            REVOLVER, WEAPON,
            WEAPON_MOD, COMPOUND_ITEM,
            FUNCTIONAL_MOD, WEAPON_MOD,
            GEAR_MOD, WEAPON_MOD,
            MOUNT, GEAR_MOD,
            SIGHTS, FUNCTIONAL_MOD,
            SCOPE, SIGHTS,
            REFLEX_SIGHT, SIGHTS,
            SPECIAL_SCOPE, SIGHTS,
            NIGHT_VISION, SPECIAL_SCOPE,
            EQUIPMENT, COMPOUND_ITEM,
            FACE_COVER, EQUIPMENT));

    // items (real ids)
    static final String RSH_12 = "633ec7c2a6918cb895019c6c";
    static final String RSH_12_SCOPE_SLOT = "633ec892a6918cb895019c73";
    static final String MARCH_TACTICAL = "57c5ac0824597754771e88a9";
    static final String EOTECH_553 = "570fd6c2d2720bc6458b457f";
    static final String VOMZ_PILAD = "5dff772da3651922b360bf91";
    static final String AIMPOINT_LRP_MOUNT = "5c7d55f52e221644f31bff6a";
    static final String AIMPOINT_LRP_MOUNT_SCOPE_SLOT = "5c7d55f52e221644f31bff6c";
    static final String AIMPOINT_COMPM4 = "5c7d55de2e221644f31bff68";
    static final String PVS_14 = "57235b6f24597759bf5a30f1";
    static final String MSA_RIOT_GAS_MASK = "6a83168651cfbe231b091a0f";

    static final List<String> EVERYTHING = List.of(
            MARCH_TACTICAL, EOTECH_553, VOMZ_PILAD, AIMPOINT_LRP_MOUNT, AIMPOINT_COMPM4, PVS_14, MSA_RIOT_GAS_MASK);

    // the RSh-12 lists two scopes in conflictingItems
    static final GraphItem RSH_12_ITEM = item(
            RSH_12, ItemKind.WEAPON, REVOLVER,
            Set.of(MARCH_TACTICAL, "5a37cb10c4a282329a73b4e7"), Set.of(), Set.of(),
            new SlotDefinition(RSH_12_SCOPE_SLOT, "mod_scope", "Scope", false, allow(EVERYTHING)));

    // made up: one slot per part the tests put next to each other
    static final GraphItem HOST = item(
            "host", ItemKind.WEAPON, WEAPON, Set.of(), Set.of(), Set.of(),
            hostSlot("a"), hostSlot("b"), hostSlot("c"));

    // the scopes list the reflex sights; the reflex sights list nothing
    static final GraphItem MARCH_TACTICAL_ITEM = item(
            MARCH_TACTICAL, ItemKind.MOD, SCOPE,
            Set.of("591c4efa86f7741030027726", "570fd79bd2720bc7458b4583", EOTECH_553, AIMPOINT_LRP_MOUNT),
            Set.of(), Set.of());
    static final GraphItem EOTECH_553_ITEM = item(EOTECH_553, ItemKind.MOD, REFLEX_SIGHT, Set.of(), Set.of(), Set.of());

    // the VOMZ Pilad 4x32 needs the Aimpoint LRP mount's scope slot empty
    static final GraphItem VOMZ_PILAD_ITEM = item(
            VOMZ_PILAD, ItemKind.MOD, SCOPE,
            Set.of("591c4efa86f7741030027726", "570fd79bd2720bc7458b4583", EOTECH_553),
            Set.of(AIMPOINT_LRP_MOUNT_SCOPE_SLOT), Set.of());
    static final GraphItem AIMPOINT_LRP_MOUNT_ITEM = item(
            AIMPOINT_LRP_MOUNT, ItemKind.MOD, MOUNT, Set.of(), Set.of(), Set.of(),
            new SlotDefinition(AIMPOINT_LRP_MOUNT_SCOPE_SLOT, "mod_scope", "Scope", false, allow(List.of(AIMPOINT_COMPM4))));
    static final GraphItem AIMPOINT_COMPM4_ITEM = item(AIMPOINT_COMPM4, ItemKind.MOD, REFLEX_SIGHT, Set.of(), Set.of(), Set.of());

    // No weapon mod uses conflictingCategories in the live data; the only users are headsets, masks
    // and mandibles. The MSA riot gas mask (gear, stored here as a mod so the graph keeps it) excludes
    // night and thermal vision, and the AN/PVS-14 is a night vision mod.
    static final GraphItem MSA_RIOT_GAS_MASK_ITEM = item(
            MSA_RIOT_GAS_MASK, ItemKind.MOD, FACE_COVER,
            Set.of("5ca2113f86f7740b2547e1d2", "5f60c85b58eff926626a60f7", "5aa7e373e5b5b000137b76f0"),
            Set.of("6a670ae11ab6ac490a0b9a9b", "6a670ae11ab6ac490a0b9a9e", "689dbded6c7e684817080c2b"),
            Set.of(NIGHT_VISION, "5d21f59b6dbe99052b54ef83"));
    static final GraphItem PVS_14_ITEM = item(PVS_14, ItemKind.MOD, NIGHT_VISION, Set.of(), Set.of(), Set.of());

    final CompatibilityGraph graph = CompatibilityGraph.build(
            CATEGORIES,
            List.of(RSH_12_ITEM, HOST, MARCH_TACTICAL_ITEM, EOTECH_553_ITEM, VOMZ_PILAD_ITEM, AIMPOINT_LRP_MOUNT_ITEM,
                    AIMPOINT_COMPM4_ITEM, MSA_RIOT_GAS_MASK_ITEM, PVS_14_ITEM));

    @Test
    void conflictingItemsWorkInBothDirections() {
        Placement scope = onHost("a", MARCH_TACTICAL);
        Placement reflex = onHost("b", EOTECH_553);

        // only the scope lists the reflex sight
        assertThat(Conflicts.between(scope, reflex)).contains(new Conflict(Type.ITEM, scope, reflex));
        assertThat(Conflicts.between(reflex, scope)).contains(new Conflict(Type.ITEM, scope, reflex));
    }

    @Test
    void conflictingItemsOfTheWeaponApplyToItsMods() {
        Placement rsh12 = Placement.root(part(RSH_12));
        Placement scope = new Placement(slot(RSH_12, "mod_scope"), part(MARCH_TACTICAL));

        assertThat(Conflicts.between(rsh12, scope)).contains(new Conflict(Type.ITEM, rsh12, scope));
        assertThat(Conflicts.between(scope, rsh12)).contains(new Conflict(Type.ITEM, rsh12, scope));
        assertThat(Conflicts.between(rsh12, new Placement(slot(RSH_12, "mod_scope"), part(VOMZ_PILAD)))).isEmpty();
    }

    @Test
    void conflictingSlotIdsKeepThatSlotEmptyInBothDirections() {
        Placement pilad = onHost("a", VOMZ_PILAD);
        Placement mount = onHost("b", AIMPOINT_LRP_MOUNT);
        Placement reflexOnMount = new Placement(slot(AIMPOINT_LRP_MOUNT, "mod_scope"), part(AIMPOINT_COMPM4));

        assertThat(Conflicts.between(pilad, reflexOnMount)).contains(new Conflict(Type.SLOT, pilad, reflexOnMount));
        assertThat(Conflicts.between(reflexOnMount, pilad)).contains(new Conflict(Type.SLOT, pilad, reflexOnMount));
        // the mount itself and the same reflex sight elsewhere are fine
        assertThat(Conflicts.between(pilad, mount)).isEmpty();
        assertThat(Conflicts.between(pilad, onHost("c", AIMPOINT_COMPM4))).isEmpty();
    }

    @Test
    void conflictingCategoriesWorkInBothDirections() {
        Placement mask = onHost("a", MSA_RIOT_GAS_MASK);
        Placement nightVision = onHost("b", PVS_14);

        assertThat(Conflicts.between(mask, nightVision)).contains(new Conflict(Type.CATEGORY, mask, nightVision));
        assertThat(Conflicts.between(nightVision, mask)).contains(new Conflict(Type.CATEGORY, mask, nightVision));
        assertThat(Conflicts.between(mask, onHost("b", EOTECH_553))).isEmpty();
    }

    @Test
    void conflictingCategoriesIncludeDescendantCategories() {
        // the PVS-14 is stored with "night vision" only; "special scope" is its parent
        GraphItem noSpecialScopes = item("no-special-scopes", ItemKind.MOD, MOUNT, Set.of(), Set.of(), Set.of(SPECIAL_SCOPE));
        CompatibilityGraph graph = CompatibilityGraph.build(CATEGORIES, List.of(noSpecialScopes, PVS_14_ITEM, EOTECH_553_ITEM));
        Placement mount = new Placement(null, graph.part("no-special-scopes").orElseThrow());

        assertThat(Conflicts.between(mount, new Placement(null, graph.part(PVS_14).orElseThrow())))
                .map(Conflict::type)
                .contains(Type.CATEGORY);
        assertThat(Conflicts.between(mount, new Placement(null, graph.part(EOTECH_553).orElseThrow()))).isEmpty();
    }

    @Test
    void findListsEveryConflictingPairOfABuild() {
        Placement rsh12 = Placement.root(part(RSH_12));
        Placement scope = new Placement(slot(RSH_12, "mod_scope"), part(MARCH_TACTICAL));
        Placement reflex = onHost("a", EOTECH_553);
        Placement nightVision = onHost("b", PVS_14);

        assertThat(Conflicts.find(List.of(rsh12, scope, reflex, nightVision)))
                .containsExactly(new Conflict(Type.ITEM, rsh12, scope), new Conflict(Type.ITEM, scope, reflex));
        assertThat(Conflicts.find(List.of(rsh12, reflex, nightVision))).isEmpty();
    }

    @Test
    void conflictsWithAnyChecksACandidateAgainstTheChosenParts() {
        List<Placement> chosen = List.of(onHost("a", VOMZ_PILAD), onHost("b", AIMPOINT_LRP_MOUNT));

        assertThat(Conflicts.conflictsWithAny(new Placement(slot(AIMPOINT_LRP_MOUNT, "mod_scope"), part(AIMPOINT_COMPM4)), chosen))
                .isTrue();
        assertThat(Conflicts.conflictsWithAny(onHost("c", EOTECH_553), chosen)).isTrue();
        assertThat(Conflicts.conflictsWithAny(onHost("c", PVS_14), chosen)).isFalse();
        assertThat(Conflicts.conflictsWithAny(onHost("c", PVS_14), List.of())).isFalse();
    }

    private Placement onHost(String slot, String partId) {
        return new Placement(slot("host", slot), part(partId));
    }

    private Part part(String id) {
        return graph.part(id).orElseThrow();
    }

    private Slot slot(String partId, String nameId) {
        Slot slot = part(partId).slots().stream()
                .filter(s -> s.nameId().equals(nameId))
                .findFirst()
                .orElseThrow();
        assertThat(slot.candidates()).isNotEmpty();
        return slot;
    }

    private static SlotDefinition hostSlot(String nameId) {
        return new SlotDefinition("host-" + nameId, nameId, nameId, false, allow(EVERYTHING));
    }

    private static SlotFilter allow(List<String> items) {
        return new SlotFilter(items, List.of(), List.of(), List.of());
    }

    private static GraphItem item(
            String id,
            ItemKind kind,
            String category,
            Set<String> conflictingItems,
            Set<String> conflictingSlotIds,
            Set<String> conflictingCategories,
            SlotDefinition... slots) {
        Double base = kind == ItemKind.WEAPON ? 50.0 : null;
        return new GraphItem(
                id, kind, List.of(category), 0.1, base, base, base, 0, 0, List.of(slots),
                conflictingItems, conflictingSlotIds, conflictingCategories);
    }

    private static Map<String, String> categories(String... idsAndParents) {
        Map<String, String> parents = new HashMap<>();
        for (int i = 0; i < idsAndParents.length; i += 2) {
            parents.put(idsAndParents[i], idsAndParents[i + 1]);
        }
        return parents;
    }
}
