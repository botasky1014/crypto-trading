package com.cryptotrading.architecture.samples.good.service;

import com.cryptotrading.architecture.samples.good.model.dto.OrderView;

public interface OrderService {
    OrderView find(long id);
}
