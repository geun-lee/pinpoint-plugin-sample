package com.navercorp.pinpoint.plugin.jeus.interceptor;

import com.navercorp.pinpoint.bootstrap.context.MethodDescriptor;
import com.navercorp.pinpoint.bootstrap.context.SpanEventRecorder;
import com.navercorp.pinpoint.bootstrap.context.Trace;
import com.navercorp.pinpoint.bootstrap.context.TraceContext;
import com.navercorp.pinpoint.bootstrap.interceptor.AroundInterceptor;
import com.navercorp.pinpoint.bootstrap.logging.PLogger;
import com.navercorp.pinpoint.bootstrap.logging.PLoggerFactory;
import com.navercorp.pinpoint.plugin.jeus.JeusConstants;

import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicLong;

/**
 * himed.his 패키지 하위 메서드들을 SpanEvent로 추적하는 인터셉터.
 * WebActionDispatcher에서 생성된 Trace 내에서 호출되는 메서드들을 콜스택에 표시.
 */
public class HimedMethodInterceptor implements AroundInterceptor {
    private final PLogger logger = PLoggerFactory.getLogger(this.getClass());
    private final TraceContext traceContext;
    private final MethodDescriptor descriptor;

    // 로그 throttle: 동일 종류의 로그를 10초에 1회로 제한 (로그 폭발 방지)
    // 인스턴스 필드: 메서드별로 독립적으로 throttle하여 어느 메서드에서 발생했는지 파악 가능
    // (static이면 한 메서드에서 로그 출력 시 다른 모든 메서드 로그가 10초간 차단됨)
    private static final long LOG_THROTTLE_MS = 10_000L;
    private final AtomicLong lastNullTraceLogTime    = new AtomicLong(0);
    private final AtomicLong lastNotSampledLogTime   = new AtomicLong(0);
    private final AtomicLong lastBeginFailLogTime    = new AtomicLong(0);
    private final AtomicLong lastAfterErrorLogTime   = new AtomicLong(0);
    private final AtomicLong lastEndFailLogTime      = new AtomicLong(0);

    // traceBlockBegin 성공 여부를 스택으로 추적: 중첩 호출 시 begin/end 쌍 보장
    // Boolean 단일값이 아닌 Deque: 메서드가 서로 중첩 호출되므로 스택이 필수
    // (단일 ThreadLocal<Boolean>은 중첩 시 remove()로 상위 메서드의 값까지 삭제됨)
    private static final ThreadLocal<ArrayDeque<Boolean>> blockStack =
            new ThreadLocal<ArrayDeque<Boolean>>();

    public HimedMethodInterceptor(TraceContext traceContext, MethodDescriptor descriptor) {
        this.traceContext = traceContext;
        this.descriptor = descriptor;
    }

    @Override
    public void before(Object target, Object[] args) {
        Trace trace = traceContext.currentTraceObject();
        if (trace == null) {
            if (logger.isDebugEnabled() && shouldLog(lastNullTraceLogTime)) {
                logger.debug("[JEUS-PLUGIN][DIAG] HimedMethod.before: trace=NULL → thread=" + Thread.currentThread().getName()
                        + " method=" + descriptor.getClassName() + "." + descriptor.getMethodName());
            }
            return;
        }

        if (!trace.canSampled()) {
            if (logger.isDebugEnabled() && shouldLog(lastNotSampledLogTime)) {
                logger.debug("[JEUS-PLUGIN][DIAG] HimedMethod.before: canSampled=false → thread=" + Thread.currentThread().getName()
                        + " method=" + descriptor.getClassName() + "." + descriptor.getMethodName());
            }
            return;
        }

        ArrayDeque<Boolean> stack = blockStack.get();
        if (stack == null) {
            stack = new ArrayDeque<Boolean>();
            blockStack.set(stack);
        }

        try {
            trace.traceBlockBegin();
            stack.push(Boolean.TRUE);   // 성공 → after()에서 traceBlockEnd 호출
        } catch (Throwable t) {
            stack.push(Boolean.FALSE);  // 실패 → after()에서 traceBlockEnd 건너뜀
            if (logger.isWarnEnabled() && shouldLog(lastBeginFailLogTime)) {
                logger.warn("[JEUS-PLUGIN] traceBlockBegin failed (throttled 10s): "
                        + descriptor.getClassName() + "." + descriptor.getMethodName(), t);
            }
        }
    }

    /** LOG_THROTTLE_MS 이상 경과한 경우에만 true 반환 (문자열 생성은 호출 측에서 담당) */
    private boolean shouldLog(AtomicLong lastLogTime) {
        long now = System.currentTimeMillis();
        long last = lastLogTime.get();
        return now - last >= LOG_THROTTLE_MS && lastLogTime.compareAndSet(last, now);
    }

    @Override
    public void after(Object target, Object[] args, Object result, Throwable throwable) {
        Trace trace = traceContext.currentTraceObject();
        if (trace == null) {
            // 방어: before()에서 push했을 수 있으므로 정리
            ArrayDeque<Boolean> stack = blockStack.get();
            if (stack != null && !stack.isEmpty()) {
                Boolean began = stack.pop();
                if (stack.isEmpty()) blockStack.remove();
                // trace가 before/after 사이에 사라진 비정상 상태 감지
                if (Boolean.TRUE.equals(began) && logger.isDebugEnabled()) {
                    logger.debug("[JEUS-PLUGIN][DIAG] HimedMethod.after: trace disappeared between before/after"
                            + " → traceBlockEnd skipped. method=" + descriptor.getClassName() + "." + descriptor.getMethodName());
                }
            }
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
            if (logger.isWarnEnabled() && shouldLog(lastAfterErrorLogTime)) {
                logger.warn("[JEUS-PLUGIN] HimedMethodInterceptor.after error (throttled 10s): " + t.getMessage(), t);
            }
        } finally {
            // before()에서 해당 메서드의 traceBlockBegin 성공 여부를 pop으로 꺼냄
            // Deque 스택이므로 중첩 호출 시 각 메서드의 결과가 올바르게 매핑됨
            ArrayDeque<Boolean> stack = blockStack.get();
            Boolean began = (stack != null && !stack.isEmpty()) ? stack.pop() : null;
            if (stack != null && stack.isEmpty()) {
                blockStack.remove();  // 스택이 비면 ThreadLocal 정리
            }
            if (Boolean.TRUE.equals(began)) {
                try {
                    trace.traceBlockEnd();
                } catch (Throwable t) {
                    if (logger.isWarnEnabled() && shouldLog(lastEndFailLogTime)) {
                        logger.warn("[JEUS-PLUGIN] traceBlockEnd failed (throttled 10s): " + t.getMessage(), t);
                    }
                }
            }
        }
    }
}
