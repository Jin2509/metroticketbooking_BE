package com.metroticket_api.ai;

public interface StudentCardExtractor {

    boolean available();

    /**
     * @throws AiExtractionException retryable = true khi lỗi tạm thời (quá tải, timeout) → worker thử lại sau.
     */
    StudentCardExtraction extract(byte[] image, String mimeType) throws AiExtractionException;

    class AiExtractionException extends Exception {
        private final boolean retryable;

        public AiExtractionException(String message, boolean retryable, Throwable cause) {
            super(message, cause);
            this.retryable = retryable;
        }

        public boolean retryable() {
            return retryable;
        }
    }
}
