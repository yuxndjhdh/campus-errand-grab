package com.yu.errand.job;

import com.yu.errand.domain.model.OutboxEvent;
import com.yu.errand.repository.OutboxRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class OutboxStateService {
    private final OutboxRepository outbox;

    public OutboxStateService(OutboxRepository outbox) { this.outbox = outbox; }

    @Transactional
    public int markPublished(OutboxEvent event) {
        return outbox.markPublished(event.id(), event.lockedUntil());
    }

    @Transactional
    public int markRetry(OutboxEvent event, int retryCount, LocalDateTime nextRetryAt, String error) {
        return outbox.markRetry(event.id(), event.lockedUntil(), retryCount, nextRetryAt, error);
    }

    @Transactional
    public int markDead(OutboxEvent event, int retryCount, String error) {
        return outbox.markDead(event.id(), event.lockedUntil(), retryCount, error);
    }
}
