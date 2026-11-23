package com.cryptotrading.common.core.money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * {@link BigDecimal} helpers for amounts, quantities and prices.
 *
 * <p>Rules used across the project:
 *
 * <ul>
 *   <li>Never use {@code double} / {@code float} for money (enforced by ArchUnit).
 *   <li>Compare values with {@code compareTo}, never {@code equals} ({@code 1.0} and {@code 1.00}
 *       are not {@code equals}). The comparison helpers below do that.
 *   <li>Rounding to an asset's minimum increment always truncates toward zero, so a rounded
 *       quantity or amount is never larger (in absolute value) than the requested one.
 * </ul>
 */
public final class Decimals {

    /** Scale used for storage and events: {@code numeric(38,18)} and Avro {@code decimal(38,18)}. */
    public static final int STORAGE_SCALE = 18;

    private Decimals() {}

    /**
     * Rounds {@code value} toward zero to a multiple of {@code increment} (the asset's minimum
     * unit, e.g. {@code 0.0001} for BTC quantities). The result has the increment's scale.
     *
     * <pre>
     * roundDown(1.23456, 0.0001)  = 1.2345
     * roundDown(0.00009, 0.0001)  = 0.0000
     * roundDown(-1.23456, 0.0001) = -1.2345   (toward zero)
     * roundDown(17, 5)            = 15
     * </pre>
     *
     * @throws IllegalArgumentException if {@code increment} is not positive
     */
    public static BigDecimal roundDown(BigDecimal value, BigDecimal increment) {
        Objects.requireNonNull(value, "value");
        requirePositive(increment, "increment");
        BigDecimal units = value.divide(increment, 0, RoundingMode.DOWN);
        return units.multiply(increment).setScale(Math.max(increment.scale(), 0), RoundingMode.UNNECESSARY);
    }

    /** Truncates {@code value} toward zero to {@code scale} decimal places. */
    public static BigDecimal roundDown(BigDecimal value, int scale) {
        Objects.requireNonNull(value, "value");
        return value.setScale(scale, RoundingMode.DOWN);
    }

    /** Whether {@code value} is an exact multiple of {@code increment}. */
    public static boolean isMultipleOf(BigDecimal value, BigDecimal increment) {
        Objects.requireNonNull(value, "value");
        requirePositive(increment, "increment");
        return value.remainder(increment).signum() == 0;
    }

    /**
     * Returns {@code value} if it is zero or positive.
     *
     * @throws IllegalArgumentException if {@code value} is negative
     */
    public static BigDecimal requireNonNegative(BigDecimal value, String name) {
        Objects.requireNonNull(value, name);
        if (value.signum() < 0) {
            throw new IllegalArgumentException(name + " must not be negative: " + value.toPlainString());
        }
        return value;
    }

    /**
     * Returns {@code value} if it is strictly positive.
     *
     * @throws IllegalArgumentException if {@code value} is zero or negative
     */
    public static BigDecimal requirePositive(BigDecimal value, String name) {
        Objects.requireNonNull(value, name);
        if (value.signum() <= 0) {
            throw new IllegalArgumentException(name + " must be positive: " + value.toPlainString());
        }
        return value;
    }

    public static boolean isZero(BigDecimal value) {
        return value.signum() == 0;
    }

    public static boolean isPositive(BigDecimal value) {
        return value.signum() > 0;
    }

    public static boolean isNegative(BigDecimal value) {
        return value.signum() < 0;
    }

    /** Numeric equality, ignoring scale ({@code 1.0} equals {@code 1.00}). */
    public static boolean eq(BigDecimal a, BigDecimal b) {
        return a.compareTo(b) == 0;
    }

    public static boolean gt(BigDecimal a, BigDecimal b) {
        return a.compareTo(b) > 0;
    }

    public static boolean ge(BigDecimal a, BigDecimal b) {
        return a.compareTo(b) >= 0;
    }

    public static boolean lt(BigDecimal a, BigDecimal b) {
        return a.compareTo(b) < 0;
    }

    public static boolean le(BigDecimal a, BigDecimal b) {
        return a.compareTo(b) <= 0;
    }

    /** {@code null} becomes {@link BigDecimal#ZERO}. */
    public static BigDecimal nullToZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
