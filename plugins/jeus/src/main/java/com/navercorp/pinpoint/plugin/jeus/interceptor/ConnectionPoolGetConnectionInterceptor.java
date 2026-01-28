package com.navercorp.pinpoint.plugin.jeus.interceptor;

import com.navercorp.pinpoint.bootstrap.interceptor.AroundInterceptor;
import com.navercorp.pinpoint.bootstrap.logging.PLogger;
import com.navercorp.pinpoint.bootstrap.logging.PLoggerFactory;
import com.navercorp.pinpoint.bootstrap.plugin.monitor.DataSourceMonitorRegistry;
import com.navercorp.pinpoint.plugin.jeus.datasource.JeusDataSourceMonitor;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ConnectionPoolGetConnectionInterceptor implements AroundInterceptor {

    private final PLogger logger = PLoggerFactory.getLogger(this.getClass());

    // [핵심 수정] Boolean -> JeusDataSourceMonitor 변경
    // 모니터 객체를 여기에 저장해두어야 GC가 되지 않고 계속 살아있습니다.
    private static final Map<Object, JeusDataSourceMonitor> registeredMonitors = new ConcurrentHashMap<Object, JeusDataSourceMonitor>();

    private final DataSourceMonitorRegistry dataSourceMonitorRegistry;

    public ConnectionPoolGetConnectionInterceptor(DataSourceMonitorRegistry dataSourceMonitorRegistry) {
        this.dataSourceMonitorRegistry = dataSourceMonitorRegistry;
    }

    @Override
    public void before(Object target, Object[] args) {
        // 이미 등록된 모니터가 있으면 통과 (GC 방지됨)
        if (registeredMonitors.containsKey(target)) {
            return;
        }

        if (dataSourceMonitorRegistry == null) {
            return;
        }

        synchronized (registeredMonitors) {
            if (!registeredMonitors.containsKey(target)) {
                try {
                    logger.info("[JEUS-DATASOURCE] Found NEW ConnectionPool instance: " + target.getClass().getName());

                    JeusDataSourceMonitor monitor = new JeusDataSourceMonitor(target);
                    boolean registered = dataSourceMonitorRegistry.register(monitor);

                    if (registered) {
                        logger.info("[JEUS-DATASOURCE] SUCCESS: Monitor registered. URL: " + monitor.getUrl());

                        // [핵심] 모니터 객체 자체를 Map에 저장 (Strong Reference)
                        // 이렇게 해야 Pinpoint Registry가 Weak Reference여도 객체가 살아남습니다.
                        registeredMonitors.put(target, monitor);
                    } else {
                        logger.info("[JEUS-DATASOURCE] SKIP: Already registered in registry. URL: " + monitor.getUrl());
                        // 이미 등록되어 있다면, 기존 것을 관리하기 위해 put (안전을 위해)
                        registeredMonitors.put(target, monitor);
                    }

                } catch (Exception e) {
                    logger.warn("[JEUS-DATASOURCE] ERROR: Registration failed", e);
                    // 실패 시에는 저장하지 않아서 다음 요청 때 재시도하게 함 (선택 사항)
                }
            }
        }
    }

    @Override
    public void after(Object target, Object[] args, Object result, Throwable throwable) {
        // No-op
    }
}