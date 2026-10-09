package com.tarkovgunsmith.engine;

import com.tarkovgunsmith.engine.CompatibilityGraph.Part;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/**
 * A complete weapon build: the weapon and every mod attached to it, each with its slot path.
 *
 * @param parts the weapon first (slot path {@code ""}), then the mods, each after its parent
 * @param partsHash the canonical key (SPEC §4.1), see {@link #partsHash(List)}
 */
public record Build(Part weapon, List<BuildPart> parts, String partsHash, BuildStats stats) {

    public Build {
        parts = List.copyOf(parts);
    }

    /** Builds from the parts and computes the hash and stats. */
    public static Build of(List<BuildPart> parts) {
        Part weapon = parts.getFirst().placement().part();
        return new Build(
                weapon,
                parts,
                partsHash(parts),
                StatCalculator.compute(parts.stream().map(BuildPart::placement).toList()));
    }

    public List<Placement> placements() {
        return parts.stream().map(BuildPart::placement).toList();
    }

    /**
     * SHA-256 (hex) over the sorted {@code (slotPath, itemId)} pairs, the weapon included with the
     * empty path. Two builds with the same parts in the same slots have the same hash, whatever
     * order they were assembled in.
     */
    public static String partsHash(List<BuildPart> parts) {
        MessageDigest digest = sha256();
        parts.stream()
                .sorted(Comparator.comparing(BuildPart::slotPath))
                .forEach(part -> digest.update((part.slotPath() + '\t' + part.placement().part().id() + '\n')
                        .getBytes(StandardCharsets.UTF_8)));
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * A part of a build and where it sits.
     *
     * @param slotPath the {@code nameId}s of the slots from the weapon down to this part, joined by
     *     {@code /}, e.g. {@code mod_reciever/mod_handguard}; {@code ""} for the weapon. Unique within
     *     a build, because an item's slots have distinct {@code nameId}s.
     */
    public record BuildPart(String slotPath, Placement placement) {

        /** The slot path of the part this one is attached to; {@code null} for the weapon. */
        public String parentPath() {
            if (slotPath.isEmpty()) {
                return null;
            }
            int slash = slotPath.lastIndexOf('/');
            return slash < 0 ? "" : slotPath.substring(0, slash);
        }

        @Override
        public String toString() {
            return (slotPath.isEmpty() ? "<weapon>" : slotPath) + " <- " + placement.part();
        }
    }
}
