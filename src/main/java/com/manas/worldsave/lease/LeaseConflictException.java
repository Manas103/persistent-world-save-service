package com.manas.worldsave.lease;

/**
 * Thrown when a session tries to acquire a worldId's lease while another
 * session's lease is still unexpired. This is a distinct failure from
 * StaleLeaseException: it fires at acquire time, before any fencing token
 * is ever handed out to the losing caller.
 */
public class LeaseConflictException extends RuntimeException {

    public LeaseConflictException(String worldId, String holderSessionId) {
        super("worldId=" + worldId + " is held by session=" + holderSessionId);
    }
}
