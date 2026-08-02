package com.tinypay.finance.repository;

import com.tinypay.finance.domain.PaymentIdempotency;
import com.tinypay.global.config.JpaAuditingConfig;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("integration")
@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:tc:mysql:8.0.36:///tinypay_idempotency",
        "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=false"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaAuditingConfig.class)
class PaymentIdempotencyRepositoryIntegrationTest {

    @Autowired
    private PaymentIdempotencyRepository paymentIdempotencyRepository;

    @Test
    void 같은_사용자의_동일한_멱등성_키는_한번만_저장된다() {
        paymentIdempotencyRepository.saveAndFlush(
                PaymentIdempotency.processing(
                        1L, 10L, "duplicate-key", new BigDecimal("10.00")
                )
        );

        assertThatThrownBy(() -> paymentIdempotencyRepository.saveAndFlush(
                PaymentIdempotency.processing(
                        1L, 10L, "duplicate-key", new BigDecimal("10.00")
                )
        )).isInstanceOf(DataIntegrityViolationException.class);
    }
}
