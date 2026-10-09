package com.tarkovgunsmith;

import org.springframework.boot.SpringApplication;

/** Runs the app against a throwaway Postgres container: {@code ./gradlew bootTestRun}. */
public class TestGunsmithApplication {

    public static void main(String[] args) {
        SpringApplication.from(GunsmithApplication::main)
                .with(TestcontainersConfiguration.class)
                .run(args);
    }
}
