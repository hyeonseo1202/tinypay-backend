package com.tinypay.finance.service;

import com.tinypay.abuse.service.AbuseService;
import com.tinypay.blockchain.service.BlockchainService;
import com.tinypay.finance.domain.ChargeHistory;
import com.tinypay.finance.domain.ChargeStatus;
import com.tinypay.finance.domain.Wallet;
import com.tinypay.finance.domain.WalletStatus;
import com.tinypay.finance.dto.request.TopUpRequest;
import com.tinypay.finance.dto.response.TopUpResponse;
import com.tinypay.finance.repository.ChargeHistoryRepository;
import com.tinypay.finance.repository.WalletRepository;
import com.tinypay.blockchain.dto.PaymentAuthContext;
import com.tinypay.global.exception.CustomException;
import com.tinypay.global.exception.ErrorType;
import com.tinypay.user.domain.User;
import com.tinypay.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;

@Slf4j
@Service
@RequiredArgsConstructor
public class WalletTopUpService {

    private final AbuseService abuseService;
    private final WalletRepository walletRepository;
    private final UserRepository userRepository;
    private final ChargeHistoryRepository chargeHistoryRepository;
    private final ChargeHistoryService chargeHistoryService;
    private final BlockchainService blockchainService;
    private final StringRedisTemplate stringRedisTemplate;
    private final BCryptPasswordEncoder passwordEncoder;

    private static final int USDC_DECIMALS = 6;
    private static final int MAX_PASSWORD_FAILURES = 5;
    private static final String PASSWORD_FAIL_KEY_PREFIX = "wallet:password:fail:";

    @Transactional
    public TopUpResponse topUp(Long userId, Long walletId, TopUpRequest request) {
        // 1. 지갑 조회
        Wallet wallet = walletRepository.findByIdWithLock(walletId)
                .orElseThrow(() -> new CustomException(ErrorType.WALLET_NOT_FOUND));

        // 2. 소유자 확인
        if (!wallet.getUser().getId().equals(userId)) {
            throw new CustomException(ErrorType.WALLET_FORBIDDEN);
        }

        // 3. 지갑 상태 확인
        if (wallet.getWalletStatus() == WalletStatus.LOCKED) {
            throw new CustomException(ErrorType.WALLET_LOCKED);
        }

        // 4. 비밀번호 검증
        verifyWalletPassword(userId, request.getWalletPassword(), wallet);

        // 5. 유저 조회 (전화번호 인증 여부)
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException(ErrorType.USER_NOT_FOUND));

        // 6. 블록체인 충전 실행
        BigDecimal amount = request.getAmount();
        BigInteger rawAmount = amount.movePointRight(USDC_DECIMALS).toBigInteger();

        PaymentAuthContext authCtx = PaymentAuthContext.builder()
                .userId(userId)
                .isPasswordVerified(true)
                .isPhoneVerified(user.isPhoneVerified())
                .walletStatus(wallet.getWalletStatus().name())
                .build();

        String txHash;
        try {
            txHash = blockchainService.mintUsdc(wallet.getWalletAddress(), rawAmount, authCtx);
        } catch (Exception e) {
            log.error("[WalletTopUpService] mintUsdc 실패: walletAddress={}, amount={}, error={}",
                    wallet.getWalletAddress(), amount, e.getMessage(), e);
            chargeHistoryService.saveFailedChargeHistory(user, wallet, amount);
            throw new CustomException(ErrorType.INTERNAL_SERVER_ERROR);
        }

        // 7. 잔액 업데이트
        wallet.deposit(amount);
        BigDecimal newBalance = wallet.getBalance();

        // 8. 충전 이력 저장
        ChargeHistory chargeHistory = ChargeHistory.builder()
                .user(user)
                .wallet(wallet)
                .amount(amount)
                .balanceSnapshot(newBalance)
                .txHash(txHash)
                .chargeStatus(ChargeStatus.SUCCESS)
                .build();
        chargeHistoryRepository.save(chargeHistory);

        return TopUpResponse.builder()
                .walletId(wallet.getId())
                .amount(amount)
                .balance(newBalance)
                .build();
    }

    private void verifyWalletPassword(Long userId, String inputPassword, Wallet wallet) {
        String failKey = PASSWORD_FAIL_KEY_PREFIX + userId;

        if (!passwordEncoder.matches(inputPassword, wallet.getWalletPassword())) {
            Long failCount = stringRedisTemplate.opsForValue().increment(failKey);
            stringRedisTemplate.expire(failKey, Duration.ofHours(24));

            if (failCount != null && failCount >= MAX_PASSWORD_FAILURES) {
                abuseService.recordRateLimitViolation(userId, "충전 비밀번호 " + MAX_PASSWORD_FAILURES + "회 실패: userId=" + userId);
                blockchainService.lockWalletForBruteForce(userId);
                stringRedisTemplate.delete(failKey);
                throw new CustomException(ErrorType.WALLET_LOCKED_BY_PASSWORD_FAILURE);
            }

            throw new CustomException(ErrorType.WRONG_WALLET_PASSWORD);
        }

        stringRedisTemplate.delete(failKey);
    }
}
