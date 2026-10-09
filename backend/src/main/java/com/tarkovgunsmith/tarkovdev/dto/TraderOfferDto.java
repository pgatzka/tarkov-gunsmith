package com.tarkovgunsmith.tarkovdev.dto;

/**
 * A trader offer from {@code buyFromTrader}. {@code price} is in {@code currency}; {@code priceRUB}
 * is the same price converted to roubles. {@code taskUnlock} is the id of the task that unlocks the
 * offer, or {@code null}.
 */
public record TraderOfferDto(
        String trader, Integer price, String currency, Integer priceRUB, Integer minTraderLevel, String taskUnlock) {

    public boolean isQuestLocked() {
        return taskUnlock != null;
    }
}
