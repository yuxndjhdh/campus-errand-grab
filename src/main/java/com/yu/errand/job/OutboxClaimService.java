package com.yu.errand.job;

import com.yu.errand.domain.model.OutboxEvent;
import com.yu.errand.repository.OutboxRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class OutboxClaimService {
    private final OutboxRepository outbox;

    public OutboxClaimService(OutboxRepository outbox) { this.outbox = outbox; }

    @Transactional
    public List<OutboxEvent> claimBatch(int limit, LocalDateTime now, LocalDateTime lockUntil) {
        return outbox.claimBatch(limit, now, lockUntil);
    }
}
