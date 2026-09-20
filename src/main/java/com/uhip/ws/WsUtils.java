package com.uhip.ws;

import org.java_websocket.WebSocket;

import java.net.InetSocketAddress;

/**
 * Utility functions for WebSocket connection handling and parameter parsing.
 */
public final class WsUtils {

    private WsUtils() {}

    /**
     * Extracts a clientId from query parameters or falls back to remote IP:port.
     */
    public static String extractClientId(WebSocket conn) {
        if (conn == null) {
            return "unknown";
        }
        String descriptor = conn.getResourceDescriptor();
        if (descriptor != null && descriptor.contains("clientId=")) {
            int idx = descriptor.indexOf("clientId=") + 9;
            int amp = descriptor.indexOf('&', idx);
            return (amp != -1) ? descriptor.substring(idx, amp) : descriptor.substring(idx);
        }
        InetSocketAddress address = conn.getRemoteSocketAddress();
        return (address != null) ? address.getHostString() : "anonymous";
    }
}
