package com.nocook.service.chaos;

/** A downstream fault-injection dependency (Toxiproxy, mock OMS, another replica) failed; mapped to HTTP 502. */
public class ChaosException extends RuntimeException {

    public ChaosException(String message, Throwable cause) {
        super(message, cause);
    }
}
