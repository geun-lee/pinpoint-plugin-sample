package com.navercorp.pinpoint.plugin.jeus.interceptor;

import com.navercorp.pinpoint.bootstrap.interceptor.AroundInterceptor;
import com.navercorp.pinpoint.bootstrap.logging.PLogger;
import com.navercorp.pinpoint.bootstrap.logging.PLoggerFactory;
import com.navercorp.pinpoint.bootstrap.plugin.monitor.DataSourceMonitorRegistry;
import com.navercorp.pinpoint.plugin.jeus.datasource.JeusDataSourceMonitor;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class ConnectionPoolGetConnectionInterceptor implements AroundInterceptor {

    private final PLogger logger = PLoggerFactory.getLogger(this.getClass());

    // 모니터 객체를 여기에 저장해두어야 GC가 되지 않고 계속 살아있습니다.
    private static final ConcurrentHashMap<Object, JeusDataSourceMonitor> registeredMonitors = new ConcurrentHashMap<Object, JeusDataSourceMonitor>();

    // 등록 실패 횟수 추적 (재시도 제한용)
    private static final ConcurrentHashMap<Object, AtomicInteger> failedAttempts = new ConcurrentHashMap<Object, AtomicInteger>();
    private static final int MAX_RETRY_ATTEMPTS = 3;

    // 등록 진행 중 표시 (중복 등록 방지)
    private static final Object REGISTERING_MARKER = new Object();
    private static final ConcurrentHashMap<Object, Object> registeringTargets = new ConcurrentHashMap<Object, Object>();

    private final DataSourceMonitorRegistry dataSourceMonitorRegistry;

    public ConnectionPoolGetConnectionInterceptor(DataSourceMonitorRegistry dataSourceMonitorRegistry) {
        this.dataSourceMonitorRegistry = dataSourceMonitorRegistry;
    }

    @Override
    public void before(Object target, Object[] args) {
        // 이미 등록된 모니터가 있으면 통과
        if (registeredMonitors.containsKey(target)) {
            return;
        }

        if (dataSourceMonitorRegistry == null) {
            return;
        }

        // 최대 재시도 횟수 초과 시 스킵
        AtomicInteger attempts = failedAttempts.get(target);
        if (attempts != null && attempts.get() >= MAX_RETRY_ATTEMPTS) {
            return;
        }

        // 다른 스레드에서 이미 등록 중이면 스킵 (lock-free)
        if (registeringTargets.putIfAbsent(target, REGISTERING_MARKER) != null) {
            return;
        }

        try {
            // Double-check after acquiring marker
            if (registeredMonitors.containsKey(target)) {
                return;
            }

            logger.info("[JEUS-DATASOURCE] Found NEW ConnectionPool instance: " + target.getClass().getName());

            JeusDataSourceMonitor monitor = new JeusDataSourceMonitor(target);

            // 초기화 실패 시 재시도 대상으로 처리
            if (!monitor.isInitialized()) {
                int attemptCount = incrementFailedAttempts(target);
                logger.warn("[JEUS-DATASOURCE] Monitor initialization failed. Retry attempt: " + attemptCount + "/" + MAX_RETRY_ATTEMPTS);
                return;
            }

            boolean registered = dataSourceMonitorRegistry.register(monitor);

            if (registered) {
                logger.info("[JEUS-DATASOURCE] SUCCESS: Monitor registered. URL: " + monitor.getUrl());
            } else {
                // 이미 레지스트리에 등록된 경우 - 성공으로 처리
                logger.info("[JEUS-DATASOURCE] SKIP: Already registered in registry. URL: " + monitor.getUrl());
            }

            // 성공 시 모니터 저장 및 실패 카운터 제거
            registeredMonitors.put(target, monitor);
            failedAttempts.remove(target);

        } catch (Exception e) {
            int attemptCount = incrementFailedAttempts(target);
            logger.warn("[JEUS-DATASOURCE] ERROR: Registration failed. Retry attempt: " + attemptCount + "/" + MAX_RETRY_ATTEMPTS, e);
        } finally {
            // 등록 마커 제거
            registeringTargets.remove(target);
        }
    }

    private int incrementFailedAttempts(Object target) {
        AtomicInteger counter = failedAttempts.get(target);
        if (counter == null) {
            counter = new AtomicInteger(0);
            AtomicInteger existing = failedAttempts.putIfAbsent(target, counter);
            if (existing != null) {
                counter = existing;
            }
        }
        return counter.incrementAndGet();
    }

    @Override
    public void after(Object target, Object[] args, Object result, Throwable throwable) {
        // No-op
    }
}
