package com.cryptotrading.architecture.samples.bad.modelusesservice.model.entity;

import com.cryptotrading.architecture.samples.good.service.OrderService;

/** Violates MODEL_IS_PURE. */
public class OrderEntity {

    public Object view(OrderService orderService) {
        return orderService.find(1L);
    }
}
