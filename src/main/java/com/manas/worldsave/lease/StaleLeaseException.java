package com.manas.worldsave.lease;

/**
 * Thrown when a write presents a fencing token that is not the current one
 * for its worldId: a superseded session, or a session whose lease expired
 * and was reissued to someone else. This is a real, assertable rejection,
 * never a silent no-op; callers must catch it or let it propagate as an
 * error, and no storage write happens before it is thrown.
 */
public class StaleLeaseException extends RuntimeException {

    public StaleLeaseException(String worldId, long presentedToken, long currentToken) {
        super("stale fencing token for worldId=" + worldId + ": presented=" + presentedToken
                + " current=" + currentToken);
    }
}
