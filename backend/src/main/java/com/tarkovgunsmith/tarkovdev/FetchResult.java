package com.tarkovgunsmith.tarkovdev;

/**
 * Outcome of a conditional GET. Pass {@link #etag()} back as {@code ifNoneMatch} on the next fetch
 * to get {@link NotModified} when the payload hasn't changed.
 */
public sealed interface FetchResult<T> {

    /** Validator from the response, or {@code null} if the server sent none. */
    String etag();

    record Modified<T>(T body, String etag) implements FetchResult<T> {}

    record NotModified<T>(String etag) implements FetchResult<T> {}
}
