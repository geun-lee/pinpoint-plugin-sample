package com.navercorp.pinpoint.plugin.jeus;

import com.navercorp.pinpoint.common.trace.TraceMetadataProvider;
import com.navercorp.pinpoint.common.trace.TraceMetadataSetupContext;

public class JeusTraceMetadataProvider implements TraceMetadataProvider {
    @Override
    public void setup(TraceMetadataSetupContext context) {
        context.addServiceType(JeusConstants.JEUS);
        context.addServiceType(JeusConstants.JEUS_METHOD);
    }
}
