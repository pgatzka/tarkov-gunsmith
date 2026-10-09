package com.tarkovgunsmith.engine;

import com.tarkovgunsmith.engine.CompatibilityGraph.Part;
import com.tarkovgunsmith.engine.CompatibilityGraph.Slot;
import java.util.Objects;

/**
 * A part in a build and the slot it sits in; {@code slot} is {@code null} for the weapon itself.
 */
public record Placement(Slot slot, Part part) {

    public Placement {
        Objects.requireNonNull(part, "part");
    }

    /** The weapon at the root of a build. */
    public static Placement root(Part weapon) {
        return new Placement(null, weapon);
    }

    @Override
    public String toString() {
        return (slot == null ? "root" : slot.toString()) + " <- " + part;
    }
}
