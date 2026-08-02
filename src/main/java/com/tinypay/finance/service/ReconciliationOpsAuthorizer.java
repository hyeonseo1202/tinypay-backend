package com.tinypay.finance.service;

import com.tinypay.global.exception.CustomException;
import com.tinypay.global.exception.ErrorType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Component
public class ReconciliationOpsAuthorizer {

    private final String expectedOpsKey;

    public ReconciliationOpsAuthorizer(@Value("${ops.api-key:}") String expectedOpsKey) {
        this.expectedOpsKey = expectedOpsKey;
    }

    public void authorize(String providedOpsKey) {
        if (!StringUtils.hasText(expectedOpsKey)
                || !StringUtils.hasText(providedOpsKey)
                || !MessageDigest.isEqual(
                        expectedOpsKey.getBytes(StandardCharsets.UTF_8),
                        providedOpsKey.getBytes(StandardCharsets.UTF_8)
                )) {
            throw new CustomException(ErrorType.OPS_ACCESS_FORBIDDEN);
        }
    }
}
