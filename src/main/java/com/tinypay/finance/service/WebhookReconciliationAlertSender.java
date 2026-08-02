package com.tinypay.finance.service;

import com.tinypay.finance.event.ReconciliationAlertEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.util.Map;

@Component
public class WebhookReconciliationAlertSender implements ReconciliationAlertSender {

    private final RestClient restClient;
    private final String webhookUrl;

    public WebhookReconciliationAlertSender(
            RestClient restClient,
            @Value("${payment.reconciliation.alert.webhook-url:}") String webhookUrl
    ) {
        this.restClient = restClient;
        this.webhookUrl = webhookUrl;
    }

    @Override
    public void send(ReconciliationAlertEvent event) {
        if (!StringUtils.hasText(webhookUrl)) {
            throw new IllegalStateException("대사 알림 Webhook URL이 설정되지 않았습니다.");
        }

        restClient.post()
                .uri(webhookUrl)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("text", message(event)))
                .retrieve()
                .toBodilessEntity();
    }

    private String message(ReconciliationAlertEvent event) {
        return "[TinyPay 결제 대사 경고] paymentId=%d, status=%s, attempt=%d, amount=%s, txHash=%s, detail=%s"
                .formatted(event.paymentId(), event.status(), event.attempt(), event.amount(),
                        event.txHash(), event.detail());
    }
}
