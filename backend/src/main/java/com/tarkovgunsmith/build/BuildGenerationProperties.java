package com.tarkovgunsmith.build;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Background build generation ({@link BuildGeneration}, SPEC §4.1).
 *
 * @param enabled whether the workers start with the app
 * @param threads number of worker threads; keep it below the core count so the API stays responsive
 * @param batchSize builds a worker generates for one weapon before writing them in one batch
 * @param idleDelay how long a worker waits when there is nothing to do (no game data yet, or every
 *     weapon backed off) or after a failed batch
 */
@ConfigurationProperties("gunsmith.generator")
public record BuildGenerationProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("2") int threads,
        @DefaultValue("1000") int batchSize,
        @DefaultValue("10s") Duration idleDelay) {

    public BuildGenerationProperties {
        if (threads < 1) {
            throw new IllegalArgumentException("gunsmith.generator.threads must be at least 1, was " + threads);
        }
        if (batchSize < 1) {
            throw new IllegalArgumentException("gunsmith.generator.batch-size must be at least 1, was " + batchSize);
        }
    }
}
