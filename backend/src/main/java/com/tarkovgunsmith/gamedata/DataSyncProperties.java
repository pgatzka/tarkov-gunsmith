package com.tarkovgunsmith.gamedata;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Schedule of {@link DataSync}.
 *
 * @param enabled whether the sync runs on startup and then every {@code interval}
 * @param interval pause between the end of one sync and the start of the next
 */
@ConfigurationProperties("gunsmith.sync")
public record DataSyncProperties(@DefaultValue("true") boolean enabled, @DefaultValue("10m") Duration interval) {}
