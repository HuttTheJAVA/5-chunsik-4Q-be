package org.chunsik.pq.generate.exception;

public class ClientRateLimitExceededException extends RuntimeException {
    public ClientRateLimitExceededException(String message) {
        super(message);
    }
}
