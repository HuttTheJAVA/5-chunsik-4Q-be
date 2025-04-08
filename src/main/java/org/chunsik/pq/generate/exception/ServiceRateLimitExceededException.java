package org.chunsik.pq.generate.exception;

public class ServiceRateLimitExceededException extends RuntimeException {
    public ServiceRateLimitExceededException(String message) {
        super(message);
    }
}
