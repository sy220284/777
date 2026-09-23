package com.github.sisong;

/**
 * Minimal JNI bridge for the MIT-licensed HDiffPatch Android patch library.
 *
 * The package/class/method names are part of the JNI ABI and must not be renamed by R8.
 */
public final class HPatch {
    static {
        System.loadLibrary("hpatchz");
    }

    private HPatch() {}

    /**
     * Returns 0 on success; non-zero values are HDiffPatch error codes.
     */
    public static native int patch(
            String oldFileName,
            String diffFileName,
            String outNewFileName,
            long cacheMemory,
            int threadNum,
            boolean checksumNewData
    );
}
