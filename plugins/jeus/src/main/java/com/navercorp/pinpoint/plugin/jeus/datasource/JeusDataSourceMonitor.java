package com.navercorp.pinpoint.plugin.jeus.datasource;

import com.navercorp.pinpoint.bootstrap.logging.PLogger;
import com.navercorp.pinpoint.bootstrap.logging.PLoggerFactory;
import com.navercorp.pinpoint.bootstrap.plugin.monitor.DataSourceMonitor;
import com.navercorp.pinpoint.common.trace.ServiceType;
import com.navercorp.pinpoint.plugin.jeus.JeusConstants;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;

public class JeusDataSourceMonitor implements DataSourceMonitor {

    private final PLogger logger = PLoggerFactory.getLogger(this.getClass());
    private final Object connectionPool;

    private Method getCurrentPoolSizeMethod;
    private Method getNumberOfIdleConnectionsMethod;
    private Method getPoolInfoMethod;
    private Method getMaxPoolSizeMethod;
    private Method getConnectionPoolIdMethod;

    private volatile String dataSourceName;
    private volatile boolean closed = false;
    private volatile boolean initialized = false;

    // 운영 환경용: 로그 출력 주기 제어 (기본 5분 = 300초, 5초 수집 기준 60회)
    private static final int LOG_INTERVAL_COUNT = 60;
    private final AtomicInteger logCounter = new AtomicInteger(0);

    public JeusDataSourceMonitor(Object connectionPool) {
        this.connectionPool = connectionPool;
        initMethods();
    }

    private void initMethods() {
        if (connectionPool == null) return;

        try {
            Class<?> poolClass = connectionPool.getClass();
            // 메서드 이름이 정확한지 확인 필요 (JEUS 버전에 따라 다를 수 있음)
            this.getCurrentPoolSizeMethod = poolClass.getMethod("getCurrentPoolSize");
            this.getNumberOfIdleConnectionsMethod = poolClass.getMethod("getNumberOfIdleConnections");
            this.getPoolInfoMethod = poolClass.getMethod("getPoolInfo");

            Object poolInfo = getPoolInfoMethod.invoke(connectionPool);
            if (poolInfo != null) {
                Class<?> poolInfoClass = poolInfo.getClass();
                this.getMaxPoolSizeMethod = poolInfoClass.getMethod("getMaxPoolSize");
                this.getConnectionPoolIdMethod = poolInfoClass.getMethod("getConnectionPoolId");

                // 이름 추출
                this.dataSourceName = (String) getConnectionPoolIdMethod.invoke(poolInfo);
                this.initialized = true;
                logger.info("[JEUS-DATASOURCE] Initialized monitor for: " + dataSourceName);
            }
        } catch (Exception e) {
            logger.warn("[JEUS-DATASOURCE] Failed to initialize reflection methods. Check JEUS version compatibility.", e);
            this.dataSourceName = "JEUS-ERROR";
            this.initialized = false;
        }
    }

    public boolean isInitialized() {
        return initialized;
    }

    @Override
    public ServiceType getServiceType() {
        return JeusConstants.JEUS_DATASOURCE;
    }

    @Override
    public String getUrl() {
        return dataSourceName != null ? dataSourceName : "JEUS-UNKNOWN";
    }

    @Override
    public int getActiveConnectionSize() {
        if (closed || connectionPool == null) return -1;

        // null safe 체크: 초기화 실패 시 메서드가 null일 수 있음
        if (!initialized || getCurrentPoolSizeMethod == null || getNumberOfIdleConnectionsMethod == null) {
            return -1;
        }

        try {
            // 리플렉션 호출
            int current = (Integer) getCurrentPoolSizeMethod.invoke(connectionPool);
            int idle = (Integer) getNumberOfIdleConnectionsMethod.invoke(connectionPool);
            int active = current - idle;

            // 운영 환경용: 5분마다 한 번씩 DEBUG 레벨로 로그 출력
            if (logger.isDebugEnabled()) {
                int count = logCounter.incrementAndGet();
                if (count >= LOG_INTERVAL_COUNT) {
                    logger.debug("[JEUS-DATASOURCE] " + dataSourceName + " -> Active: " + active + ", Total: " + current + ", Idle: " + idle);
                    logCounter.set(0);
                }
            }

            return active;
        } catch (Exception e) {
            // 수집 실패 시 DEBUG 레벨 로그 (운영 환경에서 과도한 로그 방지)
            if (logger.isDebugEnabled()) {
                logger.debug("[JEUS-DATASOURCE] Error collecting active connections for " + dataSourceName, e);
            }
            return -1;
        }
    }

    @Override
    public int getMaxConnectionSize() {
        if (closed || connectionPool == null) return -1;

        // null safe 체크
        if (!initialized || getPoolInfoMethod == null || getMaxPoolSizeMethod == null) {
            return -1;
        }

        try {
            Object poolInfo = getPoolInfoMethod.invoke(connectionPool);
            if (poolInfo != null) {
                return (Integer) getMaxPoolSizeMethod.invoke(poolInfo);
            }
        } catch (Exception e) {
            if (logger.isDebugEnabled()) {
                logger.debug("[JEUS-DATASOURCE] Error collecting max connection size for " + dataSourceName, e);
            }
            return -1;
        }
        return -1;
    }

    @Override
    public boolean isDisabled() {
        return closed;
    }

    public void close() {
        this.closed = true;
    }
}