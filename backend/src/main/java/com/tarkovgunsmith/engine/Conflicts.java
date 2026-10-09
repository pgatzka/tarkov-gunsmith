package com.tarkovgunsmith.engine;

import com.tarkovgunsmith.engine.CompatibilityGraph.Part;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * The conflict rules (SPEC §2): two parts can't be in the same build when
 *
 * <ul>
 *   <li>one lists the other in {@code conflictingItems} ({@link Type#ITEM}),
 *   <li>one lists the slot the other sits in in {@code conflictingSlotIds}, i.e. that slot must stay
 *       empty while the first part is in the build ({@link Type#SLOT}), or
 *   <li>one lists a category of the other, or an ancestor of one, in {@code conflictingCategories}
 *       ({@link Type#CATEGORY}).
 * </ul>
 *
 * <p>The game data usually lists a conflict on one side only (a scope lists the reflex sights, but
 * not the other way round), so every rule is checked in both directions.
 */
public final class Conflicts {

    private Conflicts() {}

    public enum Type {
        ITEM,
        SLOT,
        CATEGORY
    }

    /**
     * Why two placements can't be in the same build.
     *
     * @param source the placement whose data lists the conflict
     * @param target the placement it conflicts with
     */
    public record Conflict(Type type, Placement source, Placement target) {}

    /** The first rule that keeps {@code a} and {@code b} out of the same build, checking both directions. */
    public static Optional<Conflict> between(Placement a, Placement b) {
        return oneWay(a, b).or(() -> oneWay(b, a));
    }

    /** Whether {@code candidate} conflicts with any of the placements already in a build. */
    public static boolean conflictsWithAny(Placement candidate, Collection<Placement> chosen) {
        for (Placement placement : chosen) {
            if (between(candidate, placement).isPresent()) {
                return true;
            }
        }
        return false;
    }

    /** Every conflicting pair in {@code build}; empty for a conflict-free build. */
    public static List<Conflict> find(List<Placement> build) {
        List<Conflict> conflicts = new ArrayList<>();
        for (int i = 0; i < build.size(); i++) {
            for (int j = i + 1; j < build.size(); j++) {
                between(build.get(i), build.get(j)).ifPresent(conflicts::add);
            }
        }
        return conflicts;
    }

    private static Optional<Conflict> oneWay(Placement source, Placement target) {
        GraphItem item = source.part().item();
        Part other = target.part();
        if (item.conflictingItems().contains(other.id())) {
            return Optional.of(new Conflict(Type.ITEM, source, target));
        }
        if (target.slot() != null && item.conflictingSlotIds().contains(target.slot().id())) {
            return Optional.of(new Conflict(Type.SLOT, source, target));
        }
        if (!item.conflictingCategories().isEmpty()) {
            for (String category : other.categories()) {
                if (item.conflictingCategories().contains(category)) {
                    return Optional.of(new Conflict(Type.CATEGORY, source, target));
                }
            }
        }
        return Optional.empty();
    }
}
