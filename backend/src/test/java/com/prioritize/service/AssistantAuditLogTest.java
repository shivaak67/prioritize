package com.prioritize.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.*;
import java.util.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.prioritize.config.AiProperties;
import com.prioritize.dto.*;
import com.sun.net.httpserver.HttpServer;

class AssistantAuditLogTest {
    private final ObjectMapper json = new ObjectMapper();

    @ParameterizedTest @ValueSource(ints = {200, 500})
    void capturesActualModelExchangeAndProviderFailuresWithoutTransportCredentials(int status) throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(AssistantAuditLog.LOGGER_NAME);
        var capture = new ListAppender<ILoggingEvent>();
        capture.start(); logger.addAppender(capture);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String providerBody = status == 200 ? "{\"choices\":[{\"message\":{\"content\":\"Hello\"}}]}" : "provider-secret-error-body";
        server.createContext("/v1/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] bytes = providerBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        MDC.put(AssistantAuditLog.REQUEST_ID, UUID.randomUUID().toString());
        MDC.put(AssistantAuditLog.USER_ID, UUID.randomUUID().toString());
        try {
            var properties = new AiProperties(); properties.setApiKey("secret-transport-credential");
            properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
            var client = new LlmClient(properties, json);
            List<Map<String, Object>> messages = List.of(Map.of("role", "user", "content", "Hello\nsecond line"));
            if (status == 200) assertThat(client.complete(messages, null).content()).isEqualTo("Hello");
            else assertThatThrownBy(() -> client.complete(messages, null)).hasMessageContaining("HTTP 500");
            List<JsonNode> entries = new ArrayList<>();
            for (var event : capture.list) entries.add(json.readTree(event.getFormattedMessage()));
            assertThat(entries.getFirst().path("event").asText()).isEqualTo("model_request");
            assertThat(entries.getFirst().at("/data/messages/0/content").asText()).isEqualTo("Hello\nsecond line");
            assertThat(entries.getLast().path("event").asText()).isEqualTo(status == 200 ? "model_response" : "model_error");
            if (status == 200) assertThat(entries.getLast().at("/data/body").asText()).isEqualTo(providerBody);
            assertThat(capture.list).allSatisfy(e -> assertThat(e.getFormattedMessage())
                    .doesNotContain("secret-transport-credential", "provider-secret-error-body", "\n"));
        } finally {
            MDC.clear(); server.stop(0); logger.detachAppender(capture); capture.stop();
        }
    }

    @Test void correlatesMessagesToolArgumentsResolvedDatesResultsAndFinalReply() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(AssistantAuditLog.LOGGER_NAME);
        var capture = new ListAppender<ILoggingEvent>();
        capture.start(); logger.addAppender(capture);
        MDC.put("existingContext", "preserved");
        try {
            UUID owner = UUID.randomUUID();
            var llm = mock(LlmClient.class);
            var tasks = mock(TaskService.class);
            var events = mock(CalendarEventService.class);
            var dashboard = mock(DashboardService.class);
            var properties = new AiProperties();
            properties.setEnabled(true); properties.setApiKey("secret-credential-never-log");
            var clock = Clock.fixed(Instant.parse("2026-11-25T20:00:00Z"), ZoneOffset.UTC);
            var executor = new AssistantToolExecutor(json, tasks, events, clock);
            var service = new AssistantService(properties, llm, executor, tasks, events, dashboard, clock);
            when(dashboard.summary(eq(owner), any())).thenReturn(mock(DashboardSummaryResponse.class));
            when(events.create(eq(owner), any())).thenAnswer(call -> {
                CalendarEventRequest request = call.getArgument(1);
                return new CalendarEventResponse(UUID.randomUUID(), null, request.title(), null,
                        request.startAt(), request.endAt(), false, null, null, null, null, null, null, false);
            });
            when(llm.complete(any(), any()))
                    .thenReturn(new LlmCompletion(null, List.of(new LlmToolCall("call-1", "create_calendar_event",
                            "{\"title\":\"Final\",\"startDate\":\"2026-12-01\",\"startTime\":\"13:00\",\"endTime\":\"15:00\"}"))))
                    .thenReturn(new LlmCompletion("Saved", List.of()));
            service.chat(owner, new AssistantChatRequest("Create Final on Dec. 9 from 1–3 PM\nSecond line", null, "America/Chicago"));
            List<JsonNode> entries = new ArrayList<>();
            for (ILoggingEvent event : capture.list) entries.add(json.readTree(event.getFormattedMessage()));
            assertThat(entries).extracting(e -> e.path("event").asText())
                    .contains("chat_request", "model_round", "tool_call", "tool_resolved_arguments", "tool_result", "chat_response");
            assertThat(entries.stream().map(e -> e.get("requestId").asText()).distinct()).hasSize(1);
            assertThat(entries).allSatisfy(e -> assertThat(e.get("userId").asText()).isEqualTo(owner.toString()));
            assertThat(entries).anySatisfy(e -> {
                assertThat(e.path("event").asText()).isEqualTo("tool_resolved_arguments");
                assertThat(e.at("/data/arguments/startDate").asText()).isEqualTo("2026-12-09");
            }).anySatisfy(e -> {
                assertThat(e.path("event").asText()).isEqualTo("chat_response");
                assertThat(e.at("/data/response/reply").asText()).contains("December 9, 2026", "1:00 PM–3:00 PM");
            });
            assertThat(capture.list).allSatisfy(e -> assertThat(e.getFormattedMessage())
                    .doesNotContain("secret-credential-never-log", "\n"));
            assertThat(MDC.get(AssistantAuditLog.REQUEST_ID)).isNull();
            assertThat(MDC.get("existingContext")).isEqualTo("preserved");
        } finally {
            MDC.clear(); logger.detachAppender(capture); capture.stop();
        }
    }

    @Test void logsFailuresAndRestoresRequestContext() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(AssistantAuditLog.LOGGER_NAME);
        var capture = new ListAppender<ILoggingEvent>();
        capture.start(); logger.addAppender(capture);
        try {
            var properties = new AiProperties(); properties.setEnabled(true); properties.setApiKey("secret");
            var dashboard = mock(DashboardService.class);
            when(dashboard.summary(any(), any())).thenThrow(new IllegalStateException("Sensitive internal details"));
            var service = new AssistantService(properties, mock(LlmClient.class), mock(AssistantToolExecutor.class),
                    mock(TaskService.class), mock(CalendarEventService.class), dashboard, Clock.systemUTC());
            assertThatThrownBy(() -> service.chat(UUID.randomUUID(), new AssistantChatRequest("Help me plan", null, "America/Chicago")))
                    .isInstanceOf(IllegalStateException.class);
            JsonNode failure = json.readTree(capture.list.getLast().getFormattedMessage());
            assertThat(failure.path("event").asText()).isEqualTo("chat_error");
            assertThat(failure.at("/data/errorType").asText()).isEqualTo(IllegalStateException.class.getName());
            assertThat(MDC.get(AssistantAuditLog.REQUEST_ID)).isNull();
        } finally {
            MDC.clear(); logger.detachAppender(capture); capture.stop();
        }
    }
}
