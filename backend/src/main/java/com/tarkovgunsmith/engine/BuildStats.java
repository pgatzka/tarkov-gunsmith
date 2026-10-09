package com.tarkovgunsmith.engine;

/**
 * The final stats of an assembled weapon (SPEC §3), unrounded.
 *
 * @param weight in kg, including the weapon itself
 */
public record BuildStats(double ergonomics, double recoilVertical, double recoilHorizontal, double weight) {}
