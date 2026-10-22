package dev.workbench.engine.jadx;

import com.android.tools.smali.smali.Smali;
import com.android.tools.smali.smali.SmaliOptions;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Real-runtime smoke test; pass a DEX/APK path or synthesize a default HelloWorld DEX. No JEB or server required. */
public final class JadxSmokeTest {
    public static void main(String[] args) throws Exception {
        File input = args.length >= 1 ? new File(args[0]) : synthesizeHelloDex();
        JadxEngine engine = new JadxEngine("test", input.getName(), input.getAbsolutePath(), input,
                Files.createTempDirectory("jeeves-jadx-smoke-").toFile());
        try {
            engine.openAsync();
            long deadline = System.nanoTime() + 60_000_000_000L;
            while ("loading".equals(engine.info().get("state")) && System.nanoTime() < deadline) Thread.sleep(20);
            require("ready".equals(engine.info().get("state")), "ready: " + engine.info());
            require(engine.inputIdentity().get("sha256").toString().length() == 64, "input identity");
            require(!((List<?>) engine.tree("").get("items")).isEmpty(), "tree roots");
            List<?> classes = (List<?>) engine.symbolSearchPage("hello", 100, "class").get("rows");
            require(!classes.isEmpty(), "fixture class search");
            String address = ((Map<?, ?>) classes.getFirst()).get("addr").toString();
            require(address.equals(engine.resolveStrict(address).get("origSig")), "exact resolution");
            require(((Number) engine.classinfo(address).get("methods")).intValue() == 2, "class metadata");
            Map<String, Object> rendered = engine.decompile(address, Map.of("tokens", true));
            require(!rendered.get("text").toString().isBlank(), "class source");
            require(!((List<?>) rendered.get("tokens")).isEmpty(), "semantic spans");
            for (Object obj : (List<?>) engine.outline(address).get("methods")) {
                String method = ((Map<?, ?>) obj).get("addr").toString();
                engine.xrefsFrom(method); engine.callers(method);
                engine.implementations(method);
                Map<String, Object> scoped = engine.decompile(method, Map.of("scope", "method", "tokens", true));
                require(!scoped.get("text").toString().isBlank(), "method source: " + method);
                try {
                    engine.resolveStrict(method + "WrongReturn");
                    throw new AssertionError("contradictory descriptor accepted");
                } catch (dev.workbench.registry.OpException expected) {
                    require("E_NOTFOUND".equals(expected.code), "contradictory descriptor refused");
                }
            }
            engine.strings(null, List.of(), false, 0, 100, null);
            engine.reindex();
            deadline = System.nanoTime() + 60_000_000_000L;
            while (!"built".equals(engine.searchIndexStatus().get("state")) && System.nanoTime() < deadline) Thread.sleep(20);
            require("built".equals(engine.searchIndexStatus().get("state")), "index built: " + engine.searchIndexStatus());
            require(!engine.search("class", Map.of()).isEmpty(), "indexed source search");
        } finally { engine.close(); }
        require("closed".equals(engine.info().get("state")), "close");
        verifyWorkspace(input);
    }

    private static void verifyWorkspace(File dex) throws Exception {
        java.nio.file.Path root = Files.createTempDirectory("jeeves-jadx-workspace-");
        java.nio.file.Path apk = root.resolve("hello.apk");
        try (var zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(apk))) {
            zip.putNextEntry(new java.util.zip.ZipEntry("classes.dex"));
            Files.copy(dex.toPath(), zip); zip.closeEntry();
        }
        var workspace = new dev.workbench.server.Workspace();
        workspace.setWorkbenchRoot(Files.createDirectories(root.resolve("home")).toFile());
        String target = String.valueOf(workspace.openTarget(apk.toString(), "jadx").get("id"));
        try {
            var engine = workspace.engineFor(target);
            long deadline = System.nanoTime() + 60_000_000_000L;
            while ("loading".equals(engine.info().get("state")) && System.nanoTime() < deadline) Thread.sleep(20);
            require("ready".equals(engine.info().get("state")), "workspace APK ready: " + engine.info());
            require(target.equals(workspace.openTarget(apk.toString(), "jadx").get("id")), "workspace duplicate open reuses target");
            require(!engine.symbolSearch("hello").isEmpty(), "workspace engine dispatch");
            String site = "Lhello/HelloWorld;->main([Ljava/lang/String;)V+2h";
            Map<String, Object> commentRes = engine.comment(site, "site history note", "human");
            Map<String, Object> h = new LinkedHashMap<>();
            h.put("op", "comment");
            h.put("actor", "human");
            h.put("addr", commentRes.get("addr"));
            h.put("newAddr", commentRes.get("addr"));
            h.put("oldText", commentRes.get("oldText"));
            h.put("newText", "site history note");
            h.put("owner", "Lhello/HelloWorld;");
            workspace.recordHistory(target, h);
            require(site.equals(h.get("originalSymbol")), "Workspace.recordHistory preserves instruction +offset in originalSymbol");
            workspace.historyUndo(target, String.valueOf(h.get("id")));
            require("".equals(engine.hover(site, 0).get("comment")), "Workspace.historyUndo clears instruction-site comment");
            workspace.historyRedo(target, String.valueOf(h.get("id")));
            require("site history note".equals(engine.hover(site, 0).get("comment")), "Workspace.historyRedo restores instruction-site comment");
        } finally { workspace.closeTarget(target, false); }
        require(!workspace.openTargetIds().contains(target), "workspace detaches closed target");
    }
    private static File synthesizeHelloDex() throws Exception {
        Path root = Files.createTempDirectory("jeeves-jadx-hello-");
        Path src = Files.createDirectories(root.resolve("smali"));
        Files.writeString(src.resolve("HelloWorld.smali"), """
                .class public Lhello/HelloWorld;
                .super Ljava/lang/Object;
                .method public static say()Ljava/lang/String;
                    .registers 1
                    const-string v0, "Hello, World!"
                    return-object v0
                .end method
                .method public static main([Ljava/lang/String;)V
                    .registers 3
                    sget-object v0, Ljava/lang/System;->out:Ljava/io/PrintStream;
                    invoke-static {}, Lhello/HelloWorld;->say()Ljava/lang/String;
                    move-result-object v1
                    invoke-virtual {v0, v1}, Ljava/io/PrintStream;->println(Ljava/lang/String;)V
                    return-void
                .end method
                """);
        Path outDex = root.resolve("hello.dex");
        SmaliOptions options = new SmaliOptions();
        options.outputDexFile = outDex.toString();
        options.jobs = 1;
        require(Smali.assemble(options, src.toString()), "assemble hello.dex fixture");
        return outDex.toFile();
    }

    private static void require(boolean ok, String label) {
        if (!ok) throw new AssertionError(label);
        System.out.println("PASS " + label);
    }
}
