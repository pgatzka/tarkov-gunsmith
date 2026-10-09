package com.tarkovgunsmith.gamedata;

import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Key/value sync state in {@code data_version}, e.g. the ETag of the last imported payload. */
@Repository
public class DataVersionRepository {

    private final JdbcClient jdbc;

    public DataVersionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<String> get(String key) {
        return jdbc.sql("SELECT value FROM data_version WHERE key = ?")
                .param(key)
                .query(String.class)
                .optional();
    }

    public void put(String key, String value) {
        jdbc.sql("""
                        INSERT INTO data_version (key, value) VALUES (?, ?)
                        ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_at = now()
                        WHERE data_version.value IS DISTINCT FROM EXCLUDED.value
                        """)
                .params(key, value)
                .update();
    }
}
