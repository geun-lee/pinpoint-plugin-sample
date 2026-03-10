package com.navercorp.pinpoint.plugin.jeus;

import com.navercorp.pinpoint.bootstrap.instrument.InstrumentClass;
import com.navercorp.pinpoint.bootstrap.instrument.InstrumentException;
import com.navercorp.pinpoint.bootstrap.instrument.InstrumentMethod;
import com.navercorp.pinpoint.bootstrap.instrument.Instrumentor;
import com.navercorp.pinpoint.bootstrap.instrument.MethodFilters;
import com.navercorp.pinpoint.bootstrap.instrument.matcher.Matcher;
import com.navercorp.pinpoint.bootstrap.instrument.matcher.Matchers;
import com.navercorp.pinpoint.bootstrap.instrument.matcher.operand.SuperClassInternalNameMatcherOperand;
import com.navercorp.pinpoint.bootstrap.instrument.transformer.MatchableTransformTemplate;
import com.navercorp.pinpoint.bootstrap.instrument.transformer.MatchableTransformTemplateAware;
import com.navercorp.pinpoint.bootstrap.instrument.transformer.TransformCallback;
import com.navercorp.pinpoint.bootstrap.logging.PLogger;
import com.navercorp.pinpoint.bootstrap.logging.PLoggerFactory;
import com.navercorp.pinpoint.bootstrap.plugin.ProfilerPlugin;
import com.navercorp.pinpoint.bootstrap.plugin.ProfilerPluginSetupContext;
import com.navercorp.pinpoint.plugin.jeus.interceptor.ConnectionPoolGetConnectionInterceptor;
import com.navercorp.pinpoint.plugin.jeus.interceptor.HimedMethodInterceptor;
import com.navercorp.pinpoint.plugin.jeus.interceptor.LoggingAppenderInterceptor;
import com.navercorp.pinpoint.plugin.jeus.interceptor.WebActionDispatcherServiceInterceptor;

import java.lang.reflect.Modifier;
import java.security.ProtectionDomain;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

public class JeusPlugin implements ProfilerPlugin, MatchableTransformTemplateAware {
    private final PLogger logger = PLoggerFactory.getLogger(this.getClass());
    private MatchableTransformTemplate transformTemplate;

    @Override
    public void setup(ProfilerPluginSetupContext context) {
        final JeusConfiguration config = new JeusConfiguration(context.getConfig());

        JeusConfigurationHolder.setConfiguration(config);

        if (!config.isJeusEnabled()) {
            logger.info("[JEUS-PLUGIN] JeusPlugin disabled");
            return;
        }

        logger.info("[JEUS-PLUGIN] JeusPlugin setup started");

        addWebActionDispatcherTransform();

        if (config.isJeusMethodTraceEnabled()) {
            addHimedPackageTransform(config);
        }

        if (config.isJeusDataSourceEnabled()) {
            logger.info("[JEUS-PLUGIN] JEUS DataSource monitoring enabled");
            addConnectionPoolTransform();
        }

        // 로깅 연동: 로그 발생 시 Pinpoint에 LOGGED 마킹 → Web UI "View Log" 버튼 활성화
        addLoggingAppenderTransform(config);
    }

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

    private void addHimedPackageTransform(JeusConfiguration config) {
        List<String> tracePackages = config.getJeusTracePackages();
        if (tracePackages != null && !tracePackages.isEmpty()) {
            addPackageBasedTransform(tracePackages);
        }

        List<String> traceClasses = config.getJeusTraceClasses();
        if (traceClasses != null && !traceClasses.isEmpty()) {
            addClassBasedTransform(traceClasses);
        }

        if ((tracePackages == null || tracePackages.isEmpty()) &&
            (traceClasses == null || traceClasses.isEmpty())) {
            logger.info("[JEUS-PLUGIN] No trace packages or classes configured. Method tracing disabled.");
        }
    }

    private void addPackageBasedTransform(List<String> packages) {
        // ContextAwareService를 직접 상속하는 클래스만 계측
        // → DAO 계층(JdbcQueryDAO 상속) 및 기타 유틸 클래스 자동 제외
        // considerHierarchy=false: 직접 상속만 체크 (간접 상속 제외)
        SuperClassInternalNameMatcherOperand superClassOperand =
                new SuperClassInternalNameMatcherOperand("kr/co/hit/live/context/ContextAwareService", false);
        Matcher matcher = Matchers.newPackageBasedMatcher(packages, superClassOperand);
        transformTemplate.transform(matcher, HimedClassTransformCallback.class);
        logger.info("[JEUS-PLUGIN] SuperClass-based transform registered. packages=" + packages
                + " superClass=kr.co.hit.live.context.ContextAwareService");
    }

    private void addClassBasedTransform(List<String> classes) {
        int registeredCount = 0;
        for (String className : classes) {
            String trimmed = className.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }

            if (trimmed.equals("himed.his.hit.web.action.WebActionDispatcher")) {
                continue;
            }

            transformTemplate.transform(trimmed, HimedClassTransformCallback.class);
            registeredCount++;
        }

        if (registeredCount > 0) {
            logger.info("[JEUS-PLUGIN] Class-based method tracing registered for " + registeredCount + " classes");
        }
    }

    /**
     * himed 클래스 Transform Callback - public 메서드에 인터셉터 추가
     *
     * [핫 디플로이 처리]
     * JEUS 핫 디플로이는 새 ClassLoader로 클래스를 재로드하므로 doInTransform이 재호출됨.
     * addInterceptor(int id) 재사용 방식은 Pinpoint 내부적으로 InterceptorHolder$N 클래스를
     * 새 ClassLoader에서 찾을 수 없어 항상 실패(InstrumentException: not found class).
     *
     * 따라서 핫 디플로이 시에도 새 ID를 발급하며, 레지스트리 고갈 방지를 위해
     * profiler.interceptor.registry.size 값을 충분히 크게 설정해야 함.
     * 권장값: profiler.interceptor.registry.size=1048576
     */
    public static class HimedClassTransformCallback implements TransformCallback {
        private final PLogger logger = PLoggerFactory.getLogger(this.getClass());

        // 핫 디플로이 감지용: className → 이미 변환된 적 있는지 여부
        // static: ClassLoader 교체(핫 디플로이) 후에도 JVM 종료 시까지 유지
        private static final ConcurrentHashMap<String, Boolean> transformedClasses =
                new ConcurrentHashMap<String, Boolean>();

        @Override
        public byte[] doInTransform(Instrumentor instrumentor, ClassLoader classLoader, String className,
                Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classfileBuffer) throws InstrumentException {

            // Matcher 레벨(SuperClassInternalNameMatcherOperand)에서 이미 필터링됨
            // → ContextAwareService 직접 상속 클래스만 여기까지 도달

            InstrumentClass target = instrumentor.getInstrumentClass(classLoader, className, classfileBuffer);

            if (target.isInterface()) {
                return null;
            }

            boolean isHotDeploy = transformedClasses.containsKey(className);

            if (isHotDeploy) {
                logger.warn("[JEUS-PLUGIN] Hot-deploy detected: " + className + " | Re-transforming with new IDs");
            }

            List<InstrumentMethod> methods = target.getDeclaredMethods(MethodFilters.modifier(Modifier.PUBLIC));

            int addedCount = 0;

            for (InstrumentMethod method : methods) {
                String methodName = method.getName();

                if (isExcludedMethod(methodName)) {
                    continue;
                }

                try {
                    method.addInterceptor(HimedMethodInterceptor.class);
                    addedCount++;
                } catch (Exception e) {
                    if (logger.isDebugEnabled()) {
                        logger.debug("[JEUS-PLUGIN] Failed to add interceptor to "
                                + className + "." + methodName, e);
                    }
                }
            }

            transformedClasses.put(className, Boolean.TRUE);

            logger.info("[JEUS-PLUGIN] " + className
                    + ": added=" + addedCount
                    + " [hot-deploy=" + isHotDeploy + "]");

            return target.toBytecode();
        }

        private static boolean isExcludedMethod(String methodName) {
            if (methodName.equals("toString") || methodName.equals("hashCode") ||
                methodName.equals("equals") || methodName.equals("clone") ||
                methodName.equals("finalize") || methodName.equals("getClass")) {
                return true;
            }
            if (methodName.startsWith("get") || methodName.startsWith("set") ||
                methodName.startsWith("is")) {
                return true;
            }
            if (methodName.equals("<init>") || methodName.equals("<clinit>") ||
                methodName.equals("init") || methodName.equals("destroy")) {
                return true;
            }
            return false;
        }
    }

    /**
     * 로깅 Appender 계측 등록.
     *
     * profiler.jeus.logging.appender.classes에 지정된 클래스의 append/doAppend 메서드를
     * 인터셉트하여, 로그 발생 시 현재 Trace에 LoggingInfo.LOGGED를 마킹.
     *
     * 주요 클래스 예시:
     *   Log4j2 : org.apache.logging.log4j.core.config.LoggerConfig
     *   Logback : ch.qos.logback.core.AppenderBase
     *   Log4j   : org.apache.log4j.AppenderSkeleton
     */
    private void addLoggingAppenderTransform(JeusConfiguration config) {
        List<String> appenderClasses = config.getJeusLoggingAppenderClasses();
        if (appenderClasses == null || appenderClasses.isEmpty()) {
            return;
        }

        for (String className : appenderClasses) {
            String trimmed = className.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            transformTemplate.transform(trimmed, LoggingClassTransformCallback.class);
            logger.info("[JEUS-PLUGIN] Logging appender transform registered for: " + trimmed);
        }
    }

    /**
     * 로깅 Appender Transform Callback.
     *
     * 대상 클래스에서 append(E) 또는 doAppend(E) 메서드를 찾아 인터셉터 추가.
     * 메서드 파라미터 타입은 로깅 프레임워크마다 다르므로 메서드명만으로 탐색.
     */
    public static class LoggingClassTransformCallback implements TransformCallback {
        private final PLogger logger = PLoggerFactory.getLogger(this.getClass());

        @Override
        public byte[] doInTransform(Instrumentor instrumentor, ClassLoader classLoader, String className,
                Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classfileBuffer) throws InstrumentException {

            InstrumentClass target = instrumentor.getInstrumentClass(classLoader, className, classfileBuffer);
            boolean interceptorAdded = false;

            // "doAppend" 메서드 우선 탐색 (Logback: AppenderBase.doAppend)
            List<InstrumentMethod> doAppendMethods = target.getDeclaredMethods(MethodFilters.name("doAppend"));
            for (InstrumentMethod method : doAppendMethods) {
                if (method.getParameterTypes().length == 1) {
                    try {
                        method.addInterceptor(LoggingAppenderInterceptor.class);
                        interceptorAdded = true;
                        logger.info("[JEUS-PLUGIN] Logging interceptor added to: " + className + ".doAppend()");
                    } catch (Exception e) {
                        logger.warn("[JEUS-PLUGIN] Failed to add logging interceptor to: " + className + ".doAppend()", e);
                    }
                }
            }

            // "append" 메서드 탐색 (Log4j2: AbstractAppender.append / Log4j: AppenderSkeleton.append)
            if (!interceptorAdded) {
                List<InstrumentMethod> appendMethods = target.getDeclaredMethods(MethodFilters.name("append"));
                for (InstrumentMethod method : appendMethods) {
                    if (method.getParameterTypes().length == 1) {
                        try {
                            method.addInterceptor(LoggingAppenderInterceptor.class);
                            interceptorAdded = true;
                            logger.info("[JEUS-PLUGIN] Logging interceptor added to: " + className + ".append()");
                        } catch (Exception e) {
                            logger.warn("[JEUS-PLUGIN] Failed to add logging interceptor to: " + className + ".append()", e);
                        }
                    }
                }
            }

            if (!interceptorAdded) {
                logger.warn("[JEUS-PLUGIN] No append/doAppend method found in: " + className);
                return null;
            }

            return target.toBytecode();
        }
    }

    private void addConnectionPoolTransform() {
        transformTemplate.transform(JeusConstants.JEUS_CONNECTION_POOL_IMPL, new TransformCallback() {
            @Override
            public byte[] doInTransform(Instrumentor instrumentor, ClassLoader classLoader, String className,
                    Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classfileBuffer) throws InstrumentException {
                logger.info("[JEUS-PLUGIN] Transforming JEUS ConnectionPool: " + className);

                InstrumentClass target = instrumentor.getInstrumentClass(classLoader, className, classfileBuffer);

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
    public void setTransformTemplate(MatchableTransformTemplate transformTemplate) {
        this.transformTemplate = transformTemplate;
    }
}
