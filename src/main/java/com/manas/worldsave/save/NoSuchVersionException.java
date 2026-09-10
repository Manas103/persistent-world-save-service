package com.manas.worldsave.save;

public class NoSuchVersionException extends RuntimeException {

    public NoSuchVersionException(String versionId) {
        super("no such save version: " + versionId);
    }
}
