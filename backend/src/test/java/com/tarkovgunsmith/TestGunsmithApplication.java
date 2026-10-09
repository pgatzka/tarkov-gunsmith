package com.tarkovgunsmith;

import java.util.Arrays;
import java.util.stream.Stream;
import org.springframework.boot.SpringApplication;

/**
 * Runs the app against a throwaway Postgres container: {@code ./gradlew bootTestRun}. Unlike in
 * tests, the data sync is on, so the database fills from the live json.tarkov.dev.
 */
public class TestGunsmithApplication {

    public static void main(String[] args) {
        String[] withSync = Stream.concat(Stream.of("--gunsmith.sync.enabled=true"), Arrays.stream(args))
                .toArray(String[]::new);
        SpringApplication.from(GunsmithApplication::main)
                .with(TestcontainersConfiguration.class)
                .run(withSync);
    }
}
