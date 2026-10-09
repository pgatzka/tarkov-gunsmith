package com.tarkovgunsmith.tarkovdev;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.InstanceOfAssertFactories.type;

import com.tarkovgunsmith.tarkovdev.dto.ContainedItemDto;
import com.tarkovgunsmith.tarkovdev.dto.ItemDto;
import com.tarkovgunsmith.tarkovdev.dto.ItemsPayload;
import com.tarkovgunsmith.tarkovdev.dto.SlotDto;
import com.tarkovgunsmith.tarkovdev.dto.TraderOfferDto;
import com.tarkovgunsmith.tarkovdev.dto.TradersPayload;
import com.tarkovgunsmith.tarkovdev.dto.Translations;
import java.io.IOException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class TarkovDevClientTest {

    static final String MP5 = "5926bb2186f7744b1c6c6e60";
    static final String MP5_DEFAULT_PRESET = "59411aa786f7747aeb37f9a5";
    static final String MP5_UPPER_RECEIVER = "5926c0df86f77462f647f764";
    static final String AR15_BARREL = "55d35ee94bdc2d61338b4568";
    static final String PILAD_SCOPE = "5dff772da3651922b360bf91";
    static final String COMTAC_V = "66b5f69ea7f72d197e70bcdb";
    static final String GPNVG = "5c0558060db834001b735271";
    static final String MOE_STOCK = "56eabf3bd2720b75698b4569";
    static final String PEACEKEEPER = "5935c25fb3acc3127c3d8cd9";

    FixtureServer server;
    TarkovDevClient client;

    @BeforeEach
    void start() throws IOException {
        server = new FixtureServer();
        client = TestClients.client(server.baseUrl());
    }

    @AfterEach
    void stop() {
        server.close();
    }

    ItemsPayload items() {
        return modified(client.fetchItems(GameMode.REGULAR, null));
    }

    static <T> T modified(FetchResult<T> result) {
        assertThat(result).isInstanceOf(FetchResult.Modified.class);
        return ((FetchResult.Modified<T>) result).body();
    }

    @ParameterizedTest
    @EnumSource(GameMode.class)
    void requestsModePathsWithCompressionHeaders(GameMode mode) {
        client.fetchItems(mode, null);
        client.fetchItemTranslations(mode, null);
        client.fetchTraders(mode, null);
        client.fetchTraderTranslations(mode, null);

        String m = "/" + mode.path() + "/";
        assertThat(server.paths()).containsExactly(m + "items", m + "items_en", m + "traders", m + "traders_en");
        assertThat(server.requests())
                .allSatisfy(headers -> {
                    assertThat(headers.getFirst("Accept-Encoding")).isEqualTo("br, gzip");
                    assertThat(headers.getFirst("If-None-Match")).isNull();
                });
    }

    @Test
    void parsesWeapon() {
        ItemDto mp5 = items().items().get(MP5);

        assertThat(mp5.isGun()).isTrue();
        assertThat(mp5.isWeapon()).isTrue();
        assertThat(mp5.isPreset()).isFalse();
        assertThat(mp5.weight()).isEqualTo(1.21);
        assertThat(mp5.properties().ergonomics()).isEqualTo(50);
        assertThat(mp5.properties().recoilVertical()).isEqualTo(56);
        assertThat(mp5.properties().recoilHorizontal()).isEqualTo(275);
        assertThat(mp5.properties().defaultPreset()).isEqualTo(MP5_DEFAULT_PRESET);
        assertThat(mp5.properties().presets()).contains(MP5_DEFAULT_PRESET);
        assertThat(mp5.categories()).containsExactly(
                "5447b5e04bdc2d62278b4567",
                "5422acb9af1c889c16000029",
                "566162e44bdc2d3f298b4573",
                "54009119af1c881c07000029");
        assertThat(mp5.iconLink()).isEqualTo("https://assets.tarkov.dev/" + MP5 + "-icon.webp");
        assertThat(mp5.lastLowPrice()).isEqualTo(50000);
        assertThat(mp5.avg24hPrice()).isEqualTo(67961);
        assertThat(mp5.buyFromTrader()).isEmpty();
    }

    @Test
    void parsesSlotsAndFilters() {
        ItemDto mp5 = items().items().get(MP5);

        assertThat(mp5.slots()).extracting(SlotDto::nameId, SlotDto::required).containsExactly(
                org.assertj.core.groups.Tuple.tuple("mod_magazine", false),
                org.assertj.core.groups.Tuple.tuple("mod_reciever", true),
                org.assertj.core.groups.Tuple.tuple("mod_charge", true));

        SlotDto receiver = mp5.slots().get(1);
        assertThat(receiver.id()).isEqualTo("5926bb2186f7744b1c6c6e63");
        assertThat(receiver.name()).isEqualTo("MOD_RECIEVER");
        assertThat(receiver.filters().allowedItems()).containsExactly("5926f2e086f7745aae644231", MP5_UPPER_RECEIVER);
        assertThat(receiver.filters().allowedCategories()).isEmpty();
        assertThat(receiver.filters().excludedItems()).isEmpty();
        assertThat(receiver.filters().excludedCategories()).isEmpty();

        // Mods have slots too, walked recursively.
        assertThat(items().items().get(MP5_UPPER_RECEIVER).slots())
                .extracting(SlotDto::nameId)
                .containsExactly("mod_handguard", "mod_sight_rear", "mod_stock", "mod_muzzle", "mod_mount");
    }

    @Test
    void parsesModifiersAndConflicts() {
        ItemsPayload items = items();

        ItemDto barrel = items.items().get(AR15_BARREL);
        assertThat(barrel.ergonomicsModifier()).isEqualTo(-2);
        assertThat(barrel.recoilModifier()).isEqualTo(-5.2);
        assertThat(barrel.accuracyModifier()).isZero();
        assertThat(barrel.weight()).isEqualTo(0.409);
        assertThat(barrel.conflictingItems()).containsExactly(
                "68a63cdac92ee33ffa01bf5f", "68a63cb3e1fb670852024664", "68a63cc0c92ee33ffa01bf5c");

        assertThat(items.items().get(PILAD_SCOPE).conflictingSlotIds()).containsExactly("5c7d55f52e221644f31bff6c");
        assertThat(items.items().get(COMTAC_V).conflictingCategories()).containsExactly("5a341c4686f77469e155819e");
    }

    @Test
    void parsesOffersAndFleaFlags() {
        ItemsPayload items = items();

        TraderOfferDto barrelOffer = items.items().get(AR15_BARREL).buyFromTrader().getFirst();
        assertThat(barrelOffer.trader()).isEqualTo(PEACEKEEPER);
        assertThat(barrelOffer.currency()).isEqualTo("USD");
        assertThat(barrelOffer.price()).isEqualTo(209);
        assertThat(barrelOffer.priceRUB()).isEqualTo(35919);
        assertThat(barrelOffer.minTraderLevel()).isEqualTo(3);
        assertThat(barrelOffer.isQuestLocked()).isFalse();

        assertThat(items.items().get(MOE_STOCK).buyFromTrader())
                .anySatisfy(offer -> assertThat(offer.taskUnlock()).isEqualTo("626bd75d5bef5d7d590bd415"))
                .filteredOn(TraderOfferDto::isQuestLocked)
                .isNotEmpty();

        assertThat(items.items().get(GPNVG).isNoFlea()).isTrue();
        assertThat(items.items().get(AR15_BARREL).isNoFlea()).isFalse();
    }

    @Test
    void parsesPresets() {
        ItemDto preset = items().items().get(MP5_DEFAULT_PRESET);

        assertThat(preset.isPreset()).isTrue();
        assertThat(preset.isWeapon()).isFalse();
        assertThat(preset.properties().baseItem()).isEqualTo(MP5);
        assertThat(preset.properties().isDefault()).isTrue();
        assertThat(preset.properties().ergonomics()).isEqualTo(72);
        assertThat(preset.properties().recoilVertical()).isEqualTo(43);
        assertThat(preset.properties().recoilHorizontal()).isEqualTo(209);
        assertThat(preset.containsItems()).hasSize(8).extracting(ContainedItemDto::item).contains(MP5, MP5_UPPER_RECEIVER);
    }

    @Test
    void parsesCategoryTree() {
        var categories = items().itemCategories();

        var smg = categories.get("5447b5e04bdc2d62278b4567");
        assertThat(smg.normalizedName()).isEqualTo("smg");
        assertThat(smg.parent()).isEqualTo("5422acb9af1c889c16000029");
        assertThat(categories.get("5422acb9af1c889c16000029").children()).contains(smg.id());
        assertThat(categories.get("54009119af1c881c07000029").parent()).as("root").isNull();
    }

    @Test
    void parsesTraders() {
        TradersPayload traders = modified(client.fetchTraders(GameMode.REGULAR, null));

        var peacekeeper = traders.traders().get(PEACEKEEPER);
        assertThat(peacekeeper.normalizedName()).isEqualTo("peacekeeper");
        assertThat(peacekeeper.name()).isEqualTo(PEACEKEEPER + " Nickname");
        assertThat(peacekeeper.levels()).extracting(l -> l.level()).containsExactly(1, 2, 3, 4);
    }

    @Test
    void returnsEtagAndNotModifiedOnRevalidation() {
        FetchResult<ItemsPayload> first = client.fetchItems(GameMode.REGULAR, null);
        assertThat(first).isInstanceOf(FetchResult.Modified.class);
        assertThat(first.etag()).isEqualTo(FixtureServer.ETAG);

        FetchResult<ItemsPayload> second = client.fetchItems(GameMode.REGULAR, first.etag());
        assertThat(second).isInstanceOf(FetchResult.NotModified.class);
        assertThat(second.etag()).isEqualTo(FixtureServer.ETAG);
        assertThat(server.requests().get(1).getFirst("If-None-Match")).isEqualTo(FixtureServer.ETAG);
    }

    @Test
    void staleEtagGetsFullPayload() {
        FetchResult<Translations> result = client.fetchItemTranslations(GameMode.PVE, "W/\"stale\"");

        assertThat(result)
                .asInstanceOf(type(FetchResult.Modified.class))
                .extracting(FetchResult.Modified::body)
                .isInstanceOf(Translations.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"br", "gzip"})
    void decodesCompressedResponses(String encoding) {
        server.contentEncoding = encoding;

        TradersPayload traders = modified(client.fetchTraders(GameMode.REGULAR, null));

        assertThat(traders.traders()).containsKey(PEACEKEEPER).hasSize(4);
    }

    @Test
    void failsOnErrorStatus() {
        server.forcedStatus = 503;

        assertThatThrownBy(() -> client.fetchItems(GameMode.REGULAR, null))
                .isInstanceOf(TarkovDevException.class)
                .hasMessageContaining("HTTP 503");
    }
}
