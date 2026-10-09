package com.tarkovgunsmith.gamedata;

import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/** Runs {@link DataSync} right after startup and then every {@code gunsmith.sync.interval}. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "gunsmith.sync.enabled", havingValue = "true", matchIfMissing = true)
class DataSyncScheduling implements SchedulingConfigurer {

    private final DataSync dataSync;
    private final DataSyncProperties properties;

    DataSyncScheduling(DataSync dataSync, DataSyncProperties properties) {
        this.dataSync = dataSync;
        this.properties = properties;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.addFixedDelayTask(new FixedDelayTask(dataSync::run, properties.interval(), Duration.ZERO));
    }
}
