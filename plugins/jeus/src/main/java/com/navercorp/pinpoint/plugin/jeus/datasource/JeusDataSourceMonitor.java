package com.navercorp.pinpoint.plugin.jeus.datasource;

import com.navercorp.pinpoint.bootstrap.logging.PLogger;
import com.navercorp.pinpoint.bootstrap.logging.PLoggerFactory;
import com.navercorp.pinpoint.bootstrap.plugin.monitor.DataSourceMonitor;
import com.navercorp.pinpoint.common.trace.ServiceType;
import com.navercorp.pinpoint.plugin.jeus.JeusConstants;

import java.lang.reflect.Method;

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
                logger.info("[JEUS-DATASOURCE] Initialized monitor for: " + dataSourceName);
            }
        } catch (Exception e) {
            logger.warn("[JEUS-DATASOURCE] Failed to initialize reflection methods. Check JEUS version compatibility.", e);
            this.dataSourceName = "JEUS-ERROR";
        }
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

        try {
            // 리플렉션 호출
            int current = (Integer) getCurrentPoolSizeMethod.invoke(connectionPool);
            int idle = (Integer) getNumberOfIdleConnectionsMethod.invoke(connectionPool);
            int active = current - idle;

            // [디버깅 로그] 5초마다 이 로그가 찍혀야 정상 수집 중인 것입니다.
            // 확인 후에는 주석 처리하세요.
            logger.info("[JEUS-DATASOURCE] Collecting for " + dataSourceName + " -> Active: " + active + " (Total: " + current + ", Idle: " + idle + ")");

            return active;
        } catch (Exception e) {
            // 수집 실패 시 로그 (너무 많이 찍힐 수 있으므로 주의)
            logger.warn("[JEUS-DATASOURCE] Error collecting active connections", e);
            return -1;
        }
    }

    @Override
    public int getMaxConnectionSize() {
        if (closed || connectionPool == null) return -1;
        try {
            Object poolInfo = getPoolInfoMethod.invoke(connectionPool);
            if (poolInfo != null) {
                return (Integer) getMaxPoolSizeMethod.invoke(poolInfo);
            }
        } catch (Exception e) {
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