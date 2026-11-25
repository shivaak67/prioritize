package com.prioritize.service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import com.fasterxml.jackson.databind.ObjectMapper;

/** Structured diagnostics for one assistant request. Transport credentials are never supplied here. */
final class AssistantAuditLog {
    static final String LOGGER_NAME = "com.prioritize.ai.audit";
    static final String REQUEST_ID = "aiRequestId";
    static final String USER_ID = "aiUserId";
    private static final Logger LOGGER = LoggerFactory.getLogger(LOGGER_NAME);
    private static final ObjectMapper JSON = new ObjectMapper();

    private AssistantAuditLog() {}

    static void write(String event, Object data) {
        if (!LOGGER.isInfoEnabled() || MDC.get(REQUEST_ID) == null) return;
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("timestamp", Instant.now().toString());
        entry.put("requestId", MDC.get(REQUEST_ID));
        entry.put("userId", MDC.get(USER_ID));
        entry.put("event", event);
        entry.put("data", data);
        try {
            // JSON escaping keeps user-supplied newlines inside a single log record.
            LOGGER.info(JSON.writeValueAsString(entry));
        } catch (Exception ex) {
            // Diagnostics must not turn a completed save into a failed chat request.
            LoggerFactory.getLogger(AssistantAuditLog.class).warn("Could not serialize assistant audit event {}", event);
        }
    }
}
