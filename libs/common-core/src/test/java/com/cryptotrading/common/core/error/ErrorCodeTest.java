package com.cryptotrading.common.core.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class ErrorCodeTest {

    enum SampleErrorCode implements ErrorCode {
        INSUFFICIENT_PLATFORM_FUNDS(409),
        lowerCase(400);

        private final int httpStatus;

        SampleErrorCode(int httpStatus) {
            this.httpStatus = httpStatus;
        }

        @Override
        public int httpStatus() {
            return httpStatus;
        }
    }

    @Test
    void codeIsTheEnumNameAndTitleIsDerived() {
        assertThat(SampleErrorCode.INSUFFICIENT_PLATFORM_FUNDS.code()).isEqualTo("INSUFFICIENT_PLATFORM_FUNDS");
        assertThat(SampleErrorCode.INSUFFICIENT_PLATFORM_FUNDS.title()).isEqualTo("Insufficient platform funds");
    }

    @Test
    void allCommonCodesUseUpperSnakeCase() {
        assertThat(Arrays.stream(CommonErrorCode.values()).map(ErrorCode::code)).allMatch(ErrorCode::isValidCode);
    }

    @Test
    void codeFormat() {
        assertThat(ErrorCode.isValidCode("R04_LIMIT")).isTrue();
        assertThat(ErrorCode.isValidCode("lowerCase")).isFalse();
        assertThat(ErrorCode.isValidCode("TRAILING_")).isFalse();
        assertThat(ErrorCode.isValidCode("_LEADING")).isFalse();
        assertThat(ErrorCode.isValidCode(null)).isFalse();
    }

    @Test
    void fromHttpStatus() {
        assertThat(CommonErrorCode.fromHttpStatus(404)).isEqualTo(CommonErrorCode.NOT_FOUND);
        assertThat(CommonErrorCode.fromHttpStatus(418)).isEqualTo(CommonErrorCode.BAD_REQUEST);
        assertThat(CommonErrorCode.fromHttpStatus(502)).isEqualTo(CommonErrorCode.INTERNAL_ERROR);
    }

    @Test
    void businessExceptionCarriesCodeDetailAndProperties() {
        BusinessException e = new BusinessException(SampleErrorCode.INSUFFICIENT_PLATFORM_FUNDS, "Platform pool is short")
                .withProperty("asset", "USD");

        assertThat(e.getErrorCode()).isEqualTo(SampleErrorCode.INSUFFICIENT_PLATFORM_FUNDS);
        assertThat(e.getMessage()).isEqualTo("Platform pool is short");
        assertThat(e.getProperties()).containsEntry("asset", "USD");
    }

    @Test
    void businessExceptionDefaultsDetailToTitle() {
        assertThat(new BusinessException(CommonErrorCode.NOT_FOUND).getMessage()).isEqualTo("Not found");
    }

    @Test
    void businessExceptionRejectsMalformedCode() {
        assertThatThrownBy(() -> new BusinessException(SampleErrorCode.lowerCase))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("UPPER_SNAKE_CASE");
    }
}
