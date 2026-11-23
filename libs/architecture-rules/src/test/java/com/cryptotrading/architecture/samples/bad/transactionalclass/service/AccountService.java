package com.cryptotrading.architecture.samples.bad.transactionalclass.service;

import org.springframework.transaction.annotation.Transactional;

/** Violates TRANSACTIONAL_TYPES_ONLY_IN_SERVICE_IMPL: service interface, not service.impl. */
@Transactional
public interface AccountService {
    void open(long userId);
}
