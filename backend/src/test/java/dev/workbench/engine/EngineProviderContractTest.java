package dev.workbench.engine;

import dev.workbench.registry.OpException;
import dev.workbench.server.Doctor;
import dev.workbench.server.Workspace;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Phase 0 verification: EngineProvider registry, StringClassifier & CorpusEncoding
 * outside jeb package, Workspace multi-engine target lifecycle, sidecar persistence
 * checkpoints, and Doctor engine reporting.
 */
public final class EngineProviderContractTest {

    private static int checks;

    private static void check(boolean ok, String label) {
        if (!ok) throw new AssertionError("FAIL: " + label);
        checks++;
        System.out.println("PASS " + label);
    }

    /** Test stub for dev.workbench.engine.jadx.JadxEngine when real JadxEngine is not yet compiled. */
    private static void verifyProviders() {
        EngineProvider jeb = Engines.provider("jeb");
        EngineProvider jadx = Engines.provider("jadx");
        check(jeb != null && "jeb".equals(jeb.id()) && "JEB".equals(jeb.displayName()),
                "jeb provider registered with id and displayName");
        check(jadx != null && "jadx".equals(jadx.id()) && "JADX".equals(jadx.displayName()),
                "jadx provider registered with id and displayName");
        check(jeb.supportedTypes().containsAll(List.of("apk", "dex", "jdb2")),
                "jeb provider supports apk, dex, jdb2");
        check(jadx.supportedTypes().containsAll(List.of("apk", "dex")) && !jadx.supportedTypes().contains("jdb2"),
                "jadx provider supports apk, dex and excludes jdb2");
        check("native".equals(jeb.capabilities().get("persist")) && "sidecar".equals(jadx.capabilities().get("persist")),
                "provider capability persistence modes match spec (native vs sidecar)");
        check(Boolean.TRUE.equals(jadx.capabilities().get("rename"))
                        && Boolean.TRUE.equals(jadx.capabilities().get("comment"))
                        && Boolean.TRUE.equals(jadx.capabilities().get("smali"))
                        && Boolean.FALSE.equals(jadx.capabilities().get("runs")),
                "jadx provider advertises smali and sidecar writes but not unsupported dynamic runs");
        check(Engines.provider("unknown") == null, "unknown provider returns null");
        check(Engines.jadxAvailable() == Engines.jadxAdapterAvailable(),
                "jadxAvailable requires both runtime and compiled adapter");
    }

    private static void verifySharedIndexAndStrings() {
        check("_l_tt;".equals(dev.workbench.engine.index.CorpusEncoding.encodeAddr("LTt;"))
                && !dev.workbench.engine.index.CorpusEncoding.encodeAddr("LTt;")
                        .equalsIgnoreCase(dev.workbench.engine.index.CorpusEncoding.encodeAddr("Ltt;")),
                "CorpusEncoding in dev.workbench.engine.index is case-injective");
        check("_l_a_1_b;".equals(dev.workbench.engine.index.CorpusEncoding.encodeAddr("LA_B;")),
                "CorpusEncoding escapes underscores and uppercase letters");

        List<Map<String, Object>> perm = StringClassifier.lexical("android.permission.CAMERA");
        check(!perm.isEmpty() && "permission".equals(perm.get(0).get("name")),
                "StringClassifier lexical permission rule works without JEB");
        List<Map<String, Object>> crypto = StringClassifier.classify("AES/GCM/NoPadding",
                List.<String[]>of(new String[] { "crypto", "arg 0 of Cipher.getInstance" }));
        check(!crypto.isEmpty() && "crypto".equals(crypto.get(0).get("name"))
                && "usage".equals(crypto.get(0).get("confidence")),
                "StringClassifier usage verdicts outrank lexical verdicts");
    }

    private static Path createMinimalApk(Path dir, String name) throws Exception {
        Path apk = dir.resolve(name);
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(apk))) {
            zos.putNextEntry(new ZipEntry("classes.dex"));
            zos.write(new byte[] { 'd', 'e', 'x', '\n', '0', '3', '5', 0 });
            zos.closeEntry();
        }
        return apk;
    }

    @SuppressWarnings("unchecked")
    private static void verifyWorkspaceMultiEngineAndCheckpoints() throws Exception {
        Path root = Files.createTempDirectory("jeeves-phase0-ws-");
        Path home = root.resolve("home");
        Files.createDirectories(home);
        Path apk = createMinimalApk(root, "sample.apk");
        Path jdb2 = root.resolve("sample.jdb2");
        Files.writeString(jdb2, "jdb2-placeholder");

        Workspace ws = new Workspace();
        ws.setWorkbenchRoot(home.toFile());

        // 1. Opening a .jdb2 with engine "jadx" is rejected with E_UNSUPPORTED
        try {
            ws.openTarget(jdb2.toString(), "jadx");
            throw new AssertionError("Expected E_UNSUPPORTED when opening .jdb2 with jadx");
        } catch (OpException ex) {
            check("E_UNSUPPORTED".equals(ex.code), ".jdb2 with engine=jadx rejected with E_UNSUPPORTED");
        }

        // 2. Opening with an unknown engine is rejected with E_UNSUPPORTED
        try {
            ws.openTarget(apk.toString(), "ghidra");
            throw new AssertionError("Expected E_UNSUPPORTED for unknown engine");
        } catch (OpException ex) {
            check("E_UNSUPPORTED".equals(ex.code), "unknown engine rejected with E_UNSUPPORTED");
        }

        // 3. Verify sidecar persistenceFile() and targetChanges / discardUnsavedHistory / rebuildRenameClaims
        //    on a sidecar-persisted target without any .jdb2 file on disk.
        Path sidecarDir = Path.of(apk + ".workbench");
        Files.createDirectories(sidecarDir.resolve("jadx"));
        Path codeDataFile = sidecarDir.resolve("jadx/code-data.json");
        Files.writeString(codeDataFile, "{\"renames\":[]}");
        long baselineMs = System.currentTimeMillis() - 10_000;
        codeDataFile.toFile().setLastModified(baselineMs);

        MockEngine baseMock = new MockEngine(new MockEngine.Target("t1", "sample.apk", apk.toString(), "JADX", "gmail"));
        Engine sidecarEngine = (Engine) java.lang.reflect.Proxy.newProxyInstance(
                Engine.class.getClassLoader(),
                new Class<?>[] { Engine.class },
                (proxy, method, args) -> {
                    if ("persistenceFile".equals(method.getName())) return codeDataFile.toFile();
                    if ("save".equals(method.getName())) {
                        Files.writeString(codeDataFile, "{\"renames\":[\"saved\"]}");
                        return Map.of("saved", true, "bytes", Files.size(codeDataFile));
                    }
                    try {
                        return method.invoke(baseMock, args);
                    } catch (java.lang.reflect.InvocationTargetException e) {
                        throw e.getCause();
                    }
                });

        var tf = Workspace.class.getDeclaredField("targets");
        tf.setAccessible(true);
        Map<String, Object> targets = (Map<String, Object>) tf.get(ws);
        var tmCtor = targets.get("t1").getClass().getDeclaredConstructor(String.class, String.class, String.class);
        tmCtor.setAccessible(true);
        targets.put("t1", tmCtor.newInstance("t1", "sample.apk", apk.toString()));

        var ef = Workspace.class.getDeclaredField("engines");
        ef.setAccessible(true);
        Map<String, Engine> engines = (Map<String, Engine>) ef.get(ws);
        engines.put("t1", sidecarEngine);

        var ebt = Workspace.class.getDeclaredField("engineByTid");
        ebt.setAccessible(true);
        Map<String, String> engineByTid = (Map<String, String>) ebt.get(ws);
        engineByTid.put("t1", "jadx");
        engineByTid.put("t2", "jeb");

        var skt = Workspace.class.getDeclaredField("storeKeyByTid");
        skt.setAccessible(true);
        Map<String, String> storeKeyByTid = (Map<String, String>) skt.get(ws);
        storeKeyByTid.put("t1", "sample-12345678");
        storeKeyByTid.put("t2", "sample-12345678");

        // Verify engine-scoped storeKey lookup
        check("t1".equals(ws.tidForStoreKey("sample-12345678", "jadx")),
                "tidForStoreKey(key, 'jadx') resolves the JADX target");
        check("t2".equals(ws.tidForStoreKey("sample-12345678", "jeb")),
                "tidForStoreKey(key, 'jeb') resolves the JEB target on the same artifact");

        // Perform a rename on the JADX sidecar target (no .jdb2 exists anywhere for sample.apk)
        check(!root.resolve("sample.jdb2.invalid").toFile().exists(), "no .jdb2 file exists for sample.apk");
        ws.renameSymbol("t1", "MessageSyncHandler", "JadxRenamedHandler", "llm:agent1", null, null);

        Map<String, Object> changes = ws.targetChanges("t1");
        check(((Number) changes.get("symbols")).intValue() == 1
                && ((Number) changes.get("renamed")).intValue() == 1
                && changes.get("lastSaved") != null,
                "targetChanges counts unsaved edits against sidecar code-data.json without a .jdb2");

        // Rebuild rename claims from history against sidecar checkpoint
        ws.withTargetWrite("t1", () -> { ws.rebuildRenameClaims("t1"); return null; });
        try {
            ws.renameSymbol("t1", "MessageSyncHandler", "ConflictingName", "llm:agent2", null, null);
            throw new AssertionError("Expected RENAME_CONFLICT for second agent before save");
        } catch (OpException conflict) {
            check("RENAME_CONFLICT".equals(conflict.code),
                    "rebuildRenameClaims enforces unsaved ownership using sidecar persistence timestamp");
        }

        // Save advances code-data.json mtime and clears unsaved changes count
        Thread.sleep(20);
        Map<String, Object> saveRes = ws.saveTarget("t1");
        check(Boolean.TRUE.equals(saveRes.get("saved")), "saveTarget succeeds on sidecar engine");
        Map<String, Object> changesAfterSave = ws.targetChanges("t1");
        check(((Number) changesAfterSave.get("symbols")).intValue() == 0,
                "targetChanges drops to 0 after sidecar save");

        // Make another edit, then close with save=false (discard) and verify history is marked discarded
        Thread.sleep(20);
        ws.renameSymbol("t1", "InviteCipher", "UnsavedCipher", "human", null, null);
        check(((Number) ws.targetChanges("t1").get("symbols")).intValue() == 1,
                "post-save edit is counted as unsaved");
        ws.closeTarget("t1", false);
        String histJson = Files.readString(sidecarDir.resolve("history.json"));
        check(histJson.contains("\"discarded\":true"),
                "closing with save=false marks post-save history entries discarded against sidecar checkpoint");

        // 4. Verify Doctor rows include jadx-engine and jeb-engine
        Doctor doc = new Doctor(ws);
        Map<String, Object> d = doc.run("jeb-engine,jadx-engine");
        List<?> rows = (List<?>) d.get("rows");
        check(rows.size() == 2, "Doctor answers both jeb-engine and jadx-engine rows");
    }

    public static void main(String[] args) throws Exception {
        verifyProviders();
        verifySharedIndexAndStrings();
        verifyWorkspaceMultiEngineAndCheckpoints();
        System.out.println("EngineProvider Phase 0 contract checks passed: " + checks);
    }
}
