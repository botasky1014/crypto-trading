package com.cryptotrading.architecture.samples.bad.controllercallsclient.controller;

import com.cryptotrading.architecture.samples.good.client.LedgerClient;

/** Violates CONTROLLERS_ONLY_CALL_SERVICES: skips the service and calls a client. */
public class BalanceController {

    private final LedgerClient ledgerClient;

    public BalanceController(LedgerClient ledgerClient) {
        this.ledgerClient = ledgerClient;
    }

    public Object balance() {
        return ledgerClient.balance();
    }
}
