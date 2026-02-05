package com.navercorp.pinpoint.plugin.jeus;

import com.navercorp.pinpoint.bootstrap.config.ProfilerConfig;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
    private final List<String> jeusTraceClasses;

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
        this.jeusTraceClasses = config.readList("profiler.jeus.trace.classes");
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

    public List<String> getJeusTraceClasses() {
        return jeusTraceClasses;
    }

    public static class ExcludeUrlFilter {
        private final Set<String> excludeUrls;

        public ExcludeUrlFilter(String excludeUrl) {
            if (excludeUrl == null || excludeUrl.isEmpty()) {
                this.excludeUrls = Collections.emptySet();
                return;
            }
            
            this.excludeUrls = new HashSet<String>();
            String[] urls = excludeUrl.split(",");
            for (String url : urls) {
                String trimmed = url.trim();
                if (!trimmed.isEmpty()) {
                    this.excludeUrls.add(trimmed);
                }
            }
        }

        public boolean filter(String value) {
            if (value == null) {
                return false;
            }
            return excludeUrls.contains(value);
        }
    }
}
