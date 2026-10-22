CREATE TABLE IF NOT EXISTS namebook_players (
  player_uuid  BINARY(16)   NOT NULL PRIMARY KEY,
  name         VARCHAR(32)  NOT NULL,
  UNIQUE KEY uq_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
