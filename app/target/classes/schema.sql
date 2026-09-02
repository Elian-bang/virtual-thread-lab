CREATE TABLE IF NOT EXISTS target (
  id         BIGINT       NOT NULL PRIMARY KEY,
  address    VARCHAR(64)  NOT NULL,
  sent_count INT          NOT NULL DEFAULT 0
) ENGINE=InnoDB;
