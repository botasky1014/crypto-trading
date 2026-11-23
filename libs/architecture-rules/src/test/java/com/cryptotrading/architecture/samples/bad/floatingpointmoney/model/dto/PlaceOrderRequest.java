package com.cryptotrading.architecture.samples.bad.floatingpointmoney.model.dto;

/** Violates NO_FLOATING_POINT_MONEY. */
public record PlaceOrderRequest(String symbol, double qty, Float limitPrice) {}
