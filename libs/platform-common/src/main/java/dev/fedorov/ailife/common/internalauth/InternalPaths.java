package dev.fedorov.ailife.common.internalauth;

/** Shared path predicate for the internal-auth guard: which request paths are {@code /internal/*}. */
final class InternalPaths {

    private InternalPaths() {
    }

    /** True when {@code path} addresses an internal inter-service endpoint (guarded by the secret). */
    static boolean isInternal(String path) {
        return path != null && (path.startsWith("/internal/") || path.contains("/internal/"));
    }
}
