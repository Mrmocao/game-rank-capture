-- Backward-compatible migration for databases created before AI configuration
-- was added to V1. Fresh installs already contain this table; IF NOT EXISTS
-- keeps the migration safe for both paths.
CREATE TABLE IF NOT EXISTS ai_provider_config (
 id BIGINT PRIMARY KEY,
 provider_name VARCHAR(80) NOT NULL, base_url VARCHAR(500) NOT NULL, model VARCHAR(160) NOT NULL,
 api_key_ciphertext TEXT NULL, api_key_configured BOOLEAN NOT NULL DEFAULT FALSE,
 timeout_seconds INT NOT NULL DEFAULT 60, temperature DECIMAL(4,3) NOT NULL DEFAULT 0.200,
 enabled BOOLEAN NOT NULL DEFAULT FALSE, created_at DATETIME(3) NOT NULL, updated_at DATETIME(3) NOT NULL,
 version INT NOT NULL DEFAULT 0, CHECK(timeout_seconds BETWEEN 5 AND 300), CHECK(temperature>=0 AND temperature<=2)
);
