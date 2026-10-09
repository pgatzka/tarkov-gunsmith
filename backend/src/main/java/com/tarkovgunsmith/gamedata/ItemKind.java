package com.tarkovgunsmith.gamedata;

/** What an imported item is used for. */
public enum ItemKind {
    /** A gun builds are generated for; carries base stats and slots. */
    WEAPON,
    /** A part that goes into a slot; carries modifiers, slots and conflicts. */
    MOD,
    /** A preassembled weapon from the game data, used to validate computed stats. */
    PRESET
}
