package com.navercorp.pinpoint.plugin.jeus;

import com.navercorp.pinpoint.bootstrap.config.ProfilerConfig;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class JeusConfiguration {
    private final boolean jeusEnabled;
    private final ExcludeUrlFilter jeusExcludeUrlFilter;
    private final boolean jeusTraceRequestParam;

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

        String excludeUrl = config.readString("profiler.jeus.excludeurl", "");
        this.jeusExcludeUrlFilter = new ExcludeUrlFilter(excludeUrl);

        this.jeusTraceRequestParam = config.readBoolean("profiler.jeus.trace.requestparam", true);
        
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

    public ExcludeUrlFilter getJeusExcludeUrlFilter() {
        return jeusExcludeUrlFilter;
    }

    public boolean isJeusTraceRequestParam() {
        return jeusTraceRequestParam;
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
        // 완전 일치용 URL 목록과 prefix 매칭용 목록을 분리하여 생성자에서 미리 계산
        private final List<String> excludeUrls;
        // prefix 매칭용: 슬래시로 끝나도록 미리 정규화 (매 요청마다 문자열 concat 방지)
        private final List<String> excludePrefixes;

        public ExcludeUrlFilter(String excludeUrl) {
            if (excludeUrl == null || excludeUrl.isEmpty()) {
                this.excludeUrls = Collections.emptyList();
                this.excludePrefixes = Collections.emptyList();
                return;
            }

            List<String> urls = new ArrayList<String>();
            List<String> prefixes = new ArrayList<String>();
            String[] split = excludeUrl.split(",");
            for (String url : split) {
                String trimmed = url.trim();
                if (!trimmed.isEmpty()) {
                    urls.add(trimmed);
                    prefixes.add(trimmed.endsWith("/") ? trimmed : trimmed + "/");
                }
            }
            this.excludeUrls = Collections.unmodifiableList(urls);
            this.excludePrefixes = Collections.unmodifiableList(prefixes);
        }

        /**
         * URL 필터링.
         * - 완전 일치: /health → /health, /health?foo=bar 제외
         * - Prefix 일치: /health/ → /health/check 제외 (슬래시 경계 기준)
         * - 쿼리 파라미터 무시: path 부분만 비교
         *
         * 주의: /health 설정은 /healthcheck를 제외하지 않음 (슬래시 경계 준수)
         *       /healthcheck도 제외하려면 /health/ 로 설정할 것
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
            for (int i = 0; i < excludeUrls.size(); i++) {
                if (path.equals(excludeUrls.get(i))) {
                    return true;
                }
                // 슬래시 경계 기준 prefix 일치: /health 설정 시 /health/check 도 제외,
                // 단 /healthcheck 는 제외하지 않음
                if (path.startsWith(excludePrefixes.get(i))) {
                    return true;
                }
            }
            return false;
        }
    }
}
