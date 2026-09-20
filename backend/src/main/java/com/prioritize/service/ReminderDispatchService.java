package com.prioritize.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.prioritize.model.AppNotification;
import com.prioritize.exception.ResourceNotFoundException;
import com.prioritize.model.NotificationChannel;
import com.prioritize.model.NotificationSettings;
import com.prioritize.model.Reminder;
import com.prioritize.model.ReminderStatus;
import com.prioritize.model.User;
import com.prioritize.repository.AppNotificationRepository;
import com.prioritize.repository.NotificationSettingsRepository;
import com.prioritize.repository.ReminderRepository;
import com.prioritize.repository.UserRepository;
import com.prioritize.service.ReminderContentResolver.ReminderContent;

@Service
public class ReminderDispatchService {

    private static final Logger log = LoggerFactory.getLogger(ReminderDispatchService.class);
    private static final int BATCH_SIZE = 50;

    private final ReminderRepository reminderRepository;
    private final AppNotificationRepository appNotificationRepository;
    private final NotificationSettingsRepository notificationSettingsRepository;
    private final UserRepository userRepository;
    private final ReminderContentResolver reminderContentResolver;
    private final EmailNotificationService emailNotificationService;
    private final SmsNotificationService smsNotificationService;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;

    public ReminderDispatchService(
            ReminderRepository reminderRepository,
            AppNotificationRepository appNotificationRepository,
            NotificationSettingsRepository notificationSettingsRepository,
            UserRepository userRepository,
            ReminderContentResolver reminderContentResolver,
            EmailNotificationService emailNotificationService,
            SmsNotificationService smsNotificationService,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        this.reminderRepository = reminderRepository;
        this.appNotificationRepository = appNotificationRepository;
        this.notificationSettingsRepository = notificationSettingsRepository;
        this.userRepository = userRepository;
        this.reminderContentResolver = reminderContentResolver;
        this.emailNotificationService = emailNotificationService;
        this.smsNotificationService = smsNotificationService;
        this.clock = clock;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Scheduled(fixedDelay = 30_000)
    public void dispatchDueReminders() {
        Instant now = Instant.now(clock);
        List<Reminder> due = reminderRepository.findDuePending(now, PageRequest.of(0, BATCH_SIZE));
        for (Reminder reminder : due) {
            UUID reminderId = reminder.getId();
            try {
                transactionTemplate.executeWithoutResult(status -> processReminder(reminderId));
            } catch (Exception ex) {
                log.warn("Failed to process reminder {}: {}", reminderId, ex.getMessage());
            }
        }
    }

    public void processReminder(UUID reminderId) {
        Instant now = Instant.now(clock);
        int claimed = reminderRepository.claimPending(reminderId, now);
        if (claimed == 0) {
            return;
        }

        Reminder reminder = reminderRepository.findById(reminderId).orElse(null);
        if (reminder == null) {
            return;
        }

        try {
            deliver(reminder);
            reminder.setStatus(ReminderStatus.SENT);
            reminder.setSentAt(now);
            reminder.setFailureReason(null);
        } catch (ResourceNotFoundException ex) {
            reminder.setStatus(ReminderStatus.CANCELLED);
            reminder.setFailureReason("The related item no longer exists.");
        } catch (DeliveryException ex) {
            reminder.setStatus(ReminderStatus.FAILED);
            reminder.setFailureReason(ex.getMessage());
            log.debug("Reminder {} delivery failed: {}", reminderId, ex.getMessage());
        } catch (Exception ex) {
            reminder.setStatus(ReminderStatus.FAILED);
            reminder.setFailureReason(trimReason(ex.getMessage()));
            log.warn("Reminder {} unexpected failure: {}", reminderId, ex.getMessage());
        }
        reminderRepository.save(reminder);
    }

    private void deliver(Reminder reminder) {
        NotificationSettings settings = notificationSettingsRepository.findById(reminder.getUserId())
                .orElseGet(() -> {
                    NotificationSettings defaults = new NotificationSettings();
                    defaults.setUserId(reminder.getUserId());
                    defaults.setEmailEnabled(false);
                    defaults.setSmsEnabled(false);
                    defaults.setInAppEnabled(true);
                    return defaults;
                });

        User user = userRepository.findById(reminder.getUserId())
                .orElseThrow(() -> new DeliveryException("User not found"));

        ReminderContent content = reminderContentResolver.resolve(
                reminder.getUserId(),
                reminder.getRelatedEntityType(),
                reminder.getRelatedEntityId());

        String subject = "Reminder: " + content.title();
        String body = content.title() + "\n" + content.eventAtLabel();

        NotificationChannel channel = reminder.getChannel();
        if (channel == NotificationChannel.EMAIL) {
            if (!settings.isEmailEnabled()) {
                throw new DeliveryException("email disabled");
            }
            emailNotificationService.send(user.getEmail(), subject, body);
            return;
        }
        if (channel == NotificationChannel.SMS) {
            if (!settings.isSmsEnabled()) {
                throw new DeliveryException("SMS disabled");
            }
            if (!user.isPhoneVerified() || user.getPhoneNumber() == null || user.getPhoneNumber().isBlank()) {
                throw new DeliveryException("phone not verified");
            }
            smsNotificationService.send(user.getPhoneNumber(), "Prioritize: " + content.title() + " — " + content.eventAtLabel());
            return;
        }
        if (channel == NotificationChannel.IN_APP) {
            if (!settings.isInAppEnabled()) {
                throw new DeliveryException("in-app disabled");
            }
            AppNotification notification = new AppNotification();
            notification.setUserId(reminder.getUserId());
            notification.setTitle("Reminder");
            notification.setBody(content.title() + " · " + content.eventAtLabel());
            notification.setRelatedEntityType(reminder.getRelatedEntityType().name());
            notification.setRelatedEntityId(reminder.getRelatedEntityId());
            appNotificationRepository.save(notification);
            return;
        }
        throw new DeliveryException("Unsupported channel");
    }

    private static String trimReason(String message) {
        if (message == null || message.isBlank()) {
            return "Delivery failed";
        }
        return message.length() <= 500 ? message : message.substring(0, 500);
    }

    private static final class DeliveryException extends RuntimeException {
        private DeliveryException(String message) {
            super(message);
        }
    }
}
