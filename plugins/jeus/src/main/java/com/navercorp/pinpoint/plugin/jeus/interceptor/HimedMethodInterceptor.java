package com.navercorp.pinpoint.plugin.jeus.interceptor;

import com.navercorp.pinpoint.bootstrap.context.MethodDescriptor;
import com.navercorp.pinpoint.bootstrap.context.SpanEventRecorder;
import com.navercorp.pinpoint.bootstrap.context.Trace;
import com.navercorp.pinpoint.bootstrap.context.TraceContext;
import com.navercorp.pinpoint.bootstrap.interceptor.AroundInterceptor;
import com.navercorp.pinpoint.bootstrap.logging.PLogger;
import com.navercorp.pinpoint.bootstrap.logging.PLoggerFactory;
import com.navercorp.pinpoint.plugin.jeus.JeusConstants;

import java.util.concurrent.atomic.AtomicLong;

/**
 * himed.his 패키지 하위 메서드들을 SpanEvent로 추적하는 인터셉터.
 * WebActionDispatcher에서 생성된 Trace 내에서 호출되는 메서드들을 콜스택에 표시.
 */
public class HimedMethodInterceptor implements AroundInterceptor {
    private final PLogger logger = PLoggerFactory.getLogger(this.getClass());
    private final TraceContext traceContext;
    private final MethodDescriptor descriptor;

    // 진단용: 10초마다 한 번씩만 로그 출력 (로그 폭발 방지)
    private static final long DIAG_INTERVAL_MS = 10_000L;
    private static final AtomicLong lastNullTraceLogTime    = new AtomicLong(0);
    private static final AtomicLong lastNotSampledLogTime   = new AtomicLong(0);

    public HimedMethodInterceptor(TraceContext traceContext, MethodDescriptor descriptor) {
        this.traceContext = traceContext;
        this.descriptor = descriptor;
    }

    @Override
    public void before(Object target, Object[] args) {
        Trace trace = traceContext.currentTraceObject();
        if (trace == null) {
            // [진단] trace가 null인 경우 → 문자열은 실제 출력 시에만 생성 (GC 부담 최소화)
            if (shouldLog(lastNullTraceLogTime)) {
                logger.warn("[JEUS-PLUGIN][DIAG] HimedMethod.before: trace=NULL → thread=" + Thread.currentThread().getName()
                        + " method=" + descriptor.getClassName() + "." + descriptor.getMethodName());
            }
            return;
        }

        if (!trace.canSampled()) {
            // [진단] trace는 있지만 샘플링 대상이 아닌 경우
            if (shouldLog(lastNotSampledLogTime)) {
                logger.warn("[JEUS-PLUGIN][DIAG] HimedMethod.before: canSampled=false → thread=" + Thread.currentThread().getName()
                        + " method=" + descriptor.getClassName() + "." + descriptor.getMethodName());
            }
            return;
        }

        trace.traceBlockBegin();
    }

    /** DIAG_INTERVAL_MS 이상 경과한 경우에만 true 반환 (문자열 생성은 호출 측에서 담당) */
    private boolean shouldLog(AtomicLong lastLogTime) {
        long now = System.currentTimeMillis();
        long last = lastLogTime.get();
        return now - last >= DIAG_INTERVAL_MS && lastLogTime.compareAndSet(last, now);
    }

    @Override
    public void after(Object target, Object[] args, Object result, Throwable throwable) {
        Trace trace = traceContext.currentTraceObject();
        if (trace == null) {
            return;
        }

        // canSampled가 false이면 before()에서 traceBlockBegin()을 호출하지 않았으므로
        // traceBlockEnd()도 호출하지 않아야 함 (try 블록 진입 전 return → finally 미실행)
        if (!trace.canSampled()) {
            return;
        }

        try {
            SpanEventRecorder recorder = trace.currentSpanEventRecorder();
            recorder.recordServiceType(JeusConstants.JEUS_METHOD);
            recorder.recordApi(descriptor);

            if (throwable != null) {
                recorder.recordException(throwable);
            }
        } catch (Throwable t) {
            if (logger.isWarnEnabled()) {
                logger.warn("[JEUS-PLUGIN] HimedMethodInterceptor.after error: " + t.getMessage(), t);
            }
        } finally {
            trace.traceBlockEnd();
        }
    }
}
