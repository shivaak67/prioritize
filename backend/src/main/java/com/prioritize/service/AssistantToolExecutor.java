package com.prioritize.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.prioritize.dto.CalendarEventRequest;
import com.prioritize.dto.CalendarEventResponse;
import com.prioritize.dto.TaskRequest;
import com.prioritize.dto.TaskResponse;
import com.prioritize.model.TaskPriority;
import com.prioritize.model.TaskStatus;

@Component
public class AssistantToolExecutor {


    private static final DateTimeFormatter TIME_12H =
            DateTimeFormatter.ofPattern("h:mm a", Locale.US);

    private final ObjectMapper objectMapper;
    private final TaskService taskService;
    private final CalendarEventService calendarEventService;
    private final Clock clock;

    public AssistantToolExecutor(
            ObjectMapper objectMapper,
            TaskService taskService,
            CalendarEventService calendarEventService,
            Clock clock) {
        this.objectMapper = objectMapper;
        this.taskService = taskService;
        this.calendarEventService = calendarEventService;
        this.clock = clock;
    }

    static List<Map<String, Object>> toolDefinitions() {
        List<Map<String, Object>> tools = new ArrayList<>();
        tools.add(functionTool(
                "create_task",
                "Create a new task for the user.",
                Map.of(
                        "title", stringProp("Task title"),
                        "dueDate", stringProp("Due date as YYYY-MM-DD, or 'today' or 'tomorrow'"),
                        "dueTime", stringProp("Due time as HH:MM (24h) or h:mm AM/PM"),
                        "priority", enumProp("Task priority", "LOW", "MEDIUM", "HIGH", "URGENT"),
                        "status", enumProp("Task status", "TODO", "IN_PROGRESS", "COMPLETED", "CANCELLED"),
                        "description", stringProp("Optional description")),
                List.of("title")));
        tools.add(functionTool(
                "update_task",
                "Update an existing task. Provide taskId or taskTitle to identify the task.",
                Map.of(
                        "taskId", stringProp("Task UUID"),
                        "taskTitle", stringProp("Exact or partial task title if taskId is unknown"),
                        "title", stringProp("New title"),
                        "dueDate", stringProp("Due date as YYYY-MM-DD, or 'today' or 'tomorrow'"),
                        "dueTime", stringProp("Due time as HH:MM (24h) or h:mm AM/PM, or empty to clear"),
                        "priority", enumProp("Task priority", "LOW", "MEDIUM", "HIGH", "URGENT"),
                        "status", enumProp("Task status", "TODO", "IN_PROGRESS", "COMPLETED", "CANCELLED"),
                        "description", stringProp("Optional description")),
                List.of()));
        tools.add(functionTool(
                "complete_task",
                "Mark a task as completed.",
                Map.of(
                        "taskId", stringProp("Task UUID"),
                        "taskTitle", stringProp("Exact or partial task title if taskId is unknown")),
                List.of()));
        tools.add(functionTool(
                "delete_task",
                "Delete a task.",
                Map.of(
                        "taskId", stringProp("Task UUID"),
                        "taskTitle", stringProp("Exact or partial task title if taskId is unknown")),
                List.of()));
        tools.add(functionTool(
                "create_calendar_event",
                "Create a calendar event with a start and end time.",
                Map.of(
                        "title", stringProp("Event title"),
                        "startAt", stringProp("Start time as ISO-8601 datetime"),
                        "endAt", stringProp("End time as ISO-8601 datetime"),
                        "description", stringProp("Optional description")),
                List.of("title", "startAt", "endAt")));
        tools.add(functionTool(
                "update_calendar_event",
                "Update a calendar event. Provide eventId or eventTitle to identify the event.",
                Map.of(
                        "eventId", stringProp("Event UUID"),
                        "eventTitle", stringProp("Exact or partial event title if eventId is unknown"),
                        "title", stringProp("New title"),
                        "startAt", stringProp("Start time as ISO-8601 datetime"),
                        "endAt", stringProp("End time as ISO-8601 datetime"),
                        "description", stringProp("Optional description")),
                List.of()));
        tools.add(functionTool(
                "delete_calendar_event",
                "Delete a calendar event.",
                Map.of(
                        "eventId", stringProp("Event UUID"),
                        "eventTitle", stringProp("Exact or partial event title if eventId is unknown")),
                List.of()));
        return tools;
    }

    public String execute(UUID userId, String toolName, String argumentsJson, ZoneId zone) {
        try {
            JsonNode args = objectMapper.readTree(argumentsJson == null ? "{}" : argumentsJson);
            return switch (toolName) {
                case "create_task" -> createTask(userId, args, zone);
                case "update_task" -> updateTask(userId, args, zone);
                case "complete_task" -> completeTask(userId, args);
                case "delete_task" -> deleteTask(userId, args);
                case "create_calendar_event" -> createEvent(userId, args, zone);
                case "update_calendar_event" -> updateEvent(userId, args, zone);
                case "delete_calendar_event" -> deleteEvent(userId, args);
                default -> error("Unknown tool: " + toolName);
            };
        } catch (Exception ex) {
            return error(ex.getMessage() == null ? "Tool execution failed" : ex.getMessage());
        }
    }

    private String createTask(UUID userId, JsonNode args, ZoneId zone) {
        String title = requiredText(args, "title");
        TaskRequest request = new TaskRequest(
                title,
                optionalText(args, "description"),
                null,
                null,
                parseDueDate(args.get("dueDate"), zone),
                parseDueTime(args.get("dueTime")),
                null,
                parsePriority(args.get("priority")),
                parseStatus(args.get("status")));
        TaskResponse created = taskService.create(userId, request);
        return success("Task created", taskSummary(created));
    }

    private String updateTask(UUID userId, JsonNode args, ZoneId zone) {
        TaskResponse existing = findTask(userId, args);
        TaskRequest request = new TaskRequest(
                optionalText(args, "title") != null ? optionalText(args, "title") : existing.title(),
                args.has("description") ? optionalText(args, "description") : existing.description(),
                existing.categoryId(),
                existing.projectId(),
                args.has("dueDate") ? parseDueDate(args.get("dueDate"), zone) : existing.dueDate(),
                args.has("dueTime") ? parseDueTime(args.get("dueTime")) : existing.dueTime(),
                existing.estimatedMinutes(),
                args.has("priority") ? parsePriority(args.get("priority")) : existing.priority(),
                args.has("status") ? parseStatus(args.get("status")) : existing.status());
        TaskResponse updated = taskService.update(userId, existing.id(), request);
        return success("Task updated", taskSummary(updated));
    }

    private String completeTask(UUID userId, JsonNode args) {
        TaskResponse existing = findTask(userId, args);
        TaskRequest request = new TaskRequest(
                existing.title(),
                existing.description(),
                existing.categoryId(),
                existing.projectId(),
                existing.dueDate(),
                existing.dueTime(),
                existing.estimatedMinutes(),
                existing.priority(),
                TaskStatus.COMPLETED);
        TaskResponse updated = taskService.update(userId, existing.id(), request);
        return success("Task completed", taskSummary(updated));
    }

    private String deleteTask(UUID userId, JsonNode args) {
        TaskResponse existing = findTask(userId, args);
        taskService.delete(userId, existing.id());
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("id", existing.id().toString());
        payload.put("title", existing.title());
        return success("Task deleted", payload);
    }

    private String createEvent(UUID userId, JsonNode args, ZoneId zone) {
        String title = requiredText(args, "title");
        Instant startAt = parseInstant(requiredText(args, "startAt"), zone);
        Instant endAt = parseInstant(requiredText(args, "endAt"), zone);
        CalendarEventRequest request = new CalendarEventRequest(
                title,
                optionalText(args, "description"),
                null,
                startAt,
                endAt,
                false);
        CalendarEventResponse created = calendarEventService.create(userId, request);
        return success("Calendar event created", eventSummary(created, zone));
    }

    private String updateEvent(UUID userId, JsonNode args, ZoneId zone) {
        CalendarEventResponse existing = findEvent(userId, args);
        CalendarEventRequest request = new CalendarEventRequest(
                optionalText(args, "title") != null ? optionalText(args, "title") : existing.title(),
                args.has("description") ? optionalText(args, "description") : existing.description(),
                existing.categoryId(),
                args.has("startAt") ? parseInstant(requiredText(args, "startAt"), zone) : existing.startAt(),
                args.has("endAt") ? parseInstant(requiredText(args, "endAt"), zone) : existing.endAt(),
                existing.allDay());
        CalendarEventResponse updated = calendarEventService.update(userId, existing.id(), request);
        return success("Calendar event updated", eventSummary(updated, zone));
    }

    private String deleteEvent(UUID userId, JsonNode args) {
        CalendarEventResponse existing = findEvent(userId, args);
        calendarEventService.delete(userId, existing.id());
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("id", existing.id().toString());
        payload.put("title", existing.title());
        return success("Calendar event deleted", payload);
    }

    private TaskResponse findTask(UUID userId, JsonNode args) {
        String taskId = optionalText(args, "taskId");
        if (taskId != null) {
            return taskService.get(userId, UUID.fromString(taskId));
        }
        String taskTitle = optionalText(args, "taskTitle");
        if (taskTitle == null) {
            throw new IllegalArgumentException("Provide taskId or taskTitle");
        }
        return findByTitle(taskService.list(userId, null, null, null), taskTitle, "task");
    }

    private CalendarEventResponse findEvent(UUID userId, JsonNode args) {
        String eventId = optionalText(args, "eventId");
        if (eventId != null) {
            return calendarEventService.get(userId, UUID.fromString(eventId));
        }
        String eventTitle = optionalText(args, "eventTitle");
        if (eventTitle == null) {
            throw new IllegalArgumentException("Provide eventId or eventTitle");
        }
        Instant now = clock.instant();
        Instant weekAhead = now.plusSeconds(7L * 24 * 60 * 60);
        List<CalendarEventResponse> events = calendarEventService.list(userId, now.minusSeconds(30L * 24 * 60 * 60), weekAhead);
        return findByTitle(events, eventTitle, "calendar event");
    }

    private <T> T findByTitle(List<T> items, String title, String label) {
        String needle = title.trim().toLowerCase(Locale.US);
        T exact = null;
        T partial = null;
        for (T item : items) {
            String candidate = itemLabel(item).toLowerCase(Locale.US);
            if (candidate.equals(needle)) {
                exact = item;
            } else if (candidate.contains(needle) && partial == null) {
                partial = item;
            }
        }
        if (exact != null) {
            return exact;
        }
        if (partial != null) {
            return partial;
        }
        throw new IllegalArgumentException("No matching " + label + " found for: " + title);
    }

    private String itemLabel(Object item) {
        if (item instanceof TaskResponse task) {
            return task.title();
        }
        if (item instanceof CalendarEventResponse event) {
            return event.title();
        }
        return "";
    }

    private ObjectNode taskSummary(TaskResponse task) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("id", task.id().toString());
        node.put("title", task.title());
        node.put("status", task.status().name());
        node.put("priority", task.priority().name());
        if (task.dueDate() != null) {
            node.put("dueDate", task.dueDate().toString());
        }
        if (task.dueTime() != null) {
            node.put("dueTime", TIME_12H.format(task.dueTime()));
        }
        return node;
    }

    private ObjectNode eventSummary(CalendarEventResponse event, ZoneId zone) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("id", event.id().toString());
        node.put("title", event.title());
        node.put("startAt", event.startAt().atZone(zone).toOffsetDateTime().toString());
        node.put("endAt", event.endAt().atZone(zone).toOffsetDateTime().toString());
        node.put("timeZone", zone.getId());
        node.put("allDay", event.allDay());
        return node;
    }

    private LocalDate parseDueDate(JsonNode node, ZoneId zone) {
        if (node == null || node.isNull()) {
            return null;
        }
        String value = node.asText().trim();
        if (value.isEmpty()) {
            return null;
        }
        String lower = value.toLowerCase(Locale.US);
        LocalDate today = LocalDate.now(clock.withZone(zone));
        if ("today".equals(lower)) {
            return today;
        }
        if ("tomorrow".equals(lower)) {
            return today.plusDays(1);
        }
        return LocalDate.parse(value);
    }

    private LocalTime parseDueTime(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        String value = node.asText().trim();
        if (value.isEmpty()) {
            return null;
        }
        try {
            return LocalTime.parse(value);
        } catch (DateTimeParseException ignored) {
            // fall through
        }
        try {
            return LocalTime.parse(value, DateTimeFormatter.ofPattern("h:mm a", Locale.US));
        } catch (DateTimeParseException ignored) {
            // fall through
        }
        return LocalTime.parse(value, DateTimeFormatter.ofPattern("h:mma", Locale.US));
    }

    private Instant parseInstant(String value, ZoneId zone) {
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ignored) {
            return LocalDateTime.parse(value).atZone(zone).toInstant();
        }
    }

    private TaskPriority parsePriority(JsonNode node) {
        if (node == null || node.isNull() || node.asText().isBlank()) {
            return TaskPriority.MEDIUM;
        }
        return TaskPriority.valueOf(node.asText().trim().toUpperCase(Locale.US));
    }

    private TaskStatus parseStatus(JsonNode node) {
        if (node == null || node.isNull() || node.asText().isBlank()) {
            return TaskStatus.TODO;
        }
        return TaskStatus.valueOf(node.asText().trim().toUpperCase(Locale.US));
    }

    private String requiredText(JsonNode args, String field) {
        String value = optionalText(args, field);
        if (value == null) {
            throw new IllegalArgumentException("Missing required field: " + field);
        }
        return value;
    }

    private String optionalText(JsonNode args, String field) {
        JsonNode node = args.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        String value = node.asText().trim();
        return value.isEmpty() ? null : value;
    }

    private String success(String message, ObjectNode data) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("success", true);
        root.put("message", message);
        root.set("data", data);
        return root.toString();
    }

    private String error(String message) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("success", false);
        root.put("error", message);
        return root.toString();
    }

    private static Map<String, Object> functionTool(
            String name, String description, Map<String, Object> properties, List<String> required) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("type", "object");
        parameters.put("properties", properties);
        parameters.put("required", required);

        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", name);
        function.put("description", description);
        function.put("parameters", parameters);

        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("type", "function");
        tool.put("function", function);
        return tool;
    }

    private static Map<String, Object> stringProp(String description) {
        return Map.of("type", "string", "description", description);
    }

    private static Map<String, Object> enumProp(String description, String... values) {
        return Map.of("type", "string", "description", description, "enum", List.of(values));
    }
}
