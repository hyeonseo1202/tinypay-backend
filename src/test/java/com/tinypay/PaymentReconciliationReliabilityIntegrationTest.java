package com.tinypay.finance.repository;

import com.tinypay.chat.domain.ChatSession;
import com.tinypay.chat.repository.ChatSessionRepository;
import com.tinypay.dify.domain.AiRequest;
import com.tinypay.dify.domain.AiRequestStatus;
import com.tinypay.dify.repository.AiRequestRepository;
import com.tinypay.finance.domain.*;
import com.tinypay.finance.event.ReconciliationAlertEvent;
import com.tinypay.global.config.JpaAuditingConfig;
import com.tinypay.user.domain.User;
import com.tinypay.user.repository.UserRepository;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("integration")
@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:tc:mysql:8.0.36:///tinypay",
        "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=false"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaAuditingConfig.class, PaymentReconciliationReliabilityIntegrationTest.EventTestConfig.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PaymentReconciliationReliabilityIntegrationTest {

    @Autowired UserRepository userRepository;
    @Autowired ChatSessionRepository chatSessionRepository;
    @Autowired AiRequestRepository aiRequestRepository;
    @Autowired WalletRepository walletRepository;
    @Autowired PaymentLogRepository paymentLogRepository;
    @Autowired ReconciliationAlertRepository alertRepository;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired ApplicationEventPublisher eventPublisher;
    @Autowired RecordingAlertListener recordingAlertListener;

    @BeforeEach
    void clearRecordedEvents() {
        recordingAlertListener.clear();
    }

    @Test
    void 두_작업자가_동시에_조회해도_같은_결제는_한번만_선점된다() throws Exception {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        Long paymentId = transaction.execute(status -> createPayment().getId());
        AtomicInteger claimedCount = new AtomicInteger();
        CountDownLatch firstClaimed = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<?> first = executor.submit(() -> transaction.executeWithoutResult(status -> {
                List<PaymentLog> claimed = findCandidates();
                claimed.forEach(PaymentLog::startReconciliation);
                claimedCount.addAndGet(claimed.size());
                firstClaimed.countDown();
                await(releaseFirst);
            }));

            assertThat(firstClaimed.await(5, TimeUnit.SECONDS)).isTrue();
            Future<?> second = executor.submit(() -> transaction.executeWithoutResult(status -> {
                List<PaymentLog> claimed = findCandidates();
                claimed.forEach(PaymentLog::startReconciliation);
                claimedCount.addAndGet(claimed.size());
            }));

            releaseFirst.countDown();
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
        }

        ReconciliationStatus finalStatus = transaction.execute(status ->
                paymentLogRepository.findById(paymentId).orElseThrow().getReconciliationStatus());
        assertThat(claimedCount).hasValue(1);
        assertThat(finalStatus).isEqualTo(ReconciliationStatus.PROCESSING);
    }

    @Test
    void 동일한_대사_알림_이벤트키는_DB에서_중복_저장되지_않는다() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.executeWithoutResult(status -> alertRepository.saveAndFlush(
                ReconciliationAlert.pending("1:MISMATCHED:1", 1L,
                        ReconciliationStatus.MISMATCHED, 1)));

        assertThatThrownBy(() -> transaction.executeWithoutResult(status ->
                alertRepository.saveAndFlush(ReconciliationAlert.pending(
                        "1:MISMATCHED:1", 1L, ReconciliationStatus.MISMATCHED, 1))))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 두_작업자가_동시에_조회해도_같은_실패_알림은_한번만_선점된다() throws Exception {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        Long alertId = transaction.execute(status -> {
            ReconciliationAlert alert = ReconciliationAlert.pending(alertEvent(999L));
            alert.startDelivery();
            alert.markFailed("webhook timeout", LocalDateTime.now().minusSeconds(1));
            return alertRepository.save(alert).getId();
        });
        AtomicInteger claimedCount = new AtomicInteger();
        CountDownLatch firstClaimed = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<?> first = executor.submit(() -> transaction.executeWithoutResult(status -> {
                List<ReconciliationAlert> claimed = findAlertRetryCandidates();
                claimed.forEach(ReconciliationAlert::startDelivery);
                claimedCount.addAndGet(claimed.size());
                firstClaimed.countDown();
                await(releaseFirst);
            }));
            assertThat(firstClaimed.await(5, TimeUnit.SECONDS)).isTrue();

            Future<?> second = executor.submit(() -> transaction.executeWithoutResult(status -> {
                List<ReconciliationAlert> claimed = findAlertRetryCandidates();
                claimed.forEach(ReconciliationAlert::startDelivery);
                claimedCount.addAndGet(claimed.size());
            }));
            releaseFirst.countDown();
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
        }

        ReconciliationAlert stored = transaction.execute(status ->
                alertRepository.findById(alertId).orElseThrow());
        assertThat(claimedCount).hasValue(1);
        assertThat(stored.getDeliveryStatus()).isEqualTo(ReconciliationAlertStatus.PROCESSING);
        assertThat(stored.getDeliveryAttempts()).isEqualTo(2);
    }

    @Test
    void 대사_알림_이벤트는_트랜잭션_커밋_이후에만_전달된다() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        ReconciliationAlertEvent event = alertEvent();

        transaction.executeWithoutResult(status -> {
            eventPublisher.publishEvent(event);
            assertThat(recordingAlertListener.events()).isEmpty();
        });

        assertThat(recordingAlertListener.events()).containsExactly(event);
    }

    @Test
    void 대사_트랜잭션이_롤백되면_알림_이벤트도_전달되지_않는다() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        transaction.executeWithoutResult(status -> {
            eventPublisher.publishEvent(alertEvent());
            status.setRollbackOnly();
        });

        assertThat(recordingAlertListener.events()).isEmpty();
    }

    private List<PaymentLog> findCandidates() {
        return paymentLogRepository.findReconciliationCandidatesForUpdate(
                Set.of(PaymentStatus.COMPLETED),
                Set.of(ReconciliationStatus.PENDING, ReconciliationStatus.RETRY_REQUIRED),
                LocalDateTime.now(), PageRequest.of(0, 10));
    }

    private List<ReconciliationAlert> findAlertRetryCandidates() {
        return alertRepository.findRetryCandidatesForUpdate(
                ReconciliationAlertStatus.FAILED,
                LocalDateTime.now(),
                PageRequest.of(0, 10)
        );
    }

    private PaymentLog createPayment() {
        User user = userRepository.save(User.builder()
                .providerId("reconciliation-lock-user")
                .email("reconciliation-lock@example.com")
                .nickname("reconciliation-lock").build());
        ChatSession session = chatSessionRepository.save(ChatSession.builder()
                .user(user).title("reconciliation").build());
        AiRequest request = aiRequestRepository.save(AiRequest.builder()
                .user(user).session(session).prompt("pay")
                .status(AiRequestStatus.COMPLETED)
                .estimatedTotalCost(new BigDecimal("10.000000")).build());
        Wallet wallet = walletRepository.save(Wallet.builder()
                .user(user).walletAddress("0x-lock-wallet")
                .privateKeyEncrypted("encrypted")
                .balance(new BigDecimal("100.000000")).build());
        return paymentLogRepository.save(PaymentLog.builder()
                .user(user).request(request).wallet(wallet).orderId("order-lock")
                .txHash("0x-lock-tx").payerWalletAddress("0x-lock-wallet")
                .receiverWalletAddress("0x-receiver").amount(new BigDecimal("10.000000"))
                .paymentStatus(PaymentStatus.COMPLETED).verificationStatus(VerificationStatus.SUCCESS)
                .blockchainNetwork("POLYGON_AMOY").build());
    }

    private ReconciliationAlertEvent alertEvent() {
        return alertEvent(1L);
    }

    private ReconciliationAlertEvent alertEvent(Long paymentId) {
        return new ReconciliationAlertEvent(paymentId, "0x-event-" + paymentId, new BigDecimal("10.000000"),
                ReconciliationStatus.MISMATCHED, 1, "amount mismatch");
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("동시성 테스트 대기 시간이 초과되었습니다.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("동시성 테스트가 중단되었습니다.", e);
        }
    }

    @TestConfiguration
    static class EventTestConfig {
        @Bean
        RecordingAlertListener recordingAlertListener() {
            return new RecordingAlertListener();
        }
    }

    static class RecordingAlertListener {
        private final List<ReconciliationAlertEvent> events = new CopyOnWriteArrayList<>();

        @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
        public void record(ReconciliationAlertEvent event) {
            events.add(event);
        }

        List<ReconciliationAlertEvent> events() {
            return List.copyOf(events);
        }

        void clear() {
            events.clear();
        }
    }
}
