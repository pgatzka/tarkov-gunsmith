package com.tarkovgunsmith.gamedata;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tarkovgunsmith.tarkovdev.dto.ItemCategoryDto;
import com.tarkovgunsmith.tarkovdev.dto.ItemDto;
import com.tarkovgunsmith.tarkovdev.dto.ItemPropertiesDto;
import com.tarkovgunsmith.tarkovdev.dto.ItemsPayload;
import com.tarkovgunsmith.tarkovdev.dto.TraderDto;
import com.tarkovgunsmith.tarkovdev.dto.TradersPayload;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stores json.tarkov.dev payloads in {@code item_category}, {@code item} and {@code trader}.
 *
 * <p>Each import is a full replacement: rows are upserted (only rows whose data changed are
 * written) and rows missing from the payload are deleted. Payloads must already be translated, so
 * names are stored as English text. Items come from regular mode only; their stats and slots are
 * the same in both modes.
 */
@Service
@EnableConfigurationProperties(GameDataProperties.class)
public class GameDataImporter {

    private static final Logger log = LoggerFactory.getLogger(GameDataImporter.class);

    private static final List<String> CATEGORY_COLUMNS = List.of("id", "name", "normalized_name", "parent_id");

    private static final List<String> ITEM_COLUMNS = List.of(
            "id",
            "kind",
            "weapon_class",
            "name",
            "short_name",
            "normalized_name",
            "types",
            "categories",
            "no_flea",
            "weight",
            "ergonomics",
            "recoil_vertical",
            "recoil_horizontal",
            "ergonomics_modifier",
            "recoil_modifier",
            "accuracy_modifier",
            "slots",
            "conflicting_items",
            "conflicting_slot_ids",
            "conflicting_categories",
            "default_preset_id",
            "base_item_id",
            "contains_items",
            "icon_link",
            "grid_image_link",
            "base_image_link");

    private static final List<String> TRADER_COLUMNS =
            List.of("id", "name", "normalized_name", "image_link", "max_level");

    private static final Set<String> JSONB_COLUMNS = Set.of("slots", "contains_items");

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final ItemClassifier classifier;

    public GameDataImporter(JdbcTemplate jdbc, ObjectMapper objectMapper, ItemClassifier classifier) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.classifier = classifier;
    }

    /** Imports the category tree and all weapons, mods and weapon presets of a translated payload. */
    @Transactional
    public ItemImportResult importItems(ItemsPayload items) {
        List<Object[]> categoryRows = items.itemCategories().values().stream()
                .map(GameDataImporter::categoryRow)
                .toList();
        upsert("item_category", CATEGORY_COLUMNS, categoryRows);
        deleteMissing("item_category", categoryRows);

        ItemClassifier.Classification classification = classifier.classify(items);
        List<Object[]> itemRows = new ArrayList<>();
        classification.kinds().forEach((id, kind) ->
                itemRows.add(itemRow(items.items().get(id), kind, items.itemCategories())));
        int changed = upsert("item", ITEM_COLUMNS, itemRows);
        int removed = deleteMissing("item", itemRows);

        var result = new ItemImportResult(
                (int) classification.count(ItemKind.WEAPON),
                (int) classification.count(ItemKind.MOD),
                (int) classification.count(ItemKind.PRESET),
                changed,
                removed,
                classification.excludedWeapons());
        log.info(
                "Imported items: {} weapons, {} mods, {} presets ({} inserted or changed, {} removed); excluded weapons: {}",
                result.weapons(),
                result.mods(),
                result.presets(),
                result.changed(),
                result.removed(),
                classification.excludedWeapons().stream()
                        .map(id -> items.items().get(id).shortName())
                        .collect(Collectors.joining(", ")));
        return result;
    }

    /** Imports the traders of a translated payload. Returns the number of inserted or changed rows. */
    @Transactional
    public int importTraders(TradersPayload traders) {
        List<Object[]> rows = traders.traders().values().stream()
                .map(GameDataImporter::traderRow)
                .toList();
        int changed = upsert("trader", TRADER_COLUMNS, rows);
        int removed = deleteMissing("trader", rows);
        log.info("Imported {} traders ({} inserted or changed, {} removed)", rows.size(), changed, removed);
        return changed;
    }

    private static Object[] categoryRow(ItemCategoryDto category) {
        return new Object[] {category.id(), category.name(), category.normalizedName(), category.parent()};
    }

    private Object[] itemRow(ItemDto item, ItemKind kind, Map<String, ItemCategoryDto> categories) {
        ItemPropertiesDto properties = item.properties();
        boolean assembled = kind == ItemKind.WEAPON || kind == ItemKind.PRESET;
        return new Object[] {
            item.id(),
            kind.name(),
            assembled ? ItemClassifier.weaponClass(item, categories) : null,
            item.name(),
            item.shortName(),
            item.normalizedName(),
            array(item.types()),
            array(item.categories()),
            item.isNoFlea(),
            orZero(item.weight()),
            assembled ? properties.ergonomics() : null,
            assembled ? properties.recoilVertical() : null,
            assembled ? properties.recoilHorizontal() : null,
            // on a gun, ergonomicsModifier repeats its base ergonomics; it only means something on mods
            kind == ItemKind.MOD ? orZero(item.ergonomicsModifier()) : 0.0,
            kind == ItemKind.MOD ? orZero(item.recoilModifier()) : 0.0,
            kind == ItemKind.MOD ? orZero(item.accuracyModifier()) : 0.0,
            json(item.slots()),
            array(item.conflictingItems()),
            array(item.conflictingSlotIds()),
            array(item.conflictingCategories()),
            properties == null ? null : properties.defaultPreset(),
            properties == null ? null : properties.baseItem(),
            json(item.containsItems()),
            item.iconLink(),
            item.gridImageLink(),
            item.baseImageLink()
        };
    }

    private static Object[] traderRow(TraderDto trader) {
        int maxLevel = trader.levels().stream()
                .mapToInt(TraderDto.TraderLevelDto::level)
                .max()
                .orElse(0);
        return new Object[] {trader.id(), trader.name(), trader.normalizedName(), trader.imageLink(), maxLevel};
    }

    /**
     * Inserts or updates {@code rows} (first column is the id). Rows whose data is unchanged are
     * left alone. Returns the number of rows inserted or changed.
     */
    private int upsert(String table, List<String> columns, List<Object[]> rows) {
        if (rows.isEmpty()) {
            return 0;
        }
        List<String> data = columns.subList(1, columns.size());
        String sql = "INSERT INTO " + table + " (" + String.join(", ", columns) + ") VALUES ("
                + columns.stream().map(c -> JSONB_COLUMNS.contains(c) ? "CAST(? AS jsonb)" : "?").collect(Collectors.joining(", "))
                + ") ON CONFLICT (id) DO UPDATE SET "
                + data.stream().map(c -> c + " = EXCLUDED." + c).collect(Collectors.joining(", "))
                + ", updated_at = now()"
                + " WHERE (" + data.stream().map(c -> table + "." + c).collect(Collectors.joining(", ")) + ")"
                + " IS DISTINCT FROM ("
                + data.stream().map(c -> "EXCLUDED." + c).collect(Collectors.joining(", ")) + ")";
        return Arrays.stream(jdbc.batchUpdate(sql, rows)).map(n -> Math.max(n, 0)).sum();
    }

    /** Deletes the rows of {@code table} whose id is not among {@code rows}. Returns how many. */
    private int deleteMissing(String table, List<Object[]> rows) {
        String[] ids = rows.stream().map(row -> (String) row[0]).toArray(String[]::new);
        return jdbc.update("DELETE FROM " + table + " WHERE NOT (id = ANY (?))", (Object) ids);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize " + value, e);
        }
    }

    private static String[] array(List<String> values) {
        return values.toArray(String[]::new);
    }

    private static double orZero(Double value) {
        return value == null ? 0.0 : value;
    }

    /**
     * @param changed items inserted or changed by this import
     * @param removed items deleted because they are no longer in the payload (or no longer imported)
     * @param excludedWeapons ids of guns left out by {@link GameDataProperties}
     */
    public record ItemImportResult(
            int weapons, int mods, int presets, int changed, int removed, Set<String> excludedWeapons) {}
}
