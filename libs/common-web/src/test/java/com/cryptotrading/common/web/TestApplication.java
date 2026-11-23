package com.cryptotrading.common.web;

import com.cryptotrading.common.core.error.BusinessException;
import com.cryptotrading.common.core.error.ErrorCode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Minimal application used by the web tests. */
@SpringBootApplication
public class TestApplication {

    enum SampleErrorCode implements ErrorCode {
        INSUFFICIENT_PLATFORM_FUNDS(409);

        private final int httpStatus;

        SampleErrorCode(int httpStatus) {
            this.httpStatus = httpStatus;
        }

        @Override
        public int httpStatus() {
            return httpStatus;
        }
    }

    record Quote(String symbol, BigDecimal price, BigDecimal qty, Instant time) {}

    record OrderRequest(@NotBlank String symbol, @NotNull BigDecimal qty) {}

    @RestController
    static class SampleController {

        @GetMapping("/sample/quote")
        Quote quote() {
            return new Quote(
                    "BTC-USD", new BigDecimal("97000.10"), new BigDecimal("1E-8"), Instant.parse("2026-11-23T08:00:00Z"));
        }

        @PostMapping("/sample/orders")
        OrderRequest placeOrder(@Valid @RequestBody OrderRequest request) {
            return request;
        }

        @GetMapping("/sample/business-error")
        void businessError() {
            throw new BusinessException(SampleErrorCode.INSUFFICIENT_PLATFORM_FUNDS, "Platform pool cannot fund 10000 USD")
                    .withProperty("asset", "USD");
        }

        @GetMapping("/sample/unexpected-error")
        void unexpectedError() {
            throw new IllegalStateException("db password=hunter2");
        }

        @GetMapping(path = "/sample/version", version = "1")
        String versionOne() {
            return "v1";
        }

        @GetMapping(path = "/sample/version", version = "2")
        String versionTwo() {
            return "v2";
        }
    }
}
