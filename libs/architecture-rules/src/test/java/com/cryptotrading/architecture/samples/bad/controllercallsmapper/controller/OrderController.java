package com.cryptotrading.architecture.samples.bad.controllercallsmapper.controller;

import com.cryptotrading.architecture.samples.good.mapper.OrderMapper;

/** Violates CONTROLLERS_ONLY_CALL_SERVICES: skips the service and calls a mapper. */
public class OrderController {

    private final OrderMapper orderMapper;

    public OrderController(OrderMapper orderMapper) {
        this.orderMapper = orderMapper;
    }

    public Object get(long id) {
        return orderMapper.qty(id);
    }
}
