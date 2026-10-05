package com.uhip.ws;

import org.java_websocket.WebSocket;

import java.net.InetSocketAddress;

/**
 * Utility functions for WebSocket connection handling and parameter parsing.
 */
public final class WsUtils {

    private WsUtils() {}

    /**
     * Extracts a clientId from query parameters or falls back to unique connection attachment.
     */
    public static String extractClientId(WebSocket conn) {
        if (conn == null) {
            return "client_" + java.util.UUID.randomUUID().toString().substring(0, 8);
        }
        String descriptor = conn.getResourceDescriptor();
        if (descriptor != null && descriptor.contains("clientId=")) {
            int idx = descriptor.indexOf("clientId=") + 9;
            int amp = descriptor.indexOf('&', idx);
            String id = (amp != -1) ? descriptor.substring(idx, amp) : descriptor.substring(idx);
            if (!id.isBlank()) {
                return id.trim();
            }
        }
        Object attachment = conn.getAttachment();
        if (attachment instanceof String str && !str.isBlank()) {
            return str;
        }
        String generated = "client_" + java.util.UUID.randomUUID().toString().substring(0, 8);
        conn.setAttachment(generated);
        return generated;
    }

    public static String extractParam(WebSocket conn, String paramName) {
        if (conn == null || paramName == null) return "";
        String descriptor = conn.getResourceDescriptor();
        String prefix = paramName + "=";
        if (descriptor != null && descriptor.contains(prefix)) {
            int idx = descriptor.indexOf(prefix) + prefix.length();
            int amp = descriptor.indexOf('&', idx);
            String val = (amp != -1) ? descriptor.substring(idx, amp) : descriptor.substring(idx);
            return val.trim();
        }
        return "";
    }
}
