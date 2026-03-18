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

    // ConcurrentHashMap: lock-free read로 DB 연결 hot path 경합 제거
    // ConnectionPool 인스턴스는 JEUS 서버 클래스로더에 속하며 서버 생애 동안 유지되므로
    // strong key가 Metaspace 누수를 유발하지 않음
    private static final ConcurrentHashMap<Object, JeusDataSourceMonitor> registeredMonitors =
            new ConcurrentHashMap<Object, JeusDataSourceMonitor>();

    // 등록 실패 횟수 추적 (재시도 제한용)
    private static final ConcurrentHashMap<Object, AtomicInteger> failedAttempts =
            new ConcurrentHashMap<Object, AtomicInteger>();
    private static final int MAX_RETRY_ATTEMPTS = 3;

    // 등록 진행 중 표시: putIfAbsent로 원자적 처리 (synchronized 블록 불필요)
    private static final ConcurrentHashMap<Object, Boolean> registeringTargets =
            new ConcurrentHashMap<Object, Boolean>();

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

        // 다른 스레드에서 이미 등록 중이면 스킵 (putIfAbsent로 원자적 처리)
        if (registeringTargets.putIfAbsent(target, Boolean.TRUE) != null) {
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
        // 안전 장치: failedAttempts 맵이 비정상적으로 커지면 정리
        if (failedAttempts.size() > 100) {
            logger.warn("[JEUS-DATASOURCE] failedAttempts map exceeded 100 entries, clearing stale entries");
            failedAttempts.clear();
        }
        AtomicInteger counter = failedAttempts.get(target);
        if (counter == null) {
            AtomicInteger newCounter = new AtomicInteger(0);
            AtomicInteger existing = failedAttempts.putIfAbsent(target, newCounter);
            counter = existing != null ? existing : newCounter;
        }
        return counter.incrementAndGet();
    }

    @Override
    public void after(Object target, Object[] args, Object result, Throwable throwable) {
        // No-op
    }
}
