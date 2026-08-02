-- Runs automatically the first time the postgres container starts.
-- This is what makes Postgres capable of storing and searching vector embeddings.
CREATE EXTENSION IF NOT EXISTS vector;
