package com.tarkovgunsmith.engine;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tarkovgunsmith.engine.CompatibilityGraph.Part;
import com.tarkovgunsmith.tarkovdev.dto.ContainedItemDto;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Validates {@link StatCalculator} against the game's presets (SPEC §3): for every stored preset,
 * computes the stats of its weapon with its parts and compares them with the preset's stored stats.
 *
 * <p>Tolerances: ergonomics are stored unrounded (e.g. 54.5), so they must match up to float
 * error. Recoil is stored rounded to a whole number, so the computed value may be up to 0.5 off
 * (a tie such as 60.5 can be stored as 60). Weight must match up to float error; it is only
 * checked when every part is a stored weapon or mod, because presets with loaded magazines also
 * list the ammo, which isn't imported.
 */
final class PresetCheck {

    static final double ERGONOMICS_TOLERANCE = 1e-6;
    static final double RECOIL_TOLERANCE = 0.5;
    static final double WEIGHT_TOLERANCE = 1e-6;

    private static final TypeReference<List<ContainedItemDto>> CONTAINED = new TypeReference<>() {};

    private PresetCheck() {}

    /**
     * @param weightChecked whether every part is a stored mod, so {@code computed.weight()} is comparable
     * @param duplicateErgonomics what parts listed more than once ({@code count > 1}) add to the
     *     ergonomics beyond their first copy
     */
    record Result(
            String presetId,
            String name,
            String weaponId,
            BuildStats stored,
            BuildStats computed,
            boolean weightChecked,
            double duplicateErgonomics) {

        boolean ergonomicsMatch() {
            return Math.abs(computed.ergonomics() - stored.ergonomics()) <= ERGONOMICS_TOLERANCE;
        }

        boolean recoilMatches() {
            return Math.abs(computed.recoilVertical() - stored.recoilVertical()) <= RECOIL_TOLERANCE + 1e-9
                    && Math.abs(computed.recoilHorizontal() - stored.recoilHorizontal()) <= RECOIL_TOLERANCE + 1e-9;
        }

        boolean weightMatches() {
            return !weightChecked || Math.abs(computed.weight() - stored.weight()) <= WEIGHT_TOLERANCE;
        }

        boolean matches() {
            return ergonomicsMatch() && recoilMatches() && weightMatches();
        }

        /**
         * The known tarkov.dev quirk: the stored ergonomics count a part listed twice only once
         * (while the stored weight counts both copies).
         */
        boolean ergonomicsOffByDuplicates() {
            return duplicateErgonomics != 0
                    && Math.abs(computed.ergonomics() - duplicateErgonomics - stored.ergonomics()) <= ERGONOMICS_TOLERANCE;
        }

        @Override
        public String toString() {
            return "%s %s: stored %s, computed %s".formatted(presetId, name, stored, computed);
        }
    }

    /** One result per stored preset, ordered by preset id. */
    static List<Result> run(JdbcClient jdbc, ObjectMapper objectMapper, CompatibilityGraph graph) {
        return jdbc.sql("""
                        SELECT id, name, base_item_id, ergonomics, recoil_vertical, recoil_horizontal, weight,
                               contains_items::text AS contains_items
                        FROM item WHERE kind = 'PRESET' ORDER BY id
                        """)
                .query((rs, n) -> {
                    String weaponId = rs.getString("base_item_id");
                    BuildStats stored = new BuildStats(
                            rs.getDouble("ergonomics"),
                            rs.getDouble("recoil_vertical"),
                            rs.getDouble("recoil_horizontal"),
                            rs.getDouble("weight"));
                    return check(rs.getString("id"), rs.getString("name"), graph.weapon(weaponId).orElseThrow(), stored,
                            contained(objectMapper, rs.getString("contains_items")), graph);
                })
                .list();
    }

    private static Result check(
            String presetId, String name, Part weapon, BuildStats stored, List<ContainedItemDto> contained,
            CompatibilityGraph graph) {
        List<Part> mods = new ArrayList<>();
        boolean weightChecked = true;
        double duplicateErgonomics = 0;
        for (ContainedItemDto entry : contained) {
            if (entry.item().equals(weapon.id())) {
                continue;
            }
            Optional<Part> mod = graph.part(entry.item()).filter(Part::isMod);
            if (mod.isEmpty()) {
                // ammo in a magazine: no modifiers, but its weight is part of the preset's
                weightChecked = false;
                continue;
            }
            for (int i = 0; i < entry.count(); i++) {
                mods.add(mod.get());
            }
            duplicateErgonomics += (entry.count() - 1) * mod.get().item().ergonomicsModifier();
        }
        return new Result(presetId, name, weapon.id(), stored, StatCalculator.compute(weapon, mods), weightChecked,
                duplicateErgonomics);
    }

    private static List<ContainedItemDto> contained(ObjectMapper objectMapper, String json) {
        try {
            return objectMapper.readValue(json, CONTAINED);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot read contains_items " + json, e);
        }
    }
}
