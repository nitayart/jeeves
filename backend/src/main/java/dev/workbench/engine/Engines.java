package dev.workbench.engine;

import dev.workbench.registry.OpException;
import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * Engine factory and provider registry.
 *
 * The JEB adapter (dev.workbench.engine.jeb.JebEngine) and the JADX adapter
 * (dev.workbench.engine.jadx.JadxEngine) are discovered and loaded reflectively so the
 * backend builds and runs cleanly with JEB only, JADX only, both, or mock-only.
 */
public final class Engines {

    private Engines() {}

    public static final String JEB_CLASS = "dev.workbench.engine.jeb.JebEngine";
    public static final String JADX_CLASS = "dev.workbench.engine.jadx.JadxEngine";
    public static final String JADX_CORE_CLASS = "jadx.api.JadxDecompiler";

    private static final EngineProvider JEB_PROVIDER = new EngineProvider() {
        @Override public String id() { return "jeb"; }
        @Override public String displayName() { return "JEB"; }
        @Override public boolean available() { return jebAvailable(); }
        @Override public String version() { return jebVersion(); }
        @Override public Set<String> supportedTypes() { return Set.of("apk", "dex", "jdb2"); }
        @Override public Map<String, Object> capabilities() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("decompile", true); m.put("smali", true); m.put("xrefs", true);
            m.put("rename", true); m.put("comment", true); m.put("callgraph", true);
            m.put("strings", true); m.put("runs", true); m.put("persist", "native");
            return m;
        }
        @Override public Engine open(EngineOpenRequest req) throws OpException {
            return openJeb(req.id(), req.name(), req.path(), req.artifact(), req.jdb2());
        }
    };

    private static final EngineProvider JADX_PROVIDER = new EngineProvider() {
        @Override public String id() { return "jadx"; }
        @Override public String displayName() { return "JADX"; }
        @Override public boolean available() { return jadxAvailable(); }
        @Override public String version() { return jadxVersion(); }
        @Override public Set<String> supportedTypes() { return Set.of("apk", "dex"); }
        @Override public Map<String, Object> capabilities() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("decompile", true); m.put("smali", true); m.put("xrefs", true);
            m.put("rename", true); m.put("comment", true); m.put("callgraph", true);
            m.put("strings", true); m.put("runs", false); m.put("persist", "sidecar");
            return m;
        }
        @Override public Engine open(EngineOpenRequest req) throws OpException {
            return openJadx(req.id(), req.name(), req.path(), req.artifact(), req.sidecarDir());
        }
    };

    private static final Map<String, EngineProvider> PROVIDERS = Map.of(
            "jeb", JEB_PROVIDER,
            "jadx", JADX_PROVIDER);

    public static EngineProvider provider(String id) {
        if (id == null) return null;
        return PROVIDERS.get(id.trim().toLowerCase(Locale.ROOT));
    }

    public static Map<String, EngineProvider> providers() {
        return PROVIDERS;
    }

    public static boolean jebAvailable() {
        try {
            Class.forName(JEB_CLASS);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** True when the JADX runtime is on the classpath. */
    public static boolean jadxRuntimeAvailable() {
        try {
            Class.forName(JADX_CORE_CLASS);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** True when both the JADX runtime and Jeeves' JadxEngine adapter are present on the classpath. */
    public static boolean jadxAvailable() {
        return jadxAdapterAvailable();
    }

    /** True when JadxEngine adapter class is compiled and loadable. */
    public static boolean jadxAdapterAvailable() {
        try {
            Class.forName(JADX_CLASS);
            return jadxRuntimeAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    public static String jebVersion() {
        if (!jebAvailable()) return null;
        try {
            Object v = Class.forName("dev.workbench.engine.jeb.JebCore").getMethod("version").invoke(null);
            return v == null ? null : String.valueOf(v);
        } catch (Throwable t) {
            return null;
        }
    }

    public static String jadxVersion() {
        if (!jadxRuntimeAvailable()) return null;
        try {
            Object v = Class.forName(JADX_CORE_CLASS).getMethod("getVersion").invoke(null);
            if (v != null && !String.valueOf(v).isBlank()) return String.valueOf(v);
        } catch (Throwable ignored) {}
        try (InputStream in = Engines.class.getClassLoader().getResourceAsStream("jadx-build-info.properties")) {
            if (in != null) {
                Properties p = new Properties();
                p.load(in);
                String v = p.getProperty("jadx.version", p.getProperty("jadxVersion"));
                if (v != null && !v.isBlank()) return v;
            }
        } catch (Throwable ignored) {}
        return "embedded";
    }

    /** Summary of available real engines for runtimeIdentity / diagnostics. */
    public static String runtimeSummary() {
        List<String> parts = new ArrayList<>();
        if (jebAvailable()) {
            String v = jebVersion();
            parts.add(v == null ? "JEB" : "JEB " + v);
        }
        if (jadxAvailable()) {
            String v = jadxVersion();
            parts.add(v == null ? "JADX" : "JADX " + v);
        }
        return parts.isEmpty() ? "no analysis engine (mock)" : String.join(" + ", parts);
    }

    /** Open an engine session through the registered provider. */
    public static Engine open(String engineId, EngineProvider.EngineOpenRequest request) throws OpException {
        EngineProvider p = provider(engineId);
        if (p == null) {
            throw OpException.unsupported("engine '" + engineId + "' is not available",
                    "available: jeb, jadx");
        }
        return p.open(request);
    }

    /** Open a JEB engine session for an artifact (or a bare .jdb2 when artifact == null). */
    public static Engine openJeb(String id, String name, String path, File artifact, File jdb2)
            throws OpException {
        try {
            Class<?> c = Class.forName(JEB_CLASS);
            return (Engine) c.getConstructor(String.class, String.class, String.class,
                    File.class, File.class).newInstance(id, name, path, artifact, jdb2);
        } catch (ClassNotFoundException e) {
            throw OpException.unsupported(
                    "JEB engine not on classpath — set JEB_HOME to an activated JEB install and rebuild "
                            + "(or run via backend/workbench.sh)",
                    "class=" + JEB_CLASS);
        } catch (ReflectiveOperationException e) {
            Throwable cause = e instanceof java.lang.reflect.InvocationTargetException && e.getCause() != null
                    ? e.getCause() : e;
            if (cause instanceof OpException oe) throw oe;
            throw OpException.unsupported("JEB engine init failed: " + cause, "target=" + id);
        }
    }

    /** Open a JADX engine session for an APK/DEX artifact. */
    public static Engine openJadx(String id, String name, String path, File artifact, File sidecarDir)
            throws OpException {
        if (!jadxRuntimeAvailable()) {
            throw OpException.unsupported(
                    "JADX runtime not on classpath — build JADX or set JADX_HOME and rebuild",
                    "class=" + JADX_CORE_CLASS);
        }
        try {
            Class<?> c = Class.forName(JADX_CLASS);
            return (Engine) c.getConstructor(String.class, String.class, String.class,
                    File.class, File.class).newInstance(id, name, path, artifact, sidecarDir);
        } catch (ClassNotFoundException e) {
            throw OpException.unsupported(
                    "JADX engine adapter not yet compiled on classpath (" + JADX_CLASS + ")",
                    "class=" + JADX_CLASS);
        } catch (ReflectiveOperationException e) {
            Throwable cause = e instanceof java.lang.reflect.InvocationTargetException && e.getCause() != null
                    ? e.getCause() : e;
            if (cause instanceof OpException oe) throw oe;
            throw OpException.unsupported("JADX engine init failed: " + cause, "target=" + id);
        }
    }

    /** target.open's check before a target is minted: would loading this saved database take an
     *  open target's place in the JEB context (same internal project key)? Answers {kind, key or
     *  "", reason, the ids of the targets it is about…}, or null when the load may go ahead. Takes no lock and makes no
     *  JEB call. `bare`: the database is opened by itself; `asked`: the file the caller named. */
    public static String[] preflightJeb(File jdb2, boolean bare, String asked) throws OpException {
        if (jdb2 == null || !jdb2.isFile()) return null;
        try {
            return (String[]) Class.forName(JEB_CLASS).getMethod("preflight", File.class, Boolean.class, String.class).invoke(null, jdb2, bare, asked);
        } catch (ClassNotFoundException e) {
            return null;   // mock-only build: target.open fails on its own, with its own message
        } catch (ReflectiveOperationException e) {
            Throwable cause = e instanceof java.lang.reflect.InvocationTargetException && e.getCause() != null ? e.getCause() : e;
            if (cause instanceof OpException oe) throw oe;
            throw OpException.unsupported("JEB preflight failed: " + cause, "jdb2=" + jdb2);
        }
    }

    /** The marker backend/jdb2-rekey.sh leaves beside a database it set aside: analyse again, then
     *  re-apply the whole edit history (Workspace.dirtyReplayFor, JebEngine.firstSaveAfterReplay). */
    public static File replayMarker(File jdb2) { return new File(jdb2.getPath() + ".replay"); }

    /** Start background analysis on a session returned by {@link #open}. */
    public static void startAnalysis(Engine e) {
        try {
            e.getClass().getMethod("openAsync").invoke(e);
        } catch (ReflectiveOperationException ignored) {}
    }
}
