package com.navercorp.pinpoint.plugin.jeus;

public class JeusConfigurationHolder {
    private static volatile JeusConfiguration configuration;

    public static void setConfiguration(JeusConfiguration config) {
        configuration = config;
    }

    public static JeusConfiguration getConfiguration() {
        return configuration;
    }
}
