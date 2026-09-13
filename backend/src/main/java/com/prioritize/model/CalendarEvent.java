package com.prioritize.model;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

@Entity
@Table(name = "calendar_events")
public class CalendarEvent {
    @Column(name = "canvas_completed", nullable = false)
    private boolean canvasCompleted;
    public boolean isCanvasCompleted() { return canvasCompleted; }
    public void setCanvasCompleted(boolean value) { canvasCompleted = value; }

    @Column(name = "canvas_due_time")
    private java.time.LocalTime canvasDueTime;
    @Column(name = "canvas_due_zone")
    private String canvasDueZone;
    public void setCanvasDueTime(java.time.LocalTime value) { canvasDueTime = value; }
    public void setCanvasDueZone(String value) { canvasDueZone = value; }
    private boolean hasDeadlineOverride() {
        return canvasDueTime != null && canvasDueZone != null && canvasStartDate != null && "DEADLINE".equals(canvasKind);
    }

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "category_id")
    private UUID categoryId;

    @Column(nullable = false, length = 255)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "start_at", nullable = false)
    private Instant startAt;

    @Column(name = "end_at", nullable = false)
    private Instant endAt;

    @Column(name = "all_day", nullable = false)
    private boolean allDay;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name="canvas_key")
    private String canvasKey;
    @Column(name="canvas_kind")
    private String canvasKind;
    @Column(name="canvas_url")
    private String canvasUrl;
    @Column(name="canvas_start_date")
    private java.time.LocalDate canvasStartDate;
    @Column(name="canvas_end_date")
    private java.time.LocalDate canvasEndDate;
    public String getCanvasKey() { return canvasKey; }
    public void setCanvasKey(String value) { canvasKey = value; }
    public String getCanvasKind() { return canvasKind; }
    public void setCanvasKind(String value) { canvasKind = value; }
    public String getCanvasUrl() { return canvasUrl; }
    public void setCanvasUrl(String value) { canvasUrl = value; }
    public java.time.LocalDate getCanvasStartDate() { return canvasStartDate; }
    public void setCanvasStartDate(java.time.LocalDate value) { canvasStartDate = value; }
    public java.time.LocalDate getCanvasEndDate() { return canvasEndDate; }
    public void setCanvasEndDate(java.time.LocalDate value) { canvasEndDate = value; }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (id == null) {
            id = UUID.randomUUID();
        }
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getUserId() {
        return userId;
    }

    public void setUserId(UUID userId) {
        this.userId = userId;
    }

    public UUID getCategoryId() {
        return categoryId;
    }

    public void setCategoryId(UUID categoryId) {
        this.categoryId = categoryId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Instant getStartAt() {
        return hasDeadlineOverride() ? canvasStartDate.atTime(canvasDueTime).atZone(java.time.ZoneId.of(canvasDueZone)).toInstant() : startAt;
    }

    public void setStartAt(Instant startAt) {
        this.startAt = startAt;
    }

    public Instant getEndAt() {
        return hasDeadlineOverride() ? getStartAt().plusSeconds(60) : endAt;
    }

    public void setEndAt(Instant endAt) {
        this.endAt = endAt;
    }

    public boolean isAllDay() {
        return allDay && !hasDeadlineOverride();
    }

    public void setAllDay(boolean allDay) {
        this.allDay = allDay;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
