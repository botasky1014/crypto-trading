package com.cryptotrading.architecture.samples.good.mapper;

import java.math.BigDecimal;

public interface OrderMapper {
    BigDecimal qty(long id);

    void delete(long id);
}
