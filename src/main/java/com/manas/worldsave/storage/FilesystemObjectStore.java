package com.manas.worldsave.storage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Real local-filesystem-backed implementation of the same ObjectStore
 * contract as S3ObjectStore. Each chunk is one file on disk. This exists as
 * an honest fallback path (see README "Honest framing"); it is a genuine
 * implementation against a real filesystem, not a fake or an in-memory map.
 */
public class FilesystemObjectStore implements ObjectStore {

    private final Path rootDir;

    public FilesystemObjectStore(Path rootDir) {
        this.rootDir = rootDir;
        try {
            Files.createDirectories(rootDir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Path chunkPath(String objectKey, int chunkIndex) {
        // objectKey may contain path-hostile characters in principle; this project only ever
        // passes save-version UUIDs, so a direct join is safe here.
        Path dir = rootDir.resolve(objectKey);
        return dir.resolve("chunk-" + chunkIndex);
    }

    @Override
    public void putChunk(String objectKey, int chunkIndex, byte[] data) {
        try {
            Path path = chunkPath(objectKey, chunkIndex);
            Files.createDirectories(path.getParent());
            Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
            Files.write(tmp, data);
            Files.move(tmp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public byte[] getChunk(String objectKey, int chunkIndex) {
        Path path = chunkPath(objectKey, chunkIndex);
        if (!Files.exists(path)) {
            throw new NoSuchChunkException(objectKey, chunkIndex);
        }
        try {
            return Files.readAllBytes(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public boolean hasChunk(String objectKey, int chunkIndex) {
        return Files.exists(chunkPath(objectKey, chunkIndex));
    }

    @Override
    public void deleteObject(String objectKey, int chunkCountUpperBound) {
        Path dir = rootDir.resolve(objectKey);
        if (!Files.exists(dir)) {
            return;
        }
        try (var stream = Files.walk(dir)) {
            stream.sorted((a, b) -> b.compareTo(a)).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best-effort cleanup
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
