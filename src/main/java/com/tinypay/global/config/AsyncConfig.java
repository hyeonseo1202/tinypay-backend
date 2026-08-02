package com.tinypay.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

// Dify AI 분석 작업을 백그라운드에서 동시에 여러 개 처리할 수 있도록 스레드 풀을 설정하는 코드
@Configuration
public class AsyncConfig {

    @Bean(name = "difyTaskExecutor")
    public Executor difyTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(5);
        executor.setMaxPoolSize(10);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("dify-async-");
        executor.initialize();
        return executor;
    }

    @Bean(name = "reconciliationAlertExecutor")
    public Executor reconciliationAlertExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("reconciliation-alert-");
        executor.initialize();
        return executor;
    }
}
