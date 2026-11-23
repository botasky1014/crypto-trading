package com.cryptotrading.architecture.samples.bad.serviceusescontroller.service.impl;

import com.cryptotrading.architecture.samples.good.controller.OrderController;

/** Violates SERVICES_DO_NOT_DEPEND_ON_CONTROLLERS. */
public class ReportServiceImpl {

    public Object report(OrderController controller) {
        return controller.get(1L);
    }
}
