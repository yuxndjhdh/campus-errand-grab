CREATE TABLE IF NOT EXISTS t_user (
    id BIGINT NOT NULL AUTO_INCREMENT,
    nickname VARCHAR(64) NOT NULL,
    role VARCHAR(16) NOT NULL DEFAULT 'USER',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_user_role (role)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS t_account (
    user_id BIGINT NOT NULL,
    account_type VARCHAR(16) NOT NULL,
    balance_cents BIGINT NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (user_id, account_type),
    CONSTRAINT fk_account_user FOREIGN KEY (user_id) REFERENCES t_user(id),
    CONSTRAINT ck_account_type CHECK (account_type IN ('AVAILABLE', 'FROZEN'))
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS t_errand_order (
    id BIGINT NOT NULL AUTO_INCREMENT,
    publisher_id BIGINT NOT NULL,
    taker_id BIGINT NULL,
    title VARCHAR(120) NOT NULL,
    detail VARCHAR(1000) NULL,
    reward_cents BIGINT NOT NULL,
    commission_cents BIGINT NOT NULL DEFAULT 0,
    status VARCHAR(16) NOT NULL,
    claim_deadline_at DATETIME(3) NOT NULL,
    deliver_deadline_at DATETIME(3) NULL,
    grabbed_at DATETIME(3) NULL,
    delivered_at DATETIME(3) NULL,
    settled_at DATETIME(3) NULL,
    cancelled_at DATETIME(3) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    CONSTRAINT fk_order_publisher FOREIGN KEY (publisher_id) REFERENCES t_user(id),
    CONSTRAINT fk_order_taker FOREIGN KEY (taker_id) REFERENCES t_user(id),
    CONSTRAINT ck_order_status CHECK (status IN ('PUBLISHED', 'TAKEN', 'DELIVERED', 'SETTLED', 'CANCELLED')),
    CONSTRAINT ck_order_reward CHECK (reward_cents > 0),
    KEY idx_status_claim (status, claim_deadline_at, id),
    KEY idx_status_deliver (status, deliver_deadline_at, id),
    KEY idx_order_publisher (publisher_id, created_at),
    KEY idx_order_taker (taker_id, created_at)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS t_ledger_entry (
    id BIGINT NOT NULL AUTO_INCREMENT,
    biz_type VARCHAR(32) NOT NULL,
    biz_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    account_type VARCHAR(16) NOT NULL,
    amount_cents BIGINT NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    CONSTRAINT fk_ledger_user FOREIGN KEY (user_id) REFERENCES t_user(id),
    CONSTRAINT uk_biz_account UNIQUE (biz_type, biz_id, user_id, account_type),
    KEY idx_ledger_account (user_id, account_type, created_at),
    KEY idx_ledger_biz (biz_type, biz_id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS t_idempotent_op (
    op_key VARCHAR(160) NOT NULL,
    op_type VARCHAR(32) NOT NULL,
    biz_id BIGINT NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (op_key)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS t_recon_report (
    id BIGINT NOT NULL AUTO_INCREMENT,
    run_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    passed BIT NOT NULL,
    report_json JSON NOT NULL,
    PRIMARY KEY (id),
    KEY idx_recon_run_at (run_at)
) ENGINE=InnoDB;

