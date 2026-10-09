package com.tarkovgunsmith.gamedata;

import static com.tarkovgunsmith.gamedata.Fixtures.GPNVG;
import static com.tarkovgunsmith.gamedata.Fixtures.MP5_UPPER_RECEIVER;
import static com.tarkovgunsmith.gamedata.Fixtures.withFleaPrices;
import static org.assertj.core.api.Assertions.assertThat;

import com.tarkovgunsmith.tarkovdev.dto.ItemDto;
import org.junit.jupiter.api.Test;

class FleaPriceTest {

    private final ItemDto receiver = Fixtures.items().items().get(MP5_UPPER_RECEIVER);

    @Test
    void usesLastLowPrice() {
        assertThat(GameDataImporter.fleaPrice(withFleaPrices(receiver, 900, 1200))).isEqualTo(900);
    }

    @Test
    void fallsBackToAvg24hPrice() {
        assertThat(GameDataImporter.fleaPrice(withFleaPrices(receiver, null, 1200))).isEqualTo(1200);
        assertThat(GameDataImporter.fleaPrice(withFleaPrices(receiver, 0, 1200))).isEqualTo(1200);
    }

    @Test
    void noPriceWithoutAnyFleaData() {
        assertThat(GameDataImporter.fleaPrice(withFleaPrices(receiver, null, null))).isNull();
        assertThat(GameDataImporter.fleaPrice(withFleaPrices(receiver, 0, 0))).isNull();
    }

    @Test
    void noFleaItemsHaveNoFleaPrice() {
        ItemDto gpnvg = Fixtures.items().items().get(GPNVG);
        assertThat(gpnvg.isNoFlea()).isTrue();
        assertThat(GameDataImporter.fleaPrice(withFleaPrices(gpnvg, 100_000, 100_000))).isNull();
    }
}
