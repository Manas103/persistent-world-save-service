package com.manas.worldsave.save;

/** Thrown when completeSave is called before every chunk has been committed. */
public class IncompleteUploadException extends RuntimeException {

    public IncompleteUploadException(String versionId, long committed, int total) {
        super("versionId=" + versionId + " has only " + committed + " of " + total + " chunks committed");
    }
}
