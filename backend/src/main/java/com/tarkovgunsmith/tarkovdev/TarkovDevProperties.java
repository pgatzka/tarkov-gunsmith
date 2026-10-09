package com.tarkovgunsmith.tarkovdev;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("gunsmith.tarkov-dev")
public record TarkovDevProperties(
        @DefaultValue("https://json.tarkov.dev") URI baseUrl,
        @DefaultValue("10s") Duration connectTimeout,
        @DefaultValue("60s") Duration requestTimeout,
        @DefaultValue("tarkov-gunsmith (+https://github.com/pgatzka/tarkov-gunsmith)") String userAgent) {}
