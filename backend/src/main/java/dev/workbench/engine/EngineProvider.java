package dev.workbench.engine;

import dev.workbench.registry.OpException;
import java.io.File;
import java.util.Map;
import java.util.Set;

/**
 * Provider interface for creating and discovering analysis engine sessions
 * (WORKBENCH_SPEC §3.1).
 */
public interface EngineProvider {

    /** Canonical lowercase engine identifier ("jeb", "jadx", "mock"). */
    String id();

    /** Display label used on target badges ("JEB", "JADX", "MOCK"). */
    String displayName();

    /** Whether the provider's runtime dependencies are available on the classpath. */
    boolean available();

    /** Version string when available, or null. */
    default String version() { return null; }

    /** Supported artifact types ("apk", "dex", "jdb2", ...). */
    Set<String> supportedTypes();

    /** Default capability map for this engine provider. */
    Map<String, Object> capabilities();

    /** Open an engine session for the given request. */
    Engine open(EngineOpenRequest request) throws OpException;

    /** Request payload for opening a target in an engine provider. */
    record EngineOpenRequest(
            String id,
            String name,
            String path,
            File artifact,
            File jdb2,
            File sidecarDir,
            Map<String, Object> options) {}
}
