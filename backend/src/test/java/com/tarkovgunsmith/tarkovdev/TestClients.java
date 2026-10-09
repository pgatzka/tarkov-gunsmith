package com.tarkovgunsmith.tarkovdev;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.Duration;

final class TestClients {

    private TestClients() {}

    static TarkovDevClient client(URI baseUrl) {
        var properties =
                new TarkovDevProperties(baseUrl, Duration.ofSeconds(10), Duration.ofSeconds(120), "tarkov-gunsmith-tests");
        return new TarkovDevClient(properties, new ObjectMapper());
    }
}
