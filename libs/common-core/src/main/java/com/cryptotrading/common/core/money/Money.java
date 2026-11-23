package com.cryptotrading.common.core.money;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * An amount of one asset, e.g. {@code 0.5 BTC} or {@code 100 USD}.
 *
 * <p>Equality is numeric: {@code 1.0 USD} equals {@code 1.00 USD}. Arithmetic between different
 * assets is rejected.
 *
 * @param asset asset code, e.g. {@code BTC}, {@code USD}
 * @param amount the amount; may be negative (e.g. a debit)
 */
public record Money(String asset, BigDecimal amount) implements Comparable<Money> {

    public Money {
        Objects.requireNonNull(asset, "asset");
        Objects.requireNonNull(amount, "amount");
        if (asset.isBlank()) {
            throw new IllegalArgumentException("asset must not be blank");
        }
    }

    public static Money of(String asset, BigDecimal amount) {
        return new Money(asset, amount);
    }

    /** Parses a decimal string, the wire format for amounts. */
    public static Money of(String asset, String amount) {
        return new Money(asset, new BigDecimal(amount));
    }

    public static Money zero(String asset) {
        return new Money(asset, BigDecimal.ZERO);
    }

    public Money plus(Money other) {
        requireSameAsset(other);
        return new Money(asset, amount.add(other.amount));
    }

    public Money minus(Money other) {
        requireSameAsset(other);
        return new Money(asset, amount.subtract(other.amount));
    }

    public Money negate() {
        return new Money(asset, amount.negate());
    }

    /** Truncates toward zero to a multiple of the asset's minimum increment. */
    public Money roundDown(BigDecimal increment) {
        return new Money(asset, Decimals.roundDown(amount, increment));
    }

    public boolean isZero() {
        return Decimals.isZero(amount);
    }

    public boolean isPositive() {
        return Decimals.isPositive(amount);
    }

    public boolean isNegative() {
        return Decimals.isNegative(amount);
    }

    /**
     * Returns this if the amount is zero or positive.
     *
     * @throws IllegalArgumentException if the amount is negative
     */
    public Money requireNonNegative() {
        Decimals.requireNonNegative(amount, asset + " amount");
        return this;
    }

    @Override
    public int compareTo(Money other) {
        requireSameAsset(other);
        return amount.compareTo(other.amount);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Money other && asset.equals(other.asset) && amount.compareTo(other.amount) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(asset, amount.stripTrailingZeros());
    }

    @Override
    public String toString() {
        return amount.toPlainString() + " " + asset;
    }

    private void requireSameAsset(Money other) {
        Objects.requireNonNull(other, "other");
        if (!asset.equals(other.asset)) {
            throw new IllegalArgumentException("Asset mismatch: " + asset + " vs " + other.asset);
        }
    }
}
