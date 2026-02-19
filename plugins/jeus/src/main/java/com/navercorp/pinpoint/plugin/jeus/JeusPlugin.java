package com.navercorp.pinpoint.plugin.jeus;

import com.navercorp.pinpoint.bootstrap.instrument.InstrumentClass;
import com.navercorp.pinpoint.bootstrap.instrument.InstrumentException;
import com.navercorp.pinpoint.bootstrap.instrument.InstrumentMethod;
import com.navercorp.pinpoint.bootstrap.instrument.Instrumentor;
import com.navercorp.pinpoint.bootstrap.instrument.MethodFilters;
import com.navercorp.pinpoint.bootstrap.instrument.matcher.Matcher;
import com.navercorp.pinpoint.bootstrap.instrument.matcher.Matchers;
import com.navercorp.pinpoint.bootstrap.instrument.transformer.MatchableTransformTemplate;
import com.navercorp.pinpoint.bootstrap.instrument.transformer.MatchableTransformTemplateAware;
import com.navercorp.pinpoint.bootstrap.instrument.transformer.TransformCallback;
import com.navercorp.pinpoint.bootstrap.logging.PLogger;
import com.navercorp.pinpoint.bootstrap.logging.PLoggerFactory;
import com.navercorp.pinpoint.bootstrap.plugin.ProfilerPlugin;
import com.navercorp.pinpoint.bootstrap.plugin.ProfilerPluginSetupContext;
import com.navercorp.pinpoint.plugin.jeus.interceptor.ConnectionPoolGetConnectionInterceptor;
import com.navercorp.pinpoint.plugin.jeus.interceptor.HimedMethodInterceptor;
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
    
    /**
     * himed.his 패키지 하위 클래스들의 메서드를 트레이싱.
     * 1. 패키지 패턴 기반 (신규 방식) - profiler.jeus.trace.packages
     * 2. 클래스 목록 기반 (기존 호환) - profiler.jeus.trace.classes
     */
    private void addHimedPackageTransform(JeusConfiguration config) {
        // 1. 패키지 패턴 기반 트랜스폼 (신규 방식)
        List<String> tracePackages = config.getJeusTracePackages();
        if (tracePackages != null && !tracePackages.isEmpty()) {
            addPackageBasedTransform(tracePackages);
        }

        // 2. 클래스 목록 기반 트랜스폼 (기존 호환)
        List<String> traceClasses = config.getJeusTraceClasses();
        if (traceClasses != null && !traceClasses.isEmpty()) {
            addClassBasedTransform(traceClasses);
        }

        if ((tracePackages == null || tracePackages.isEmpty()) &&
            (traceClasses == null || traceClasses.isEmpty())) {
            logger.info("[JEUS-PLUGIN] No trace packages or classes configured. Method tracing disabled.");
        }
    }

    /**
     * 패키지 패턴 기반 트랜스폼 (신규 방식)
     * Impl 클래스만 필터링하여 트랜스폼
     */
    private void addPackageBasedTransform(List<String> packages) {
        logger.info("[JEUS-PLUGIN] Package-based transform enabled for: " + packages);

        // 패키지 기반 Matcher 생성
        Matcher packageMatcher = Matchers.newPackageBasedMatcher(packages);

        // 패키지 하위 모든 클래스에 트랜스폼 적용
        transformTemplate.transform(packageMatcher, HimedClassTransformCallback.class);

        logger.info("[JEUS-PLUGIN] Package-based method tracing registered for packages: " + packages);
    }

    /**
     * 클래스 목록 기반 트랜스폼 (기존 호환)
     */
    private void addClassBasedTransform(List<String> classes) {
        int registeredCount = 0;
        for (String className : classes) {
            String trimmed = className.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }

            // WebActionDispatcher는 이미 별도로 처리하므로 제외
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
     * Impl 클래스만 필터링하여 처리
     *
     * [핫 디플로이 레지스트리 성장 방지]
     * - 클래스명 기반으로 메서드 시그니처 → 인터셉터 ID 매핑을 static으로 관리
     *   (static이므로 ClassLoader 교체와 무관하게 JVM 종료 시까지 유지)
     * - 핫 디플로이 시 기존 메서드는 addInterceptor(existingId)로 ID 재사용
     *   → 새 바이트코드에도 기존 ID가 주입됨 (레지스트리 증가 없음, 트레이싱 유지)
     * - 핫 디플로이로 새로 추가된 메서드만 신규 ID 할당
     */
    public static class HimedClassTransformCallback implements TransformCallback {
        private final PLogger logger = PLoggerFactory.getLogger(this.getClass());

        // ─── 핵심: static 선언으로 모든 ClassLoader 에서 공유 ───
        // className → (메서드 시그니처 → 인터셉터 ID)
        private static final ConcurrentHashMap<String, ConcurrentHashMap<String, Integer>> classMethodIdMap =
                new ConcurrentHashMap<String, ConcurrentHashMap<String, Integer>>();

        @Override
        public byte[] doInTransform(Instrumentor instrumentor, ClassLoader classLoader, String className,
                Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classfileBuffer) throws InstrumentException {

            if (!className.endsWith("Impl")) {
                return null;
            }

            InstrumentClass target = instrumentor.getInstrumentClass(classLoader, className, classfileBuffer);

            if (target.isInterface()) {
                return null;
            }

            // ─── 핫 디플로이 감지 ───
            ConcurrentHashMap<String, Integer> existingMethodIds = classMethodIdMap.get(className);
            boolean isHotDeploy = (existingMethodIds != null);

            if (isHotDeploy) {
                logger.warn("[JEUS-PLUGIN] ★ Hot-deploy detected: " + className
                        + " | Reusing " + existingMethodIds.size() + " existing interceptor IDs");
            } else if (logger.isInfoEnabled()) {
                logger.info("[JEUS-PLUGIN] Transforming class: " + className);
            }

            ConcurrentHashMap<String, Integer> methodIds = isHotDeploy
                    ? existingMethodIds
                    : new ConcurrentHashMap<String, Integer>();

            List<InstrumentMethod> methods = target.getDeclaredMethods(MethodFilters.modifier(Modifier.PUBLIC));

            int reusedCount = 0;
            int addedCount = 0;

            for (InstrumentMethod method : methods) {
                String methodName = method.getName();

                if (isExcludedMethod(methodName)) {
                    continue;
                }

                String sig = buildMethodSig(method);

                if (isHotDeploy && existingMethodIds.containsKey(sig)) {
                    // ─── 핫 디플로이: 기존 ID 재사용 ───
                    // addInterceptor(int id): 새 인터셉터 인스턴스 생성 없이
                    // 기존 레지스트리 ID를 새 바이트코드에 주입
                    // → 레지스트리 증가 없음, 트레이싱 정상 유지
                    int existingId = existingMethodIds.get(sig);
                    try {
                        method.addInterceptor(existingId);
                        reusedCount++;
                    } catch (Exception e) {
                        if (logger.isDebugEnabled()) {
                            logger.debug("[JEUS-PLUGIN] Failed to reuse interceptor id=" + existingId
                                    + " for " + className + "." + methodName, e);
                        }
                    }
                } else {
                    // ─── 최초 로드 또는 핫 디플로이로 새로 추가된 메서드 ───
                    try {
                        int newId = method.addInterceptor(HimedMethodInterceptor.class);
                        methodIds.put(sig, newId);
                        addedCount++;
                    } catch (Exception e) {
                        if (logger.isDebugEnabled()) {
                            logger.debug("[JEUS-PLUGIN] Failed to add interceptor to "
                                    + className + "." + methodName, e);
                        }
                    }
                }
            }

            if (!isHotDeploy) {
                classMethodIdMap.put(className, methodIds);
            }

            logger.info("[JEUS-PLUGIN] " + className
                    + ": new_ids=" + addedCount
                    + ", reused_ids=" + reusedCount
                    + " [hot-deploy=" + isHotDeploy + "]");

            return target.toBytecode();
        }

        /**
         * 메서드 고유 식별자: 이름 + 파라미터 타입 목록
         * ex) "doSomething(java.lang.String,int)"
         */
        private static String buildMethodSig(InstrumentMethod method) {
            String[] paramTypes = method.getParameterTypes();
            if (paramTypes == null || paramTypes.length == 0) {
                return method.getName() + "()";
            }
            StringBuilder sb = new StringBuilder(method.getName()).append('(');
            for (int i = 0; i < paramTypes.length; i++) {
                if (i > 0) sb.append(',');
                sb.append(paramTypes[i]);
            }
            return sb.append(')').toString();
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
    public void setTransformTemplate(MatchableTransformTemplate transformTemplate) {
        this.transformTemplate = transformTemplate;
    }
}
