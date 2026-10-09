package com.tarkovgunsmith.engine;

import com.tarkovgunsmith.engine.Build.BuildPart;
import com.tarkovgunsmith.engine.CompatibilityGraph.Part;
import com.tarkovgunsmith.engine.CompatibilityGraph.Slot;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Checks a generated {@link Build} against the rules of a valid build, independently of the generator. */
final class BuildCheck {

    private BuildCheck() {}

    /** What is wrong with {@code build}; empty for a valid build. */
    static List<String> violations(Build build) {
        List<String> violations = new ArrayList<>();
        Map<String, BuildPart> byPath = new HashMap<>();
        for (BuildPart part : build.parts()) {
            if (byPath.put(part.slotPath(), part) != null) {
                violations.add("two parts at " + part.slotPath());
            }
        }

        BuildPart root = build.parts().getFirst();
        if (!root.slotPath().isEmpty() || root.placement().slot() != null || !root.placement().part().equals(build.weapon())) {
            violations.add("first part is not the weapon: " + root);
        }
        for (BuildPart part : build.parts().subList(1, build.parts().size())) {
            Slot slot = part.placement().slot();
            BuildPart parent = byPath.get(part.parentPath());
            if (slot == null) {
                violations.add("mod without a slot: " + part);
            } else if (parent == null || !slot.owner().equals(parent.placement().part())) {
                violations.add("slot of " + part + " doesn't belong to the part at " + part.parentPath());
            } else if (!part.slotPath().equals(prefix(part.parentPath()) + slot.nameId())) {
                violations.add("slot path of " + part + " doesn't match its slot " + slot);
            } else if (!slot.accepts(part.placement().part())) {
                violations.add(part + " is not allowed in " + slot);
            }
        }

        // every required slot of every part is filled
        for (BuildPart part : build.parts()) {
            Part item = part.placement().part();
            for (Slot slot : item.slots()) {
                if (slot.required() && !byPath.containsKey(prefix(part.slotPath()) + slot.nameId())) {
                    violations.add("required " + slot + " at " + part.slotPath() + " is empty");
                }
            }
        }

        Conflicts.find(build.placements()).forEach(conflict -> violations.add("conflict: " + conflict));

        if (!build.partsHash().equals(Build.partsHash(build.parts()))) {
            violations.add("parts hash doesn't match the parts");
        }
        if (!build.stats().equals(StatCalculator.compute(build.placements()))) {
            violations.add("stats don't match the parts");
        }
        return violations;
    }

    private static String prefix(String path) {
        return path.isEmpty() ? "" : path + "/";
    }
}
