package com.navercorp.pinpoint.plugin.jeus.interceptor;

import com.navercorp.pinpoint.bootstrap.context.MethodDescriptor;
import com.navercorp.pinpoint.bootstrap.context.SpanEventRecorder;
import com.navercorp.pinpoint.bootstrap.context.SpanRecorder;
import com.navercorp.pinpoint.bootstrap.context.Trace;
import com.navercorp.pinpoint.bootstrap.context.TraceContext;
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

    // request attribute key for caching values between before/after
    private static final String ATTR_REQUEST_URI = "pinpoint.jeus.requestUri";
    private static final String ATTR_SERVER_PORT = "pinpoint.jeus.serverPort";

    public WebActionDispatcherServiceInterceptor(TraceContext traceContext, MethodDescriptor descriptor) {
        this.traceContext = traceContext;
        this.descriptor = descriptor;
    }

    @Override
    public void before(Object target, Object[] args) {
        if (args == null || args.length < 2) {
            return;
        }

        JeusConfiguration config = JeusConfigurationHolder.getConfiguration();
        Object request = args[0];
        MethodCache cache = getMethodCache(request.getClass());

        String requestURI = invokeStringMethod(cache.getRequestURI, request);

        if (config != null && requestURI != null && config.getJeusExcludeUrlFilter().filter(requestURI)) {
            return;
        }

        // 기존 Trace 제거
        traceContext.removeTraceObject();

        // 항상 새로운 Trace 생성
        Trace trace = traceContext.newTraceObject();
        if (trace == null) {
            return;
        }

        // 헤더에서 부모 정보 읽기
        String transactionId = invokeStringMethodWithParam(cache.getHeader, request, "Pinpoint-TraceID");

        if (logger.isDebugEnabled()) {
            logger.debug("[JEUS-PLUGIN] Pinpoint-TraceID header: " + transactionId);
        }

        // Trace 시작
        trace.traceBlockBegin();

        try {
            SpanRecorder recorder = trace.getSpanRecorder();
            recorder.recordServiceType(JeusConstants.JEUS);
            recorder.recordApi(descriptor);
            recorder.recordRpcName(requestURI);

            // 값 캐싱 (after에서 재사용)
            String serverName = invokeStringMethod(cache.getServerName, request);
            int serverPort = invokeIntMethod(cache.getServerPort, request, 80);

            // request attribute에 캐싱
            invokeSetAttribute(cache.setAttribute, request, ATTR_REQUEST_URI, requestURI);
            invokeSetAttribute(cache.setAttribute, request, ATTR_SERVER_PORT, serverPort);

            // 문자열 연결 최적화
            StringBuilder sb = new StringBuilder(64);
            sb.append(serverName).append(':').append(serverPort);
            recorder.recordEndPoint(sb.toString());

            recorder.recordRemoteAddress(invokeStringMethod(cache.getRemoteAddr, request));

            // AcceptorHost 설정
            String hostHeader = invokeStringMethodWithParam(cache.getHeader, request, "Host");
            String acceptorHost;
            if (hostHeader != null && !hostHeader.isEmpty()) {
                acceptorHost = hostHeader;
            } else {
                sb.setLength(0);
                sb.append(invokeStringMethod(cache.getLocalName, request)).append(':').append(serverPort);
                acceptorHost = sb.toString();
            }
            recorder.recordAcceptorHost(acceptorHost);

            if (logger.isDebugEnabled()) {
                logger.debug("[JEUS-PLUGIN] Final AcceptorHost: " + acceptorHost);
            }

            // 부모 정보가 있으면 설정
            if (transactionId != null) {
                String parentAppName = invokeStringMethodWithParam(cache.getHeader, request, "Pinpoint-pAppName");
                String parentAppType = invokeStringMethodWithParam(cache.getHeader, request, "Pinpoint-pAppType");

                if (parentAppName != null && parentAppType != null) {
                    recorder.recordParentApplication(parentAppName, Short.parseShort(parentAppType));

                    if (logger.isDebugEnabled()) {
                        logger.debug("[JEUS-PLUGIN] Parent App: " + parentAppName);
                    }
                }
            }
        } catch (Throwable t) {
            if (logger.isWarnEnabled()) {
                logger.warn("BEFORE. Caused: " + t.getMessage(), t);
            }
        }
    }

    @Override
    public void after(Object target, Object[] args, Object result, Throwable throwable) {
        Trace trace = traceContext.currentTraceObject();
        if (trace == null) {
            return;
        }

        JeusConfiguration config = JeusConfigurationHolder.getConfiguration();

        try {
            if (trace.canSampled()) {
                SpanEventRecorder recorder = trace.currentSpanEventRecorder();
                recorder.recordServiceType(JeusConstants.JEUS_METHOD);
                recorder.recordApi(descriptor);

                if (throwable != null) {
                    recorder.recordException(throwable);
                }

                if (args != null && args.length > 0) {
                    Object request = args[0];
                    MethodCache cache = getMethodCache(request.getClass());

                    // before에서 캐싱한 값 재사용
                    Object cachedUri = invokeGetAttribute(cache.getAttribute, request, ATTR_REQUEST_URI);
                    String requestURI = cachedUri instanceof String ? (String) cachedUri : invokeStringMethod(cache.getRequestURI, request);

                    String targetParam = invokeStringMethodWithParam(cache.getParameter, request, "target");
                    String methodParam = invokeStringMethodWithParam(cache.getParameter, request, "method");

                    String uriTemplate = null;
                    if (targetParam != null && !targetParam.isEmpty() && methodParam != null && !methodParam.isEmpty()) {
                        StringBuilder sb = new StringBuilder(64);
                        sb.append('/').append(targetParam).append('/').append(methodParam);
                        uriTemplate = sb.toString();
                    } else {
                        String businessId = invokeStringMethodWithParam(cache.getParameter, request, "business_id");
                        String submitId = invokeStringMethodWithParam(cache.getParameter, request, "submit_id");
                        if (businessId != null && !businessId.isEmpty() && submitId != null && !submitId.isEmpty()) {
                            StringBuilder sb = new StringBuilder(64);
                            sb.append('/').append(businessId).append('/').append(submitId);
                            uriTemplate = sb.toString();
                        }
                    }

                    if (uriTemplate != null) {
                        invokeSetAttribute(cache.setAttribute, request, "pinpoint.metric.uri-template", uriTemplate);
                        recordUriTemplate(trace, uriTemplate);

                        recorder.recordAttribute(AnnotationKey.HTTP_PARAM, "UriTemplate=" + uriTemplate);
                    }

                    if (config != null && config.isJeusTraceRequestParam()) {
                        String params = invokeStringMethod(cache.getQueryString, request);
                        if (params != null && !params.isEmpty()) {
                            recorder.recordAttribute(AnnotationKey.HTTP_PARAM_ENTITY, params);
                        }
                    }

                    recorder.recordAttribute(AnnotationKey.HTTP_URL, requestURI);

                    if (args.length > 1) {
                        Object response = args[1];
                        int statusCode = getStatus(response);

                        if (throwable != null) {
                            statusCode = 500;
                        } else if (statusCode <= 0) {
                            statusCode = 200;
                        }

                        recorder.recordAttribute(AnnotationKey.HTTP_STATUS_CODE, statusCode);
                    }
                }
            }

            // SpanRecorder에도 Exception 기록 (트랜잭션 레벨 에러 표시)
            if (throwable != null) {
                SpanRecorder spanRecorder = trace.getSpanRecorder();
                spanRecorder.recordException(throwable);

                if (logger.isDebugEnabled()) {
                    logger.debug("[JEUS-PLUGIN] Exception recorded to SpanRecorder: " + throwable.getClass().getName());
                }
            }
        } catch (Throwable t) {
            if (logger.isWarnEnabled()) {
                logger.warn("AFTER. Caused: " + t.getMessage(), t);
            }
        } finally {
            try {
                trace.traceBlockEnd();
            } catch (Throwable t) {
                if (logger.isWarnEnabled()) {
                    logger.warn("traceBlockEnd failed. Caused: " + t.getMessage(), t);
                }
            }

            if (trace.isRoot()) {
                trace.close();
                traceContext.removeTraceObject();
            }
        }
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

    // --- 리플렉션 헬퍼 메소드 ---

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
                // ignore
            }
        }
    }
}
