package com.cryptotrading.architecture.samples.good.controller;

import com.cryptotrading.architecture.samples.good.model.dto.OrderView;
import com.cryptotrading.architecture.samples.good.service.OrderService;

public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    public OrderView get(long id) {
        return orderService.find(id);
    }
}
