package com.tarkovgunsmith.gamedata;

import com.tarkovgunsmith.gamedata.GameDataImporter.ItemImportResult;
import com.tarkovgunsmith.gamedata.GameDataImporter.OfferImportResult;
import com.tarkovgunsmith.tarkovdev.FetchResult;
import com.tarkovgunsmith.tarkovdev.GameMode;
import com.tarkovgunsmith.tarkovdev.TarkovDevClient;
import com.tarkovgunsmith.tarkovdev.dto.ItemsPayload;
import com.tarkovgunsmith.tarkovdev.dto.TradersPayload;
import com.tarkovgunsmith.tarkovdev.dto.Translations;
import java.time.Duration;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Keeps the game data in sync with json.tarkov.dev (SPEC §4): items and traders from regular mode,
 * offers and prices per mode.
 *
 * <p>Every payload is fetched conditionally with the ETag stored in {@code data_version} after its
 * last successful import, so a sync where nothing changed downloads and writes nothing. A changed
 * payload is imported in full; the importer only writes rows whose data differs. When items or
 * traders change, the offers of every mode are re-imported, since they refer to both.
 *
 * <p>All imports of one sync and the new ETags are committed in one transaction: a failed sync
 * leaves the last imported data in place and is retried in full on the next run.
 */
@Service
@EnableConfigurationProperties(DataSyncProperties.class)
public class DataSync {

    private static final Logger log = LoggerFactory.getLogger(DataSync.class);

    private final TarkovDevClient client;
    private final GameDataImporter importer;
    private final DataVersionRepository versions;
    private final TransactionTemplate transaction;

    public DataSync(
            TarkovDevClient client,
            GameDataImporter importer,
            DataVersionRepository versions,
            TransactionTemplate transaction) {
        this.client = client;
        this.importer = importer;
        this.versions = versions;
        this.transaction = transaction;
    }

    /** Scheduled entry point: {@link #sync()}, logging instead of throwing on failure. */
    void run() {
        try {
            sync();
        } catch (RuntimeException e) {
            log.error("Data sync failed, keeping the last imported data: {}", e.toString(), e);
        }
    }

    /** Fetches what changed since the last sync and imports it. */
    public synchronized SyncResult sync() {
        long start = System.nanoTime();
        Map<String, String> etags = new LinkedHashMap<>();

        ItemsPayload items = fetchTranslated(
                GameMode.REGULAR, "items", client::fetchItems, client::fetchItemTranslations, etags,
                ItemsPayload::translate);
        TradersPayload traders = fetchTranslated(
                GameMode.REGULAR, "traders", client::fetchTraders, client::fetchTraderTranslations, etags,
                TradersPayload::translate);

        // offers refer to items and traders, so a change to either refreshes the offers of every mode
        boolean refreshOffers = items != null || traders != null;
        Map<GameMode, ItemsPayload> offers = new EnumMap<>(GameMode.class);
        for (GameMode mode : GameMode.values()) {
            ItemsPayload payload;
            if (mode == GameMode.REGULAR) {
                // regular-mode offers come with the items payload, which was already checked above
                payload = items != null || !refreshOffers ? items : fetch(mode, "items", client::fetchItems, true, etags);
            } else {
                payload = fetch(mode, "items", client::fetchItems, refreshOffers, etags);
            }
            if (payload != null) {
                offers.put(mode, payload);
            }
        }

        SyncResult result;
        if (items == null && traders == null && offers.isEmpty()) {
            result = new SyncResult(null, null, Map.of(), elapsed(start));
        } else {
            result = transaction.execute(status -> {
                ItemImportResult itemResult = items == null ? null : importer.importItems(items);
                Integer traderResult = traders == null ? null : importer.importTraders(traders);
                Map<GameMode, OfferImportResult> offerResults = new EnumMap<>(GameMode.class);
                offers.forEach((mode, payload) -> offerResults.put(mode, importer.importOffers(mode, payload)));
                etags.forEach(versions::put);
                return new SyncResult(itemResult, traderResult, offerResults, elapsed(start));
            });
        }
        log.info("Data sync finished: {}", result.summary());
        return result;
    }

    /**
     * Fetches a payload and its translations; {@code null} if neither changed. If only one of them
     * changed, the other is fetched again in full, since a payload is only usable translated.
     */
    private <T> T fetchTranslated(
            GameMode mode,
            String endpoint,
            Fetcher<T> fetcher,
            Fetcher<Translations> translationFetcher,
            Map<String, String> etags,
            Translator<T> translator) {
        T payload = fetch(mode, endpoint, fetcher, false, etags);
        Translations translations = fetch(mode, endpoint + "_en", translationFetcher, payload != null, etags);
        if (payload == null && translations == null) {
            return null;
        }
        if (payload == null) {
            payload = fetch(mode, endpoint, fetcher, true, etags);
        }
        return translator.translate(payload, translations);
    }

    /**
     * Fetches {@code /{mode}/{endpoint}}: unconditionally if {@code force}, otherwise only if it
     * changed since the last sync. Returns {@code null} if it didn't. The response's ETag is put
     * into {@code etags}, to be stored once the payload is imported.
     */
    private <T> T fetch(GameMode mode, String endpoint, Fetcher<T> fetcher, boolean force, Map<String, String> etags) {
        String key = etagKey(mode, endpoint);
        String ifNoneMatch = force ? null : versions.get(key).orElse(null);
        FetchResult<T> result = fetcher.fetch(mode, ifNoneMatch);
        if (result.etag() != null) {
            etags.put(key, result.etag());
        }
        return result instanceof FetchResult.Modified<T> modified ? modified.body() : null;
    }

    /** The {@code data_version} key holding the ETag of {@code /{mode}/{endpoint}}. */
    static String etagKey(GameMode mode, String endpoint) {
        return "etag:" + mode.path() + "/" + endpoint;
    }

    private static Duration elapsed(long startNanos) {
        return Duration.ofNanos(System.nanoTime() - startNanos);
    }

    @FunctionalInterface
    private interface Fetcher<T> {
        FetchResult<T> fetch(GameMode mode, String ifNoneMatch);
    }

    @FunctionalInterface
    private interface Translator<T> {
        T translate(T payload, Translations translations);
    }

    /**
     * What one sync imported. A {@code null} or missing entry means that payload had not changed.
     *
     * @param items the item import, if the regular items changed
     * @param tradersChanged traders inserted or changed, if the traders payload changed
     * @param offers the offer import per mode, for the modes whose offers were re-imported
     */
    public record SyncResult(
            ItemImportResult items, Integer tradersChanged, Map<GameMode, OfferImportResult> offers, Duration took) {

        public SyncResult {
            offers = Map.copyOf(offers);
        }

        /** Whether this sync found nothing new and wrote nothing. */
        public boolean unchanged() {
            return items == null && tradersChanged == null && offers.isEmpty();
        }

        String summary() {
            String itemPart = items == null
                    ? "not modified"
                    : items.changed() + " inserted or changed, " + items.removed() + " removed";
            String traderPart = tradersChanged == null ? "not modified" : tradersChanged + " inserted or changed";
            String offerPart = Arrays.stream(GameMode.values())
                    .map(mode -> {
                        OfferImportResult offer = offers.get(mode);
                        return mode + " " + (offer == null
                                ? "not modified"
                                : offer.changed() + " inserted or changed, " + offer.removed() + " removed");
                    })
                    .collect(Collectors.joining("; "));
            return "items " + itemPart + "; traders " + traderPart + "; offers " + offerPart + " (took "
                    + took.toMillis() + " ms)";
        }
    }
}
