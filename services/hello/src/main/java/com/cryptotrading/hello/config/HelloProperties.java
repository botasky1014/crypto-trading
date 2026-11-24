package com.cryptotrading.hello.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code hello.*} from Nacos {@code hello.yaml}. A mutable bean on purpose: Spring Cloud rebinds
 * {@code @ConfigurationProperties} beans in place when Nacos pushes a change, which does not work
 * for constructor-bound records.
 */
@ConfigurationProperties(prefix = "hello")
public class HelloProperties {

    private String greeting = "";

    public String getGreeting() {
        return greeting;
    }

    public void setGreeting(String greeting) {
        this.greeting = greeting;
    }
}
