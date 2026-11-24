package com.cryptotrading.hello;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** Temporary sample service for the version compatibility check (P0-T06, removed in P1-T09). */
@SpringBootApplication
@ConfigurationPropertiesScan
public class HelloApplication {

    public static void main(String[] args) {
        SpringApplication.run(HelloApplication.class, args);
    }
}
