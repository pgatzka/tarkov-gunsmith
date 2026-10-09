package com.tarkovgunsmith.tarkovdev;

import static org.assertj.core.api.Assertions.assertThat;

import com.tarkovgunsmith.tarkovdev.dto.ItemDto;
import com.tarkovgunsmith.tarkovdev.dto.ItemsPayload;
import com.tarkovgunsmith.tarkovdev.dto.TradersPayload;
import java.net.URI;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Calls the real json.tarkov.dev. Not part of {@code ./gradlew test}; run with {@code ./gradlew liveTest}. */
@Tag("live")
class TarkovDevLiveSmokeTest {

    final TarkovDevClient client = TestClients.client(URI.create("https://json.tarkov.dev"));

    @ParameterizedTest
    @EnumSource(GameMode.class)
    void fetchesAndTranslatesEverything(GameMode mode) {
        FetchResult<ItemsPayload> itemsResult = client.fetchItems(mode, null);
        ItemsPayload items = TarkovDevClientTest.modified(itemsResult)
                .translate(TarkovDevClientTest.modified(client.fetchItemTranslations(mode, null)));
        TradersPayload traders = TarkovDevClientTest.modified(client.fetchTraders(mode, null))
                .translate(TarkovDevClientTest.modified(client.fetchTraderTranslations(mode, null)));

        assertThat(items.items()).hasSizeGreaterThan(1000);
        assertThat(items.items().values().stream().filter(ItemDto::isWeapon).count()).isGreaterThan(50);
        ItemDto mp5 = items.items().get(TarkovDevClientTest.MP5);
        assertThat(mp5.name()).startsWith("HK MP5");
        assertThat(mp5.slots()).isNotEmpty();
        assertThat(traders.traders().values()).anySatisfy(t -> assertThat(t.name()).isEqualTo("Prapor"));

        assertThat(itemsResult.etag()).isNotBlank();
        assertThat(client.fetchItems(mode, itemsResult.etag())).isInstanceOf(FetchResult.NotModified.class);
    }
}
