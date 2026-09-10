INSERT INTO t_user (id, nickname, role)
VALUES (1, 'Mint Treasury', 'MINT')
ON DUPLICATE KEY UPDATE nickname = VALUES(nickname), role = VALUES(role);

INSERT INTO t_user (id, nickname, role)
VALUES (2, 'Campus Platform', 'PLATFORM')
ON DUPLICATE KEY UPDATE nickname = VALUES(nickname), role = VALUES(role);

INSERT INTO t_account (user_id, account_type, balance_cents)
VALUES (1, 'AVAILABLE', 0), (1, 'FROZEN', 0),
       (2, 'AVAILABLE', 0), (2, 'FROZEN', 0)
ON DUPLICATE KEY UPDATE user_id = VALUES(user_id);

