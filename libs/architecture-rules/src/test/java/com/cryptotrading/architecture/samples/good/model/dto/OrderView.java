package com.cryptotrading.architecture.samples.good.model.dto;

import java.math.BigDecimal;

/** {@code fillRatio} is a double on purpose: the money rule only looks at money field names. */
public record OrderView(long id, BigDecimal qty, BigDecimal availableBalance, double fillRatio) {}
