package com.navercorp.pinpoint.plugin.jeus;

import com.navercorp.pinpoint.bootstrap.instrument.InstrumentClass;
import com.navercorp.pinpoint.bootstrap.instrument.InstrumentException;
import com.navercorp.pinpoint.bootstrap.instrument.InstrumentMethod;
import com.navercorp.pinpoint.bootstrap.instrument.Instrumentor;
import com.navercorp.pinpoint.bootstrap.instrument.MethodFilters;
import com.navercorp.pinpoint.bootstrap.instrument.transformer.TransformCallback;
import com.navercorp.pinpoint.bootstrap.instrument.transformer.TransformTemplate;
import com.navercorp.pinpoint.bootstrap.instrument.transformer.TransformTemplateAware;
import com.navercorp.pinpoint.bootstrap.logging.PLogger;
import com.navercorp.pinpoint.bootstrap.logging.PLoggerFactory;
import com.navercorp.pinpoint.bootstrap.plugin.ProfilerPlugin;
import com.navercorp.pinpoint.bootstrap.plugin.ProfilerPluginSetupContext;
import com.navercorp.pinpoint.plugin.jeus.interceptor.ConnectionPoolGetConnectionInterceptor;
import com.navercorp.pinpoint.plugin.jeus.interceptor.HimedMethodInterceptor;
import com.navercorp.pinpoint.plugin.jeus.interceptor.WebActionDispatcherServiceInterceptor;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Modifier;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.List;

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

        // WebActionDispatcher 트랜스폼 (기존) - Entry Point
        addWebActionDispatcherTransform();

        // 메서드 트레이싱 - 콜스택 표시용
        if (config.isJeusMethodTraceEnabled()) {
            addHimedPackageTransform(config);
        }

        // DataSource 모니터링 트랜스폼
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
    
    // 클래스 목록 파일 경로 (리소스)
    private static final String TRACE_CLASSES_FILE = "himed-trace-classes.txt";

    /**
     * himed.his 패키지 하위 클래스들의 메서드를 트레이싱.
     * 1. 설정 파일에서 클래스 목록 읽기 (profiler.jeus.trace.classes)
     * 2. 리소스 파일에서 클래스 목록 읽기 (himed-trace-classes.txt)
     */
    private void addHimedPackageTransform(JeusConfiguration config) {
        List<String> traceClasses = new ArrayList<String>();

        // 1. 설정 파일에서 지정된 클래스 추가
        List<String> configClasses = config.getJeusTraceClasses();
        if (configClasses != null && !configClasses.isEmpty()) {
            traceClasses.addAll(configClasses);
            logger.info("[JEUS-PLUGIN] Loaded " + configClasses.size() + " classes from config");
        }

        // 2. 리소스 파일에서 클래스 목록 로드
        List<String> fileClasses = loadClassesFromResource();
        if (!fileClasses.isEmpty()) {
            traceClasses.addAll(fileClasses);
            logger.info("[JEUS-PLUGIN] Loaded " + fileClasses.size() + " classes from " + TRACE_CLASSES_FILE);
        }

        if (traceClasses.isEmpty()) {
            logger.info("[JEUS-PLUGIN] No trace classes found. Method tracing disabled.");
            return;
        }

        int registeredCount = 0;
        for (String className : traceClasses) {
            String trimmed = className.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue; // 빈 줄이나 주석 무시
            }

            // WebActionDispatcher는 이미 별도로 처리하므로 제외
            if (trimmed.equals("himed.his.hit.web.action.WebActionDispatcher")) {
                continue;
            }

            transformTemplate.transform(trimmed, HimedClassTransformCallback.class);
            registeredCount++;
        }

        logger.info("[JEUS-PLUGIN] Method tracing registered for " + registeredCount + " classes");
    }

    /**
     * 리소스 파일에서 클래스 목록 로드
     */
    private List<String> loadClassesFromResource() {
        List<String> classes = new ArrayList<String>();
        InputStream is = null;
        BufferedReader reader = null;

        try {
            is = getClass().getClassLoader().getResourceAsStream(TRACE_CLASSES_FILE);
            if (is == null) {
                logger.info("[JEUS-PLUGIN] Resource file not found: " + TRACE_CLASSES_FILE);
                return classes;
            }

            reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                    classes.add(trimmed);
                }
            }
        } catch (Exception e) {
            logger.warn("[JEUS-PLUGIN] Failed to load classes from resource: " + e.getMessage());
        } finally {
            if (reader != null) {
                try { reader.close(); } catch (Exception ignored) {}
            }
            if (is != null) {
                try { is.close(); } catch (Exception ignored) {}
            }
        }

        return classes;
    }

    /**
     * himed 클래스 Transform Callback - public 메서드에 인터셉터 추가
     */
    public static class HimedClassTransformCallback implements TransformCallback {
        private final PLogger logger = PLoggerFactory.getLogger(this.getClass());

        @Override
        public byte[] doInTransform(Instrumentor instrumentor, ClassLoader classLoader, String className,
                Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classfileBuffer) throws InstrumentException {

            InstrumentClass target = instrumentor.getInstrumentClass(classLoader, className, classfileBuffer);

            // 인터페이스는 제외
            if (target.isInterface()) {
                return null;
            }

            if (logger.isInfoEnabled()) {
                logger.info("[JEUS-PLUGIN] Transforming class: " + className);
            }

            // public 메서드에 인터셉터 추가
            List<InstrumentMethod> methods = target.getDeclaredMethods(MethodFilters.modifier(Modifier.PUBLIC));

            int addedCount = 0;
            for (InstrumentMethod method : methods) {
                String methodName = method.getName();

                // 제외할 메서드
                if (isExcludedMethod(methodName)) {
                    continue;
                }

                try {
                    method.addInterceptor(HimedMethodInterceptor.class);
                    addedCount++;
                } catch (Exception e) {
                    if (logger.isDebugEnabled()) {
                        logger.debug("[JEUS-PLUGIN] Failed to add interceptor to " + className + "." + methodName, e);
                    }
                }
            }

            if (addedCount > 0 && logger.isInfoEnabled()) {
                logger.info("[JEUS-PLUGIN] Added interceptor to " + addedCount + " methods in " + className);
            }

            return target.toBytecode();
        }

        private static boolean isExcludedMethod(String methodName) {
            // Object 클래스 메서드 제외
            if (methodName.equals("toString") || methodName.equals("hashCode") ||
                methodName.equals("equals") || methodName.equals("clone") ||
                methodName.equals("finalize") || methodName.equals("getClass")) {
                return true;
            }
            // getter/setter 제외
            if (methodName.startsWith("get") || methodName.startsWith("set") ||
                methodName.startsWith("is")) {
                return true;
            }
            // 생성자, 초기화 메서드 제외
            if (methodName.equals("<init>") || methodName.equals("<clinit>") ||
                methodName.equals("init") || methodName.equals("destroy")) {
                return true;
            }
            return false;
        }
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
