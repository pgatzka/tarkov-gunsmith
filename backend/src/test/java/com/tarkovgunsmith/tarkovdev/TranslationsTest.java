package com.tarkovgunsmith.tarkovdev;

import static com.tarkovgunsmith.tarkovdev.TarkovDevClientTest.MP5;
import static com.tarkovgunsmith.tarkovdev.TarkovDevClientTest.MP5_UPPER_RECEIVER;
import static com.tarkovgunsmith.tarkovdev.TarkovDevClientTest.PEACEKEEPER;
import static com.tarkovgunsmith.tarkovdev.TarkovDevClientTest.modified;
import static org.assertj.core.api.Assertions.assertThat;

import com.tarkovgunsmith.tarkovdev.dto.ItemsPayload;
import com.tarkovgunsmith.tarkovdev.dto.SlotDto;
import com.tarkovgunsmith.tarkovdev.dto.TradersPayload;
import com.tarkovgunsmith.tarkovdev.dto.Translations;
import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TranslationsTest {

    @Test
    void resolvesKnownKeysAndFallsBackToTheKey() {
        var translations = new Translations(Map.of("MOD_STOCK", "Stock"));

        assertThat(translations.resolve("MOD_STOCK")).isEqualTo("Stock");
        assertThat(translations.resolve("unknown")).isEqualTo("unknown");
        assertThat(translations.resolve(null)).isNull();
    }

    @Test
    void translatesItemsSlotsCategoriesAndTraders() throws IOException {
        try (var server = new FixtureServer()) {
            var client = TestClients.client(server.baseUrl());

            ItemsPayload items = modified(client.fetchItems(GameMode.REGULAR, null))
                    .translate(modified(client.fetchItemTranslations(GameMode.REGULAR, null)));
            TradersPayload traders = modified(client.fetchTraders(GameMode.REGULAR, null))
                    .translate(modified(client.fetchTraderTranslations(GameMode.REGULAR, null)));

            var mp5 = items.items().get(MP5);
            assertThat(mp5.name()).isEqualTo("HK MP5 9x19 submachine gun (Navy 3 Round Burst)");
            assertThat(mp5.shortName()).isEqualTo("MP5");
            assertThat(mp5.slots()).extracting(SlotDto::name).containsExactly("Magazine", "Receiver", "Ch. Handle");
            assertThat(items.items().get(MP5_UPPER_RECEIVER).name()).isEqualTo("HK MP5 9x19 upper receiver");
            assertThat(items.itemCategories().get("5447b5e04bdc2d62278b4567").name()).isEqualTo("SMG");
            assertThat(traders.traders().get(PEACEKEEPER).name()).isEqualTo("Peacekeeper");

            // Everything else is carried over unchanged.
            assertThat(mp5.properties().ergonomics()).isEqualTo(50);
            assertThat(mp5.slots().get(1).filters().allowedItems()).contains(MP5_UPPER_RECEIVER);
        }
    }
}
