package org.busystack.caldav.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Independently switchable, narrow DAV input interoperability accommodations. */
@ConfigurationProperties("busystack.dav")
public record DavCompatibilityProperties(
    @DefaultValue("true") boolean allowSyncDepthOne,
    @DefaultValue("true") boolean repairLocalObservanceUntil) {}
