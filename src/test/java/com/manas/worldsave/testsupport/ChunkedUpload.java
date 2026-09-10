package com.manas.worldsave.testsupport;

import com.manas.worldsave.util.Sha256;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Test-only helper: splits synthetic random bytes into fixed-size chunks and computes each
 * chunk's SHA-256, the way a real client would before calling uploadChunk. The synthetic payload
 * bytes are generated here, at test time, from a seed, and are never committed to the repo.
 */
public final class ChunkedUpload {

    public record Chunk(int index, byte[] data, String checksum) {
    }

    private final byte[] wholeObject;
    private final List<Chunk> chunks;

    private ChunkedUpload(byte[] wholeObject, List<Chunk> chunks) {
        this.wholeObject = wholeObject;
        this.chunks = chunks;
    }

    public static ChunkedUpload synthetic(long seed, int totalSizeBytes, int chunkSizeBytes) {
        byte[] whole = new byte[totalSizeBytes];
        new Random(seed).nextBytes(whole);
        List<Chunk> chunkList = new ArrayList<>();
        int index = 0;
        for (int offset = 0; offset < totalSizeBytes; offset += chunkSizeBytes) {
            int end = Math.min(offset + chunkSizeBytes, totalSizeBytes);
            byte[] slice = java.util.Arrays.copyOfRange(whole, offset, end);
            chunkList.add(new Chunk(index, slice, Sha256.hex(slice)));
            index++;
        }
        return new ChunkedUpload(whole, chunkList);
    }

    public byte[] wholeObject() {
        return wholeObject;
    }

    public List<Chunk> chunks() {
        return chunks;
    }

    public int totalChunks() {
        return chunks.size();
    }
}
