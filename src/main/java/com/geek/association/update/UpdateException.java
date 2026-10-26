package com.geek.association.update;

/**
 * A failure while talking to the update source.
 *
 * It is a {@link RuntimeException} so the scheduler and the HTTP layer do not have to declare it,
 * and its message is always written for the user: the UI shows it verbatim in the status card.
 */
public class UpdateException extends RuntimeException {

    public UpdateException(String message) {
        super(message);
    }

    public UpdateException(String message, Throwable cause) {
        super(message, cause);
    }
}
