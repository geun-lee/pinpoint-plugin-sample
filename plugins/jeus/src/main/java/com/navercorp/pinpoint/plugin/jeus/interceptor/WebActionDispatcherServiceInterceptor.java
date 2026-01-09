package com.navercorp.pinpoint.plugin.jeus.interceptor;

import com.navercorp.pinpoint.bootstrap.context.MethodDescriptor;
import com.navercorp.pinpoint.bootstrap.context.SpanEventRecorder;
import com.navercorp.pinpoint.bootstrap.context.SpanRecorder;
import com.navercorp.pinpoint.bootstrap.context.Trace;
import com.navercorp.pinpoint.bootstrap.context.TraceContext;
import com.navercorp.pinpoint.bootstrap.context.TraceId;
import com.navercorp.pinpoint.bootstrap.interceptor.AroundInterceptor;
import com.navercorp.pinpoint.common.trace.AnnotationKey;
import com.navercorp.pinpoint.plugin.jeus.JeusConfiguration;
import com.navercorp.pinpoint.plugin.jeus.JeusConfigurationHolder;
import com.navercorp.pinpoint.plugin.jeus.JeusConstants;

import java.lang.reflect.Method;

public class WebActionDispatcherServiceInterceptor implements AroundInterceptor {
    private final TraceContext traceContext;
    private final MethodDescriptor descriptor;

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
        if (config == null) {
            return;
        }

        Object request = args[0];
        String requestURI = getRequestURI(request);

        if (requestURI != null && config.getJeusExcludeUrlFilter().filter(requestURI)) {
            return;
        }

        Trace trace = readRequestTrace(request);
        
        if (trace.canSampled()) {
            SpanRecorder recorder = trace.getSpanRecorder();
            recorder.recordServiceType(JeusConstants.JEUS);
            recorder.recordApi(descriptor);
            recorder.recordRpcName(requestURI);
            recorder.recordEndPoint(getServerName(request) + ":" + getServerPort(request));
            recorder.recordRemoteAddress(getRemoteAddr(request));
            
            if (!trace.isRoot()) {
                String parentAppName = getHeader(request, "Pinpoint-pAppName");
                String parentAppType = getHeader(request, "Pinpoint-pAppType");
                if (parentAppName != null && parentAppType != null) {
                    recorder.recordParentApplication(parentAppName, Short.parseShort(parentAppType));
                }
            }
        }
        
        trace.traceBlockBegin();
    }

    @Override
    public void after(Object target, Object[] args, Object result, Throwable throwable) {
        Trace trace = traceContext.currentTraceObject();
        if (trace == null) {
            return;
        }

        JeusConfiguration config = JeusConfigurationHolder.getConfiguration();

        try {
            SpanEventRecorder recorder = trace.currentSpanEventRecorder();
            recorder.recordServiceType(JeusConstants.JEUS_METHOD);
            recorder.recordApi(descriptor);
            recorder.recordException(throwable);
            
            if (args != null && args.length > 0) {
                Object request = args[0];
                
                String targetParam = getParameter(request, "target");
                String methodParam = getParameter(request, "method");
                
                if (targetParam != null && !targetParam.isEmpty() && methodParam != null && !methodParam.isEmpty()) {
                    String uriTemplate = "/" + targetParam + "/" + methodParam;
                    // setAttribute는 생략하거나 리플렉션으로 구현
                    recorder.recordAttribute(AnnotationKey.HTTP_PARAM, "UriTemplate=" + uriTemplate);
                }
                
                if (config != null && config.isJeusTraceRequestParam()) {
                    String params = getQueryString(request);
                    if (params != null && !params.isEmpty()) {
                        recorder.recordAttribute(AnnotationKey.HTTP_PARAM_ENTITY, params);
                    }
                }
                
                recorder.recordAttribute(AnnotationKey.HTTP_URL, getRequestURI(request));
                recorder.recordAttribute(AnnotationKey.HTTP_STATUS_CODE, 200);
            }
        } finally {
            trace.traceBlockEnd();
            if (trace.isRoot()) {
                trace.close();
                traceContext.removeTraceObject();
            }
        }
    }

    // 리플렉션 헬퍼 메소드들
    private String getRequestURI(Object request) {
        try {
            Method method = request.getClass().getMethod("getRequestURI");
            return (String) method.invoke(request);
        } catch (Exception e) {
            return null;
        }
    }

    private String getParameter(Object request, String name) {
        try {
            Method method = request.getClass().getMethod("getParameter", String.class);
            return (String) method.invoke(request, name);
        } catch (Exception e) {
            return null;
        }
    }

    private String getHeader(Object request, String name) {
        try {
            Method method = request.getClass().getMethod("getHeader", String.class);
            return (String) method.invoke(request, name);
        } catch (Exception e) {
            return null;
        }
    }

    private String getServerName(Object request) {
        try {
            Method method = request.getClass().getMethod("getServerName");
            return (String) method.invoke(request);
        } catch (Exception e) {
            return "unknown";
        }
    }

    private int getServerPort(Object request) {
        try {
            Method method = request.getClass().getMethod("getServerPort");
            return (Integer) method.invoke(request);
        } catch (Exception e) {
            return 80;
        }
    }

    private String getRemoteAddr(Object request) {
        try {
            Method method = request.getClass().getMethod("getRemoteAddr");
            return (String) method.invoke(request);
        } catch (Exception e) {
            return "unknown";
        }
    }

    private String getQueryString(Object request) {
        try {
            Method method = request.getClass().getMethod("getQueryString");
            return (String) method.invoke(request);
        } catch (Exception e) {
            return null;
        }
    }

    private Trace readRequestTrace(Object request) {
        String transactionId = getHeader(request, "Pinpoint-TraceID");
        if (transactionId != null) {
            long parentSpanID = Long.parseLong(getHeader(request, "Pinpoint-pSpanID"));
            long spanID = Long.parseLong(getHeader(request, "Pinpoint-SpanID"));
            short flags = Short.parseShort(getHeader(request, "Pinpoint-Flags"));
            TraceId traceId = traceContext.createTraceId(transactionId, parentSpanID, spanID, flags);
            return traceContext.continueTraceObject(traceId);
        } else {
            return traceContext.newTraceObject();
        }
    }
}
