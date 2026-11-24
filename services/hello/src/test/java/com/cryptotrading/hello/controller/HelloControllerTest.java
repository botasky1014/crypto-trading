package com.cryptotrading.hello.controller;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cryptotrading.hello.model.dto.GreetingResponse;
import com.cryptotrading.hello.service.GreetingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

// Unit test without a Nacos server: Nacos config and registration are disabled, and the then
// unresolvable `nacos:hello.yaml` import is ignored (only here; the service itself fails fast).
@WebMvcTest(
        controllers = HelloController.class,
        properties = {
            "spring.cloud.nacos.config.enabled=false",
            "spring.cloud.nacos.discovery.enabled=false",
            "spring.config.on-not-found=ignore"
        })
class HelloControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private GreetingService greetingService;

    @Test
    void returnsTheCurrentGreeting() throws Exception {
        given(greetingService.currentGreeting()).willReturn(new GreetingResponse("Hello from Nacos"));

        mockMvc.perform(get("/hello"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.greeting").value("Hello from Nacos"));
    }
}
