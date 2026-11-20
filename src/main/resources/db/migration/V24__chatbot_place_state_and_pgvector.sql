CREATE EXTENSION IF NOT EXISTS vector;

UPDATE chatbot_faq
SET embedding = '[' || embedding || ']'
WHERE embedding IS NOT NULL
  AND left(embedding, 1) <> '[';

CREATE INDEX idx_chatbot_faq_embedding_cosine
    ON chatbot_faq USING hnsw ((embedding::vector(768)) vector_cosine_ops)
    WHERE status = 'ACTIVE' AND embedding IS NOT NULL;

ALTER TABLE chatbot_session
    ADD COLUMN pending_place_resolution TEXT,
    ADD COLUMN pending_place_expires_at TIMESTAMPTZ;
