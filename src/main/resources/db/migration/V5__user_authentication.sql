ALTER TABLE t_user
    ADD COLUMN username VARCHAR(128) NULL,
    ADD COLUMN password_hash VARCHAR(100) NULL;

UPDATE t_user
SET username = CASE id
                   WHEN 1 THEN 'system-mint'
                   WHEN 2 THEN 'system-platform'
                   ELSE CONCAT('legacy-', id)
               END,
    password_hash = '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy'
WHERE username IS NULL OR password_hash IS NULL;

ALTER TABLE t_user
    MODIFY COLUMN username VARCHAR(128) NOT NULL,
    MODIFY COLUMN password_hash VARCHAR(100) NOT NULL,
    ADD UNIQUE KEY uk_user_username (username);
