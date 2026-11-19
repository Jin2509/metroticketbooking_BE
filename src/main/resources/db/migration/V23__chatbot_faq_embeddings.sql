ALTER TABLE chatbot_faq
    ADD COLUMN embedding TEXT,
    ADD COLUMN embedding_model VARCHAR(100);
