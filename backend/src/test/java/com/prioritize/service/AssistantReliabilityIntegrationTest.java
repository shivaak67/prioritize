package com.prioritize.service;

import com.prioritize.config.AiProperties;
import com.prioritize.dto.*;
import com.prioritize.model.*;
import com.prioritize.repository.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest @ActiveProfiles("test")
class AssistantReliabilityIntegrationTest {
    @Autowired AssistantService assistant;
    @Autowired AiProperties properties;
    @Autowired UserRepository users;
    @Autowired ReminderRepository reminders;
    @Autowired CalendarEventRepository events;
    @Autowired ReminderDispatchService dispatch;
    @Autowired PlatformTransactionManager transactions;
    @MockitoBean LlmClient llm;

    UUID user() {
        User u = new User(); u.setId(UUID.randomUUID()); u.setEmail(u.getId()+"@example.com");
        u.setFirstName("Reliability"); u.setLastName("Test"); u.setAuthProvider(AuthProvider.LOCAL);
        u.setPasswordHash("unused"); u.setRole(Role.USER); return users.save(u).getId();
    }

    @Test void missingTaskToolDoesNotRollbackTheChatResponse() {
        UUID owner = user();
        boolean enabled = properties.isEnabled(); String key = properties.getApiKey();
        properties.setEnabled(true); properties.setApiKey("test");
        try {
            when(llm.complete(any(), any()))
                .thenReturn(new LlmCompletion(null,List.of(new LlmToolCall("call1","complete_task",
                    "{\"taskId\":\""+UUID.randomUUID()+"\"}"))))
                .thenReturn(new LlmCompletion("That task could not be found; nothing was changed.",List.of()));
            assertThat(assistant.chat(owner,new AssistantChatRequest("Complete that task",null,"America/Chicago")).reply())
                .contains("could not be found");
            verify(llm,times(2)).complete(any(),any());
        } finally { properties.setEnabled(enabled); properties.setApiKey(key); users.deleteById(owner); }
    }

    @Test void missingCalendarReminderCancellationCommitsAndCannotBeClaimedAgain() {
        UUID owner = user();
        Reminder r = new Reminder(); r.setUserId(owner); r.setStatus(ReminderStatus.PENDING);
        r.setChannel(NotificationChannel.EMAIL); r.setRelatedEntityType(ReminderEntityType.CALENDAR_EVENT);
        r.setRelatedEntityId(UUID.randomUUID()); r.setReminderAt(Instant.now().plusSeconds(3600));
        UUID id = reminders.save(r).getId();
        var tx = new TransactionTemplate(transactions);
        try {
            tx.executeWithoutResult(status -> dispatch.processReminder(id));
            Reminder saved = reminders.findById(id).orElseThrow();
            assertThat(saved.getStatus()).isEqualTo(ReminderStatus.CANCELLED);
            assertThat(saved.getFailureReason()).contains("no longer exists");
            tx.executeWithoutResult(status -> dispatch.processReminder(id));
            assertThat(reminders.findById(id).orElseThrow().getAttemptCount()).isEqualTo(1);
        } finally { reminders.deleteById(id); users.deleteById(owner); }
    }

    @Test void rejectedCanvasEditReturnsAReplyAndPreservesTheAssignment() {
        UUID owner = user();
        CalendarEvent event = new CalendarEvent(); event.setUserId(owner); event.setTitle("Canvas quiz");
        event.setStartAt(Instant.now().plusSeconds(3600)); event.setEndAt(Instant.now().plusSeconds(7200));
        event.setCanvasKey("test-"+UUID.randomUUID()); event.setCanvasKind("DEADLINE");
        UUID id = events.save(event).getId();
        boolean enabled = properties.isEnabled(); String key = properties.getApiKey();
        properties.setEnabled(true); properties.setApiKey("test");
        try {
            when(llm.complete(any(), any()))
                .thenReturn(new LlmCompletion(null,List.of(new LlmToolCall("call1","delete_calendar_event",
                    "{\"eventId\":\""+id+"\"}"))))
                .thenReturn(new LlmCompletion("Canvas assignments are read-only; nothing was deleted.",List.of()));
            assertThat(assistant.chat(owner,new AssistantChatRequest("Delete that event",null,"America/Chicago")).reply())
                .contains("nothing was deleted");
            assertThat(events.existsById(id)).isTrue();
        } finally {
            properties.setEnabled(enabled); properties.setApiKey(key); events.deleteById(id); users.deleteById(owner);
        }
    }
}
