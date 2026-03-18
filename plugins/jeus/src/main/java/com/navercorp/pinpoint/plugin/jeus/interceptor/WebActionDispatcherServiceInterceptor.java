package com.navercorp.pinpoint.plugin.jeus.interceptor;

import com.navercorp.pinpoint.bootstrap.context.MethodDescriptor;
import com.navercorp.pinpoint.bootstrap.context.SpanEventRecorder;
import com.navercorp.pinpoint.bootstrap.context.SpanRecorder;
import com.navercorp.pinpoint.bootstrap.context.Trace;
import com.navercorp.pinpoint.bootstrap.context.TraceContext;
import com.navercorp.pinpoint.bootstrap.context.TraceId;
import com.navercorp.pinpoint.bootstrap.interceptor.AroundInterceptor;
import com.navercorp.pinpoint.bootstrap.logging.PLogger;
import com.navercorp.pinpoint.bootstrap.logging.PLoggerFactory;
import com.navercorp.pinpoint.common.trace.AnnotationKey;
import com.navercorp.pinpoint.plugin.jeus.JeusConfiguration;
import com.navercorp.pinpoint.plugin.jeus.JeusConfigurationHolder;
import com.navercorp.pinpoint.plugin.jeus.JeusConstants;

import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class WebActionDispatcherServiceInterceptor implements AroundInterceptor {
    private final PLogger logger = PLoggerFactory.getLogger(this.getClass());
    private final TraceContext traceContext;
    private final MethodDescriptor descriptor;

    // 로그 throttle: 반복 가능한 warn 로그를 10초에 1회로 제한 (로그 폭발 방지)
    private static final long LOG_THROTTLE_MS = 10_000L;
    private static final AtomicLong lastStaleTraceLogTime = new AtomicLong(0);

    // ClassLoader별 Request Method 캐싱
    // String(className) 키: Class<?> strong reference로 인한 ClassLoader GC 차단 방지
    // hot deploy 시 동일 className으로 put → 이전 MethodCache(old Method → old Class) 교체 → GC 가능
    private static final ConcurrentHashMap<String, MethodCache> methodCacheMap =
            new ConcurrentHashMap<String, MethodCache>();

    // Response.getStatus() 캐시: String 키로 ClassLoader 누수 방지
    private static final ConcurrentHashMap<String, Method> statusMethodCache =
            new ConcurrentHashMap<String, Method>();

    // 재진입 감지: 동일 스레드에서 WebActionDispatcher.service()가 중첩 호출될 때
    // 첫 번째 trace를 stale로 잘못 인식하여 강제 close하는 것을 방지
    private static final ThreadLocal<int[]> dispatchDepth = new ThreadLocal<int[]>();

    // recordUriTemplate 지원 방식 캐시 (Pinpoint 버전마다 위치 다름)
    // 0: 미확인, 1: Trace에서 지원, 2: SpanRecorder에서 지원, 3: 미지원
    private static volatile int uriTemplateMode = 0;
    private static volatile Method uriTemplateMethod = null;

    private static final String ATTR_REQUEST_URI = "pinpoint.jeus.requestUri";
    private static final String ATTR_SERVER_PORT = "pinpoint.jeus.serverPort";

    public WebActionDispatcherServiceInterceptor(TraceContext traceContext, MethodDescriptor descriptor) {
        this.traceContext = traceContext;
        this.descriptor = descriptor;
    }

    /**
     * Pinpoint 분산 트레이싱 헤더를 읽어 Trace를 생성.
     *
     * - Pinpoint-TraceID 헤더 있음: continueTraceObject() - 기존 트랜잭션 이어받기
     *   (다른 서비스에서 호출된 경우, 동일 트랜잭션 ID로 연결됨)
     * - Pinpoint-TraceID 헤더 없음: newTraceObject() - 새 트랜잭션 시작
     *
     * 필수 헤더:
     *   Pinpoint-TraceID  : 전역 트랜잭션 ID
     *   Pinpoint-SpanID   : 이 Span에 할당된 ID (호출자가 생성)
     *   Pinpoint-pSpanID  : 호출자(부모) Span ID
     *   Pinpoint-Flags    : 샘플링 플래그
     */
    private Trace createTrace(Object request, MethodCache cache) {
        String transactionId = invokeStringMethodWithParam(cache.getHeader, request, "Pinpoint-TraceID");

        if (transactionId != null && !transactionId.isEmpty()) {
            try {
                String spanIdStr = invokeStringMethodWithParam(cache.getHeader, request, "Pinpoint-SpanID");
                String parentSpanIdStr = invokeStringMethodWithParam(cache.getHeader, request, "Pinpoint-pSpanID");
                String flagsStr = invokeStringMethodWithParam(cache.getHeader, request, "Pinpoint-Flags");

                long spanId = spanIdStr != null ? Long.parseLong(spanIdStr) : -1L;
                long parentSpanId = parentSpanIdStr != null ? Long.parseLong(parentSpanIdStr) : -1L;
                short flags = flagsStr != null ? Short.parseShort(flagsStr) : 0;

                TraceId traceId = traceContext.createTraceId(transactionId, parentSpanId, spanId, flags);

                if (logger.isDebugEnabled()) {
                    logger.debug("[JEUS-PLUGIN] Continuing distributed trace. transactionId=" + transactionId
                            + ", spanId=" + spanId + ", parentSpanId=" + parentSpanId);
                }

                return traceContext.continueTraceObject(traceId);
            } catch (Exception e) {
                if (logger.isWarnEnabled()) {
                    logger.warn("[JEUS-PLUGIN] Failed to continue trace, starting new trace. error=" + e.getMessage());
                }
            }
        }

        // DisableTrace는 currentRawTraceObject()로 감지되지 않는 버전이 있어,
        // newTraceObject() 호출 전 factory 내부 스토리지를 강제 정리한다.
        traceContext.removeTraceObject();

        try {
            return traceContext.newTraceObject();
        } catch (Exception e) {
            traceContext.removeTraceObject();
            if (logger.isWarnEnabled()) {
                logger.warn("[JEUS-PLUGIN] newTraceObject failed, request will not be traced. error=" + e.getMessage());
            }
            return null;
        }
    }

    @Override
    public void before(Object target, Object[] args) {
        if (args == null || args.length < 2) {
            return;
        }

        // 재진입 감지: depth counter 증가
        int[] depth = dispatchDepth.get();
        if (depth == null) {
            depth = new int[]{0};
            dispatchDepth.set(depth);
        }
        depth[0]++;

        if (depth[0] > 1) {
            // 재진입 호출 - 현재 trace를 stale로 잘못 인식하지 않도록 skip
            if (logger.isDebugEnabled()) {
                logger.debug("[JEUS-PLUGIN] Re-entrant WebActionDispatcher call (depth=" + depth[0] + "), skipping trace creation.");
            }
            return;
        }

        Object request = args[0];
        if (request == null) {
            return;
        }
        MethodCache cache = getMethodCache(request.getClass());
        String requestURI = invokeStringMethod(cache.getRequestURI, request);

        // excludeUrl 체크를 stale trace 정리보다 먼저 수행:
        // 제외 대상 URL이면 trace를 생성하지도, 기존 trace를 건드리지도 않고 즉시 반환
        JeusConfiguration config = JeusConfigurationHolder.getConfiguration();
        if (config != null && requestURI != null && config.getJeusExcludeUrlFilter().filter(requestURI)) {
            return;
        }

        Trace staleTrace = traceContext.currentRawTraceObject();
        if (staleTrace != null) {
            // depth==1이 확인된 상태이므로 현재 trace는 실제 stale trace
            if (shouldLogThrottled(lastStaleTraceLogTime)) {
                logger.warn("[JEUS-PLUGIN] Stale trace detected at request start. Force removing. (throttled 10s)");
            }
            try {
                staleTrace.close();  // ActiveTraceRepository에서 해제
            } catch (Throwable t) {
                logger.warn("[JEUS-PLUGIN] staleTrace.close() failed", t);
            }
            traceContext.removeTraceObject();
        }

        Trace trace = createTrace(request, cache);
        if (trace == null) {
            return;
        }

        try {
            if (trace.canSampled()) {
                try {
                    // 루트 Span 데이터 기록 (SpanRecorder)
                    SpanRecorder spanRecorder = trace.getSpanRecorder();
                    spanRecorder.recordServiceType(JeusConstants.JEUS);
                    spanRecorder.recordApi(descriptor);
                    spanRecorder.recordRpcName(requestURI != null ? requestURI : "/");

                    String serverName = invokeStringMethod(cache.getServerName, request);
                    int serverPort = invokeIntMethod(cache.getServerPort, request, 80);

                    StringBuilder sb = new StringBuilder(64);
                    sb.append(serverName != null ? serverName : "").append(':').append(serverPort);
                    spanRecorder.recordEndPoint(sb.toString());
                    spanRecorder.recordRemoteAddress(invokeStringMethod(cache.getRemoteAddr, request));

                    // AcceptorHost: Host 헤더 우선, 없으면 localName:port
                    String hostHeader = invokeStringMethodWithParam(cache.getHeader, request, "Host");
                    String acceptorHost;
                    if (hostHeader != null && !hostHeader.isEmpty()) {
                        acceptorHost = hostHeader;
                    } else {
                        sb.setLength(0);
                        sb.append(invokeStringMethod(cache.getLocalName, request)).append(':').append(serverPort);
                        acceptorHost = sb.toString();
                    }
                    spanRecorder.recordAcceptorHost(acceptorHost);

                    // 부모 앱 정보 (분산 트레이싱에서 continueTraceObject인 경우 표시)
                    String parentAppName = invokeStringMethodWithParam(cache.getHeader, request, "Pinpoint-pAppName");
                    String parentAppTypeStr = invokeStringMethodWithParam(cache.getHeader, request, "Pinpoint-pAppType");
                    if (parentAppName != null && !parentAppName.isEmpty() && parentAppTypeStr != null) {
                        try {
                            spanRecorder.recordParentApplication(parentAppName, Short.parseShort(parentAppTypeStr));
                        } catch (NumberFormatException ignore) {
                            // ignore malformed header
                        }
                    }

                    // after()에서 재사용하기 위해 request attribute에 캐싱
                    invokeSetAttribute(cache.setAttribute, request, ATTR_REQUEST_URI, requestURI);
                    invokeSetAttribute(cache.setAttribute, request, ATTR_SERVER_PORT, serverPort);

                    if (logger.isDebugEnabled()) {
                        logger.debug("[JEUS-PLUGIN] AcceptorHost: " + acceptorHost);
                    }
                } catch (Throwable t) {
                    if (logger.isWarnEnabled()) {
                        logger.warn("[JEUS-PLUGIN] BEFORE. Caused: " + t.getMessage(), t);
                    }
                }
            }

            // traceBlockBegin은 canSampled 여부와 무관하게 항상 호출
            // → after()의 traceBlockEnd와 쌍을 맞춤
            trace.traceBlockBegin();
        } catch (Throwable t) {
            // traceBlockBegin() 실패 시 after()에서 traceBlockEnd 쌍 불일치를 막기 위해
            // 여기서 직접 trace를 정리한다.
            if (logger.isWarnEnabled()) {
                logger.warn("[JEUS-PLUGIN] BEFORE setup failed, cleaning up trace. Caused: " + t.getMessage(), t);
            }
            try {
                trace.close();
            } catch (Throwable ignore) {
            }
            traceContext.removeTraceObject();
        }
    }

    @Override
    public void after(Object target, Object[] args, Object result, Throwable throwable) {
        // 재진입 depth 감소: before()에서 재진입으로 return된 경우에도 after()는 항상 호출됨
        int[] depth = dispatchDepth.get();
        if (depth != null) {
            depth[0]--;
            if (depth[0] > 0) {
                // 재진입에 대한 after() - before()에서 trace를 만들지 않았으므로 skip
                return;
            }
            // depth <= 0: 정상(0) 또는 비정상 잔류 음수값 → 모두 ThreadLocal 정리
            dispatchDepth.remove();
            if (depth[0] < 0) {
                // before() 없이 after()가 호출된 비정상 상태 → trace 처리 건너뜀
                return;
            }
        }

        Trace trace = traceContext.currentRawTraceObject();
        if (trace == null) {
            return;
        }

        try {
            if (trace.canSampled()) {
                SpanEventRecorder recorder = trace.currentSpanEventRecorder();
                recorder.recordServiceType(JeusConstants.JEUS_METHOD);
                recorder.recordApi(descriptor);

                if (throwable != null) {
                    // SpanEvent 레벨: 콜스택 상세 뷰에서 해당 span에 에러 표시
                    recorder.recordException(throwable);
                    // Span(루트) 레벨: 트랜잭션 목록 뷰에서 해당 요청을 에러 트랜잭션으로 표시
                    // → 두 곳에 기록하는 것이 의도된 동작 (목록+상세 양쪽에서 에러 식별 가능)
                    trace.getSpanRecorder().recordException(throwable);
                }

                if (args != null && args.length > 0 && args[0] != null) {
                    Object request = args[0];
                    MethodCache cache = getMethodCache(request.getClass());

                    // before에서 캐싱한 값 재사용
                    Object cachedUri = invokeGetAttribute(cache.getAttribute, request, ATTR_REQUEST_URI);
                    String requestURI = cachedUri instanceof String
                            ? (String) cachedUri
                            : invokeStringMethod(cache.getRequestURI, request);

                    // JEUS 특화 URI 템플릿 (target/method 또는 business_id/submit_id)
                    // fallback: 파라미터 기반 템플릿이 없으면 requestURI에서 추출
                    String uriTemplate = buildUriTemplate(request, cache);
                    if (uriTemplate == null) {
                        uriTemplate = extractUriTemplate(requestURI);
                    }
                    if (uriTemplate != null) {
                        invokeSetAttribute(cache.setAttribute, request, "pinpoint.metric.uri-template", uriTemplate);
                        recordUriTemplate(trace, uriTemplate);
                        recorder.recordAttribute(AnnotationKey.HTTP_PARAM, "UriTemplate=" + uriTemplate);
                    }

                    JeusConfiguration config = JeusConfigurationHolder.getConfiguration();
                    if (config != null && config.isJeusTraceRequestParam()) {
                        String params = invokeStringMethod(cache.getQueryString, request);
                        if (params != null && !params.isEmpty()) {
                            recorder.recordAttribute(AnnotationKey.HTTP_PARAM_ENTITY, params);
                        }
                    }

                    recorder.recordAttribute(AnnotationKey.HTTP_URL, requestURI);

                    if (args.length > 1) {
                        int statusCode = throwable != null ? 500 : getStatus(args[1]);
                        if (statusCode <= 0) statusCode = 200;
                        recorder.recordAttribute(AnnotationKey.HTTP_STATUS_CODE, statusCode);
                    }
                }
            } else if (throwable != null) {
                // 비샘플링이더라도 예외는 SpanRecorder에 기록
                trace.getSpanRecorder().recordException(throwable);
            }
        } catch (Throwable t) {
            if (logger.isWarnEnabled()) {
                logger.warn("[JEUS-PLUGIN] AFTER. Caused: " + t.getMessage(), t);
            }
        } finally {
            // 각 단계에서 예외가 발생해도 removeTraceObject()는 반드시 호출되도록 보장
            try {
                trace.traceBlockEnd();
            } catch (Throwable t) {
                if (logger.isWarnEnabled()) {
                    logger.warn("[JEUS-PLUGIN] traceBlockEnd failed: " + t.getMessage(), t);
                }
            }
            try {
                trace.close();
            } catch (Throwable t) {
                if (logger.isWarnEnabled()) {
                    logger.warn("[JEUS-PLUGIN] trace.close() failed: " + t.getMessage(), t);
                }
            }
            traceContext.removeTraceObject();
        }
    }

    /**
     * JEUS 특화 URI 템플릿 추출.
     *
     * 우선순위:
     * 1. target+method 파라미터 → /target/method
     * 2. business_id+submit_id 파라미터 → /businessId/submitId
     * 3. .xfdl 요청 → 파일명에서 폼 ID 추출 (예: SPZUM00400_암호확인.xfdl → /xpapps/SPZUM00400)
     * 4. null (caller에서 requestURI fallback)
     */
    private String buildUriTemplate(Object request, MethodCache cache) {
        String targetParam = invokeStringMethodWithParam(cache.getParameter, request, "target");
        String methodParam = invokeStringMethodWithParam(cache.getParameter, request, "method");
        if (targetParam != null && !targetParam.isEmpty() && methodParam != null && !methodParam.isEmpty()) {
            return '/' + targetParam + '/' + methodParam;
        }

        String businessId = invokeStringMethodWithParam(cache.getParameter, request, "business_id");
        String submitId = invokeStringMethodWithParam(cache.getParameter, request, "submit_id");
        if (businessId != null && !businessId.isEmpty() && submitId != null && !submitId.isEmpty()) {
            return '/' + businessId + '/' + submitId;
        }

        return null;
    }

    /**
     * requestURI에서 URI 템플릿을 추출.
     *
     * URL-encoded 문자를 디코딩하여 한글 등이 원본 그대로 표시되도록 한다.
     * 예: /himed2/xpapps/com/.../SPZUM00400_%EC%95%94%ED%98%B8%ED%99%95%EC%9D%B8.xfdl
     *   → /himed2/xpapps/com/.../SPZUM00400_암호확인.xfdl
     */
    private static String extractUriTemplate(String requestURI) {
        if (requestURI == null) {
            return null;
        }
        try {
            return java.net.URLDecoder.decode(requestURI, "UTF-8");
        } catch (Exception e) {
            return requestURI;
        }
    }

    private static boolean shouldLogThrottled(AtomicLong lastLogTime) {
        long now = System.currentTimeMillis();
        long last = lastLogTime.get();
        return now - last >= LOG_THROTTLE_MS && lastLogTime.compareAndSet(last, now);
    }

    // --- ClassLoader별 Method 캐싱 ---

    private MethodCache getMethodCache(Class<?> clazz) {
        String key = clazz.getName();
        MethodCache cache = methodCacheMap.get(key);
        if (cache != null && cache.targetClass == clazz) {
            return cache;
        }
        // 첫 접근 또는 ClassLoader 변경(hot deploy) → 새 캐시 생성, 이전 엔트리 교체
        // 두 스레드가 동시에 새 MethodCache를 생성할 수 있으나 (benign race):
        // - MethodCache는 불변(all final) → 어느 인스턴스든 동일하게 동작
        // - hot deploy 전환 시 양쪽 모두 new Class 기준으로 생성 → 최종 결과 동일
        // - 최악의 경우 리플렉션 1회 중복뿐, 정합성 문제 없음
        MethodCache newCache = new MethodCache(clazz);
        methodCacheMap.put(key, newCache);
        return newCache;
    }

    /**
     * Request/Response 클래스별 Method 캐시
     */
    private static class MethodCache {
        final Class<?> targetClass;  // identity check: hot deploy 시 ClassLoader 변경 감지용
        final Method getRequestURI;
        final Method getParameter;
        final Method getHeader;
        final Method getServerName;
        final Method getServerPort;
        final Method getRemoteAddr;
        final Method getLocalName;
        final Method getQueryString;
        final Method setAttribute;
        final Method getAttribute;

        MethodCache(Class<?> clazz) {
            this.targetClass = clazz;
            this.getRequestURI = findMethod(clazz, "getRequestURI");
            this.getParameter = findMethod(clazz, "getParameter", String.class);
            this.getHeader = findMethod(clazz, "getHeader", String.class);
            this.getServerName = findMethod(clazz, "getServerName");
            this.getServerPort = findMethod(clazz, "getServerPort");
            this.getRemoteAddr = findMethod(clazz, "getRemoteAddr");
            this.getLocalName = findMethod(clazz, "getLocalName");
            this.getQueryString = findMethod(clazz, "getQueryString");
            this.setAttribute = findMethod(clazz, "setAttribute", String.class, Object.class);
            this.getAttribute = findMethod(clazz, "getAttribute", String.class);
        }

        private static final PLogger findMethodLogger = PLoggerFactory.getLogger(MethodCache.class);

        private static Method findMethod(Class<?> clazz, String name, Class<?>... paramTypes) {
            try {
                return clazz.getMethod(name, paramTypes);
            } catch (Exception e) {
                if (findMethodLogger.isDebugEnabled()) {
                    findMethodLogger.debug("[JEUS-PLUGIN] MethodCache: " + name + " not found in " + clazz.getName());
                }
                return null;
            }
        }
    }

    // --- 리플렉션 헬퍼 메서드 ---

    private String invokeStringMethod(Method method, Object target) {
        if (method == null) return null;
        try {
            return (String) method.invoke(target);
        } catch (Exception e) {
            if (logger.isDebugEnabled()) {
                logger.debug("[JEUS-PLUGIN] invokeStringMethod failed: " + method.getName(), e);
            }
            return null;
        }
    }

    private String invokeStringMethodWithParam(Method method, Object target, String param) {
        if (method == null) return null;
        try {
            return (String) method.invoke(target, param);
        } catch (Exception e) {
            if (logger.isDebugEnabled()) {
                logger.debug("[JEUS-PLUGIN] invokeStringMethodWithParam failed: " + method.getName() + "(" + param + ")", e);
            }
            return null;
        }
    }

    private int invokeIntMethod(Method method, Object target, int defaultValue) {
        if (method == null) return defaultValue;
        try {
            Object result = method.invoke(target);
            return result instanceof Integer ? (Integer) result : defaultValue;
        } catch (Exception e) {
            if (logger.isDebugEnabled()) {
                logger.debug("[JEUS-PLUGIN] invokeIntMethod failed: " + method.getName(), e);
            }
            return defaultValue;
        }
    }

    private void invokeSetAttribute(Method method, Object target, String name, Object value) {
        if (method == null) return;
        try {
            method.invoke(target, name, value);
        } catch (Exception e) {
            if (logger.isDebugEnabled()) {
                logger.debug("[JEUS-PLUGIN] invokeSetAttribute failed: " + name, e);
            }
        }
    }

    private Object invokeGetAttribute(Method method, Object target, String name) {
        if (method == null) return null;
        try {
            return method.invoke(target, name);
        } catch (Exception e) {
            if (logger.isDebugEnabled()) {
                logger.debug("[JEUS-PLUGIN] invokeGetAttribute failed: " + name, e);
            }
            return null;
        }
    }

    private int getStatus(Object response) {
        if (response == null) return 0;
        try {
            Class<?> clazz = response.getClass();
            String key = clazz.getName();
            Method method = statusMethodCache.get(key);
            // ClassLoader 변경 감지: hot deploy 시 이전 Method는 새 ClassLoader 인스턴스에서 사용 불가
            if (method == null || method.getDeclaringClass().getClassLoader() != clazz.getClassLoader()) {
                try {
                    method = clazz.getMethod("getStatus");
                    statusMethodCache.put(key, method);
                } catch (Exception e) {
                    return 0;
                }
            }
            Object result = method.invoke(response);
            return result instanceof Integer ? (Integer) result : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private void recordUriTemplate(Trace trace, String uriTemplate) {
        // 최초 1회만 탐색 후 mode에 캐싱 (이후 호출에서 getMethod() 생략)
        int mode = uriTemplateMode;
        if (mode == 0) {
            synchronized (WebActionDispatcherServiceInterceptor.class) {
                mode = uriTemplateMode;
                if (mode == 0) {
                    try {
                        uriTemplateMethod = trace.getClass().getMethod("recordUriTemplate", String.class);
                        uriTemplateMode = 1;
                        mode = 1;
                    } catch (Exception e) {
                        try {
                            SpanRecorder sr = trace.getSpanRecorder();
                            uriTemplateMethod = sr.getClass().getMethod("recordUriTemplate", String.class);
                            uriTemplateMode = 2;
                            mode = 2;
                        } catch (Exception ex) {
                            uriTemplateMode = 3; // 미지원
                            mode = 3;
                        }
                    }
                }
            }
        }
        if (mode == 3 || uriTemplateMethod == null) return;
        try {
            if (mode == 1) {
                uriTemplateMethod.invoke(trace, uriTemplate);
            } else if (mode == 2) {
                uriTemplateMethod.invoke(trace.getSpanRecorder(), uriTemplate);
            }
        } catch (Exception e) {
            // ignore - Pinpoint 버전에 따라 미지원
        }
    }
}
