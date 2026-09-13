package com.prioritize.service;

import java.net.URI;
import java.time.*;
import java.util.*;
import com.prioritize.model.*;
import com.prioritize.repository.*;
import com.prioritize.exception.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class CanvasFeedService {
    public record Status(boolean connected, String host, String timezone, Instant lastSyncedAt,
            Instant nextSyncAt, int itemCount, String error) {}
    private final CanvasCalendarFeedRepository feeds;
    private final CalendarEventRepository events;
    private final UserRepository users;
    private final CanvasFeedClient client;
    private final CanvasFeedParser parser;
    private final CanvasFeedCrypto crypto;
    public CanvasFeedService(CanvasCalendarFeedRepository feeds, CalendarEventRepository events, UserRepository users,
            CanvasFeedClient client, CanvasFeedParser parser, CanvasFeedCrypto crypto) {
        this.feeds=feeds; this.events=events; this.users=users; this.client=client; this.parser=parser; this.crypto=crypto;
    }
    @Transactional(readOnly=true)
    public Status status(UUID userId) { return describe(feeds.findById(userId).orElse(null)); }
    public Status connect(UUID userId, String url, String timezone) {
        lock(userId);
        URI uri=client.validate(url);
        ZoneId zone;
        try { zone=ZoneId.of(timezone); }
        catch (Exception e) { throw new ApiException(HttpStatus.BAD_REQUEST,"Choose a valid calendar timezone."); }
        CanvasCalendarFeed feed=feeds.findById(userId).orElseGet(CanvasCalendarFeed::new);
        enforceCooldown(feed);
        var items=parser.parse(client.fetch(uri),uri,zone);
        // Only replace a connection after its complete new feed has been fetched and validated.
        feed.setUserId(userId); feed.setEncryptedUrl(crypto.encrypt(uri.toString()));
        feed.setHost(uri.getHost()); feed.setTimezone(zone.getId());
        apply(userId,feed,items);
        return describe(feed);
    }
    public Status sync(UUID userId, boolean automatic) {
        lock(userId);
        CanvasCalendarFeed feed=feeds.findById(userId).orElse(null);
        if (feed==null) return describe(null);
        if (automatic && feed.getLastAttemptAt()!=null && feed.getLastAttemptAt().isAfter(Instant.now().minusSeconds(1800))) return describe(feed);
        if (!automatic) enforceCooldown(feed);
        feed.setLastAttemptAt(Instant.now());
        try {
            URI uri=client.validate(crypto.decrypt(feed.getEncryptedUrl()));
            var items=parser.parse(client.fetch(uri),uri,ZoneId.of(feed.getTimezone()));
            apply(userId,feed,items);
        } catch (ApiException | IllegalStateException e) {
            // Failed downloads/parses never replace the previous successful snapshot.
            feed.setLastError("Could not refresh Canvas. Your previous items are kept. Check the feed link or try again later.");
            feeds.save(feed);
        }
        return describe(feed);
    }
    public void disconnect(UUID userId) {
        lock(userId);
        events.deleteAll(events.findByUserIdAndCanvasKeyIsNotNull(userId));
        feeds.deleteById(userId);
    }
    public void setCompleted(UUID userId, UUID eventId, boolean completed) {
        // Serialize with feed refresh so neither operation overwrites the other's fields.
        lock(userId);
        CalendarEvent event = events.findByIdAndUserId(eventId, userId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Assignment not found."));
        if (event.getCanvasKey() == null || !"DEADLINE".equals(event.getCanvasKind()))
            throw new ApiException(HttpStatus.CONFLICT, "Only Canvas assignments have a completion status.");
        event.setCanvasCompleted(completed);
        events.save(event);
    }
    public com.prioritize.dto.CalendarEventResponse setDeadlineTime(UUID userId, UUID eventId, LocalTime time, String timezone) {
        lock(userId);
        CalendarEvent event = events.findByIdAndUserId(eventId, userId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Assignment not found."));
        if (event.getCanvasKey() == null || !"DEADLINE".equals(event.getCanvasKind()) || event.getCanvasStartDate() == null)
            throw new ApiException(HttpStatus.CONFLICT, "Only Canvas assignments with a date-only feed can have a local deadline time.");
        try { ZoneId.of(timezone); }
        catch (Exception e) { throw new ApiException(HttpStatus.BAD_REQUEST, "Choose a valid timezone."); }
        event.setCanvasDueTime(time);
        event.setCanvasDueZone(timezone);
        events.save(event);
        return new com.prioritize.mapper.CalendarEventMapper().toResponse(event);
    }

    private void apply(UUID userId, CanvasCalendarFeed feed, List<CanvasFeedParser.Item> items) {
        Map<String,CalendarEvent> old=new HashMap<>();
        events.findByUserIdAndCanvasKeyIsNotNull(userId).forEach(e->old.put(e.getCanvasKey(),e));
        for (var item:items) {
            CalendarEvent e=old.remove(item.key());
            if (e==null) { e=new CalendarEvent(); e.setUserId(userId); e.setCanvasKey(item.key()); }
            e.setTitle(item.title()); e.setDescription(item.description()); e.setStartAt(item.start()); e.setEndAt(item.end());
            e.setAllDay(item.startDate()!=null); e.setCanvasStartDate(item.startDate()); e.setCanvasEndDate(item.endDate());
            e.setCanvasKind(item.kind()); e.setCanvasUrl(item.url()); events.save(e);
        }
        // Mirror only Canvas-owned copies. A successful empty feed clears those copies as well.
        events.deleteAll(old.values());
        feed.setLastAttemptAt(Instant.now()); feed.setLastSyncedAt(Instant.now()); feed.setLastError(null);
        feed.setItemCount(items.size()); feeds.save(feed);
    }
    private void lock(UUID id) {
        users.lockById(id).orElseThrow(()->new ApiException(HttpStatus.UNAUTHORIZED,"Please sign in again."));
    }
    private void enforceCooldown(CanvasCalendarFeed feed) {
        if (feed.getLastAttemptAt()!=null && feed.getLastAttemptAt().isAfter(Instant.now().minusSeconds(60)))
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,"Please wait one minute between Canvas refreshes.");
    }
    private Status describe(CanvasCalendarFeed feed) {
        if (feed==null) return new Status(false,null,null,null,null,0,null);
        return new Status(true,feed.getHost(),feed.getTimezone(),feed.getLastSyncedAt(),
                feed.getLastAttemptAt()==null ? null : feed.getLastAttemptAt().plusSeconds(1800),feed.getItemCount(),feed.getLastError());
    }
}
