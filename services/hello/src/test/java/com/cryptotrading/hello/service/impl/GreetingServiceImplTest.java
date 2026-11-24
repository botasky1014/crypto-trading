package com.cryptotrading.hello.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.cryptotrading.hello.config.HelloProperties;
import org.junit.jupiter.api.Test;

class GreetingServiceImplTest {

    @Test
    void returnsTheCurrentValueAfterARebind() {
        HelloProperties properties = new HelloProperties();
        properties.setGreeting("first");
        GreetingServiceImpl service = new GreetingServiceImpl(properties);
        assertThat(service.currentGreeting().greeting()).isEqualTo("first");

        // What the Spring Cloud rebinder does when Nacos pushes a new value.
        properties.setGreeting("second");
        assertThat(service.currentGreeting().greeting()).isEqualTo("second");
    }
}
