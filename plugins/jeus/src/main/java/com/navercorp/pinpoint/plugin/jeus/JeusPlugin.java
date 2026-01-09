package com.navercorp.pinpoint.plugin.jeus;

import com.navercorp.pinpoint.bootstrap.instrument.InstrumentClass;
import com.navercorp.pinpoint.bootstrap.instrument.InstrumentException;
import com.navercorp.pinpoint.bootstrap.instrument.InstrumentMethod;
import com.navercorp.pinpoint.bootstrap.instrument.Instrumentor;
import com.navercorp.pinpoint.bootstrap.instrument.transformer.TransformCallback;
import com.navercorp.pinpoint.bootstrap.instrument.transformer.TransformTemplate;
import com.navercorp.pinpoint.bootstrap.instrument.transformer.TransformTemplateAware;
import com.navercorp.pinpoint.bootstrap.plugin.ProfilerPlugin;
import com.navercorp.pinpoint.bootstrap.plugin.ProfilerPluginSetupContext;
import com.navercorp.pinpoint.plugin.jeus.interceptor.WebActionDispatcherServiceInterceptor;

import java.security.ProtectionDomain;

public class JeusPlugin implements ProfilerPlugin, TransformTemplateAware {
    private TransformTemplate transformTemplate;

    @Override
    public void setup(ProfilerPluginSetupContext context) {
        final JeusConfiguration config = new JeusConfiguration(context.getConfig());
        
        // Holder에 설정 저장
        JeusConfigurationHolder.setConfiguration(config);
        
        if (!config.isJeusEnabled()) {
            return;
        }

        transformTemplate.transform("himed.his.hit.web.action.WebActionDispatcher", new TransformCallback() {
            @Override
            public byte[] doInTransform(Instrumentor instrumentor, ClassLoader classLoader, String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classfileBuffer) throws InstrumentException {
                InstrumentClass target = instrumentor.getInstrumentClass(classLoader, className, classfileBuffer);
                
                InstrumentMethod serviceMethod = target.getDeclaredMethod("service", "javax.servlet.http.HttpServletRequest", "javax.servlet.http.HttpServletResponse");
                if (serviceMethod != null) {
                    // 문자열 대신 Class 객체 전달 (Pinpoint 3.0.x 호환)
                    serviceMethod.addInterceptor(WebActionDispatcherServiceInterceptor.class);
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
