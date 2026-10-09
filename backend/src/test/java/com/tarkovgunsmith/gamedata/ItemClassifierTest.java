package com.tarkovgunsmith.gamedata;

import static com.tarkovgunsmith.gamedata.Fixtures.AR15_BARREL;
import static com.tarkovgunsmith.gamedata.Fixtures.COMTAC_V;
import static com.tarkovgunsmith.gamedata.Fixtures.FN40GL;
import static com.tarkovgunsmith.gamedata.Fixtures.FN40GL_DEFAULT_PRESET;
import static com.tarkovgunsmith.gamedata.Fixtures.GPNVG;
import static com.tarkovgunsmith.gamedata.Fixtures.M32A1;
import static com.tarkovgunsmith.gamedata.Fixtures.MP5;
import static com.tarkovgunsmith.gamedata.Fixtures.MP5_DEFAULT_PRESET;
import static com.tarkovgunsmith.gamedata.Fixtures.RHINO_50DS;
import static com.tarkovgunsmith.gamedata.Fixtures.RSHG2;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import com.tarkovgunsmith.tarkovdev.dto.ItemsPayload;
import java.util.List;
import org.junit.jupiter.api.Test;

class ItemClassifierTest {

    final ItemsPayload items = Fixtures.items();

    @Test
    void classifiesWeaponsModsAndPresets() {
        var classification = new ItemClassifier(Fixtures.properties()).classify(items);

        assertThat(classification.kinds())
                .contains(
                        entry(MP5, ItemKind.WEAPON),
                        entry(RHINO_50DS, ItemKind.WEAPON),
                        entry(MP5_DEFAULT_PRESET, ItemKind.PRESET),
                        entry(AR15_BARREL, ItemKind.MOD),
                        entry(GPNVG, ItemKind.MOD))
                .doesNotContainKeys(COMTAC_V);
        assertThat(classification.count(ItemKind.WEAPON)).isEqualTo(2);
    }

    @Test
    void excludesWeaponsByCategoryAndIdTogetherWithTheirPresets() {
        var classification = new ItemClassifier(Fixtures.properties()).classify(items);

        assertThat(classification.excludedWeapons()).containsExactlyInAnyOrder(FN40GL, M32A1, RSHG2);
        assertThat(classification.kinds()).doesNotContainKeys(FN40GL, M32A1, RSHG2, FN40GL_DEFAULT_PRESET);
    }

    @Test
    void excludingAParentCategoryExcludesItsChildren() {
        String weaponCategory = "5422acb9af1c889c16000029";
        var classification = new ItemClassifier(new GameDataProperties(List.of(weaponCategory), List.of()))
                .classify(items);

        assertThat(classification.count(ItemKind.WEAPON)).isZero();
        assertThat(classification.count(ItemKind.PRESET)).isZero();
        assertThat(classification.excludedWeapons()).contains(MP5, RHINO_50DS);
    }

    @Test
    void withoutExclusionsEveryGunIsAWeapon() {
        var classification = new ItemClassifier(new GameDataProperties(null, null)).classify(items);

        assertThat(classification.excludedWeapons()).isEmpty();
        assertThat(classification.kinds())
                .contains(entry(FN40GL, ItemKind.WEAPON), entry(FN40GL_DEFAULT_PRESET, ItemKind.PRESET));
    }

    @Test
    void weaponClassIsTheMostSpecificCategory() {
        assertThat(ItemClassifier.weaponClass(items.items().get(MP5), items.itemCategories())).isEqualTo("smg");
        assertThat(ItemClassifier.weaponClass(items.items().get(RHINO_50DS), items.itemCategories()))
                .isEqualTo("revolver");
    }
}
