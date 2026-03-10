package com.navercorp.pinpoint.plugin.jeus.interceptor;

import com.navercorp.pinpoint.bootstrap.context.SpanRecorder;
import com.navercorp.pinpoint.bootstrap.context.Trace;
import com.navercorp.pinpoint.bootstrap.context.TraceContext;
import com.navercorp.pinpoint.bootstrap.interceptor.AroundInterceptor;
import com.navercorp.pinpoint.common.trace.LoggingInfo;

/**
 * 로깅 Appender 인터셉터.
 *
 * 로그가 기록될 때 현재 활성화된 Trace의 SpanRecorder에 LoggingInfo.LOGGED를 마킹.
 * 이 마킹이 있어야 Pinpoint Web UI에서 해당 트랜잭션에 "View Log" 버튼이 활성화됨.
 *
 * 동작 흐름:
 *   요청 처리 중 로그 발생
 *   → 이 인터셉터의 before() 호출
 *   → 현재 Trace의 SpanRecorder에 LOGGED 마킹
 *   → Pinpoint Collector가 LOGGED 상태를 저장
 *   → Pinpoint Web UI에서 transactionId 클릭 시 "View Log" 버튼 표시
 *
 * MDC 연동 (로그에 transactionId/spanId 포함):
 *   이 인터셉터는 LOGGED 마킹만 담당.
 *   실제 MDC 주입(PtxId, PspanId)은 pinpoint.config에서 설정:
 *     profiler.log4j2.logging.transactioninfo=true  (Log4j2)
 *     profiler.logback.logging.transactioninfo=true  (Logback)
 *     profiler.log4j.logging.transactioninfo=true    (Log4j)
 *
 * 주의: 이 인터셉터는 모든 로그 호출마다 실행되므로 최대한 가볍게 유지.
 */
public class LoggingAppenderInterceptor implements AroundInterceptor {

    private final TraceContext traceContext;

    public LoggingAppenderInterceptor(TraceContext traceContext) {
        this.traceContext = traceContext;
    }

    @Override
    public void before(Object target, Object[] args) {
        Trace trace = traceContext.currentTraceObject();
        if (trace == null) {
            return;
        }

        if (!trace.canSampled()) {
            return;
        }

        SpanRecorder recorder = trace.getSpanRecorder();
        recorder.recordLogging(LoggingInfo.LOGGED);
    }

    @Override
    public void after(Object target, Object[] args, Object result, Throwable throwable) {
        // 로깅 인터셉터는 after 처리 불필요
    }
}
