package com.cryptotrading.common.core.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class DecimalsTest {

    private static final BigDecimal BTC_QTY_INCREMENT = new BigDecimal("0.0001");

    @Nested
    class RoundDownToIncrement {

        @ParameterizedTest(name = "{0} -> {1}")
        @CsvSource({
            // zero
            "0, 0.0000",
            "0.00000, 0.0000",
            // exactly one minimum unit, and just below / above it
            "0.0001, 0.0001",
            "0.00009999, 0.0000",
            "0.00010001, 0.0001",
            // more digits than the increment allows: truncated, never rounded up
            "1.23456789, 1.2345",
            "1.99999999, 1.9999",
            "123456789.000099999999999999, 123456789.0000",
            // already a multiple: unchanged value, increment's scale
            "2.5, 2.5000",
            // negative: toward zero, the absolute value never grows
            "-0.00009, 0.0000",
            "-1.23456, -1.2345",
        })
        void truncatesTowardZero(String value, String expected) {
            BigDecimal result = Decimals.roundDown(new BigDecimal(value), BTC_QTY_INCREMENT);

            assertThat(result.toPlainString()).isEqualTo(expected);
        }

        @Test
        void supportsIncrementsThatAreNotPowersOfTen() {
            assertThat(Decimals.roundDown(new BigDecimal("17"), new BigDecimal("5")))
                    .isEqualTo("15");
            assertThat(Decimals.roundDown(new BigDecimal("0.37"), new BigDecimal("0.25")))
                    .isEqualTo("0.25");
        }

        @Test
        void supportsIncrementsWithNegativeScale() {
            BigDecimal result = Decimals.roundDown(new BigDecimal("1234.5"), new BigDecimal("1E+2"));

            assertThat(result.toPlainString()).isEqualTo("1200");
        }

        @Test
        void rejectsZeroOrNegativeIncrement() {
            assertThatThrownBy(() -> Decimals.roundDown(BigDecimal.ONE, BigDecimal.ZERO))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("increment must be positive");
            assertThatThrownBy(() -> Decimals.roundDown(BigDecimal.ONE, new BigDecimal("-0.01")))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void roundDownToScaleTruncatesTowardZero() {
        assertThat(Decimals.roundDown(new BigDecimal("1.999"), 2)).isEqualTo("1.99");
        assertThat(Decimals.roundDown(new BigDecimal("-1.999"), 2)).isEqualTo("-1.99");
    }

    @Test
    void isMultipleOf() {
        assertThat(Decimals.isMultipleOf(new BigDecimal("0.0003"), BTC_QTY_INCREMENT))
                .isTrue();
        assertThat(Decimals.isMultipleOf(BigDecimal.ZERO, BTC_QTY_INCREMENT)).isTrue();
        assertThat(Decimals.isMultipleOf(new BigDecimal("0.00031"), BTC_QTY_INCREMENT))
                .isFalse();
    }

    @Test
    void requireNonNegative() {
        assertThat(Decimals.requireNonNegative(BigDecimal.ZERO, "qty")).isZero();
        assertThat(Decimals.requireNonNegative(new BigDecimal("0.0001"), "qty")).isEqualTo("0.0001");
        assertThatThrownBy(() -> Decimals.requireNonNegative(new BigDecimal("-0.0001"), "qty"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("qty must not be negative: -0.0001");
    }

    @Test
    void requirePositive() {
        assertThatThrownBy(() -> Decimals.requirePositive(BigDecimal.ZERO, "price"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("price must be positive: 0");
    }

    @Test
    @SuppressWarnings("BigDecimalEquals") // shows on purpose the trap that Decimals.eq avoids
    void comparisonsIgnoreScale() {
        BigDecimal one = new BigDecimal("1.0");
        BigDecimal oneHundredths = new BigDecimal("1.00");

        assertThat(one.equals(oneHundredths)).isFalse();
        assertThat(Decimals.eq(one, oneHundredths)).isTrue();
        assertThat(Decimals.ge(one, oneHundredths)).isTrue();
        assertThat(Decimals.le(one, oneHundredths)).isTrue();
        assertThat(Decimals.gt(new BigDecimal("1.0001"), one)).isTrue();
        assertThat(Decimals.lt(new BigDecimal("0.9999"), one)).isTrue();
    }

    @Test
    void signHelpers() {
        assertThat(Decimals.isZero(new BigDecimal("0.000"))).isTrue();
        assertThat(Decimals.isPositive(new BigDecimal("0.0001"))).isTrue();
        assertThat(Decimals.isNegative(new BigDecimal("-0.0001"))).isTrue();
        assertThat(Decimals.nullToZero(null)).isZero();
    }
}
