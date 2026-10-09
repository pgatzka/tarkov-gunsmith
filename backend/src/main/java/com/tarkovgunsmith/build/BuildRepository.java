package com.tarkovgunsmith.build;

import com.tarkovgunsmith.engine.Build;
import com.tarkovgunsmith.engine.Build.BuildPart;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stores generated builds in {@code build} and {@code build_part} (SPEC §4.3).
 *
 * <p>{@link #insertAll(Collection)} writes a whole batch with two statements, one per table, passing
 * each column as an array and expanding it with {@code unnest}. Builds whose {@code partsHash} is
 * already stored, or appears earlier in the same batch, are skipped.
 */
@Repository
public class BuildRepository {

    private static final String INSERT_BUILDS = """
            INSERT INTO build (weapon_id, parts_hash, ergonomics, recoil_vertical, recoil_horizontal, weight)
            SELECT * FROM unnest(?::text[], ?::text[], ?::float8[], ?::float8[], ?::float8[], ?::float8[])
            ON CONFLICT (parts_hash) DO NOTHING
            RETURNING id, parts_hash
            """;

    private static final String INSERT_PARTS = """
            INSERT INTO build_part (build_id, slot_path, item_id, parent_path)
            SELECT * FROM unnest(?::bigint[], ?::text[], ?::text[], ?::text[])
            """;

    private final JdbcTemplate jdbc;

    public BuildRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** A build that was stored, with its new id. */
    public record StoredBuild(long id, Build build) {}

    /**
     * Stores the builds that aren't stored yet, in one transaction.
     *
     * @return the builds that were inserted, in batch order; duplicates are left out
     */
    @Transactional
    public List<StoredBuild> insertAll(Collection<Build> builds) {
        Map<String, Build> byHash = new LinkedHashMap<>();
        for (Build build : builds) {
            byHash.putIfAbsent(build.partsHash(), build);
        }
        if (byHash.isEmpty()) {
            return List.of();
        }
        // in hash order, so that concurrent batches lock the unique index in the same order
        List<Build> sorted = byHash.values().stream().sorted(Comparator.comparing(Build::partsHash)).toList();
        Map<String, Long> ids = jdbc.execute((Connection connection) -> insertBuilds(connection, sorted));

        List<StoredBuild> stored = new ArrayList<>(ids.size());
        byHash.forEach((hash, build) -> {
            Long id = ids.get(hash);
            if (id != null) {
                stored.add(new StoredBuild(id, build));
            }
        });
        if (!stored.isEmpty()) {
            jdbc.execute((Connection connection) -> insertParts(connection, stored));
        }
        return stored;
    }

    public long count() {
        Long count = jdbc.queryForObject("SELECT count(*) FROM build", Long.class);
        return count == null ? 0 : count;
    }

    private static Map<String, Long> insertBuilds(Connection connection, Collection<Build> builds) throws SQLException {
        int n = builds.size();
        String[] weaponIds = new String[n];
        String[] hashes = new String[n];
        Double[] ergonomics = new Double[n];
        Double[] recoilVertical = new Double[n];
        Double[] recoilHorizontal = new Double[n];
        Double[] weight = new Double[n];
        int i = 0;
        for (Build build : builds) {
            weaponIds[i] = build.weapon().id();
            hashes[i] = build.partsHash();
            ergonomics[i] = build.stats().ergonomics();
            recoilVertical[i] = build.stats().recoilVertical();
            recoilHorizontal[i] = build.stats().recoilHorizontal();
            weight[i] = build.stats().weight();
            i++;
        }
        try (PreparedStatement statement = connection.prepareStatement(INSERT_BUILDS)) {
            statement.setArray(1, connection.createArrayOf("text", weaponIds));
            statement.setArray(2, connection.createArrayOf("text", hashes));
            statement.setArray(3, connection.createArrayOf("float8", ergonomics));
            statement.setArray(4, connection.createArrayOf("float8", recoilVertical));
            statement.setArray(5, connection.createArrayOf("float8", recoilHorizontal));
            statement.setArray(6, connection.createArrayOf("float8", weight));
            Map<String, Long> ids = new HashMap<>();
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    ids.put(rows.getString("parts_hash"), rows.getLong("id"));
                }
            }
            return ids;
        }
    }

    private static Void insertParts(Connection connection, List<StoredBuild> stored) throws SQLException {
        int n = stored.stream().mapToInt(s -> s.build().parts().size()).sum();
        Long[] buildIds = new Long[n];
        String[] slotPaths = new String[n];
        String[] itemIds = new String[n];
        String[] parentPaths = new String[n];
        int i = 0;
        for (StoredBuild s : stored) {
            for (BuildPart part : s.build().parts()) {
                buildIds[i] = s.id();
                slotPaths[i] = part.slotPath();
                itemIds[i] = part.placement().part().id();
                parentPaths[i] = part.parentPath();
                i++;
            }
        }
        try (PreparedStatement statement = connection.prepareStatement(INSERT_PARTS)) {
            statement.setArray(1, connection.createArrayOf("bigint", buildIds));
            statement.setArray(2, connection.createArrayOf("text", slotPaths));
            statement.setArray(3, connection.createArrayOf("text", itemIds));
            statement.setArray(4, connection.createArrayOf("text", parentPaths));
            statement.executeUpdate();
        }
        return null;
    }
}
