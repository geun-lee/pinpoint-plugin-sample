package com.navercorp.pinpoint.plugin.jeus;

import com.navercorp.pinpoint.bootstrap.instrument.InstrumentClass;
import com.navercorp.pinpoint.bootstrap.instrument.InstrumentException;
import com.navercorp.pinpoint.bootstrap.instrument.InstrumentMethod;
import com.navercorp.pinpoint.bootstrap.instrument.Instrumentor;
import com.navercorp.pinpoint.bootstrap.instrument.transformer.TransformCallback;
import com.navercorp.pinpoint.bootstrap.instrument.transformer.TransformTemplate;
import com.navercorp.pinpoint.bootstrap.instrument.transformer.TransformTemplateAware;
import com.navercorp.pinpoint.bootstrap.logging.PLogger;
import com.navercorp.pinpoint.bootstrap.logging.PLoggerFactory;
import com.navercorp.pinpoint.bootstrap.plugin.ProfilerPlugin;
import com.navercorp.pinpoint.bootstrap.plugin.ProfilerPluginSetupContext;
import com.navercorp.pinpoint.plugin.jeus.datasource.DataSourceMonitorAccessor;
import com.navercorp.pinpoint.plugin.jeus.interceptor.ConnectionPoolGetConnectionInterceptor;
import com.navercorp.pinpoint.plugin.jeus.interceptor.WebActionDispatcherServiceInterceptor;

import java.security.ProtectionDomain;

public class JeusPlugin implements ProfilerPlugin, TransformTemplateAware {
    private final PLogger logger = PLoggerFactory.getLogger(this.getClass());
    private TransformTemplate transformTemplate;

    @Override
    public void setup(ProfilerPluginSetupContext context) {
        final JeusConfiguration config = new JeusConfiguration(context.getConfig());
        
        // Holder에 설정 저장
        JeusConfigurationHolder.setConfiguration(config);
        
        if (!config.isJeusEnabled()) {
            logger.info("[JEUS-PLUGIN] JeusPlugin disabled");
            return;
        }
        
        logger.info("[JEUS-PLUGIN] JeusPlugin setup started");

        // WebActionDispatcher 트랜스폼 (기존)
        addWebActionDispatcherTransform();
        
        // DataSource 모니터링 트랜스폼 (신규)
        if (config.isJeusDataSourceEnabled()) {
            logger.info("[JEUS-PLUGIN] JEUS DataSource monitoring enabled");
            addConnectionPoolTransform();
        }
    }
    
    /**
     * WebActionDispatcher Transform (기존 로직)
     */
    private void addWebActionDispatcherTransform() {
        transformTemplate.transform("himed.his.hit.web.action.WebActionDispatcher", new TransformCallback() {
            @Override
            public byte[] doInTransform(Instrumentor instrumentor, ClassLoader classLoader, String className, 
                    Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classfileBuffer) throws InstrumentException {
                logger.info("[JEUS-PLUGIN] Transforming class: " + className);
                InstrumentClass target = instrumentor.getInstrumentClass(classLoader, className, classfileBuffer);
                
                InstrumentMethod serviceMethod = target.getDeclaredMethod("service", 
                        "javax.servlet.http.HttpServletRequest", "javax.servlet.http.HttpServletResponse");
                
                if (serviceMethod != null) {
                    logger.info("[JEUS-PLUGIN] Found service method. Adding interceptor.");
                    serviceMethod.addInterceptor(WebActionDispatcherServiceInterceptor.class);
                } else {
                    logger.warn("[JEUS-PLUGIN] service method not found in " + className);
                }
                
                return target.toBytecode();
            }
        });
    }
    
    /**
     * JEUS ConnectionPool Transform (DataSource 모니터링)
     */
    private void addConnectionPoolTransform() {
        transformTemplate.transform(JeusConstants.JEUS_CONNECTION_POOL_IMPL, new TransformCallback() {
            @Override
            public byte[] doInTransform(Instrumentor instrumentor, ClassLoader classLoader, String className, 
                    Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classfileBuffer) throws InstrumentException {
                logger.info("[JEUS-PLUGIN] Transforming JEUS ConnectionPool: " + className);
                
                InstrumentClass target = instrumentor.getInstrumentClass(classLoader, className, classfileBuffer);
                
                // DataSourceMonitorAccessor 인터페이스 추가 (필드 주입용)
                target.addField(DataSourceMonitorAccessor.class);
                
                // getConnection 메서드에 인터셉터 추가
                // getConnection(String, String, boolean) 메서드 계측
                InstrumentMethod getConnectionMethod = target.getDeclaredMethod("getConnection", 
                        "java.lang.String", "java.lang.String", "boolean");
                
                if (getConnectionMethod != null) {
                    logger.info("[JEUS-PLUGIN] Found getConnection method. Adding DataSource interceptor.");
                    getConnectionMethod.addInterceptor(ConnectionPoolGetConnectionInterceptor.class);
                } else {
                    logger.warn("[JEUS-PLUGIN] getConnection method not found in " + className);
                }
                
                return target.toBytecode();
            }
        });
    }

    @Override
    public void setTransformTemplate(TransformTemplate transformTemplate) {
        this.transformTemplate = transformTemplate;
    }
}
