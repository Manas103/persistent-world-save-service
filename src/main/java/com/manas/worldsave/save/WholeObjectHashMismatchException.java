package com.manas.worldsave.save;

/** Thrown when the reassembled object's whole-object SHA-256 does not match the manifest. */
public class WholeObjectHashMismatchException extends RuntimeException {

    public WholeObjectHashMismatchException(String versionId, String expected, String actual) {
        super("whole-object hash mismatch for versionId=" + versionId
                + " expected=" + expected + " actual=" + actual);
    }
}
