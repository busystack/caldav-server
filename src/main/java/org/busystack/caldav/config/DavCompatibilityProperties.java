package org.busystack.caldav.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Explicit interoperability deviations; false preserves the normative RFC behavior. */
@ConfigurationProperties("busystack.dav")
public record DavCompatibilityProperties(boolean allowSyncDepthOne) {}
