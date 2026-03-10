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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class WebActionDispatcherServiceInterceptor implements AroundInterceptor {
    private final PLogger logger = PLoggerFactory.getLogger(this.getClass());
    private final TraceContext traceContext;
    private final MethodDescriptor descriptor;

    // ClassLoader별 Method 캐싱 (멀티 ClassLoader 환경 대응)
    private static final Map<Class<?>, MethodCache> methodCacheMap = new ConcurrentHashMap<Class<?>, MethodCache>();

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

        return traceContext.newTraceObject();
    }

    @Override
    public void before(Object target, Object[] args) {
        if (args == null || args.length < 2) {
            return;
        }

        Trace staleTrace = traceContext.currentTraceObject();
        if (staleTrace != null) {
            logger.warn("[JEUS-PLUGIN] Stale trace detected at request start. Force removing.");
            try {
                staleTrace.close();  // ActiveTraceRepository에서 해제
            } catch (Throwable t) {
                logger.warn("[JEUS-PLUGIN] staleTrace.close() failed", t);
            }
            traceContext.removeTraceObject();
        }

        Object request = args[0];
        MethodCache cache = getMethodCache(request.getClass());
        String requestURI = invokeStringMethod(cache.getRequestURI, request);

        JeusConfiguration config = JeusConfigurationHolder.getConfiguration();
        if (config != null && requestURI != null && config.getJeusExcludeUrlFilter().filter(requestURI)) {
            return;
        }

        Trace trace = createTrace(request, cache);
        if (trace == null) {
            return;
        }

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
    }

    @Override
    public void after(Object target, Object[] args, Object result, Throwable throwable) {
        Trace trace = traceContext.currentTraceObject();
        if (trace == null) {
            return;
        }

        try {
            if (trace.canSampled()) {
                SpanEventRecorder recorder = trace.currentSpanEventRecorder();
                recorder.recordServiceType(JeusConstants.JEUS_METHOD);
                recorder.recordApi(descriptor);

                if (throwable != null) {
                    recorder.recordException(throwable);
                    // 트랜잭션 레벨 에러도 SpanRecorder에 기록
                    trace.getSpanRecorder().recordException(throwable);
                }

                if (args != null && args.length > 0) {
                    Object request = args[0];
                    MethodCache cache = getMethodCache(request.getClass());

                    // before에서 캐싱한 값 재사용
                    Object cachedUri = invokeGetAttribute(cache.getAttribute, request, ATTR_REQUEST_URI);
                    String requestURI = cachedUri instanceof String
                            ? (String) cachedUri
                            : invokeStringMethod(cache.getRequestURI, request);

                    // JEUS 특화 URI 템플릿 (target/method 또는 business_id/submit_id)
                    String uriTemplate = buildUriTemplate(request, cache);
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
     * target+method 파라미터 또는 business_id+submit_id 파라미터 조합.
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

    // --- ClassLoader별 Method 캐싱 ---

    private MethodCache getMethodCache(Class<?> clazz) {
        MethodCache cache = methodCacheMap.get(clazz);
        if (cache == null) {
            cache = new MethodCache(clazz);
            MethodCache existing = methodCacheMap.putIfAbsent(clazz, cache);
            if (existing != null) {
                cache = existing;
            }
        }
        return cache;
    }

    /**
     * Request/Response 클래스별 Method 캐시
     */
    private static class MethodCache {
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

        private static Method findMethod(Class<?> clazz, String name, Class<?>... paramTypes) {
            try {
                return clazz.getMethod(name, paramTypes);
            } catch (Exception e) {
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
            return null;
        }
    }

    private String invokeStringMethodWithParam(Method method, Object target, String param) {
        if (method == null) return null;
        try {
            return (String) method.invoke(target, param);
        } catch (Exception e) {
            return null;
        }
    }

    private int invokeIntMethod(Method method, Object target, int defaultValue) {
        if (method == null) return defaultValue;
        try {
            Object result = method.invoke(target);
            return result instanceof Integer ? (Integer) result : defaultValue;
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private void invokeSetAttribute(Method method, Object target, String name, Object value) {
        if (method == null) return;
        try {
            method.invoke(target, name, value);
        } catch (Exception e) {
            // ignore
        }
    }

    private Object invokeGetAttribute(Method method, Object target, String name) {
        if (method == null) return null;
        try {
            return method.invoke(target, name);
        } catch (Exception e) {
            return null;
        }
    }

    private int getStatus(Object response) {
        if (response == null) return 0;
        try {
            Method method = response.getClass().getMethod("getStatus");
            Object result = method.invoke(response);
            return result instanceof Integer ? (Integer) result : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private void recordUriTemplate(Trace trace, String uriTemplate) {
        try {
            Method method = trace.getClass().getMethod("recordUriTemplate", String.class);
            method.invoke(trace, uriTemplate);
        } catch (Exception e) {
            try {
                SpanRecorder spanRecorder = trace.getSpanRecorder();
                Method method = spanRecorder.getClass().getMethod("recordUriTemplate", String.class);
                method.invoke(spanRecorder, uriTemplate);
            } catch (Exception ex) {
                // ignore - Pinpoint 버전에 따라 미지원
            }
        }
    }
}
