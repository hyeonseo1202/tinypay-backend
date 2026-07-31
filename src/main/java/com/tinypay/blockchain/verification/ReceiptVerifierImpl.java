package com.tinypay.blockchain.verification;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.methods.response.EthGetTransactionReceipt;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.springframework.beans.factory.annotation.Value;
import com.tinypay.blockchain.contracts.MockUSDC;
import org.web3j.crypto.Credentials;
import org.web3j.tx.gas.ContractGasProvider;

import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.Optional;
import java.math.BigInteger;
import java.time.Duration;

/**
 * 영수증 5단계 교차 검증 구현체
 *
 * 검증 순서 (fail-fast):
 *   1. Replay Attack 방지 (Redis)
 *   2. 트랜잭션 성공 여부 (온체인)
 *   3. 공식 MockUSDC 컨트랙트 주소 확인 (온체인)
 *   4. 수신자 주소 확인 (온체인 Transfer 이벤트)
 *   5. 결제 금액 확인 (온체인 Transfer 이벤트)
 *
 * 5단계 모두 통과 시에만 영수증을 "사용됨"으로 Redis에 등록한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReceiptVerifierImpl implements ReceiptVerifier {

    /** Redis 키 prefix (백엔드 팀 Rate Limiting과 충돌 방지용) */
    private static final String REDIS_KEY_PREFIX = "tx:used:";

    /** 영수증 사용 기록 TTL (명세서 9번: 1시간) */
    private static final Duration RECEIPT_TTL = Duration.ofHours(1);

    private final StringRedisTemplate redisTemplate;
    private final Web3j web3j; 

    @Value("${blockchain.mock-usdc-address}")
    private String mockUsdcAddress;  


    /** Transfer 이벤트 파싱용 컨트랙트 wrapper (BlockchainServiceImpl과 별도 인스턴스) */
    private final Credentials credentials;
    private final ContractGasProvider gasProvider;
    private MockUSDC mockUSDC;

    @PostConstruct
    private void init() {
        this.mockUSDC = MockUSDC.load(mockUsdcAddress, web3j, credentials, gasProvider);
        log.info("[ReceiptVerifier] MockUSDC 컨트랙트 로딩 완료: {}", mockUsdcAddress);
    }

    @Override
    public VerificationResult verify(String txHash, String expectedReceiver, BigInteger expectedAmount) {
        log.info("[영수증 검증 시작] txHash={}", txHash);

        // 1단계: Replay Attack 방지
        VerificationResult step1 = checkReuse(txHash);
        if (!step1.isValid()) {
            log.warn("[영수증 검증 실패] 1단계: {}", step1.getDetail());
            return step1;
        }

        VerificationResult onChainResult =
                verifyOnChain(txHash, expectedReceiver, expectedAmount);
        if (!onChainResult.isValid()) {
            return onChainResult;
        }

        // 모든 단계 통과 시 영수증을 사용됨으로 등록
        markAsUsed(txHash);

        log.info("[영수증 검증 성공] txHash={}", txHash);
        return VerificationResult.success();
    }

    @Override
    public VerificationResult verifyForReconciliation(
            String txHash,
            String expectedReceiver,
            BigInteger expectedAmount
    ) {
        log.info("[대사 영수증 검증 시작] txHash={}", txHash);
        return verifyOnChain(txHash, expectedReceiver, expectedAmount);
    }

    private VerificationResult verifyOnChain(
            String txHash,
            String expectedReceiver,
            BigInteger expectedAmount
    ) {
        // 2단계: 트랜잭션 성공 여부 확인
        VerificationResult step2 = checkTxStatus(txHash);
        if (!step2.isValid()) {
            log.warn("[영수증 검증 실패] 2단계: {}", step2.getDetail());
            return step2;
        }
        // 3단계: 공식 MockUSDC 컨트랙트 주소 확인
        // 2단계에서 이미 receipt를 조회했지만, fail-fast 패턴 유지를 위해
        // 각 단계는 독립적으로 동작하도록 설계함 (RPC 1회 추가 호출)
        VerificationResult step3 = checkContract(txHash);
        if (!step3.isValid()) {
            log.warn("[영수증 검증 실패] 3단계: {}", step3.getDetail());
            return step3;
        }
        // 4단계: 수신자 주소 확인 (Transfer 이벤트의 to 필드 비교)
        VerificationResult step4 = checkRecipient(txHash, expectedReceiver);
        if (!step4.isValid()) {
            log.warn("[영수증 검증 실패] 4단계: {}", step4.getDetail());
            return step4;
        }
        // 5단계: 결제 금액 확인 (Transfer 이벤트의 value 비교)
        VerificationResult step5 = checkAmount(txHash, expectedReceiver, expectedAmount);
        if (!step5.isValid()) {
            log.warn("[영수증 검증 실패] 5단계: {}", step5.getDetail());
            return step5;
        }

        return VerificationResult.success();
    }

    /**
     * 1단계: Replay Attack 방지
     *
     * Redis에 해당 txHash가 이미 등록되어 있는지 확인한다.
     * 등록되어 있다면 이미 사용된 영수증이므로 거부한다.
     *
     * @param txHash 검증할 트랜잭션 해시
     * @return 미사용 영수증이면 success, 이미 사용되었다면 REPLAY_ATTACK 실패
     */
    private VerificationResult checkReuse(String txHash) {
        String key = REDIS_KEY_PREFIX + txHash;
        Boolean exists = redisTemplate.hasKey(key);

        if (Boolean.TRUE.equals(exists)) {
            return VerificationResult.fail(
                    FailReason.REPLAY_ATTACK,
                    "이미 사용된 영수증입니다: " + txHash
            );
        }

        return VerificationResult.success();
    }

    /**
     * 2단계: 트랜잭션 성공 여부 확인
     *
     * 온체인에서 트랜잭션 영수증을 조회하여 status가 1(성공)인지 확인한다.
     * 트랜잭션이 존재하지 않거나 pending 상태인 경우도 실패로 처리한다.
     *
     * @param txHash 검증할 트랜잭션 해시
     * @return 성공 트랜잭션이면 success, 그 외 모두 TRANSACTION_FAILED 실패
     */

    private VerificationResult checkTxStatus(String txHash) {
        try {
            EthGetTransactionReceipt response = web3j
                    .ethGetTransactionReceipt(txHash)
                    .send();

            Optional<TransactionReceipt> receiptOpt = response.getTransactionReceipt();

            // 케이스 (C): 트랜잭션이 존재하지 않거나 pending
            if (receiptOpt.isEmpty()) {
                return VerificationResult.fail(
                        FailReason.TRANSACTION_FAILED,
                        "트랜잭션을 찾을 수 없거나 아직 처리되지 않음: " + txHash
                );
            }

            TransactionReceipt receipt = receiptOpt.get();

            // 케이스 (B): 트랜잭션이 revert되어 실패
            if (!receipt.isStatusOK()) {
                return VerificationResult.fail(
                        FailReason.TRANSACTION_FAILED,
                        "트랜잭션이 실패함 (status=" + receipt.getStatus() + "): " + txHash
                );
            }

            // 케이스 (A): 정상 성공
            return VerificationResult.success();

        } catch (Exception e) {
            // 케이스 (D): RPC 통신 에러
            log.error("[2단계 검증 RPC 에러] txHash={}, error={}", txHash, e.getMessage());
            throw new RuntimeException("블록체인 노드 통신 실패: " + e.getMessage(), e);
        }
    }

    /**
     * 3단계: 공식 MockUSDC 컨트랙트의 Transfer 이벤트 확인
     *
     * 트랜잭션이 MockUSDC 컨트랙트의 Transfer 이벤트를 발생시켰는지 확인한다.
     * 직접 호출(mint)이든 TinyPayment 경유 호출(executePayment)이든
     * 결국 MockUSDC에서 Transfer 이벤트가 발생해야 정상적인 USDC 이동이다.
     *
     * 기존 로직(receipt.getTo() 비교)은 mint 같은 직접 호출만 통과시키고,
     * TinyPayment 경유 결제는 잘못 거부하는 문제가 있었음.
     *
     * @param txHash 검증할 트랜잭션 해시
     * @return MockUSDC Transfer 이벤트 존재 시 success, 없으면 INVALID_CONTRACT 실패
     */
    private VerificationResult checkContract(String txHash) {
        try {
            EthGetTransactionReceipt response = web3j
                    .ethGetTransactionReceipt(txHash)
                    .send();

            Optional<TransactionReceipt> receiptOpt = response.getTransactionReceipt();
            if (receiptOpt.isEmpty()) {
                return VerificationResult.fail(
                        FailReason.INVALID_CONTRACT,
                        "트랜잭션 영수증을 찾을 수 없음: " + txHash
                );
            }

            // Transfer 이벤트 파싱
            List<MockUSDC.TransferEventResponse> events = mockUSDC.getTransferEvents(receiptOpt.get());

            if (events.isEmpty()) {
                return VerificationResult.fail(
                        FailReason.INVALID_CONTRACT,
                        "Transfer 이벤트가 발견되지 않음: " + txHash
                );
            }

            // Transfer 이벤트의 emitter(log address)가 MockUSDC 컨트랙트인지 확인
            boolean isMockUsdc = events.stream()
                    .anyMatch(event -> event.log != null
                            && event.log.getAddress() != null
                            && event.log.getAddress().equalsIgnoreCase(mockUsdcAddress));

            if (!isMockUsdc) {
                return VerificationResult.fail(
                        FailReason.INVALID_CONTRACT,
                        String.format("MockUSDC 컨트랙트의 Transfer 이벤트 아님 (expected=%s, actual_emitters=%s)",
                                mockUsdcAddress,
                                events.stream().map(e -> e.log != null ? e.log.getAddress() : "null").toList())
                );
            }

            return VerificationResult.success();

        } catch (Exception e) {
            log.error("[3단계 검증 RPC 에러] txHash={}, error={}", txHash, e.getMessage());
            throw new RuntimeException("블록체인 노드 통신 실패: " + e.getMessage(), e);
        }
    }

    /**
     * 4단계: 수신자 주소 확인
     *
     * Transfer 이벤트 로그를 파싱하여, 수신자가 예상 판매자 지갑과 일치하는지 확인한다.
     * 한 트랜잭션에 Transfer 이벤트가 여러 개일 수 있으므로, 그중 to 주소가
     * expectedReceiver와 일치하는 이벤트가 하나라도 있으면 통과로 간주한다.
     *
     * @param txHash           검증할 트랜잭션 해시
     * @param expectedReceiver 예상 수신자 지갑 주소
     * @return 일치하는 이벤트 존재 시 success, 없으면 INVALID_RECIPIENT 실패
     */
    private VerificationResult checkRecipient(String txHash, String expectedReceiver) {
        try {
            EthGetTransactionReceipt response = web3j
                    .ethGetTransactionReceipt(txHash)
                    .send();

            Optional<TransactionReceipt> receiptOpt = response.getTransactionReceipt();
            if (receiptOpt.isEmpty()) {
                return VerificationResult.fail(
                        FailReason.INVALID_RECIPIENT,
                        "트랜잭션 영수증을 찾을 수 없음: " + txHash
                );
            }

            // Transfer 이벤트 파싱
            List<MockUSDC.TransferEventResponse> events = mockUSDC.getTransferEvents(receiptOpt.get());

            if (events.isEmpty()) {
                return VerificationResult.fail(
                        FailReason.INVALID_RECIPIENT,
                        "Transfer 이벤트가 발견되지 않음: " + txHash
                );
            }

            // 수신자가 일치하는 이벤트 찾기 (대소문자 무시)
            boolean found = events.stream()
                    .anyMatch(event -> event.to != null
                            && event.to.equalsIgnoreCase(expectedReceiver));

            if (!found) {
                return VerificationResult.fail(
                        FailReason.INVALID_RECIPIENT,
                        String.format("수신자 불일치 (expected=%s, events=%s)",
                                expectedReceiver,
                                events.stream().map(e -> e.to).toList())
                );
            }

            return VerificationResult.success();

        } catch (Exception e) {
            log.error("[4단계 검증 RPC 에러] txHash={}, error={}", txHash, e.getMessage());
            throw new RuntimeException("블록체인 노드 통신 실패: " + e.getMessage(), e);
        }
    }
    
    /**
     * 5단계: 결제 금액 확인
     *
     * Transfer 이벤트 중 수신자가 expectedReceiver인 이벤트의 value가
     * expectedAmount 이상인지 확인한다.
     *
     * "이상"으로 비교하는 이유: 사용자가 더 많이 보낸 경우는 우리 입장에서 손해 없음.
     * 부족 결제(value < expectedAmount)만 거부.
     *
     * @param txHash           검증할 트랜잭션 해시
     * @param expectedReceiver 예상 수신자 (이 주소로 간 이벤트만 검사)
     * @param expectedAmount   예상 결제 금액 (USDC 6자리 소수점 적용값)
     * @return 충분한 금액 송금 시 success, 부족하면 INSUFFICIENT_PAYMENT 실패
     */
    private VerificationResult checkAmount(String txHash, String expectedReceiver, BigInteger expectedAmount) {
        try {
            EthGetTransactionReceipt response = web3j
                    .ethGetTransactionReceipt(txHash)
                    .send();

            Optional<TransactionReceipt> receiptOpt = response.getTransactionReceipt();
            if (receiptOpt.isEmpty()) {
                return VerificationResult.fail(
                        FailReason.INSUFFICIENT_PAYMENT,
                        "트랜잭션 영수증을 찾을 수 없음: " + txHash
                );
            }

            List<MockUSDC.TransferEventResponse> events = mockUSDC.getTransferEvents(receiptOpt.get());

            // 수신자가 일치하는 이벤트들의 value 합산
            // (한 트랜잭션에서 같은 수신자에게 여러 번 보낼 가능성 대응)
            BigInteger totalReceived = events.stream()
                    .filter(event -> event.to != null
                            && event.to.equalsIgnoreCase(expectedReceiver))
                    .map(event -> event.value)
                    .reduce(BigInteger.ZERO, BigInteger::add);

            if (totalReceived.compareTo(expectedAmount) < 0) {
                return VerificationResult.fail(
                        FailReason.INSUFFICIENT_PAYMENT,
                        String.format("결제 금액 부족 (expected>=%s, actual=%s)",
                                expectedAmount, totalReceived)
                );
            }

            return VerificationResult.success();

        } catch (Exception e) {
            log.error("[5단계 검증 RPC 에러] txHash={}, error={}", txHash, e.getMessage());
            throw new RuntimeException("블록체인 노드 통신 실패: " + e.getMessage(), e);
        }
    }

    /**
     * 영수증을 "사용됨"으로 Redis에 등록 (TTL 1시간)
     *
     * 5단계 검증 모두 통과한 영수증만 등록되어야 한다.
     * 중간 단계에서 실패한 영수증은 등록하지 않아 재검증 가능.
     *
     * @param txHash 등록할 트랜잭션 해시
     */
    private void markAsUsed(String txHash) {
        String key = REDIS_KEY_PREFIX + txHash;
        redisTemplate.opsForValue().set(key, "used", RECEIPT_TTL);
        log.debug("[영수증 등록] key={}, TTL={}h", key, RECEIPT_TTL.toHours());
    }
}
