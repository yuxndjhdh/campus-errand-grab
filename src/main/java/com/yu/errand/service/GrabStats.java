package com.yu.errand.service;

import org.springframework.stereotype.Component;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.concurrent.atomic.LongAdder;

@Component
public class GrabStats {
    private final LongAdder attempts = new LongAdder();
    private final LongAdder winners = new LongAdder();
    private final LongAdder rejected = new LongAdder();
    private final LongAdder filtered = new LongAdder();
    private final LongAdder rateLimited = new LongAdder();
    private final LongAdder dbCas = new LongAdder();
    private final Counter attemptCounter;
    private final Counter winnerCounter;
    private final Counter rejectedCounter;
    private final Counter filteredCounter;
    private final Counter rateLimitedCounter;
    private final Counter dbCasCounter;

    public GrabStats(MeterRegistry registry) {
        attemptCounter = Counter.builder("grab_attempt_total").register(registry);
        winnerCounter = Counter.builder("grab_winner_total").register(registry);
        rejectedCounter = Counter.builder("grab_rejected_total").register(registry);
        filteredCounter = Counter.builder("grab_redis_filtered_total").register(registry);
        rateLimitedCounter = Counter.builder("grab_rate_limited_total").register(registry);
        dbCasCounter = Counter.builder("grab_db_cas_total").register(registry);
    }

    public void attempt() { attempts.increment(); attemptCounter.increment(); }
    public void winner() { winners.increment(); winnerCounter.increment(); }
    public void reject() { rejected.increment(); rejectedCounter.increment(); }
    public void filter() { filtered.increment(); filteredCounter.increment(); }
    public void dbCasAttempt() { dbCas.increment(); dbCasCounter.increment(); }
    public void rateLimitRejected() { rateLimited.increment(); rateLimitedCounter.increment(); }
    public long attempts() { return attempts.sum(); }
    public long winners() { return winners.sum(); }
    public long rejected() { return rejected.sum(); }
    public long filtered() { return filtered.sum(); }
    public long rateLimited() { return rateLimited.sum(); }
    public long dbCas() { return dbCas.sum(); }
}
