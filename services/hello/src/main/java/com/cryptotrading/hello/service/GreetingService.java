package com.cryptotrading.hello.service;

import com.cryptotrading.hello.model.dto.GreetingResponse;

public interface GreetingService {

    /** The current value of the Nacos config item {@code hello.greeting}. */
    GreetingResponse currentGreeting();
}
