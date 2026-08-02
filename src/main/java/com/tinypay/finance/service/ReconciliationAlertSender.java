package com.tinypay.finance.service;

import com.tinypay.finance.event.ReconciliationAlertEvent;

public interface ReconciliationAlertSender {
    void send(ReconciliationAlertEvent event);
}
