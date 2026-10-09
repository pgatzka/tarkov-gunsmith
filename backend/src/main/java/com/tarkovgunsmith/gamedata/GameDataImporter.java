package com.tarkovgunsmith.gamedata;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tarkovgunsmith.tarkovdev.GameMode;
import com.tarkovgunsmith.tarkovdev.dto.ItemCategoryDto;
import com.tarkovgunsmith.tarkovdev.dto.ItemDto;
import com.tarkovgunsmith.tarkovdev.dto.ItemPropertiesDto;
import com.tarkovgunsmith.tarkovdev.dto.ItemsPayload;
import com.tarkovgunsmith.tarkovdev.dto.TraderDto;
import com.tarkovgunsmith.tarkovdev.dto.TraderOfferDto;
import com.tarkovgunsmith.tarkovdev.dto.TradersPayload;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
 * Stores json.tarkov.dev payloads in {@code item_category}, {@code item}, {@code trader} and
 * {@code offer}.
 *
 * <p>Each import is a full replacement: rows are upserted (only rows whose data changed are
 * written) and rows missing from the payload are deleted. Payloads must already be translated, so
 * names are stored as English text. Items come from regular mode only; their stats and slots are
 * the same in both modes. Offers and prices are imported per mode.
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

    /**
     * Imports where the stored items can be bought in {@code mode} (SPEC §1.3): trader cash offers
     * that are not quest-locked, with their loyalty level, and the flea price ({@code lastLowPrice},
     * falling back to {@code avg24hPrice}; {@code noFlea} items get none). Run it after {@link
     * #importItems} and {@link #importTraders}: offers for items or traders that aren't stored are
     * skipped.
     */
    @Transactional
    public OfferImportResult importOffers(GameMode mode, ItemsPayload items) {
        Set<String> itemIds = new HashSet<>(jdbc.queryForList("SELECT id FROM item", String.class));
        Set<String> traderIds = new HashSet<>(jdbc.queryForList("SELECT id FROM trader", String.class));

        // keyed by item, trader and level; the cheapest wins if a trader lists an item twice
        Map<List<Object>, Object[]> rows = new LinkedHashMap<>();
        int traderOffers = 0;
        int fleaOffers = 0;
        int questLocked = 0;
        int unknownTrader = 0;
        for (ItemDto item : items.items().values()) {
            if (!itemIds.contains(item.id())) {
                continue;
            }
            for (TraderOfferDto offer : item.buyFromTrader()) {
                if (offer.isQuestLocked()) {
                    questLocked++;
                } else if (!traderIds.contains(offer.trader())) {
                    unknownTrader++;
                } else if (isPrice(offer.priceRUB()) && offer.minTraderLevel() != null) {
                    Object[] row = offerRow(item.id(), mode, "TRADER", offer.trader(), offer.minTraderLevel(), offer.priceRUB());
                    Object[] existing = rows.putIfAbsent(key(row), row);
                    if (existing == null) {
                        traderOffers++;
                    } else if (offer.priceRUB() < (int) existing[5]) {
                        rows.put(key(row), row);
                    }
                }
            }
            Integer fleaPrice = fleaPrice(item);
            if (fleaPrice != null) {
                Object[] row = offerRow(item.id(), mode, "FLEA", null, null, fleaPrice);
                rows.put(key(row), row);
                fleaOffers++;
            }
        }

        List<Object[]> offerRows = List.copyOf(rows.values());
        int changed = upsertOffers(offerRows);
        int removed = deleteMissingOffers(mode, offerRows);

        var result = new OfferImportResult(traderOffers, fleaOffers, changed, removed);
        log.info(
                "Imported {} offers: {} trader, {} flea ({} inserted or changed, {} removed); skipped {} quest-locked, {} from unknown traders",
                mode,
                traderOffers,
                fleaOffers,
                changed,
                removed,
                questLocked,
                unknownTrader);
        return result;
    }

    /** {@code lastLowPrice}, falling back to {@code avg24hPrice}; {@code null} if not on the flea. */
    static Integer fleaPrice(ItemDto item) {
        if (item.isNoFlea()) {
            return null;
        }
        if (isPrice(item.lastLowPrice())) {
            return item.lastLowPrice();
        }
        return isPrice(item.avg24hPrice()) ? item.avg24hPrice() : null;
    }

    private static boolean isPrice(Integer price) {
        return price != null && price > 0;
    }

    private static Object[] offerRow(String itemId, GameMode mode, String source, String traderId, Integer minLevel, int price) {
        return new Object[] {itemId, mode.name(), source, traderId, minLevel, price};
    }

    private static List<Object> key(Object[] offerRow) {
        return Arrays.asList(offerRow[0], offerRow[2], offerRow[3], offerRow[4]);
    }

    private int upsertOffers(List<Object[]> rows) {
        if (rows.isEmpty()) {
            return 0;
        }
        String sql = """
                INSERT INTO offer (item_id, mode, source, trader_id, min_level, price_rub) VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (item_id, mode, source, trader_id, min_level)
                DO UPDATE SET price_rub = EXCLUDED.price_rub, updated_at = now()
                WHERE offer.price_rub IS DISTINCT FROM EXCLUDED.price_rub
                """;
        return Arrays.stream(jdbc.batchUpdate(sql, rows)).map(n -> Math.max(n, 0)).sum();
    }

    /** Deletes the offers of {@code mode} that are not among {@code rows}. Returns how many. */
    private int deleteMissingOffers(GameMode mode, List<Object[]> rows) {
        String sql = """
                DELETE FROM offer o WHERE o.mode = ? AND NOT EXISTS (
                    SELECT 1 FROM unnest(?::text[], ?::text[], ?::text[], ?::int[]) AS k(item_id, source, trader_id, min_level)
                    WHERE k.item_id = o.item_id AND k.source = o.source
                      AND k.trader_id IS NOT DISTINCT FROM o.trader_id AND k.min_level IS NOT DISTINCT FROM o.min_level)
                """;
        return jdbc.update(
                sql,
                mode.name(),
                rows.stream().map(row -> (String) row[0]).toArray(String[]::new),
                rows.stream().map(row -> (String) row[2]).toArray(String[]::new),
                rows.stream().map(row -> (String) row[3]).toArray(String[]::new),
                rows.stream().map(row -> (Integer) row[4]).toArray(Integer[]::new));
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

    /**
     * @param traderOffers trader cash offers stored for the mode
     * @param fleaOffers items with a flea price in the mode
     * @param changed offers inserted or whose price changed
     * @param removed offers deleted because they are no longer in the payload
     */
    public record OfferImportResult(int traderOffers, int fleaOffers, int changed, int removed) {}
}
