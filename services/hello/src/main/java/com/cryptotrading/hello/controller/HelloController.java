package com.cryptotrading.hello.controller;

import com.cryptotrading.hello.model.dto.GreetingResponse;
import com.cryptotrading.hello.service.GreetingService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HelloController {

    private final GreetingService greetingService;

    public HelloController(GreetingService greetingService) {
        this.greetingService = greetingService;
    }

    @GetMapping("/hello")
    public GreetingResponse hello() {
        return greetingService.currentGreeting();
    }
}
