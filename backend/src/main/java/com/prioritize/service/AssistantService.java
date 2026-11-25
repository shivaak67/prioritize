package com.prioritize.service;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.prioritize.config.AiProperties;
import com.prioritize.dto.AssistantChatRequest;
import com.prioritize.dto.AssistantChatResponse;
import com.prioritize.dto.AssistantMessageDto;
import com.prioritize.dto.CalendarEventResponse;
import com.prioritize.dto.DashboardSummaryResponse;
import com.prioritize.dto.TaskResponse;
import com.prioritize.model.TaskStatus;

@Service
public class AssistantService {

    private static final int MAX_HISTORY = 10;
    private static final int MAX_TOOL_ROUNDS = 5;
    private static final ZoneId DISPLAY_ZONE = ZoneId.systemDefault();
    private static final DateTimeFormatter DATE_LABEL =
            DateTimeFormatter.ofPattern("EEEE, MMM d, yyyy", Locale.US).withZone(DISPLAY_ZONE);
    private static final DateTimeFormatter TODAY_KEY =
            DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US).withZone(DISPLAY_ZONE);
    private static final DateTimeFormatter TIME_RANGE_FORMAT =
            DateTimeFormatter.ofPattern("h:mm a", Locale.US).withZone(DISPLAY_ZONE);
    private static final DateTimeFormatter LOCAL_TIME_FORMAT =
            DateTimeFormatter.ofPattern("h:mm a", Locale.US);

    private static final String SYSTEM_PROMPT = """
            You are Prioritize, a helpful productivity assistant inside a task and calendar app.
            You can read the user's tasks and calendar, and you can take actions using tools:
            create/update/complete/delete tasks, and create/update/delete calendar events.
            When the user asks you to create, update, complete, or delete something, use the appropriate tool.
            Do not tell the user to do it manually if you can do it with a tool.
            After using tools, confirm what you did in plain language.
            A quoted title followed by a due date/time is a request to create that item.
            A final, exam, meeting or appointment with a start AND end time belongs in a calendar event,
            even if the user says 'due'. A task dueTime is a single deadline and cannot store a time range.
            Tasks and calendar events may share dates and times. The application allows overlaps.
            Never refuse creation because of a supposed conflict, duplicate, or another task on that date.
            Only report a conflict if an actual tool returns that error. A deadline is not a busy interval.
            When asked about overlapping events, compare full local dates AND both endpoints:
            startA < endB AND startB < endA. Adjacent intervals do not overlap.
            If an end time is unknown, say the duration is unknown instead of inventing an overlap.
            Explicit month/day dates take precedence over relative weekday calculations.
            Use the current year for upcoming month/day dates, never a year from old chat history.
            Use only the user data provided below. If something is not in the data, say you do not have that information.
            Canvas assignment deadlines (including quizzes) are work to complete, just like personal tasks.
            For daily focus or planning questions, always check unfinished Canvas assignments due today
            and overdue assignments before recommending later work. Mention today's quiz without requiring a follow-up.
            Completed Canvas assignments must not be recommended as unfinished work.
            All-day Canvas dates are date-only deadlines, not appointments or midnight deadlines.
            Calendar questions include ALL three lists: personal tasks, Canvas assignments, and calendar events.
            An empty calendar-events list does not mean there are no assignments or tasks on that date.
            Before saying a day is empty, check all three lists for that local date.
            Reminder notification times are not assignment deadlines. No reminder data is supplied here;
            do not invent reminders or claim you checked them. Similar titles alone do not prove duplicates.
            Compare IDs, titles, dates, and times, and describe possible duplicates without deleting anything
            unless the user explicitly asks. Canvas assignments are read-only through these tools;
            do not use task or calendar editing tools to change or delete a Canvas assignment.
            If a tool reports failure, explain that failure; never claim the change succeeded.
            Be concise, practical, and friendly. Prefer short paragraphs or bullet lists when listing items.
            The current date and timezone in USER DATA are authoritative for this request.
            Use them for today, tomorrow, and relative dates, even if earlier chat messages give a different date.
            When the user gives a weekday without an exact calendar date, pass that weekday word unchanged
            in dueDate; the server resolves it in the user's timezone. Do not calculate the date yourself.
            For tool calls: use dueDate as YYYY-MM-DD, 'today', 'tomorrow', or a weekday name;
            use dueTime as HH:MM or h:mm AM/PM,
            and calendar startDate/startTime/endDate/endTime as separate LOCAL dates and times.
            For calendar dates, preserve today, tomorrow, and weekday words (including 'next Monday');
            the server resolves them. A bare weekday means the next occurrence including today.
            For '2:45-3:15 PM', both times are PM. Never calculate UTC offsets or convert event times to UTC.
            Omit timeZone unless the user explicitly specifies a different timezone; use its IANA name.
            Omit endDate for same-day creation. Include it for overnight events.
            On updates, omit unchanged fields; moving startDate also moves the end date by the same number of days.
            If a tool reports an offset mismatch, retry with the original local dates/times.
            If a time occurs twice or does not exist due to daylight saving, ask the user to clarify;
            never guess an offset. Only set startOffset/endOffset after the user chooses an occurrence.
            Confirm calendar changes using the saved tool result in the user's timezone, including date, AM/PM and timezone.
            When mentioning times to the user, always use 12-hour clock with AM/PM (for example, 3:30 PM).
            Never use 24-hour or military time in replies.
            """;

    private final AiProperties aiProperties;
    private final LlmClient llmClient;
    private final AssistantToolExecutor toolExecutor;
    private final TaskService taskService;
    private final CalendarEventService calendarEventService;
    private final DashboardService dashboardService;
    private final Clock clock;

    public AssistantService(
            AiProperties aiProperties,
            LlmClient llmClient,
            AssistantToolExecutor toolExecutor,
            TaskService taskService,
            CalendarEventService calendarEventService,
            DashboardService dashboardService,
            Clock clock) {
        this.aiProperties = aiProperties;
        this.llmClient = llmClient;
        this.toolExecutor = toolExecutor;
        this.taskService = taskService;
        this.calendarEventService = calendarEventService;
        this.dashboardService = dashboardService;
        this.clock = clock;
    }

    public AssistantChatResponse chat(UUID userId, AssistantChatRequest request) {
        ZoneId zone = resolveZone(request.timeZone());
        if (isDateQuestion(request.message())) {
            return new AssistantChatResponse("Today's date is "
                    + DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy", Locale.US)
                            .withZone(zone).format(clock.instant()) + ".", aiProperties.isConfigured());
        }
        if (!aiProperties.isConfigured()) {
            return new AssistantChatResponse(answerLocally(userId, request.message(), zone), false);
        }

        String context = buildContext(userId, zone);
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", SYSTEM_PROMPT + "\n\nUSER DATA:\n" + context));

        List<AssistantMessageDto> history = request.history() == null ? List.of() : request.history();
        int start = Math.max(0, history.size() - MAX_HISTORY);
        for (AssistantMessageDto item : history.subList(start, history.size())) {
            messages.add(Map.of("role", item.role(), "content", item.content()));
        }

        messages.add(Map.of("role", "user", "content", request.message()));

        List<Map<String, Object>> tools = AssistantToolExecutor.toolDefinitions();
        String reply = runWithTools(userId, messages, tools, zone, request.message());
        return new AssistantChatResponse(reply, true);
    }

    private String runWithTools(UUID userId, List<Map<String, Object>> messages,
            List<Map<String, Object>> tools, ZoneId zone, String userMessage) {
        List<String> savedResults = new ArrayList<>();
        String lastFailure = null;
        boolean requestedAction = userMessage.matches("(?is)\\s*(?:please\\s+|can you\\s+|could you\\s+)?(?:create|add|schedule|update|change|move|delete|remove|complete)\\b.*")
                || userMessage.matches("(?is).*?[\"“][^\"”]+[\"”]\\s+due\\b.*");
        boolean retriedMissingAction = false;
        for (int round = 0; round < MAX_TOOL_ROUNDS; round++) {
            LlmCompletion completion = llmClient.complete(messages, tools);

            if (!completion.hasToolCalls()) {
                if (!savedResults.isEmpty()) return String.join("\n", savedResults) + (lastFailure == null ? "" : "\n" + lastFailure);
                if (lastFailure != null) return lastFailure;
                if (requestedAction) {
                    if (retriedMissingAction) return "I couldn't save that change. Please retry with the title, date, and time.";
                    messages.add(Map.of("role", "system", "content",
                            "The user requested a change, but no tool has run. Use the appropriate tool now, or ask a specific question if required details are missing. Never invent conflicts or claim a change was saved."));
                    // A real clarification may be needed. Require a retry only for a refusal or success claim.
                    if (completion.content() != null && completion.content().contains("?")
                            && !completion.content().toLowerCase(Locale.US).matches("(?s).*(conflict|created|saved|scheduled|successfully).*") ) {
                        return completion.content();
                    }
                    retriedMissingAction = true;
                    continue;
                }
                return completion.content() == null ? "" : completion.content();
            }

            messages.add(buildAssistantToolMessage(completion));

            for (LlmToolCall call : completion.toolCalls()) {
                String result = toolExecutor.execute(userId, call.name(), call.arguments(), zone, userMessage);
                String confirmation = toolExecutor.describeResult(result, zone);
                if (toolExecutor.succeeded(result)) {
                    savedResults.add(confirmation);
                    lastFailure = null;
                } else lastFailure = confirmation;
                messages.add(Map.of(
                        "role", "tool",
                        "tool_call_id", call.id(),
                        "content", result));
            }
        }

        if (!savedResults.isEmpty()) return String.join("\n", savedResults) + (lastFailure == null ? "" : "\n" + lastFailure);
        return lastFailure != null ? lastFailure : "I couldn't save that change. Please try again.";
    }

    private Map<String, Object> buildAssistantToolMessage(LlmCompletion completion) {
        List<Map<String, Object>> toolCalls = new ArrayList<>();
        for (LlmToolCall call : completion.toolCalls()) {
            Map<String, Object> function = new HashMap<>();
            function.put("name", call.name());
            function.put("arguments", call.arguments());

            Map<String, Object> toolCall = new HashMap<>();
            toolCall.put("id", call.id());
            toolCall.put("type", "function");
            toolCall.put("function", function);
            toolCalls.add(toolCall);
        }

        Map<String, Object> message = new HashMap<>();
        message.put("role", "assistant");
        message.put("content", completion.content());
        message.put("tool_calls", toolCalls);
        return message;
    }

    private String buildContext(UUID userId, ZoneId zone) {
        Instant now = clock.instant();

        List<TaskResponse> tasks = taskService.list(userId, null, null, null);
        DashboardSummaryResponse summary = dashboardService.summary(userId, zone);
        List<CalendarEventResponse> events = calendarEventService.list(userId, null, null);

        StringBuilder sb = new StringBuilder();
        sb.append("Today: ").append(DATE_LABEL.withZone(zone).format(now)).append('\n');
        sb.append("Today key: ").append(TODAY_KEY.withZone(zone).format(now)).append('\n');
        sb.append("Tomorrow key: ").append(now.atZone(zone).toLocalDate().plusDays(1)).append('\n');
        sb.append("User timezone: ").append(zone.getId()).append('\n');
        sb.append('\n');

        sb.append("Combined personal-task and Canvas-assignment summary:\n");
        sb.append("- Due today: ").append(summary.dueTodayCount()).append('\n');
        sb.append("- Due this week: ").append(summary.dueThisWeekCount()).append('\n');
        sb.append("- Overdue: ").append(summary.overdueCount()).append('\n');
        sb.append("- High priority open: ").append(summary.highPriorityCount()).append('\n');
        sb.append("- Completed: ").append(summary.completedCount()).append('\n');
        sb.append("- Remaining open: ").append(summary.remainingCount()).append('\n');
        sb.append('\n');

        sb.append("Open tasks:\n");
        List<TaskResponse> openTasks = tasks.stream()
                .filter(t -> t.status() == TaskStatus.TODO || t.status() == TaskStatus.IN_PROGRESS)
                .toList();
        if (openTasks.isEmpty()) {
            sb.append("- (none)\n");
        } else {
            for (TaskResponse task : openTasks) {
                sb.append("- [id=").append(task.id()).append("] ")
                        .append(task.title())
                        .append(" [")
                        .append(task.priority())
                        .append(", ")
                        .append(task.status());
                if (task.dueDate() != null) {
                    sb.append(", due ").append(formatTaskDue(task));
                }
                sb.append("]\n");
            }
        }
        sb.append('\n');

        sb.append("Recently completed tasks:\n");
        List<TaskResponse> completed = tasks.stream()
                .filter(t -> t.status() == TaskStatus.COMPLETED)
                .limit(5)
                .toList();
        if (completed.isEmpty()) {
            sb.append("- (none)\n");
        } else {
            for (TaskResponse task : completed) {
                sb.append("- [id=").append(task.id()).append("] ").append(task.title());
                if (task.completedAt() != null) {
                    sb.append(" (completed ").append(formatInstant(task.completedAt(), zone)).append(')');
                }
                sb.append('\n');
            }
        }
        sb.append('\n');

        sb.append("Canvas assignments (completion is tracked in Prioritize):\n");
        for (CalendarEventResponse event : events) {
            if (!"DEADLINE".equals(event.canvasKind())) continue;
            var due = event.allDay() && event.canvasStartDate() != null
                    ? event.canvasStartDate() : event.startAt().atZone(zone).toLocalDate();
            var today = now.atZone(zone).toLocalDate();
            String state = event.canvasCompleted() ? "COMPLETED" : due.equals(today) ? "DUE TODAY"
                    : due.isBefore(today) ? "OVERDUE" : "UPCOMING";
            if (!event.canvasCompleted() && !event.allDay() && event.startAt().isBefore(now)) state += " / OVERDUE";
            sb.append("- [id=").append(event.id()).append("] ").append(event.title())
                    .append(" [").append(state).append(", due ").append(due)
                    .append(event.allDay() ? " (time not provided by Canvas; do not assume midnight or 11:59 PM)" : " at " + TIME_RANGE_FORMAT.withZone(zone).format(event.startAt()))
                    .append("]\n");
        }
        sb.append('\n');
        events = events.stream().filter(e -> !"DEADLINE".equals(e.canvasKind())
                && e.endAt().isAfter(now.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant())).toList();
        sb.append("Calendar events (today and upcoming):\n");
        if (events.isEmpty()) {
            sb.append("- (none)\n");
        } else {
            for (CalendarEventResponse event : events) {
                if (event.allDay() && event.canvasStartDate() != null) {
                    sb.append("- [id=").append(event.id()).append("] ").append(event.title())
                            .append(" on ").append(event.canvasStartDate())
                            .append(" (all day; exclusive end date ").append(event.canvasEndDate()).append(")\n");
                    continue;
                }
                sb.append("- [id=").append(event.id()).append("] ")
                        .append(event.title())
                        .append(" from ")
                        .append(formatInstant(event.startAt(), zone))
                        .append(" to ")
                        .append(formatInstant(event.endAt(), zone));
                if (event.allDay()) {
                    sb.append(" (all day)");
                }
                sb.append('\n');
            }
        }

        return sb.toString().trim();
    }

    private String formatInstant(Instant instant, ZoneId zone) {
        return DateTimeFormatter.ofPattern("yyyy-MM-dd h:mm a XXX", Locale.US).withZone(zone).format(instant);
    }

    static ZoneId resolveZone(String timeZone) {
        if (timeZone == null || timeZone.isBlank()) return ZoneId.of("UTC");
        try {
            return ZoneId.of(timeZone);
        } catch (java.time.DateTimeException ex) {
            throw new com.prioritize.exception.ApiException(org.springframework.http.HttpStatus.BAD_REQUEST,
                    "Invalid timezone");
        }
    }

    private static boolean isDateQuestion(String message) {
        String question = message.toLowerCase(Locale.US).replaceAll("[^a-z ]", "").trim().replaceAll(" +", " ");
        return question.matches("(what(s| is)? (the )?(date|day)( is it)?( today)?|what(s| is)? today(s)? (date|day)|today(s)? date|date today)");
    }

    private String formatTaskDue(TaskResponse task) {
        if (task.dueTime() != null) {
            return task.dueDate() + " at " + LOCAL_TIME_FORMAT.format(task.dueTime());
        }
        return task.dueDate().toString();
    }

    private String answerLocally(UUID userId, String message, ZoneId zone) {
        DashboardSummaryResponse summary = dashboardService.summary(userId, zone);
        List<TaskResponse> tasks = taskService.list(userId, null, null, null);
        String question = message == null ? "" : message.toLowerCase(Locale.US);

        if (looksLikeCreateTask(question)) {
            return """
                    I can create tasks when AI is fully configured with tool access.
                    Try again with AI_ENABLED=true and a valid API key, or create the task on the Tasks page.
                    """;
        }

        if (question.contains("overdue")) {
            return "You have " + summary.overdueCount() + " overdue open task(s) and Canvas assignment(s).";
        }
        if (question.contains("due today") || question.contains("today")) {
            return "You have " + summary.dueTodayCount() + " task(s) and Canvas assignment(s) due today.";
        }
        if (question.contains("this week") || question.contains("week")) {
            return "You have " + summary.dueThisWeekCount() + " open task(s) and Canvas assignment(s) due this week.";
        }
        if (question.contains("how many") && question.contains("task")) {
            return "You have " + summary.remainingCount() + " open task(s) in total.";
        }
        if (question.contains("open") || question.contains("remaining")) {
            return "You have " + summary.remainingCount() + " open task(s) "
                    + "(" + summary.highPriorityCount() + " high priority).";
        }
        if (question.contains("completed") || question.contains("done")) {
            return "You have completed " + summary.completedCount() + " task(s).";
        }
        if (question.contains("priority") || question.contains("focus")) {
            List<TaskResponse> focus = tasks.stream()
                    .filter(t -> t.status() == TaskStatus.TODO || t.status() == TaskStatus.IN_PROGRESS)
                    .sorted((a, b) -> b.priority().compareTo(a.priority()))
                    .limit(5)
                    .toList();
            if (focus.isEmpty()) {
                return "You have no open tasks to focus on right now.";
            }
            StringBuilder reply = new StringBuilder("Top open tasks by priority:\n");
            for (TaskResponse task : focus) {
                reply.append("- ").append(task.title()).append(" (").append(task.priority());
                if (task.dueDate() != null) {
                    reply.append(", due ").append(formatTaskDue(task));
                }
                reply.append(")\n");
            }
            return reply.toString().trim();
        }

        return """
                I can answer basic questions about your tasks right now without AI configured.
                Try asking about tasks due today, overdue items, or what to focus on.

                Current snapshot: %d open, %d due today, %d overdue.
                For full natural-language answers and actions like creating tasks, set AI_ENABLED=true and AI_API_KEY in the backend .env.
                """
                .formatted(summary.remainingCount(), summary.dueTodayCount(), summary.overdueCount())
                .trim();
    }

    private boolean looksLikeCreateTask(String question) {
        return question.contains("create") && question.contains("task")
                || question.contains("add") && question.contains("task");
    }
}
