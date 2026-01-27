package com.navercorp.pinpoint.plugin.jeus;

import com.navercorp.pinpoint.common.trace.ServiceType;
import com.navercorp.pinpoint.common.trace.ServiceTypeFactory;
import com.navercorp.pinpoint.common.trace.ServiceTypeProperty;

public final class JeusConstants {
    private JeusConstants() {
    }

    public static final ServiceType JEUS = ServiceTypeFactory.of(7010, "JEUS", "JEUS_METHOD",
            ServiceTypeProperty.RECORD_STATISTICS, ServiceTypeProperty.INCLUDE_DESTINATION_ID);
    
    public static final ServiceType JEUS_METHOD = ServiceTypeFactory.of(7011, "JEUS_METHOD", "JEUS_METHOD");
}
