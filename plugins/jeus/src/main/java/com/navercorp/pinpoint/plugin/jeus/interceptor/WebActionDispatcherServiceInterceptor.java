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

public class WebActionDispatcherServiceInterceptor implements AroundInterceptor {
    private final PLogger logger = PLoggerFactory.getLogger(this.getClass());
    private final TraceContext traceContext;
    private final MethodDescriptor descriptor;

    // 리플렉션 메소드 캐싱
    private static Method getRequestURIMethod;
    private static Method getParameterMethod;
    private static Method getHeaderMethod;
    private static Method getServerNameMethod;
    private static Method getServerPortMethod;
    private static Method getRemoteAddrMethod;
    private static Method getLocalNameMethod;
    private static Method getQueryStringMethod;
    private static Method setAttributeMethod;
    private static Method getStatusMethod;
    private static Method recordUriTemplateMethod;
    private static Method spanRecordUriTemplateMethod;

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
        String requestURI = getRequestURI(request);

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
        String transactionId = getHeader(request, "Pinpoint-TraceID");

        if (logger.isDebugEnabled()) {
            logger.debug("[CUSTOM-PLUGIN] Pinpoint-TraceID header: " + transactionId);
        }

        // Trace 시작
        trace.traceBlockBegin();

        try {
            SpanRecorder recorder = trace.getSpanRecorder();
            recorder.recordServiceType(JeusConstants.JEUS);
            recorder.recordApi(descriptor);
            recorder.recordRpcName(requestURI);
            recorder.recordEndPoint(getServerName(request) + ":" + getServerPort(request));
            recorder.recordRemoteAddress(getRemoteAddr(request));

            // AcceptorHost 설정
            String hostHeader = getHeader(request, "Host");
            String acceptorHost = hostHeader != null && !hostHeader.isEmpty() ?
                    hostHeader : getLocalName(request) + ":" + getServerPort(request);
            recorder.recordAcceptorHost(acceptorHost);

            if (logger.isDebugEnabled()) {
                logger.debug("[CUSTOM-PLUGIN] Final AcceptorHost: " + acceptorHost);
            }

            // 부모 정보가 있으면 설정
            if (transactionId != null) {
                String parentAppName = getHeader(request, "Pinpoint-pAppName");
                String parentAppType = getHeader(request, "Pinpoint-pAppType");

                if (parentAppName != null && parentAppType != null) {
                    recorder.recordParentApplication(parentAppName, Short.parseShort(parentAppType));

                    if (logger.isDebugEnabled()) {
                        logger.debug("[CUSTOM-PLUGIN] Parent App: " + parentAppName);
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
                    
                    String targetParam = getParameter(request, "target");
                    String methodParam = getParameter(request, "method");
                    
                    String uriTemplate = null;
                    if (targetParam != null && !targetParam.isEmpty() && methodParam != null && !methodParam.isEmpty()) {
                        uriTemplate = "/" + targetParam + "/" + methodParam;
                    } else {
                        String businessId = getParameter(request, "business_id");
                        String submitId = getParameter(request, "submit_id");
                        if (businessId != null && !businessId.isEmpty() && submitId != null && !submitId.isEmpty()) {
                            uriTemplate = "/" + businessId + "/" + submitId;
                        }
                    }

                    if (uriTemplate != null) {
                        setAttribute(request, "pinpoint.metric.uri-template", uriTemplate);
                        recordUriTemplate(trace, uriTemplate);
                        
                        recorder.recordAttribute(AnnotationKey.HTTP_PARAM, "UriTemplate=" + uriTemplate);
                    }
                    
                    if (config != null && config.isJeusTraceRequestParam()) {
                        String params = getQueryString(request);
                        if (params != null && !params.isEmpty()) {
                            recorder.recordAttribute(AnnotationKey.HTTP_PARAM_ENTITY, params);
                        }
                    }
                    
                    recorder.recordAttribute(AnnotationKey.HTTP_URL, getRequestURI(request));
                    
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
                    logger.debug("[CUSTOM-PLUGIN] Exception recorded to SpanRecorder: " + throwable.getClass().getName());
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

    // --- 리플렉션 헬퍼 메소드 (캐싱 적용) ---

    private String getLocalName(Object request) {
        try {
            if (getLocalNameMethod == null) {
                getLocalNameMethod = request.getClass().getMethod("getLocalName");
            }
            return (String) getLocalNameMethod.invoke(request);
        } catch (Exception e) {
            return "unknown";
        }
    }

    private String getRequestURI(Object request) {
        try {
            if (getRequestURIMethod == null) {
                getRequestURIMethod = request.getClass().getMethod("getRequestURI");
            }
            return (String) getRequestURIMethod.invoke(request);
        } catch (Exception e) {
            return null;
        }
    }

    private String getParameter(Object request, String name) {
        try {
            if (getParameterMethod == null) {
                getParameterMethod = request.getClass().getMethod("getParameter", String.class);
            }
            return (String) getParameterMethod.invoke(request, name);
        } catch (Exception e) {
            return null;
        }
    }

    private String getHeader(Object request, String name) {
        try {
            if (getHeaderMethod == null) {
                getHeaderMethod = request.getClass().getMethod("getHeader", String.class);
            }
            return (String) getHeaderMethod.invoke(request, name);
        } catch (Exception e) {
            return null;
        }
    }

    private String getServerName(Object request) {
        try {
            if (getServerNameMethod == null) {
                getServerNameMethod = request.getClass().getMethod("getServerName");
            }
            return (String) getServerNameMethod.invoke(request);
        } catch (Exception e) {
            return "unknown";
        }
    }

    private int getServerPort(Object request) {
        try {
            if (getServerPortMethod == null) {
                getServerPortMethod = request.getClass().getMethod("getServerPort");
            }
            return (Integer) getServerPortMethod.invoke(request);
        } catch (Exception e) {
            return 80;
        }
    }

    private String getRemoteAddr(Object request) {
        try {
            if (getRemoteAddrMethod == null) {
                getRemoteAddrMethod = request.getClass().getMethod("getRemoteAddr");
            }
            return (String) getRemoteAddrMethod.invoke(request);
        } catch (Exception e) {
            return "unknown";
        }
    }

    private String getQueryString(Object request) {
        try {
            if (getQueryStringMethod == null) {
                getQueryStringMethod = request.getClass().getMethod("getQueryString");
            }
            return (String) getQueryStringMethod.invoke(request);
        } catch (Exception e) {
            return null;
        }
    }

    private void setAttribute(Object request, String name, Object value) {
        try {
            if (setAttributeMethod == null) {
                setAttributeMethod = request.getClass().getMethod("setAttribute", String.class, Object.class);
            }
            setAttributeMethod.invoke(request, name, value);
        } catch (Exception e) {
            // ignore
        }
    }

    private int getStatus(Object response) {
        try {
            if (getStatusMethod == null) {
                getStatusMethod = response.getClass().getMethod("getStatus");
            }
            return (Integer) getStatusMethod.invoke(response);
        } catch (Exception e) {
            return 0;
        }
    }

    private boolean recordUriTemplate(Trace trace, String uriTemplate) {
        try {
            if (recordUriTemplateMethod == null) {
                recordUriTemplateMethod = trace.getClass().getMethod("recordUriTemplate", String.class);
            }
            recordUriTemplateMethod.invoke(trace, uriTemplate);
            return true;
        } catch (Exception e) {
            try {
                SpanRecorder spanRecorder = trace.getSpanRecorder();
                if (spanRecordUriTemplateMethod == null) {
                    spanRecordUriTemplateMethod = spanRecorder.getClass().getMethod("recordUriTemplate", String.class);
                }
                spanRecordUriTemplateMethod.invoke(spanRecorder, uriTemplate);
                return true;
            } catch (Exception ex) {
                return false;
            }
        }
    }
}
