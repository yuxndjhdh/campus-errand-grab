CREATE TABLE t_payment (
    id BIGINT NOT NULL AUTO_INCREMENT,
    provider VARCHAR(32) NOT NULL,
    provider_payment_id VARCHAR(128) NOT NULL,
    callback_event_id VARCHAR(160) NOT NULL,
    user_id BIGINT NOT NULL,
    amount_cents BIGINT NOT NULL,
    status VARCHAR(24) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_payment_provider_id (provider, provider_payment_id),
    UNIQUE KEY uk_payment_callback_event (provider, callback_event_id),
    CONSTRAINT fk_payment_user FOREIGN KEY (user_id) REFERENCES t_user(id),
    CONSTRAINT ck_payment_amount CHECK (amount_cents > 0),
    KEY idx_payment_user (user_id, created_at)
) ENGINE=InnoDB;

CREATE TABLE t_refund (
    id BIGINT NOT NULL AUTO_INCREMENT,
    payment_id BIGINT NOT NULL,
    request_key VARCHAR(160) NOT NULL,
    user_id BIGINT NOT NULL,
    amount_cents BIGINT NOT NULL,
    status VARCHAR(24) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_refund_request (request_key),
    CONSTRAINT fk_refund_payment FOREIGN KEY (payment_id) REFERENCES t_payment(id),
    CONSTRAINT fk_refund_user FOREIGN KEY (user_id) REFERENCES t_user(id),
    CONSTRAINT ck_refund_amount CHECK (amount_cents > 0),
    KEY idx_refund_payment (payment_id, created_at)
) ENGINE=InnoDB;

CREATE TABLE t_notification (
    id BIGINT NOT NULL AUTO_INCREMENT,
    event_key VARCHAR(160) NOT NULL,
    user_id BIGINT NOT NULL,
    notification_type VARCHAR(48) NOT NULL,
    payload_json JSON NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    attempts INT NOT NULL DEFAULT 0,
    last_error VARCHAR(1000) NULL,
    sent_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_notification_event (event_key),
    CONSTRAINT fk_notification_user FOREIGN KEY (user_id) REFERENCES t_user(id),
    KEY idx_notification_status (status, created_at)
) ENGINE=InnoDB;

CREATE TABLE t_dispute (
    id BIGINT NOT NULL AUTO_INCREMENT,
    payment_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'OPEN',
    reason VARCHAR(1000) NOT NULL,
    resolution VARCHAR(1000) NULL,
    resolved_by BIGINT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    resolved_at DATETIME(3) NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_dispute_payment FOREIGN KEY (payment_id) REFERENCES t_payment(id),
    CONSTRAINT fk_dispute_user FOREIGN KEY (user_id) REFERENCES t_user(id),
    CONSTRAINT fk_dispute_resolver FOREIGN KEY (resolved_by) REFERENCES t_user(id),
    KEY idx_dispute_status (status, created_at)
) ENGINE=InnoDB;

CREATE TABLE t_compensation (
    id BIGINT NOT NULL AUTO_INCREMENT,
    dispute_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    request_key VARCHAR(160) NOT NULL,
    amount_cents BIGINT NOT NULL,
    reason VARCHAR(1000) NOT NULL,
    operator_user_id BIGINT NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_compensation_request (request_key),
    CONSTRAINT fk_compensation_dispute FOREIGN KEY (dispute_id) REFERENCES t_dispute(id),
    CONSTRAINT fk_compensation_user FOREIGN KEY (user_id) REFERENCES t_user(id),
    CONSTRAINT fk_compensation_operator FOREIGN KEY (operator_user_id) REFERENCES t_user(id),
    CONSTRAINT ck_compensation_amount CHECK (amount_cents > 0)
) ENGINE=InnoDB;
