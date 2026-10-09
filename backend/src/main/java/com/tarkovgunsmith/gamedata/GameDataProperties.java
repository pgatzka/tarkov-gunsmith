package com.tarkovgunsmith.gamedata;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Which guns are left out of the app (launchers and other special weapons, SPEC §1.1).
 *
 * @param excludedWeaponCategories category ids; a gun in one of these (or a descendant) is excluded
 * @param excludedWeapons item ids of individual guns to exclude, for those filed under a normal
 *     category (e.g. the M32A1 grenade launcher is a "Revolver")
 */
@ConfigurationProperties("gunsmith.game-data")
public record GameDataProperties(List<String> excludedWeaponCategories, List<String> excludedWeapons) {

    public GameDataProperties {
        excludedWeaponCategories = excludedWeaponCategories == null ? List.of() : List.copyOf(excludedWeaponCategories);
        excludedWeapons = excludedWeapons == null ? List.of() : List.copyOf(excludedWeapons);
    }
}
