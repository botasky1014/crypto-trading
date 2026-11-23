package com.cryptotrading.architecture.samples.bad.transactionalmethod.controller;

import jakarta.transaction.Transactional;

/** Violates TRANSACTIONAL_METHODS_ONLY_IN_SERVICE_IMPL (Jakarta variant). */
public class AccountController {

    @Transactional
    public void open(long userId) {}
}
