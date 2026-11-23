package com.cryptotrading.architecture.samples.good.service.impl;

import com.cryptotrading.architecture.samples.Audited;
import com.cryptotrading.architecture.samples.good.client.LedgerClient;
import com.cryptotrading.architecture.samples.good.mapper.OrderMapper;
import com.cryptotrading.architecture.samples.good.model.dto.OrderView;
import com.cryptotrading.architecture.samples.good.service.OrderService;
import org.springframework.transaction.annotation.Transactional;

@Transactional(readOnly = true)
public class OrderServiceImpl implements OrderService {

    private final OrderMapper orderMapper;
    private final LedgerClient ledgerClient;

    public OrderServiceImpl(OrderMapper orderMapper, LedgerClient ledgerClient) {
        this.orderMapper = orderMapper;
        this.ledgerClient = ledgerClient;
    }

    @Override
    public OrderView find(long id) {
        return new OrderView(id, orderMapper.qty(id), ledgerClient.balance(), 0.5);
    }

    @Transactional
    @jakarta.transaction.Transactional
    @Audited("ORDER_CANCEL")
    public void cancel(long id) {
        orderMapper.delete(id);
    }
}
