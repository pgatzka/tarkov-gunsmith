package com.tarkovgunsmith.engine;

import com.tarkovgunsmith.engine.Build.BuildPart;
import com.tarkovgunsmith.engine.CompatibilityGraph.Part;
import com.tarkovgunsmith.engine.CompatibilityGraph.Slot;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.random.RandomGenerator;

/**
 * Generates random valid builds (SPEC §4.1) by a recursive walk from the weapon: each slot gets an
 * item picked uniformly from its candidates, or "empty" as one more equally likely option when the
 * slot is optional. A candidate that conflicts with a part already chosen is skipped, and a chosen
 * part's own slots are filled right after it. When a required slot has nothing left to try, the walk
 * backtracks and tries the next option of an earlier slot.
 *
 * <p>Every build it returns is valid: every required slot is filled (recursively), every part is a
 * candidate of its slot, and no two parts conflict ({@link Conflicts}).
 *
 * <p>Two limits keep a walk finite. Parts below {@link #MAX_DEPTH} slots are not attached (optional
 * slots there stay empty, required ones are dead ends), since mounts that take mounts could
 * otherwise nest without end. And a walk gives up after {@code maxSteps} slot decisions, so that an
 * unlucky early choice can't make it backtrack through a huge space; the generator then starts over
 * with fresh choices, up to {@code maxAttempts} times.
 *
 * <p>The generator is stateless and safe to share between threads; pass each thread its own random.
 */
public final class BuildGenerator {

    /** How many slots deep parts may sit; the weapon's own slots are depth 1. */
    public static final int MAX_DEPTH = 10;

    private final int maxSteps;
    private final int maxAttempts;

    public BuildGenerator() {
        this(10_000, 10);
    }

    public BuildGenerator(int maxSteps, int maxAttempts) {
        this.maxSteps = maxSteps;
        this.maxAttempts = maxAttempts;
    }

    /** A random valid build of {@code weapon}, or empty if none was found within the limits. */
    public Optional<Build> generate(Part weapon, RandomGenerator random) {
        if (!weapon.isWeapon()) {
            throw new IllegalArgumentException(weapon + " is not a weapon");
        }
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            Walk walk = new Walk(weapon, random);
            Outcome outcome = walk.fill(0);
            if (outcome == Outcome.DONE) {
                return Optional.of(Build.of(walk.chosen));
            }
            if (outcome == Outcome.DEAD_END) {
                // the whole space was searched: no valid build exists within MAX_DEPTH
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    private enum Outcome {
        DONE,
        DEAD_END,
        OUT_OF_STEPS
    }

    /** A slot of a chosen part that still has to be decided. */
    private record OpenSlot(Slot slot, String path, int depth) {}

    /** One attempt: a depth-first search over the open slots in tree order. */
    private final class Walk {

        private final RandomGenerator random;
        private final List<OpenSlot> open = new ArrayList<>();
        private final List<BuildPart> chosen = new ArrayList<>();
        private final List<Placement> placements = new ArrayList<>();
        private int steps;

        Walk(Part weapon, RandomGenerator random) {
            this.random = random;
            Placement root = Placement.root(weapon);
            chosen.add(new BuildPart("", root));
            placements.add(root);
            open.addAll(slotsOf(weapon, "", 0));
        }

        /** Decides {@code open[index]} and everything after it. */
        Outcome fill(int index) {
            if (index == open.size()) {
                return Outcome.DONE;
            }
            if (++steps > maxSteps) {
                return Outcome.OUT_OF_STEPS;
            }
            OpenSlot current = open.get(index);
            for (Part option : options(current)) {
                Outcome outcome = option == null ? fill(index + 1) : attach(index, current, option);
                if (outcome != Outcome.DEAD_END) {
                    return outcome;
                }
            }
            return Outcome.DEAD_END;
        }

        private Outcome attach(int index, OpenSlot current, Part part) {
            Placement placement = new Placement(current.slot(), part);
            if (Conflicts.conflictsWithAny(placement, placements)) {
                return Outcome.DEAD_END;
            }
            String path = current.path();
            List<OpenSlot> children = slotsOf(part, path, current.depth());
            chosen.add(new BuildPart(path, placement));
            placements.add(placement);
            open.addAll(index + 1, children);

            Outcome outcome = fill(index + 1);
            if (outcome == Outcome.DONE) {
                return outcome;
            }
            open.subList(index + 1, index + 1 + children.size()).clear();
            placements.removeLast();
            chosen.removeLast();
            return outcome;
        }

        /** The candidates in random order, with {@code null} for "empty" among them if the slot is optional. */
        private List<Part> options(OpenSlot current) {
            List<Part> options = new ArrayList<>();
            if (current.depth() <= MAX_DEPTH) {
                options.addAll(current.slot().candidates());
            }
            if (!current.slot().required()) {
                options.add(null);
            }
            // Fisher-Yates
            for (int i = options.size() - 1; i > 0; i--) {
                int j = random.nextInt(i + 1);
                options.set(i, options.set(j, options.get(i)));
            }
            return options;
        }

        private static List<OpenSlot> slotsOf(Part part, String path, int depth) {
            List<OpenSlot> slots = new ArrayList<>(part.slots().size());
            for (Slot slot : part.slots()) {
                slots.add(new OpenSlot(slot, path.isEmpty() ? slot.nameId() : path + '/' + slot.nameId(), depth + 1));
            }
            return slots;
        }
    }
}
