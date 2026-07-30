package com.tinypay.finance.repository;

import com.tinypay.finance.domain.Wallet;
import com.tinypay.finance.domain.WalletStatus;
import com.tinypay.global.config.JpaAuditingConfig;
import com.tinypay.user.domain.User;
import com.tinypay.user.repository.UserRepository;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:tc:mysql:8.0.36:///tinypay",
        "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=false"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaAuditingConfig.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class WalletConcurrencyIntegrationTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private WalletRepository walletRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void 동시에_잔액보다_큰_합계가_차감되어도_한_건만_성공한다() throws Exception {
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        Long userId = transactionTemplate.execute(status -> {
            User user = userRepository.save(User.builder()
                    .providerId("wallet-lock-user")
                    .email("wallet-lock@example.com")
                    .nickname("wallet-lock")
                    .build());
            walletRepository.save(Wallet.builder()
                    .user(user)
                    .walletAddress("0x-wallet-lock")
                    .privateKeyEncrypted("encrypted-key")
                    .balance(new BigDecimal("100.000000"))
                    .walletStatus(WalletStatus.ACTIVE)
                    .build());
            return user.getId();
        });

        BigDecimal paymentAmount = new BigDecimal("80.000000");
        AtomicInteger successfulPayments = new AtomicInteger();
        CountDownLatch firstLockAcquired = new CountDownLatch(1);
        CountDownLatch releaseFirstTransaction = new CountDownLatch(1);
        CountDownLatch secondRequestStarted = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<?> first = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
                Wallet wallet = walletRepository.findByUserIdWithLock(userId).orElseThrow();
                firstLockAcquired.countDown();
                await(releaseFirstTransaction);
                if (wallet.canWithdraw(paymentAmount)) {
                    wallet.withdraw(paymentAmount);
                    successfulPayments.incrementAndGet();
                }
            }));

            assertThat(firstLockAcquired.await(5, TimeUnit.SECONDS)).isTrue();

            Future<?> second = executor.submit(() -> {
                secondRequestStarted.countDown();
                transactionTemplate.executeWithoutResult(status -> {
                    Wallet wallet = walletRepository.findByUserIdWithLock(userId).orElseThrow();
                    if (wallet.canWithdraw(paymentAmount)) {
                        wallet.withdraw(paymentAmount);
                        successfulPayments.incrementAndGet();
                    }
                });
            });

            assertThat(secondRequestStarted.await(5, TimeUnit.SECONDS)).isTrue();
            releaseFirstTransaction.countDown();

            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        } finally {
            releaseFirstTransaction.countDown();
            executor.shutdownNow();
        }

        BigDecimal finalBalance = transactionTemplate.execute(status ->
                walletRepository.findByUser_Id(userId).orElseThrow().getBalance()
        );

        assertThat(successfulPayments).hasValue(1);
        assertThat(finalBalance).isEqualByComparingTo("20.000000");
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
}
