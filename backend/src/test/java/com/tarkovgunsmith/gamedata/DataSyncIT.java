package com.tarkovgunsmith.gamedata;

import static com.tarkovgunsmith.gamedata.Fixtures.MP5_UPPER_RECEIVER;
import static com.tarkovgunsmith.gamedata.Fixtures.PEACEKEEPER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tarkovgunsmith.TestcontainersConfiguration;
import com.tarkovgunsmith.gamedata.FakeTarkovDev.Request;
import com.tarkovgunsmith.tarkovdev.GameMode;
import com.tarkovgunsmith.tarkovdev.TarkovDevClient;
import com.tarkovgunsmith.tarkovdev.TarkovDevException;
import com.tarkovgunsmith.tarkovdev.TarkovDevProperties;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class DataSyncIT {

    static FakeTarkovDev server;

    @Autowired
    GameDataImporter importer;

    @Autowired
    DataVersionRepository versions;

    @Autowired
    TransactionTemplate transaction;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper objectMapper;

    DataSync sync;

    @BeforeAll
    static void startServer() throws Exception {
        server = new FakeTarkovDev();
    }

    @AfterAll
    static void stopServer() {
        server.close();
    }

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE item, item_category, trader, offer, data_version");
        server.reset();
        var properties = new TarkovDevProperties(
                server.baseUrl(), Duration.ofSeconds(10), Duration.ofSeconds(60), "tarkov-gunsmith-tests");
        sync = new DataSync(new TarkovDevClient(properties, objectMapper), importer, versions, transaction);
    }

    @Test
    void firstSyncImportsItemsTradersAndOffersOfBothModes() {
        var result = sync.sync();

        assertThat(result.items().weapons()).isEqualTo(2);
        assertThat(result.tradersChanged()).isEqualTo(4);
        assertThat(result.offers()).containsOnlyKeys(GameMode.REGULAR, GameMode.PVE);
        assertThat(fleaPrice(MP5_UPPER_RECEIVER, GameMode.REGULAR)).isEqualTo(1000);
        assertThat(fleaPrice(MP5_UPPER_RECEIVER, GameMode.PVE)).isEqualTo(25774);
        assertThat(jdbc.queryForList("SELECT key FROM data_version", String.class))
                .containsExactlyInAnyOrder(
                        "etag:regular/items",
                        "etag:regular/items_en",
                        "etag:regular/traders",
                        "etag:regular/traders_en",
                        "etag:pve/items");
    }

    @Test
    void secondSyncWithUnchangedDataGets304AndWritesNothing() {
        sync.sync();
        Map<String, List<Object>> before = snapshot();
        server.clearRequests();

        var result = sync.sync();

        assertThat(result.unchanged()).isTrue();
        assertThat(server.requests())
                .hasSize(5)
                .allSatisfy(request -> {
                    assertThat(request.ifNoneMatch()).isNotNull();
                    assertThat(request.status()).isEqualTo(304);
                });
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void changedPricesAreUpdated() throws Exception {
        sync.sync();
        Map<String, List<Object>> before = snapshot();
        server.serve("/pve/items", withFleaPrice("/pve/items", MP5_UPPER_RECEIVER, 30000));
        server.clearRequests();

        var result = sync.sync();

        assertThat(result.items()).isNull();
        assertThat(result.tradersChanged()).isNull();
        assertThat(result.offers()).containsOnlyKeys(GameMode.PVE);
        assertThat(result.offers().get(GameMode.PVE).changed()).isEqualTo(1);
        assertThat(result.offers().get(GameMode.PVE).removed()).isZero();
        assertThat(fleaPrice(MP5_UPPER_RECEIVER, GameMode.PVE)).isEqualTo(30000);
        assertThat(fleaPrice(MP5_UPPER_RECEIVER, GameMode.REGULAR)).isEqualTo(1000);
        // only the one PvE price was written
        assertThat(snapshot().get("item")).isEqualTo(before.get("item"));
        assertThat(snapshot().get("trader")).isEqualTo(before.get("trader"));
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM offer WHERE updated_at > (SELECT max(updated_at) FROM item)",
                        Integer.class))
                .isEqualTo(1);
        assertThat(server.requests()).filteredOn(r -> r.status() == 200).extracting(Request::path)
                .containsExactly("/pve/items");

        assertThat(sync.sync().unchanged()).isTrue();
    }

    @Test
    void traderChangeReimportsTheOffersOfEveryMode() throws Exception {
        sync.sync();
        ObjectNode traders = (ObjectNode) objectMapper.readTree(FakeTarkovDev.fixture("/regular/traders"));
        ((ObjectNode) traders.path("data").path(PEACEKEEPER)).put("normalizedName", "peacekeeper-renamed");
        server.serve("/regular/traders", objectMapper.writeValueAsBytes(traders));
        server.clearRequests();

        var result = sync.sync();

        assertThat(result.items()).isNull();
        assertThat(result.tradersChanged()).isEqualTo(1);
        assertThat(result.offers()).containsOnlyKeys(GameMode.REGULAR, GameMode.PVE);
        assertThat(result.offers().values()).allSatisfy(offers -> assertThat(offers.changed()).isZero());
        assertThat(server.requests()).filteredOn(r -> r.status() == 200).extracting(Request::path)
                .containsExactlyInAnyOrder("/regular/traders", "/regular/traders_en", "/regular/items", "/pve/items");
    }

    @Test
    void failedSyncKeepsTheLastDataAndIsRetried() throws Exception {
        sync.sync();
        String pveEtag = versions.get("etag:pve/items").orElseThrow();
        server.serve("/regular/items", withFleaPrice("/regular/items", MP5_UPPER_RECEIVER, 2000));
        server.serve("/pve/items", withFleaPrice("/pve/items", MP5_UPPER_RECEIVER, 30000));
        server.fail("/pve/items", 503);

        assertThatThrownBy(sync::sync).isInstanceOf(TarkovDevException.class);
        sync.run(); // the scheduled entry point logs instead of throwing

        assertThat(fleaPrice(MP5_UPPER_RECEIVER, GameMode.REGULAR)).isEqualTo(1000);
        assertThat(versions.get("etag:pve/items")).contains(pveEtag);

        server.recover("/pve/items");
        sync.sync();

        assertThat(fleaPrice(MP5_UPPER_RECEIVER, GameMode.REGULAR)).isEqualTo(2000);
        assertThat(fleaPrice(MP5_UPPER_RECEIVER, GameMode.PVE)).isEqualTo(30000);
    }

    /** The fixture at {@code path} with another {@code lastLowPrice} for {@code itemId}. */
    private byte[] withFleaPrice(String path, String itemId, int price) throws Exception {
        ObjectNode payload = (ObjectNode) objectMapper.readTree(FakeTarkovDev.fixture(path));
        ((ObjectNode) payload.path("data").path("items").path(itemId)).put("lastLowPrice", price);
        return objectMapper.writeValueAsBytes(payload);
    }

    private Integer fleaPrice(String itemId, GameMode mode) {
        return jdbc.queryForObject(
                "SELECT price_rub FROM offer WHERE item_id = ? AND mode = ? AND source = 'FLEA'",
                Integer.class,
                itemId,
                mode.name());
    }

    /** Every row of the game data tables, with its {@code updated_at}. */
    private Map<String, List<Object>> snapshot() {
        return Map.of(
                "item_category", rows("SELECT id, updated_at FROM item_category ORDER BY id"),
                "item", rows("SELECT id, updated_at FROM item ORDER BY id"),
                "trader", rows("SELECT id, updated_at FROM trader ORDER BY id"),
                "offer", rows("SELECT id, price_rub, updated_at FROM offer ORDER BY id"),
                "data_version", rows("SELECT key, value, updated_at FROM data_version ORDER BY key"));
    }

    private List<Object> rows(String sql) {
        return List.copyOf(jdbc.queryForList(sql));
    }
}
