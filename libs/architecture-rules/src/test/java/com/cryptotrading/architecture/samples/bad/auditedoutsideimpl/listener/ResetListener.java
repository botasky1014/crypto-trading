package com.cryptotrading.architecture.samples.bad.auditedoutsideimpl.listener;

import com.cryptotrading.architecture.samples.Audited;

/** Violates AUDITED_ONLY_IN_SERVICE_IMPL. */
public class ResetListener {

    @Audited("ACCOUNT_RESET")
    public void onReset(long userId) {}
}
