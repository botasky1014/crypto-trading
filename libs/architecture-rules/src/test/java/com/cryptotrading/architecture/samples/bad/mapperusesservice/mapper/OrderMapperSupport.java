package com.cryptotrading.architecture.samples.bad.mapperusesservice.mapper;

import com.cryptotrading.architecture.samples.good.service.OrderService;

/** Violates MAPPERS_AND_CLIENTS_DO_NOT_DEPEND_ON_UPPER_LAYERS. */
public class OrderMapperSupport {

    public Object load(OrderService orderService) {
        return orderService.find(1L);
    }
}
