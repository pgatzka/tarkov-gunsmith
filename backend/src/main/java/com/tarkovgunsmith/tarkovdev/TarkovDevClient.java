package com.tarkovgunsmith.tarkovdev;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.tarkovgunsmith.tarkovdev.dto.ItemsPayload;
import com.tarkovgunsmith.tarkovdev.dto.TradersPayload;
import com.tarkovgunsmith.tarkovdev.dto.Translations;
import java.io.IOException;
import java.io.InputStream;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.zip.GZIPInputStream;
import org.brotli.dec.BrotliInputStream;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Client for the static JSON dumps at json.tarkov.dev.
 *
 * <p>Every fetch is a conditional GET: pass the ETag of the last successful fetch to get {@link
 * FetchResult.NotModified} instead of re-downloading the payload. Responses are requested brotli or
 * gzip compressed and decoded transparently.
 *
 * <p>Names in the payloads are translation keys; resolve them with {@link
 * ItemsPayload#translate(Translations)} and {@link TradersPayload#translate(Translations)}.
 */
@Component
@EnableConfigurationProperties(TarkovDevProperties.class)
public class TarkovDevClient {

    private final TarkovDevProperties properties;
    private final HttpClient http;
    private final ObjectReader reader;

    public TarkovDevClient(TarkovDevProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.http = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NORMAL)
                .proxy(ProxySelector.getDefault())
                .build();
        this.reader = objectMapper.reader().without(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    /** {@code /{mode}/items}: items, slots, conflicts, modifiers, offers, flea prices, categories. */
    public FetchResult<ItemsPayload> fetchItems(GameMode mode, String ifNoneMatch) {
        return fetch(mode, "items", ifNoneMatch, ItemsPayload.class);
    }

    /** {@code /{mode}/items_en}: English texts for the translation keys in the items payload. */
    public FetchResult<Translations> fetchItemTranslations(GameMode mode, String ifNoneMatch) {
        return fetch(mode, "items_en", ifNoneMatch, Translations.class);
    }

    /** {@code /{mode}/traders}: traders and their loyalty levels. */
    public FetchResult<TradersPayload> fetchTraders(GameMode mode, String ifNoneMatch) {
        return fetch(mode, "traders", ifNoneMatch, TradersPayload.class);
    }

    /** {@code /{mode}/traders_en}: English texts for the translation keys in the traders payload. */
    public FetchResult<Translations> fetchTraderTranslations(GameMode mode, String ifNoneMatch) {
        return fetch(mode, "traders_en", ifNoneMatch, Translations.class);
    }

    private <T> FetchResult<T> fetch(GameMode mode, String endpoint, String ifNoneMatch, Class<T> type) {
        URI uri = properties.baseUrl().resolve("/" + mode.path() + "/" + endpoint);
        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                .timeout(properties.requestTimeout())
                .header("Accept", "application/json")
                .header("Accept-Encoding", "br, gzip")
                .header("User-Agent", properties.userAgent())
                .GET();
        if (ifNoneMatch != null) {
            request.header("If-None-Match", ifNoneMatch);
        }

        HttpResponse<InputStream> response;
        try {
            response = http.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException e) {
            throw new TarkovDevException("GET " + uri + " failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TarkovDevException("GET " + uri + " interrupted", e);
        }

        String etag = response.headers().firstValue("ETag").orElse(null);
        int status = response.statusCode();
        if (status != 200) {
            closeQuietly(response.body());
            if (status == 304) {
                return new FetchResult.NotModified<>(etag != null ? etag : ifNoneMatch);
            }
            throw new TarkovDevException("GET " + uri + " returned HTTP " + status);
        }
        try (InputStream body = decode(response)) {
            return new FetchResult.Modified<>(reader.readValue(body, type), etag);
        } catch (IOException e) {
            throw new TarkovDevException("GET " + uri + ": unreadable response body", e);
        }
    }

    private static void closeQuietly(InputStream body) {
        try {
            body.close();
        } catch (IOException ignored) {
            // the body is discarded anyway
        }
    }

    private static InputStream decode(HttpResponse<InputStream> response) throws IOException {
        String encoding = response.headers().firstValue("Content-Encoding").orElse("identity").trim();
        InputStream body = response.body();
        return switch (encoding.toLowerCase()) {
            case "br" -> new BrotliInputStream(body);
            case "gzip", "x-gzip" -> new GZIPInputStream(body);
            case "identity", "" -> body;
            default -> {
                body.close();
                throw new TarkovDevException("Unsupported Content-Encoding: " + encoding);
            }
        };
    }
}
