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
            logger.info("[CUSTOM-PLUGIN] JeusPlugin disabled");
            return;
        }
        
        logger.info("[CUSTOM-PLUGIN] JeusPlugin setup started");

        transformTemplate.transform("himed.his.hit.web.action.WebActionDispatcher", new TransformCallback() {
            @Override
            public byte[] doInTransform(Instrumentor instrumentor, ClassLoader classLoader, String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classfileBuffer) throws InstrumentException {
                logger.info("[CUSTOM-PLUGIN] Transforming class: " + className);
                InstrumentClass target = instrumentor.getInstrumentClass(classLoader, className, classfileBuffer);
                
                // 메소드 찾기 시도
                InstrumentMethod serviceMethod = target.getDeclaredMethod("service", "javax.servlet.http.HttpServletRequest", "javax.servlet.http.HttpServletResponse");
                
                if (serviceMethod != null) {
                    logger.info("[CUSTOM-PLUGIN] Found service method. Adding interceptor.");
                    // 문자열 대신 Class 객체 전달 (Pinpoint 3.0.x 호환)
                    serviceMethod.addInterceptor(WebActionDispatcherServiceInterceptor.class);
                } else {
                    logger.warn("[CUSTOM-PLUGIN] service method not found in " + className);
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
