package com.prioritize.controller;
import com.prioritize.service.CanvasFeedService;
import com.prioritize.security.CurrentUserService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/integrations/canvas")
public class CanvasFeedController {
    public record DeadlineTimeRequest(@jakarta.validation.constraints.NotNull java.time.LocalTime time,
            @NotBlank @Size(max=64) String timezone) {}
    @PutMapping("/assignments/{id}/deadline-time")
    public com.prioritize.dto.CalendarEventResponse deadlineTime(@PathVariable java.util.UUID id,
            @Valid @RequestBody DeadlineTimeRequest request) {
        return service.setDeadlineTime(current.requireCurrentUserId(), id, request.time(), request.timezone());
    }
    public record CompletionRequest(@jakarta.validation.constraints.NotNull Boolean completed) {}
    @PutMapping("/assignments/{id}/completion") @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public void complete(@PathVariable java.util.UUID id, @Valid @RequestBody CompletionRequest request) {
        service.setCompleted(current.requireCurrentUserId(), id, request.completed());
    }
    public record ConnectRequest(@NotBlank @Size(max=2048) String feedUrl,@NotBlank @Size(max=64) String timezone) {}
    private final CanvasFeedService service;
    private final CurrentUserService current;
    public CanvasFeedController(CanvasFeedService service,CurrentUserService current) { this.service=service; this.current=current; }
    @GetMapping public CanvasFeedService.Status status() { return service.status(current.requireCurrentUserId()); }
    @PutMapping public CanvasFeedService.Status connect(@Valid @RequestBody ConnectRequest request) {
        return service.connect(current.requireCurrentUserId(),request.feedUrl(),request.timezone());
    }
    @PostMapping("/sync") public CanvasFeedService.Status sync() { return service.sync(current.requireCurrentUserId(),false); }
    @DeleteMapping @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public void disconnect() { service.disconnect(current.requireCurrentUserId()); }
}
