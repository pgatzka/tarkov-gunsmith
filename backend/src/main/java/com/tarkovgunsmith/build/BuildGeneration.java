package com.tarkovgunsmith.build;

import com.tarkovgunsmith.engine.Build;
import com.tarkovgunsmith.engine.BuildGenerator;
import com.tarkovgunsmith.engine.CompatibilityGraph;
import com.tarkovgunsmith.engine.CompatibilityGraph.Part;
import com.tarkovgunsmith.engine.CompatibilityGraphLoader;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.SplittableRandom;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Service;

/**
 * Background workers that keep generating random builds and storing them (SPEC §4.1).
 *
 * <p>{@code gunsmith.generator.threads} workers each loop: take the next weapon from the {@link
 * WeaponQueue} (fewest builds first, round-robin among equals), generate {@code batch-size} builds
 * of it, and store the distinct ones with one {@link BuildRepository#insertAll} batch. Duplicates
 * of stored builds are dropped by the repository.
 *
 * <p>The workers start with the app unless {@code gunsmith.generator.enabled} is false; {@link
 * #start()} and {@link #stop()} also run and halt them by hand. The compatibility graph is loaded
 * when they start. While it has no weapons (the first data sync hasn't finished yet) the workers
 * wait, checking every {@code idle-delay} whether weapons were stored. {@link #reloadGraph()} picks up changed game data.
 */
@Service
@EnableConfigurationProperties(BuildGenerationProperties.class)
public class BuildGeneration implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(BuildGeneration.class);

    private static final Duration MIN_BACKOFF = Duration.ofMinutes(1);
    private static final Duration MAX_BACKOFF = Duration.ofHours(1);
    private static final Duration REPORT_INTERVAL = Duration.ofMinutes(1);

    private final CompatibilityGraphLoader graphLoader;
    private final BuildRepository repository;
    private final BuildGenerationProperties properties;
    private final BuildGenerator generator = new BuildGenerator();
    private final WeaponQueue queue;

    private final Object lifecycleLock = new Object();
    private final Object graphLock = new Object();
    private final Object reportLock = new Object();
    private final Object sleep = new Object();
    private volatile boolean running;
    private ExecutorService workers;

    private CompatibilityGraph graph;
    private long graphCheckedAt;

    private final AtomicLong generated = new AtomicLong();
    private final AtomicLong inserted = new AtomicLong();
    private long reportedAt = System.nanoTime();
    private long reportedGenerated;
    private long reportedInserted;

    public BuildGeneration(
            CompatibilityGraphLoader graphLoader, BuildRepository repository, BuildGenerationProperties properties) {
        this.graphLoader = graphLoader;
        this.repository = repository;
        this.properties = properties;
        this.queue = new WeaponQueue(properties.batchSize(), MIN_BACKOFF, MAX_BACKOFF, Clock.systemUTC());
    }

    @Override
    public boolean isAutoStartup() {
        return properties.enabled();
    }

    @Override
    public void start() {
        synchronized (lifecycleLock) {
            if (running) {
                return;
            }
            running = true;
            synchronized (graphLock) {
                // (re)loaded by the first batch
                graph = null;
            }
            AtomicInteger threadNumber = new AtomicInteger();
            workers = Executors.newFixedThreadPool(properties.threads(), task -> {
                Thread thread = new Thread(task, "build-generator-" + threadNumber.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            });
            for (int i = 0; i < properties.threads(); i++) {
                workers.execute(this::work);
            }
        }
        log.info(
                "Build generation started: {} threads, batches of {}", properties.threads(), properties.batchSize());
    }

    /** Stops the workers after their current batch and waits for them. */
    @Override
    public void stop() {
        synchronized (lifecycleLock) {
            if (!running) {
                return;
            }
            running = false;
            synchronized (sleep) {
                sleep.notifyAll();
            }
            workers.shutdown();
            try {
                if (!workers.awaitTermination(1, TimeUnit.MINUTES)) {
                    log.warn("Build generator workers did not stop within a minute");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        log.info("Build generation stopped: {} builds generated, {} stored", generated.get(), inserted.get());
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** Reloads the compatibility graph and the stored build counts, e.g. after the game data changed. */
    public void reloadGraph() {
        synchronized (graphLock) {
            CompatibilityGraph loaded = graphLoader.load();
            queue.reset(loaded.weapons(), repository.countByWeapon());
            graph = loaded;
            graphCheckedAt = System.nanoTime();
        }
    }

    /** Builds generated (including duplicates) since the app started. */
    public long generated() {
        return generated.get();
    }

    /** Builds stored since the app started. */
    public long inserted() {
        return inserted.get();
    }

    private void work() {
        SplittableRandom random = new SplittableRandom();
        while (running) {
            try {
                ensureGraph();
                Optional<Part> weapon = queue.next();
                if (weapon.isEmpty()) {
                    if (!idle()) {
                        return;
                    }
                } else {
                    batch(weapon.get(), random);
                    report();
                }
            } catch (RuntimeException e) {
                log.warn("Build generation batch failed, retrying in {}: {}", properties.idleDelay(), e.toString(), e);
                if (!idle()) {
                    return;
                }
            }
        }
    }

    /**
     * Loads the graph on the first batch. While it has no weapons, checks every idle delay whether
     * the first data sync has stored some, and loads it again once it has.
     */
    private void ensureGraph() {
        synchronized (graphLock) {
            if (graph == null) {
                reloadGraph();
                if (graph.weapons().isEmpty()) {
                    log.info("No weapons stored yet; build generation waits for the data sync");
                }
            } else if (graph.weapons().isEmpty()
                    && System.nanoTime() - graphCheckedAt >= properties.idleDelay().toNanos()) {
                graphCheckedAt = System.nanoTime();
                if (graphLoader.hasWeapons()) {
                    reloadGraph();
                }
            }
        }
    }

    private void batch(Part weapon, SplittableRandom random) {
        int attempts = 0;
        Map<String, Build> builds = new LinkedHashMap<>();
        try {
            for (; attempts < properties.batchSize() && running; attempts++) {
                Optional<Build> build = generator.generate(weapon, random);
                if (build.isEmpty()) {
                    if (builds.isEmpty()) {
                        // most likely no valid build exists; the queue backs the weapon off
                        break;
                    }
                    continue;
                }
                builds.putIfAbsent(build.get().partsHash(), build.get());
            }
            int stored = builds.isEmpty() ? 0 : repository.insertAll(builds.values()).size();
            generated.addAndGet(attempts);
            inserted.addAndGet(stored);
            queue.done(weapon, attempts, stored);
            log.debug("Generated {} builds of {}, {} distinct, {} new", attempts, weapon.id(), builds.size(), stored);
        } catch (RuntimeException e) {
            queue.failed(weapon);
            throw e;
        }
    }

    /** Logs the throughput about once every {@link #REPORT_INTERVAL}. */
    private void report() {
        synchronized (reportLock) {
            reportNow();
        }
    }

    private void reportNow() {
        long now = System.nanoTime();
        if (now - reportedAt < REPORT_INTERVAL.toNanos()) {
            return;
        }
        long totalGenerated = generated.get();
        long totalInserted = inserted.get();
        double seconds = (now - reportedAt) / 1e9;
        log.info(
                "Build generation: {} builds generated, {} stored in the last {} s ({} stored/s); {} stored since start",
                totalGenerated - reportedGenerated,
                totalInserted - reportedInserted,
                Math.round(seconds),
                Math.round((totalInserted - reportedInserted) / seconds),
                totalInserted);
        reportedAt = now;
        reportedGenerated = totalGenerated;
        reportedInserted = totalInserted;
    }

    /** Waits for the idle delay or until stopped; false if the worker was interrupted. */
    private boolean idle() {
        synchronized (sleep) {
            if (!running) {
                return true;
            }
            try {
                sleep.wait(Math.max(1, properties.idleDelay().toMillis()));
                return true;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }
}
