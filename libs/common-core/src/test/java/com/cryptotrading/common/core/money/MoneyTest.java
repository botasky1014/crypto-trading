package com.cryptotrading.common.core.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class MoneyTest {

    @Test
    void equalityIsNumeric() {
        assertThat(Money.of("USD", "1.0")).isEqualTo(Money.of("USD", "1.00"));
        assertThat(Money.of("USD", "1.0")).hasSameHashCodeAs(Money.of("USD", "1.00"));
        assertThat(Money.of("USD", "1")).isNotEqualTo(Money.of("BTC", "1"));
    }

    @Test
    void arithmetic() {
        Money a = Money.of("BTC", "0.5");
        Money b = Money.of("BTC", "0.2");

        assertThat(a.plus(b)).isEqualTo(Money.of("BTC", "0.7"));
        assertThat(b.minus(a)).isEqualTo(Money.of("BTC", "-0.3"));
        assertThat(a.negate()).isEqualTo(Money.of("BTC", "-0.5"));
        assertThat(a).isGreaterThan(b);
    }

    @Test
    void rejectsMixedAssets() {
        assertThatThrownBy(() -> Money.of("BTC", "1").plus(Money.of("USD", "1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Asset mismatch: BTC vs USD");
    }

    @Test
    void roundDown() {
        Money rounded = Money.of("BTC", "0.123456").roundDown(new BigDecimal("0.0001"));

        assertThat(rounded.amount().toPlainString()).isEqualTo("0.1234");
    }

    @Test
    void requireNonNegative() {
        assertThat(Money.zero("USD").requireNonNegative().isZero()).isTrue();
        assertThatThrownBy(() -> Money.of("USD", "-0.01").requireNonNegative())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("USD amount must not be negative: -0.01");
    }

    @Test
    void toStringUsesPlainNotation() {
        assertThat(Money.of("BTC", new BigDecimal("1E-8"))).hasToString("0.00000001 BTC");
    }

    @Test
    void rejectsBlankAsset() {
        assertThatThrownBy(() -> Money.of(" ", "1")).isInstanceOf(IllegalArgumentException.class);
    }
}
