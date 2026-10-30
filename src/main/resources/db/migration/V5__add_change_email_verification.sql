ALTER TABLE email_verification_challenge
    DROP CONSTRAINT email_verification_challenge_purpose_check;

ALTER TABLE email_verification_challenge
    ADD CONSTRAINT email_verification_challenge_purpose_check
    CHECK (purpose IN ('REGISTER', 'RESET_PASSWORD', 'CHANGE_EMAIL'));
