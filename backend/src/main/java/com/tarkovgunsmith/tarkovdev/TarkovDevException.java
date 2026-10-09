package com.tarkovgunsmith.tarkovdev;

/** A json.tarkov.dev request failed (I/O error, unexpected status or unreadable body). */
public class TarkovDevException extends RuntimeException {

    public TarkovDevException(String message) {
        super(message);
    }

    public TarkovDevException(String message, Throwable cause) {
        super(message, cause);
    }
}
