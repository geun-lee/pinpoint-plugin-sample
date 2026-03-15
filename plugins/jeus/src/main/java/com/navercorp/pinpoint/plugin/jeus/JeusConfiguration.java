package com.navercorp.pinpoint.plugin.jeus;

import com.navercorp.pinpoint.bootstrap.config.ProfilerConfig;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class JeusConfiguration {
    private final boolean jeusEnabled;
    private final List<String> jeusBootstrapMains;
    private final ExcludeUrlFilter jeusExcludeUrlFilter;
    private final boolean jeusTraceRequestParam;
    private final boolean jeusHidePinpointHeader;
    
    // DataSource 모니터링 설정
    private final boolean jeusDataSourceEnabled;

    // 메서드 트레이싱 설정
    private final boolean jeusMethodTraceEnabled;
    private final List<String> jeusTracePackages;  // 패키지 패턴 (신규)
    private final List<String> jeusTraceClasses;   // 클래스 목록 (기존 호환)

    // 로깅 연동 설정
    // 지정된 Appender 클래스를 계측하여 로그 발생 시 Pinpoint에 LOGGED 마킹
    // → Pinpoint Web UI에서 해당 트랜잭션에 "View Log" 버튼 활성화
    private final List<String> jeusLoggingAppenderClasses;

    public JeusConfiguration(ProfilerConfig config) {
        this.jeusEnabled = config.readBoolean("profiler.jeus.enable", true);
        this.jeusBootstrapMains = config.readList("profiler.jeus.bootstrap.main");
        
        String excludeUrl = config.readString("profiler.jeus.excludeurl", "");
        this.jeusExcludeUrlFilter = new ExcludeUrlFilter(excludeUrl);
        
        this.jeusTraceRequestParam = config.readBoolean("profiler.jeus.trace.requestparam", true);
        this.jeusHidePinpointHeader = config.readBoolean("profiler.jeus.hidepinpointheader", true);
        
        // DataSource 모니터링 설정
        this.jeusDataSourceEnabled = config.readBoolean("profiler.jeus.datasource.enable", true);

        // 메서드 트레이싱 설정
        this.jeusMethodTraceEnabled = config.readBoolean("profiler.jeus.method.trace.enable", true);
        this.jeusTracePackages = config.readList("profiler.jeus.trace.packages");  // 패키지 패턴 (신규)
        this.jeusTraceClasses = config.readList("profiler.jeus.trace.classes");    // 클래스 목록 (기존 호환)

        // 로깅 연동 설정
        this.jeusLoggingAppenderClasses = config.readList("profiler.jeus.logging.appender.classes");
    }

    public boolean isJeusEnabled() {
        return jeusEnabled;
    }

    public List<String> getJeusBootstrapMains() {
        return jeusBootstrapMains;
    }

    public ExcludeUrlFilter getJeusExcludeUrlFilter() {
        return jeusExcludeUrlFilter;
    }

    public boolean isJeusTraceRequestParam() {
        return jeusTraceRequestParam;
    }

    public boolean isJeusHidePinpointHeader() {
        return jeusHidePinpointHeader;
    }
    
    public boolean isJeusDataSourceEnabled() {
        return jeusDataSourceEnabled;
    }

    public boolean isJeusMethodTraceEnabled() {
        return jeusMethodTraceEnabled;
    }

    public List<String> getJeusTracePackages() {
        return jeusTracePackages;
    }

    public List<String> getJeusTraceClasses() {
        return jeusTraceClasses;
    }

    public List<String> getJeusLoggingAppenderClasses() {
        return jeusLoggingAppenderClasses;
    }

    public static class ExcludeUrlFilter {
        // List 사용: prefix 매칭을 위해 순회 필요 (URL 제외 목록은 통상 소수)
        private final List<String> excludeUrls;

        public ExcludeUrlFilter(String excludeUrl) {
            if (excludeUrl == null || excludeUrl.isEmpty()) {
                this.excludeUrls = Collections.emptyList();
                return;
            }

            List<String> urls = new ArrayList<String>();
            String[] split = excludeUrl.split(",");
            for (String url : split) {
                String trimmed = url.trim();
                if (!trimmed.isEmpty()) {
                    urls.add(trimmed);
                }
            }
            this.excludeUrls = Collections.unmodifiableList(urls);
        }

        /**
         * URL 필터링.
         * - 완전 일치: /health
         * - Prefix 일치: /health/ → /health/check 도 제외
         * - 쿼리 파라미터 무시: /health 설정 시 /health?foo=bar 도 제외
         */
        public boolean filter(String value) {
            if (value == null) {
                return false;
            }
            // 쿼리 파라미터 제거 후 비교
            String path = value;
            int queryIdx = value.indexOf('?');
            if (queryIdx >= 0) {
                path = value.substring(0, queryIdx);
            }
            for (String excludeUrl : excludeUrls) {
                if (path.equals(excludeUrl) || path.startsWith(excludeUrl)) {
                    return true;
                }
            }
            return false;
        }
    }
}
