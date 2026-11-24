package com.cryptotrading.common.observability;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Minimal application used by the observability tests. */
@SpringBootApplication
public class TestApplication {

    @RestController
    static class SampleController {

        @GetMapping("/sample/ping")
        String ping() {
            return "pong";
        }
    }
}
