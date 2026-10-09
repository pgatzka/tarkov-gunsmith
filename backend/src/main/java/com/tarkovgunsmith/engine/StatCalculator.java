package com.tarkovgunsmith.engine;

import com.tarkovgunsmith.engine.CompatibilityGraph.Part;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Computes a build's stats the way the game and tarkov.dev do (SPEC §3):
 *
 * <ul>
 *   <li>{@code ergonomics = weapon.ergonomics + Σ mod.ergonomicsModifier}
 *   <li>{@code recoil = weapon.recoil × (1 + Σ mod.recoilModifier / 100)}, vertical and horizontal
 *   <li>{@code weight = weapon.weight + Σ mod.weight}
 * </ul>
 *
 * <p>Every mod counts once per placement: a mod used in two slots adds its modifiers twice. Nothing
 * is rounded or clamped; the game shows recoil rounded to whole numbers.
 */
public final class StatCalculator {

    private StatCalculator() {}

    /** The stats of {@code weapon} with {@code mods} attached; a mod listed twice counts twice. */
    public static BuildStats compute(Part weapon, Collection<Part> mods) {
        if (!weapon.isWeapon()) {
            throw new IllegalArgumentException(weapon + " is not a weapon");
        }
        GraphItem base = weapon.item();
        double ergonomics = base.ergonomics();
        double recoilModifier = 0;
        double weight = base.weight();
        for (Part mod : mods) {
            if (!mod.isMod()) {
                throw new IllegalArgumentException(mod + " is not a mod");
            }
            GraphItem item = mod.item();
            ergonomics += item.ergonomicsModifier();
            recoilModifier += item.recoilModifier();
            weight += item.weight();
        }
        double recoilFactor = 1 + recoilModifier / 100;
        return new BuildStats(
                ergonomics, base.recoilVertical() * recoilFactor, base.recoilHorizontal() * recoilFactor, weight);
    }

    /** The stats of a build: exactly one {@link Placement#root root} weapon plus its mods. */
    public static BuildStats compute(Collection<Placement> build) {
        Part weapon = null;
        List<Part> mods = new ArrayList<>(build.size());
        for (Placement placement : build) {
            if (placement.slot() != null) {
                mods.add(placement.part());
            } else if (weapon == null) {
                weapon = placement.part();
            } else {
                throw new IllegalArgumentException("Build has more than one root: " + build);
            }
        }
        if (weapon == null) {
            throw new IllegalArgumentException("Build has no root weapon: " + build);
        }
        return compute(weapon, mods);
    }
}
