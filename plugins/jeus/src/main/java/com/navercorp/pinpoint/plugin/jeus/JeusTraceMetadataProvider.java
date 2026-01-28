package com.navercorp.pinpoint.plugin.jeus;

import com.navercorp.pinpoint.common.trace.TraceMetadataProvider;
import com.navercorp.pinpoint.common.trace.TraceMetadataSetupContext;

public class JeusTraceMetadataProvider implements TraceMetadataProvider {
    @Override
    public void setup(TraceMetadataSetupContext context) {
        context.addServiceType(JeusConstants.JEUS);
        context.addServiceType(JeusConstants.JEUS_METHOD);
        // DataSource 모니터링용 ServiceType 추가
        context.addServiceType(JeusConstants.JEUS_DATASOURCE);
    }
}
