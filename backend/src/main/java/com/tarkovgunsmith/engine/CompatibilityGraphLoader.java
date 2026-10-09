package com.tarkovgunsmith.engine;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tarkovgunsmith.gamedata.ItemKind;
import com.tarkovgunsmith.tarkovdev.dto.SlotDto;
import com.tarkovgunsmith.tarkovdev.dto.SlotFiltersDto;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Builds the {@link CompatibilityGraph} from the stored game data ({@code item}, {@code item_category}). */
@Component
public class CompatibilityGraphLoader {

    private static final Logger log = LoggerFactory.getLogger(CompatibilityGraphLoader.class);

    private static final TypeReference<List<SlotDto>> SLOTS = new TypeReference<>() {};

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public CompatibilityGraphLoader(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public CompatibilityGraph load() {
        long start = System.nanoTime();
        Map<String, String> parents = new HashMap<>();
        jdbc.sql("SELECT id, parent_id FROM item_category")
                .query(rs -> {
                    parents.put(rs.getString("id"), rs.getString("parent_id"));
                });
        List<GraphItem> items = jdbc.sql("""
                        SELECT id, kind, categories, weight, ergonomics, recoil_vertical, recoil_horizontal,
                               ergonomics_modifier, recoil_modifier, slots::text AS slots,
                               conflicting_items, conflicting_slot_ids, conflicting_categories
                        FROM item WHERE kind IN ('WEAPON', 'MOD') ORDER BY id
                        """)
                .query((rs, n) -> item(rs))
                .list();

        CompatibilityGraph graph = CompatibilityGraph.build(new CategoryTree(parents), items);
        log.info(
                "Loaded compatibility graph: {} weapons, {} mods in {} ms",
                graph.weapons().size(),
                graph.size() - graph.weapons().size(),
                (System.nanoTime() - start) / 1_000_000);
        return graph;
    }

    private GraphItem item(ResultSet rs) throws SQLException {
        return new GraphItem(
                rs.getString("id"),
                ItemKind.valueOf(rs.getString("kind")),
                List.of(strings(rs.getArray("categories"))),
                rs.getDouble("weight"),
                rs.getObject("ergonomics", Double.class),
                rs.getObject("recoil_vertical", Double.class),
                rs.getObject("recoil_horizontal", Double.class),
                rs.getDouble("ergonomics_modifier"),
                rs.getDouble("recoil_modifier"),
                slots(rs.getString("slots")),
                Set.copyOf(List.of(strings(rs.getArray("conflicting_items")))),
                Set.copyOf(List.of(strings(rs.getArray("conflicting_slot_ids")))),
                Set.copyOf(List.of(strings(rs.getArray("conflicting_categories")))));
    }

    private List<GraphItem.SlotDefinition> slots(String json) {
        try {
            return objectMapper.readValue(json, SLOTS).stream()
                    .map(slot -> new GraphItem.SlotDefinition(
                            slot.id(), slot.nameId(), slot.name(), slot.required(), filter(slot.filters())))
                    .toList();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot read slots " + json, e);
        }
    }

    private static SlotFilter filter(SlotFiltersDto filters) {
        return new SlotFilter(
                filters.allowedItems(), filters.allowedCategories(), filters.excludedItems(), filters.excludedCategories());
    }

    private static String[] strings(Array array) throws SQLException {
        return (String[]) array.getArray();
    }
}
