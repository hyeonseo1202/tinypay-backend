package com.tinypay.abuse.service;

import com.tinypay.abuse.domain.AbuseActionType;
import com.tinypay.abuse.domain.AbuseLog;
import com.tinypay.abuse.domain.AbuseLogRepository;
import com.tinypay.abuse.domain.AbuseType;
import com.tinypay.user.domain.User;
import com.tinypay.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 어뷰징 기록 서비스 구현체
 *
 * userId로 User를 조회해 AbuseLog에 연결한다.
 * 조회 실패 시에도 기록은 남긴다 (AbuseLog.user는 nullable).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AbuseServiceImpl implements AbuseService {

    private final UserRepository userRepository;
    private final AbuseLogRepository abuseLogRepository;

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(Long userId, AbuseType type, AbuseActionType action, String detail) {
        User user = (userId != null)
                ? userRepository.findById(userId).orElse(null)
                : null;

        AbuseLog abuseLog = AbuseLog.builder()
                .user(user)
                .abuseType(type.name())
                .actionTaken(action)
                .detail(detail)
                .build();

        abuseLogRepository.save(abuseLog);

        log.warn("[Abuse] type={}, action={}, userId={}, detail={}",
                type, action, userId, detail);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordRateLimitViolation(Long userId, String detail) {
        record(userId, AbuseType.RATE_LIMIT_EXCEEDED, AbuseActionType.BLOCKED, detail);
    }
}
