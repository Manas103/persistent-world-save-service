package com.manas.worldsave.save;

/** The validated, reassembled bytes of a restored (or rolled-back-to) save version. */
public record RestoreResult(String versionId, byte[] data, String wholeObjectHash) {
}
