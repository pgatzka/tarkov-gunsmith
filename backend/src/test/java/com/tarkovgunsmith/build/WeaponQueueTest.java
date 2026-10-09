package com.tarkovgunsmith.build;

import static org.assertj.core.api.Assertions.assertThat;

import com.tarkovgunsmith.engine.CategoryTree;
import com.tarkovgunsmith.engine.CompatibilityGraph;
import com.tarkovgunsmith.engine.CompatibilityGraph.Part;
import com.tarkovgunsmith.engine.GraphItem;
import com.tarkovgunsmith.gamedata.ItemKind;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class WeaponQueueTest {

    static final int BATCH = 100;

    final CompatibilityGraph graph = CompatibilityGraph.build(
            new CategoryTree(Map.of()), List.of(weapon("a"), weapon("b"), weapon("c")));
    final Part a = graph.weapon("a").orElseThrow();
    final Part b = graph.weapon("b").orElseThrow();
    final Part c = graph.weapon("c").orElseThrow();

    final MutableClock clock = new MutableClock();
    final WeaponQueue queue = new WeaponQueue(BATCH, Duration.ofMinutes(1), Duration.ofMinutes(4), clock);

    @Test
    void picksTheWeaponWithTheFewestBuildsFirst() {
        queue.reset(graph.weapons(), Map.of("a", 500L, "b", 20L, "c", 300L));

        assertThat(take()).isEqualTo(b);
    }

    @Test
    void goesRoundRobinOverWeaponsWithEqualCounts() {
        queue.reset(graph.weapons(), Map.of());

        List<Part> picks = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            Part next = take();
            picks.add(next);
            queue.done(next, BATCH, BATCH);
        }

        assertThat(picks).containsExactly(a, b, c, a, b, c);
        assertThat(queue.stored("a")).isEqualTo(2L * BATCH);
    }

    @Test
    void countsBatchesInFlight() {
        queue.reset(graph.weapons(), Map.of("a", 0L, "b", 50L, "c", 1000L));

        // a's batch in flight counts as 100 builds, so b (50) comes next, then a again (100 < 1000)
        assertThat(List.of(take(), take(), take())).containsExactly(a, b, a);
    }

    @Test
    void backsOffAWeaponWhoseBatchFoundAlmostNothingNew() {
        queue.reset(graph.weapons(), Map.of("b", 1000L, "c", 1000L));

        Part saturated = take();
        assertThat(saturated).isEqualTo(a);
        // fewer than 1 in 100 new
        queue.done(a, BATCH * 10, 9);

        assertThat(take()).isEqualTo(b);
        assertThat(take()).isEqualTo(c);
        assertThat(take()).isNotEqualTo(a);

        clock.advance(Duration.ofMinutes(1));
        assertThat(take()).isEqualTo(a);
    }

    @Test
    void theBackoffDoublesUpToTheMaximumAndAProductiveBatchResetsIt() {
        queue.reset(List.of(a), Map.of());

        Duration[] expected = {Duration.ofMinutes(1), Duration.ofMinutes(2), Duration.ofMinutes(4), Duration.ofMinutes(4)};
        for (Duration backoff : expected) {
            queue.done(take(), BATCH, 0);
            clock.advance(backoff.minusSeconds(1));
            assertThat(queue.next()).isEmpty();
            clock.advance(Duration.ofSeconds(1));
        }

        queue.done(take(), BATCH, BATCH);
        assertThat(queue.next()).contains(a);
    }

    @Test
    void aWeaponWithoutAnyBuildIsBackedOff() {
        queue.reset(List.of(a), Map.of());

        queue.done(take(), 0, 0);

        assertThat(queue.next()).isEmpty();
    }

    @Test
    void aFailedBatchIsNotCounted() {
        queue.reset(graph.weapons(), Map.of("b", 10L, "c", 10L));

        queue.failed(take());

        // neither stored nor in flight nor backed off: still the fewest
        assertThat(queue.stored("a")).isZero();
        assertThat(take()).isEqualTo(a);
    }

    @Test
    void aBatchPickedBeforeAResetIsIgnored() {
        queue.reset(graph.weapons(), Map.of());
        Part picked = take();

        CompatibilityGraph reloaded = CompatibilityGraph.build(new CategoryTree(Map.of()), List.of(weapon("a")));
        queue.reset(reloaded.weapons(), Map.of("a", 7L));
        queue.done(picked, BATCH, BATCH);

        assertThat(queue.size()).isEqualTo(1);
        assertThat(queue.stored("a")).isEqualTo(7L);
    }

    @Test
    void anEmptyQueueHasNothingNext() {
        queue.reset(List.of(), Map.of());

        assertThat(queue.next()).isEmpty();
    }

    private Part take() {
        Optional<Part> next = queue.next();
        assertThat(next).isPresent();
        return next.get();
    }

    private static GraphItem weapon(String id) {
        return new GraphItem(id, ItemKind.WEAPON, List.of(), 1, 50.0, 100.0, 200.0, 0, 0, List.of(), Set.of(), Set.of(), Set.of());
    }

    static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-09T12:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }
}
