package com.navercorp.pinpoint.plugin.jeus;

import com.navercorp.pinpoint.common.trace.ServiceType;
import com.navercorp.pinpoint.common.trace.ServiceTypeFactory;

public final class JeusConstants {
    private JeusConstants() {
    }

    // JEUS 서버 타입 (7010)
    public static final ServiceType JEUS = ServiceTypeFactory.of(7010, "JEUS", "JEUS_METHOD");
    
    // JEUS 내부 메소드 타입 (7011)
    public static final ServiceType JEUS_METHOD = ServiceTypeFactory.of(7011, "JEUS_METHOD", "JEUS_METHOD");
}
