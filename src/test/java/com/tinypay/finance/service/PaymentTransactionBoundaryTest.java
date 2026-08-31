package com.tinypay.finance.service;

import com.tinypay.abuse.service.AbuseService;
import com.tinypay.blockchain.service.BlockchainService;
import com.tinypay.chat.service.DifyServiceExecutionService;
import com.tinypay.dify.domain.AiRequest;
import com.tinypay.dify.domain.AiRequestStatus;
import com.tinypay.dify.repository.AiRequestRepository;
import com.tinypay.finance.domain.*;
import com.tinypay.finance.dto.request.PaymentApproveRequest;
import com.tinypay.finance.repository.BudgetPolicyRepository;
import com.tinypay.finance.repository.PaymentLogRepository;
import com.tinypay.finance.repository.WalletRepository;
import com.tinypay.global.exception.CustomException;
import com.tinypay.user.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentTransactionBoundaryTest {

    @Mock AbuseService abuseService;
    @Mock AiRequestRepository aiRequestRepository;
    @Mock WalletRepository walletRepository;
    @Mock BudgetPolicyRepository budgetPolicyRepository;
    @Mock PaymentLogRepository paymentLogRepository;
    @Mock PaymentIdempotencyService paymentIdempotencyService;
    @Mock BlockchainService blockchainService;
    @Mock DifyServiceExecutionService difyServiceExecutionService;
    @Mock StringRedisTemplate stringRedisTemplate;
    @Mock BCryptPasswordEncoder passwordEncoder;

    private final TrackingTransactionManager transactionManager = new TrackingTransactionManager();
    private final AtomicReference<PaymentLog> savedPayment = new AtomicReference<>();
    private PaymentApproveService service;
    private Wallet wallet;
    private AiRequest aiRequest;
    private PaymentApproveRequest request;

    @BeforeEach
    void setUp() {
        service = new PaymentApproveService(
                abuseService,
                aiRequestRepository,
                walletRepository,
                budgetPolicyRepository,
                paymentLogRepository,
                paymentIdempotencyService,
                blockchainService,
                difyServiceExecutionService,
                stringRedisTemplate,
                passwordEncoder,
                transactionManager
        );
        ReflectionTestUtils.setField(service, "receiverWalletAddress", "0x-receiver");

        User user = User.builder()
                .providerId("provider")
                .email("payment-boundary@example.com")
                .nickname("payment-boundary")
                .build();
        ReflectionTestUtils.setField(user, "id", 1L);

        aiRequest = mock(AiRequest.class);
        when(aiRequest.getId()).thenReturn(10L);
        when(aiRequest.getUser()).thenReturn(user);
        when(aiRequest.getStatus()).thenReturn(AiRequestStatus.WAITING_APPROVAL);
        when(aiRequest.getEstimatedTotalCost()).thenReturn(new BigDecimal("10.000000"));

        wallet = Wallet.builder()
                .user(user)
                .walletAddress("0x-wallet")
                .privateKeyEncrypted("encrypted")
                .balance(new BigDecimal("100.000000"))
                .walletStatus(WalletStatus.ACTIVE)
                .build();
        ReflectionTestUtils.setField(wallet, "id", 20L);

        BudgetPolicy policy = BudgetPolicy.builder()
                .user(user)
                .autoPaymentEnabled(true)
                .build();
        PaymentIdempotency idempotency = PaymentIdempotency.processing(
                1L, 10L, "idem-key", new BigDecimal("10.000000")
        );
        ReflectionTestUtils.setField(idempotency, "id", 30L);

        request = new PaymentApproveRequest();
        ReflectionTestUtils.setField(request, "idempotencyKey", "idem-key");
        ReflectionTestUtils.setField(request, "estimatedCost", new BigDecimal("10.000000"));

        when(aiRequestRepository.findByIdWithLock(10L)).thenAnswer(invocation -> {
            assertThat(transactionManager.isActive()).isTrue();
            return Optional.of(aiRequest);
        });
        when(walletRepository.findByUserIdWithLock(1L)).thenReturn(Optional.of(wallet));
        when(walletRepository.findByIdWithLock(20L)).thenReturn(Optional.of(wallet));
        when(budgetPolicyRepository.findByUser_IdAndDeletedAtIsNull(1L)).thenReturn(Optional.of(policy));
        when(paymentLogRepository.findFirstByRequestAndPaymentStatusIn(any(), any()))
                .thenReturn(Optional.empty());
        when(paymentIdempotencyService.createClaim(any(), any(), any(), any()))
                .thenReturn(idempotency);
        when(paymentLogRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            PaymentLog payment = invocation.getArgument(0);
            ReflectionTestUtils.setField(payment, "id", 40L);
            savedPayment.set(payment);
            return payment;
        });
        when(paymentLogRepository.findByIdWithLock(40L))
                .thenAnswer(invocation -> Optional.ofNullable(savedPayment.get()));
    }

    @Test
    void 블록체인_호출은_DB_트랜잭션_밖에서_실행된다() {
        when(blockchainService.transferUsdc(any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    assertThat(transactionManager.isActive()).isFalse();
                    assertThat(wallet.getBalance()).isEqualByComparingTo("90.000000");
                    return "0x-tx";
                });
        when(blockchainService.verifyReceipt(any(), any(), any()))
                .thenAnswer(invocation -> {
                    assertThat(transactionManager.isActive()).isFalse();
                    return true;
                });

        service.paymentApprove(1L, 10L, request);

        assertThat(savedPayment.get().getPaymentStatus()).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(wallet.getBalance()).isEqualByComparingTo("90.000000");
        verify(paymentIdempotencyService).complete(30L, savedPayment.get());
        verify(difyServiceExecutionService).executeService(10L);
    }

    @Test
    void 체인_제출_전_실패하면_선점한_잔액을_복원한다() {
        when(blockchainService.transferUsdc(any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("전송 실패"));

        assertThatThrownBy(() -> service.paymentApprove(1L, 10L, request))
                .isInstanceOf(CustomException.class);

        assertThat(wallet.getBalance()).isEqualByComparingTo("100.000000");
        assertThat(savedPayment.get().getPaymentStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(savedPayment.get().getTxHash()).isNull();
        verify(paymentIdempotencyService).fail(30L);
    }

    @Test
    void txHash_생성_후_검증_실패하면_잔액을_복원하지_않고_대사_근거를_남긴다() {
        when(blockchainService.transferUsdc(any(), any(), any(), any(), any())).thenReturn("0x-tx");
        when(blockchainService.verifyReceipt(any(), any(), any())).thenReturn(false);

        assertThatThrownBy(() -> service.paymentApprove(1L, 10L, request))
                .isInstanceOf(CustomException.class);

        assertThat(wallet.getBalance()).isEqualByComparingTo("90.000000");
        assertThat(savedPayment.get().getPaymentStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(savedPayment.get().getFailedFromStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(savedPayment.get().getTxHash()).isEqualTo("0x-tx");
        assertThat(savedPayment.get().getReconciliationStatus()).isEqualTo(ReconciliationStatus.PENDING);
        verify(paymentIdempotencyService).fail(30L);
    }

    private static final class TrackingTransactionManager extends AbstractPlatformTransactionManager {
        private final ThreadLocal<Boolean> active = ThreadLocal.withInitial(() -> false);

        boolean isActive() {
            return active.get();
        }

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            active.set(true);
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            active.set(false);
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            active.set(false);
        }

        @Override
        protected void doCleanupAfterCompletion(Object transaction) {
            active.remove();
        }
    }
}
