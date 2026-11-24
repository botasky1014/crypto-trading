package com.cryptotrading.hello.service.impl;

import com.cryptotrading.hello.config.HelloProperties;
import com.cryptotrading.hello.model.dto.GreetingResponse;
import com.cryptotrading.hello.service.GreetingService;
import org.springframework.stereotype.Service;

@Service
public class GreetingServiceImpl implements GreetingService {

    private final HelloProperties properties;

    public GreetingServiceImpl(HelloProperties properties) {
        this.properties = properties;
    }

    @Override
    public GreetingResponse currentGreeting() {
        // Read on every call: the properties bean is rebound when the Nacos config changes.
        return new GreetingResponse(properties.getGreeting());
    }
}
