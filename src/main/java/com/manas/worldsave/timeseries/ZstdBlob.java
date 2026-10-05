package com.manas.worldsave.timeseries;

import com.github.luben.zstd.Zstd;

/** Thin wrapper around the real zstd-jni native library; no in-repo compression stand-in. */
public final class ZstdBlob {

    private ZstdBlob() {
    }

    public static byte[] compress(byte[] raw, int level) {
        return Zstd.compress(raw, level);
    }

    public static byte[] decompress(byte[] compressed, int originalSize) {
        return Zstd.decompress(compressed, originalSize);
    }
}
