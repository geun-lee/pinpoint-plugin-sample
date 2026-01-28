package com.navercorp.pinpoint.plugin.jeus.datasource;

import com.navercorp.pinpoint.bootstrap.plugin.monitor.DataSourceMonitor;

/**
 * JEUS ConnectionPool에 DataSourceMonitor를 주입하기 위한 Accessor 인터페이스
 * 
 * Pinpoint가 계측 시 이 인터페이스를 ConnectionPoolImpl 클래스에 구현시키고,
 * 해당 필드를 통해 DataSourceMonitor 인스턴스를 저장/조회합니다.
 */
public interface DataSourceMonitorAccessor {
    
    /**
     * DataSourceMonitor 설정
     * @param dataSourceMonitor 모니터 인스턴스
     */
    void _$PINPOINT$_setDataSourceMonitor(DataSourceMonitor dataSourceMonitor);
    
    /**
     * DataSourceMonitor 조회
     * @return 설정된 모니터 인스턴스
     */
    DataSourceMonitor _$PINPOINT$_getDataSourceMonitor();
}
