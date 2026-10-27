package org.busystack.caldav.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Narrow sync interoperability accommodation; explicit false selects RFC 6578 strict Depth mode.
 */
@ConfigurationProperties("busystack.dav")
public record DavCompatibilityProperties(@DefaultValue("true") boolean allowSyncDepthOne) {}
