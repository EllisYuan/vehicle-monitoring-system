ALTER TABLE sys_user
    ADD COLUMN failed_attempts    INT         NOT NULL DEFAULT 0,
    ADD COLUMN locked_until       DATETIME(3) NULL;
