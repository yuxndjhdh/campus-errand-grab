ALTER TABLE t_refund
    ADD UNIQUE KEY uk_refund_payment (payment_id);
