package com.navercorp.pinpoint.plugin.jeus.interceptor;

import com.navercorp.pinpoint.bootstrap.context.MethodDescriptor;
import com.navercorp.pinpoint.bootstrap.context.SpanEventRecorder;
import com.navercorp.pinpoint.bootstrap.context.Trace;
import com.navercorp.pinpoint.bootstrap.context.TraceContext;
import com.navercorp.pinpoint.bootstrap.interceptor.AroundInterceptor;
import com.navercorp.pinpoint.bootstrap.logging.PLogger;
import com.navercorp.pinpoint.bootstrap.logging.PLoggerFactory;
import com.navercorp.pinpoint.plugin.jeus.JeusConstants;

/**
 * himed.his 패키지 하위 메서드들을 SpanEvent로 추적하는 인터셉터.
 * WebActionDispatcher에서 생성된 Trace 내에서 호출되는 메서드들을 콜스택에 표시.
 */
public class HimedMethodInterceptor implements AroundInterceptor {
    private final PLogger logger = PLoggerFactory.getLogger(this.getClass());
    private final TraceContext traceContext;
    private final MethodDescriptor descriptor;

    public HimedMethodInterceptor(TraceContext traceContext, MethodDescriptor descriptor) {
        this.traceContext = traceContext;
        this.descriptor = descriptor;
    }

    @Override
    public void before(Object target, Object[] args) {
        // 현재 활성화된 Trace 가져오기
        Trace trace = traceContext.currentTraceObject();
        if (trace == null) {
            // Trace가 없으면 Entry Point가 아니므로 추적하지 않음
            return;
        }

        // 샘플링 대상인지 확인
        if (!trace.canSampled()) {
            return;
        }

        // SpanEvent 시작 (콜스택에 표시됨)
        trace.traceBlockBegin();
    }

    @Override
    public void after(Object target, Object[] args, Object result, Throwable throwable) {
        Trace trace = traceContext.currentTraceObject();
        if (trace == null) {
            return;
        }

        if (!trace.canSampled()) {
            return;
        }

        try {
            SpanEventRecorder recorder = trace.currentSpanEventRecorder();

            // 메서드 정보 기록
            recorder.recordServiceType(JeusConstants.JEUS_METHOD);
            recorder.recordApi(descriptor);

            // 예외 발생시 기록
            if (throwable != null) {
                recorder.recordException(throwable);
            }
        } catch (Throwable t) {
            if (logger.isWarnEnabled()) {
                logger.warn("[JEUS-PLUGIN] HimedMethodInterceptor.after error: " + t.getMessage(), t);
            }
        } finally {
            // SpanEvent 종료
            trace.traceBlockEnd();
        }
    }
}
