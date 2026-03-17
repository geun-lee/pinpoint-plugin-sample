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

import java.lang.ref.WeakReference;
import java.lang.reflect.Modifier;
import java.security.ProtectionDomain;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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

            // 패키지 기반 transform과 중복 등록을 허용함.
            // 이중 계측 방지는 HimedClassTransformCallback 내부에서 ClassLoader 기준으로 처리.
            // (패키지 transform의 SuperClass 매처를 통과하지 못하는 클래스를 traceClasses에서
            //  명시적으로 지정한 경우, 이전 isCoveredByPackage 방식으로는 계측이 누락되는 문제 수정)
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
     *
     * [이중 계측 방지]
     * 같은 클래스가 패키지 기반 transform + 클래스 기반 transform 양쪽에 등록된 경우,
     * Pinpoint는 두 콜백을 순서대로 호출함. WeakReference<ClassLoader> 추적으로
     * 동일 ClassLoader 내 두 번째 호출을 감지하여 즉시 null을 반환(no-op).
     * 새 ClassLoader(핫 디플로이)는 WeakReference가 만료되므로 정상 계측됨.
     */
    public static class HimedClassTransformCallback implements TransformCallback {
        private final PLogger logger = PLoggerFactory.getLogger(this.getClass());

        // 이중 계측 방지 + 핫 디플로이 감지용: className → 마지막으로 계측한 ClassLoader(WeakRef)
        // WeakReference: 핫 디플로이 시 이전 ClassLoader가 GC될 수 있도록 허용
        // static: 패키지 transform/클래스 transform 양쪽 콜백 인스턴스가 공유해야 함
        private static final ConcurrentHashMap<String, WeakReference<ClassLoader>> transformedClasses =
                new ConcurrentHashMap<String, WeakReference<ClassLoader>>();

        @Override
        public byte[] doInTransform(Instrumentor instrumentor, ClassLoader classLoader, String className,
                Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classfileBuffer) throws InstrumentException {

            // Matcher 레벨(SuperClassInternalNameMatcherOperand)에서 이미 필터링됨
            // → ContextAwareService 직접 상속 클래스만 여기까지 도달

            InstrumentClass target = instrumentor.getInstrumentClass(classLoader, className, classfileBuffer);

            if (target.isInterface()) {
                return null;
            }

            // 이중 계측 감지 / 핫 디플로이 감지
            // putIfAbsent: get+put을 원자적으로 처리하여 TOCTOU 경쟁 조건 방지
            WeakReference<ClassLoader> newRef = new WeakReference<ClassLoader>(classLoader);
            WeakReference<ClassLoader> existingRef = transformedClasses.putIfAbsent(className, newRef);
            boolean isHotDeploy = false;

            if (existingRef != null) {
                ClassLoader existingCl = existingRef.get();
                if (existingCl == classLoader) {
                    // 동일 ClassLoader에서 이미 계측됨 → 패키지+클래스 이중 등록에 의한 중복 호출
                    // 두 번째 콜백은 아무것도 하지 않음 (null = 이전 transform 결과 그대로 사용)
                    if (logger.isDebugEnabled()) {
                        logger.debug("[JEUS-PLUGIN] Skipping duplicate transform for: " + className);
                    }
                    return null;
                }
                // existingCl != classLoader (또는 GC됨) → 핫 디플로이로 새 ClassLoader 사용
                // 새 ClassLoader ref로 교체 (putIfAbsent는 이미 존재하는 경우 교체 안 하므로 명시적 put)
                transformedClasses.put(className, newRef);
                isHotDeploy = true;
            }

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

            logger.info("[JEUS-PLUGIN] " + className
                    + ": added=" + addedCount
                    + " [hot-deploy=" + isHotDeploy + "]");

            return target.toBytecode();
        }

        // 제외 대상 메서드 이름: O(1) lookup
        private static final Set<String> EXCLUDED_METHODS = new HashSet<String>(Arrays.asList(
                "toString", "hashCode", "equals", "clone", "finalize", "getClass",
                "<init>", "<clinit>", "init", "destroy"
        ));

        private static boolean isExcludedMethod(String methodName) {
            if (EXCLUDED_METHODS.contains(methodName)) {
                return true;
            }
            // getter/setter/is-accessor 제외 (prefix 매칭)
            if (methodName.startsWith("get") || methodName.startsWith("set") ||
                methodName.startsWith("is")) {
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
