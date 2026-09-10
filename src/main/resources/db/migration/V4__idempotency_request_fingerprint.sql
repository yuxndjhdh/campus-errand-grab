ALTER TABLE t_idempotent_op
    ADD COLUMN request_hash CHAR(64) NULL,
    ADD COLUMN response_json JSON NULL;
