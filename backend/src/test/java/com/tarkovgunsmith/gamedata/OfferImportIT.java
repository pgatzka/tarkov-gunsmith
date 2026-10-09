package com.tarkovgunsmith.gamedata;

import static com.tarkovgunsmith.gamedata.Fixtures.AR15_BARREL;
import static com.tarkovgunsmith.gamedata.Fixtures.COMTAC_V;
import static com.tarkovgunsmith.gamedata.Fixtures.GPNVG;
import static com.tarkovgunsmith.gamedata.Fixtures.JAEGER;
import static com.tarkovgunsmith.gamedata.Fixtures.JAEGER_ONLY_MOD;
import static com.tarkovgunsmith.gamedata.Fixtures.MP5_UPPER_RECEIVER;
import static com.tarkovgunsmith.gamedata.Fixtures.PEACEKEEPER;
import static com.tarkovgunsmith.gamedata.Fixtures.QUEST_LOCKED_MOD;
import static com.tarkovgunsmith.gamedata.Fixtures.SKIER;
import static com.tarkovgunsmith.gamedata.Fixtures.withFleaPrices;
import static org.assertj.core.api.Assertions.assertThat;

import com.tarkovgunsmith.TestcontainersConfiguration;
import com.tarkovgunsmith.tarkovdev.GameMode;
import com.tarkovgunsmith.tarkovdev.dto.ItemsPayload;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class OfferImportIT {

    @Autowired
    GameDataImporter importer;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void importItemsAndTraders() {
        jdbc.execute("TRUNCATE item, item_category, trader, offer, data_version");
        importer.importItems(Fixtures.items());
        importer.importTraders(Fixtures.traders());
    }

    @Test
    void mp5ReceiverHasTraderAndFleaOffersInBothModes() {
        importer.importOffers(GameMode.REGULAR, Fixtures.items(GameMode.REGULAR));
        importer.importOffers(GameMode.PVE, Fixtures.items(GameMode.PVE));

        assertThat(offers(MP5_UPPER_RECEIVER, GameMode.REGULAR))
                .containsExactlyInAnyOrder(trader(PEACEKEEPER, 1, 6144), flea(1000));
        assertThat(offers(MP5_UPPER_RECEIVER, GameMode.PVE))
                .containsExactlyInAnyOrder(trader(PEACEKEEPER, 1, 6144), flea(25774));
    }

    @Test
    void storesTraderLevelAndCountsOffers() {
        var result = importer.importOffers(GameMode.REGULAR, Fixtures.items(GameMode.REGULAR));

        // preset, receiver, magazine, barrel, GPNVG (Peacekeeper); the Jaeger and the quest-locked offer are skipped
        assertThat(result.traderOffers()).isEqualTo(5);
        // every stored item with a flea price, i.e. all but the noFlea GPNVG
        assertThat(result.fleaOffers()).isEqualTo(8);
        assertThat(result.changed()).isEqualTo(13);
        assertThat(result.removed()).isZero();
        assertThat(offers(AR15_BARREL, GameMode.REGULAR))
                .containsExactlyInAnyOrder(trader(PEACEKEEPER, 3, 35919), flea(39998));
    }

    @Test
    void skipsQuestLockedNoFleaUnknownTradersAndItemsThatAreNotStored() {
        importer.importOffers(GameMode.REGULAR, Fixtures.items(GameMode.REGULAR));

        assertThat(offers(QUEST_LOCKED_MOD, GameMode.REGULAR)).containsExactly(flea(22000));
        assertThat(offers(GPNVG, GameMode.REGULAR)).containsExactly(trader(PEACEKEEPER, 4, 313298));
        assertThat(offers(JAEGER_ONLY_MOD, GameMode.REGULAR)).containsExactly(flea(12888));
        assertThat(offers(COMTAC_V, GameMode.REGULAR)).isEmpty();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM offer WHERE trader_id IN (?, ?)", Integer.class, SKIER, JAEGER))
                .isZero();
    }

    @Test
    void reimportingUnchangedOffersWritesNothing() {
        importer.importOffers(GameMode.REGULAR, Fixtures.items(GameMode.REGULAR));
        List<Object> before = jdbc.queryForList("SELECT updated_at FROM offer ORDER BY id", Object.class);

        var again = importer.importOffers(GameMode.REGULAR, Fixtures.items(GameMode.REGULAR));

        assertThat(again.changed()).isZero();
        assertThat(again.removed()).isZero();
        assertThat(jdbc.queryForList("SELECT updated_at FROM offer ORDER BY id", Object.class)).isEqualTo(before);
    }

    @Test
    void reimportUpdatesChangedAndRemovesMissingPricesOfThatModeOnly() {
        importer.importOffers(GameMode.REGULAR, Fixtures.items(GameMode.REGULAR));
        importer.importOffers(GameMode.PVE, Fixtures.items(GameMode.PVE));
        ItemsPayload items = Fixtures.items(GameMode.REGULAR);
        items = Fixtures.replace(items, withFleaPrices(items.items().get(MP5_UPPER_RECEIVER), 1500, 1500));
        items = Fixtures.replace(items, withFleaPrices(items.items().get(AR15_BARREL), null, null));

        var result = importer.importOffers(GameMode.REGULAR, items);

        assertThat(result.changed()).isEqualTo(1);
        assertThat(result.removed()).isEqualTo(1);
        assertThat(offers(MP5_UPPER_RECEIVER, GameMode.REGULAR))
                .containsExactlyInAnyOrder(trader(PEACEKEEPER, 1, 6144), flea(1500));
        assertThat(offers(AR15_BARREL, GameMode.REGULAR)).containsExactly(trader(PEACEKEEPER, 3, 35919));
        assertThat(offers(AR15_BARREL, GameMode.PVE)).contains(flea(40000));
    }

    @Test
    void offersAreDeletedWithTheirItem() {
        importer.importOffers(GameMode.REGULAR, Fixtures.items(GameMode.REGULAR));
        ItemsPayload items = Fixtures.items();
        var withoutGpnvg = new LinkedHashMap<>(items.items());
        withoutGpnvg.remove(GPNVG);

        importer.importItems(new ItemsPayload(new ItemsPayload.Data(withoutGpnvg, items.itemCategories())));

        assertThat(offers(GPNVG, GameMode.REGULAR)).isEmpty();
    }

    private List<Offer> offers(String itemId, GameMode mode) {
        return jdbc.query(
                "SELECT source, trader_id, min_level, price_rub FROM offer WHERE item_id = ? AND mode = ?",
                (rs, n) -> new Offer(
                        rs.getString("source"),
                        rs.getString("trader_id"),
                        (Integer) rs.getObject("min_level"),
                        rs.getInt("price_rub")),
                itemId,
                mode.name());
    }

    private static Offer trader(String traderId, int minLevel, int price) {
        return new Offer("TRADER", traderId, minLevel, price);
    }

    private static Offer flea(int price) {
        return new Offer("FLEA", null, null, price);
    }

    private record Offer(String source, String traderId, Integer minLevel, int priceRub) {}
}
