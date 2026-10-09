package com.tarkovgunsmith.build;

import com.tarkovgunsmith.engine.CompatibilityGraph.Part;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Decides which weapon a generator worker works on next: the one with the fewest builds, counting
 * the batches other workers are generating for it, and among equals the one picked longest ago
 * (round-robin).
 *
 * <p>A weapon whose batch added almost nothing new (fewer than 1 in {@value #SATURATED_RATIO} of the
 * builds generated) has few distinct builds left to find. It would otherwise stay the weapon with
 * the fewest builds and get every batch, so it is backed off: skipped for {@code minBackoff},
 * doubling on every further such batch up to {@code maxBackoff}. A productive batch resets it.
 *
 * <p>Thread-safe.
 */
final class WeaponQueue {

    static final int SATURATED_RATIO = 100;

    private final int batchSize;
    private final Duration minBackoff;
    private final Duration maxBackoff;
    private final Clock clock;

    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private long picks;

    WeaponQueue(int batchSize, Duration minBackoff, Duration maxBackoff, Clock clock) {
        this.batchSize = batchSize;
        this.minBackoff = minBackoff;
        this.maxBackoff = maxBackoff;
        this.clock = clock;
    }

    /** Replaces the weapons with {@code weapons}, starting from their stored build counts. */
    synchronized void reset(Collection<Part> weapons, Map<String, Long> storedBuilds) {
        entries.clear();
        for (Part weapon : weapons) {
            entries.put(weapon.id(), new Entry(weapon, storedBuilds.getOrDefault(weapon.id(), 0L)));
        }
    }

    synchronized int size() {
        return entries.size();
    }

    /**
     * The weapon to generate a batch for, marked as in flight until {@link #done} or {@link
     * #failed}; empty if there are no weapons or all are backed off.
     */
    synchronized Optional<Part> next() {
        Instant now = clock.instant();
        Optional<Entry> next = entries.values().stream()
                .filter(e -> e.pausedUntil == null || !now.isBefore(e.pausedUntil))
                .min(Comparator.comparingLong((Entry e) -> e.stored + (long) e.inFlight * batchSize)
                        .thenComparingLong(e -> e.lastPicked));
        next.ifPresent(e -> {
            e.inFlight++;
            e.lastPicked = ++picks;
        });
        return next.map(e -> e.weapon);
    }

    /** Records a finished batch of {@code weapon}: {@code generated} builds, {@code inserted} of them new. */
    synchronized void done(Part weapon, int generated, int inserted) {
        Entry entry = entries.get(weapon.id());
        if (entry == null || entry.weapon != weapon) {
            // picked before a reset; the new entry started from the stored counts
            return;
        }
        entry.inFlight--;
        entry.stored += inserted;
        if ((long) inserted * SATURATED_RATIO < generated || generated == 0) {
            entry.backoff = entry.backoff == null ? minBackoff : min(entry.backoff.multipliedBy(2), maxBackoff);
            entry.pausedUntil = clock.instant().plus(entry.backoff);
        } else {
            entry.backoff = null;
            entry.pausedUntil = null;
        }
    }

    /** Records a batch of {@code weapon} that failed before it was stored. */
    synchronized void failed(Part weapon) {
        Entry entry = entries.get(weapon.id());
        if (entry != null && entry.weapon == weapon) {
            entry.inFlight--;
        }
    }

    /** The stored build count of the weapon as far as this queue knows; for tests and logging. */
    synchronized long stored(String weaponId) {
        Entry entry = entries.get(weaponId);
        return entry == null ? 0 : entry.stored;
    }

    private static Duration min(Duration a, Duration b) {
        return a.compareTo(b) <= 0 ? a : b;
    }

    private static final class Entry {
        final Part weapon;
        long stored;
        int inFlight;
        long lastPicked;
        Duration backoff;
        Instant pausedUntil;

        Entry(Part weapon, long stored) {
            this.weapon = weapon;
            this.stored = stored;
        }
    }
}
