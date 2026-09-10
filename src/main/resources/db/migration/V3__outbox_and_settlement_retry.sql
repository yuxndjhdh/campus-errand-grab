ALTER TABLE t_errand_order
    ADD COLUMN settlement_retry_count INT NOT NULL DEFAULT 0,
    ADD COLUMN settlement_next_retry_at DATETIME(3) NULL,
    ADD COLUMN settlement_last_error VARCHAR(1000) NULL,
    ADD COLUMN settlement_dead BIT NOT NULL DEFAULT 0;

CREATE TABLE t_outbox_event (
    id BIGINT NOT NULL AUTO_INCREMENT,
    event_type VARCHAR(64) NOT NULL,
    biz_id BIGINT NOT NULL,
    payload_json JSON NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    retry_count INT NOT NULL DEFAULT 0,
    next_retry_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    locked_until DATETIME(3) NULL,
    last_error VARCHAR(1000) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    published_at DATETIME(3) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_outbox_event (event_type, biz_id),
    KEY idx_outbox_poll (status, next_retry_at, id)
) ENGINE=InnoDB;
