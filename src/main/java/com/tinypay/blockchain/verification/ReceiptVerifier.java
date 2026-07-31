package com.tinypay.blockchain.verification;

import java.math.BigInteger;

/**
 * 영수증 5단계 교차 검증 인터페이스
 *
 * 검증 단계:
 *   1. Replay Attack 방지 (Redis에서 tx_hash 사용 여부 확인)
 *   2. 트랜잭션 성공 여부 (receipt.status == 1)
 *   3. 공식 MockUSDC 컨트랙트 주소 확인
 *   4. 수신자 주소 확인 (예상 판매자 지갑과 일치)
 *   5. 결제 금액 확인 (예상 금액 이상)
 *
 * fail-fast 원칙: 어느 한 단계라도 실패하면 즉시 중단하고 결과 반환
 */
public interface ReceiptVerifier {

    /**
     * 트랜잭션 영수증을 5단계에 걸쳐 검증한다.
     *
     * @param txHash           검증할 트랜잭션 해시 (0x...)
     * @param expectedReceiver 예상 수신자 지갑 주소 (판매자 지갑)
     * @param expectedAmount   예상 결제 금액 (USDC 6자리 소수점 적용값, 1 USDC = 1_000_000)
     * @return 검증 결과 객체 (성공/실패 여부, 실패 사유, 실패 단계 등 포함)
     */
    VerificationResult verify(String txHash, String expectedReceiver, BigInteger expectedAmount);

    /**
     * 이미 처리된 결제를 대사하기 위해 온체인 정보만 다시 검증한다.
     * 최초 결제 시 수행하는 replay 검사와 사용 처리 등록은 생략한다.
     */
    VerificationResult verifyForReconciliation(
            String txHash,
            String expectedReceiver,
            BigInteger expectedAmount
    );
}
