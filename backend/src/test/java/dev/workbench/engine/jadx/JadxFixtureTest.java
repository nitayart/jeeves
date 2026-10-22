package dev.workbench.engine.jadx;

import java.nio.file.*;
import java.util.*;
import com.android.tools.smali.smali.Smali;
import com.android.tools.smali.smali.SmaliOptions;

/** Synthetic DEX assertions with independently specified instruction offsets. */
public final class JadxFixtureTest {
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("jadx-contract-");
        Path src = Files.createDirectories(root.resolve("smali"));
        Files.writeString(src.resolve("Base.smali"), """
                .class public abstract Lfixture/Base;
                .super Ljava/lang/Object;
                .field protected count:I
                .field public static final LABEL:Ljava/lang/String; = "constant-only"
                .method public abstract work()V
                .end method
                .method public abstract abstractArg(Ljava/lang/String;)V
                .end method
                """);
        Files.writeString(src.resolve("Child.smali"), """
                .class public Lfixture/Child;
                .super Lfixture/Base;
                .method public work()V
                    .registers 1
                    return-void
                .end method
                .method public abstractArg(Ljava/lang/String;)V
                    .registers 2
                    return-void
                .end method
                .method public static run()V
                    .registers 1
                    .local v0, "endpoint":Ljava/lang/String;
                    const-string v0, "https://example.test/api"
                    invoke-static {v0}, Lfixture/Child;->sink(Ljava/lang/String;)V
                    invoke-static {v0}, Lfixture/Child;->sink(Ljava/lang/String;)V
                    invoke-static {v0}, Ljava/lang/System;->loadLibrary(Ljava/lang/String;)V
                    return-void
                .end method
                .method public static sink(Ljava/lang/String;)V
                    .registers 1
                    .param p0, "value"    # Ljava/lang/String;
                    return-void
                .end method
                .method public static pair(II)I
                    .registers 2
                    .param p0, "left"    # I
                    .param p1, "right"   # I
                    .annotation system Ldalvik/annotation/MethodParameters;
                        accessFlags = {
                            0x0,
                            0x0
                        }
                        names = {
                            "left",
                            "right"
                        }
                    .end annotation
                    add-int/2addr p0, p1
                    return p0
                .end method
                .method public static concat(Ljava/lang/String;)Ljava/lang/String;
                    .registers 3
                    .param p0, "input"    # Ljava/lang/String;
                    new-instance v0, Ljava/lang/StringBuilder;
                    invoke-direct {v0}, Ljava/lang/StringBuilder;-><init>()V
                    .local v0, "builder":Ljava/lang/StringBuilder;
                    if-eqz p0, :done
                    invoke-virtual {v0, p0}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
                    :done
                    invoke-virtual {v0}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;
                    move-result-object v1
                    return-object v1
                .end method
                """);
        SmaliOptions options = new SmaliOptions(); options.outputDexFile = root.resolve("classes.dex").toString(); options.jobs = 1;
        check(Smali.assemble(options, src.toString()), "assemble fixture");
        var engine = new JadxEngine("fixture", "fixture", options.outputDexFile, new java.io.File(options.outputDexFile), root.resolve("sidecar").toFile());
        JadxEngine reopened = null;
        try {
            engine.openAsync(); waitReady(engine);
            check("ready".equals(engine.info().get("state")), "fixture ready");
            check(Boolean.TRUE.equals(engine.caps().get("smali"))
                    && Boolean.TRUE.equals(engine.caps().get("rename")) && Boolean.TRUE.equals(engine.caps().get("comment"))
                    && "sidecar".equals(engine.caps().get("persist")), "JADX advertises smali, write, and sidecar capabilities");
            String run = "Lfixture/Child;->run()V";
            String sink = "Lfixture/Child;->sink(Ljava/lang/String;)V";
            String pair = "Lfixture/Child;->pair(II)I";
            String concat = "Lfixture/Child;->concat(Ljava/lang/String;)Ljava/lang/String;";
            Map<String, Object> rawParams = engine.methodParameters(sink);
            check("value".equals(((Map<?, ?>) ((List<?>) rawParams.get("parameters")).getFirst()).get("name"))
                    && "present".equals(((Map<?, ?>) rawParams.get("debugInfo")).get("status")), "method parameter debug metadata and current name");
            List<Object> concatLocals = engine.locals(concat);
            Map<?, ?> builder = concatLocals.stream().map(x -> (Map<?, ?>) x).filter(x -> "builder".equals(x.get("name"))).findFirst().orElseThrow();
            check(builder.get("originalSymbol") != null && builder.get("type").equals("Ljava/lang/StringBuilder;"), "local inventory has stable identity and descriptor type");
            String builderIdentity = String.valueOf(engine.resolveLocal(concat, "builder").get("originalSymbol"));
            check(Boolean.TRUE.equals(engine.renameLocal(concat, "builder", "assembled", "test").get("applied"))
                    && builderIdentity.equals(engine.resolveLocal(concat, "assembled").get("originalSymbol")), "guarded local rename preserves stable identity");
            try {
                engine.renameLocal(concat, "assembled", "input", "test");
                throw new AssertionError("expected local collision refusal");
            } catch (dev.workbench.registry.OpException expected) {
                check("E_BUSY".equals(expected.code) && expected.extra.get("conflicts") instanceof List<?>
                        && engine.resolveLocal(concat, "assembled") != null, "local collision is refused with E_BUSY and structured conflicts");
            }
            Map<String, Object> auto = engine.autoNameLocals(concat, true, "test");
            check(((List<?>) auto.get("renames")).stream().map(x -> (Map<?, ?>) x).anyMatch(x -> "stringBuilder".equals(x.get("name")))
                    && engine.resolveLocal(concat, "stringBuilder") != null, "autoNameLocals names an object local from its type");

            try {
                engine.renameParameters("Lfixture/Base;->abstractArg(Ljava/lang/String;)V", List.of(Map.of("index", 0, "name", "unnamed")), "test");
                throw new AssertionError("expected abstract method parameter rename refusal");
            } catch (dev.workbench.registry.OpException expected) {
                check("E_UNSUPPORTED".equals(expected.code), "abstract method parameter rename is refused with E_UNSUPPORTED");
            }

            check(Boolean.TRUE.equals(engine.renameParameters(sink, List.of(Map.of("index", 0, "name", "payload")), "test").get("applied"))
                    && "payload".equals(((Map<?, ?>) ((List<?>) engine.methodParameters(sink).get("parameters")).getFirst()).get("name")), "formal parameter rename updates current name");
            engine.renameParameters(sink, List.of(Map.of("index", 0, "clear", true)), "test");
            check("value".equals(((Map<?, ?>) ((List<?>) engine.methodParameters(sink).get("parameters")).getFirst()).get("name")), "formal parameter clear restores engine name");
            engine.renameParameters(pair, List.of(Map.of("index", 0, "name", "right"), Map.of("index", 1, "name", "left")), "test");
            List<?> swapped = (List<?>) engine.methodParameters(pair).get("parameters");
            check("right".equals(((Map<?, ?>) swapped.get(0)).get("name")) && "left".equals(((Map<?, ?>) swapped.get(1)).get("name")), "atomic parameter swap succeeds");
            try {
                engine.renameParameters(pair, List.of(Map.of("index", 0, "name", "same"), Map.of("index", 1, "name", "same")), "test");
                throw new AssertionError("expected parameter collision refusal");
            } catch (dev.workbench.registry.OpException expected) {
                swapped = (List<?>) engine.methodParameters(pair).get("parameters");
                check("right".equals(((Map<?, ?>) swapped.get(0)).get("name")) && "left".equals(((Map<?, ?>) swapped.get(1)).get("name")), "invalid parameter mapping leaves all arguments unchanged");
            }
            engine.renameParameters(pair, List.of(Map.of("index", 0, "clear", true), Map.of("index", 1, "clear", true)), "test");
            List<Object> refs = engine.xrefsTo(sink);
            check(refs.size() == 2, "both invoke sites retained");
            check(((Map<?, ?>) refs.get(0)).get("offset").equals(2) && ((Map<?, ?>) refs.get(1)).get("offset").equals(5), "raw DEX code-unit offsets 2 and 5");
            List<Object> callers = engine.callers(sink);
            check(callers.size() == 1 && ((List<?>) ((Map<?, ?>) callers.getFirst()).get("sites")).size() == 2, "caller dedup retains sites");
            check(engine.implementations("Lfixture/Base;->work()V").size() == 1, "abstract method implementation");
            Map<?, ?> inherited = (Map<?, ?>) engine.outline("Lfixture/Child;", true).get("inherited");
            check(((List<?>) inherited.get("fields")).size() == 2, "inherited field inventory");
            List<?> strings = (List<?>) engine.strings("example.test", List.of(), false, 0, 100, null).get("rows");
            check(strings.size() == 1, "string inventory");
            List<?> constants = (List<?>) engine.strings("constant-only", List.of(), false, 0, 100, null).get("rows");
            check(constants.size() == 1, "static string constant inventory");
            String constantId = ((Map<?, ?>) constants.getFirst()).get("id").toString();
            Map<?, ?> constantRef = (Map<?, ?>) engine.stringXrefs(constantId).getFirst();
            check("STATIC_VALUE".equals(constantRef.get("type")) && !constantRef.containsKey("offset") && !constantRef.containsKey("addr"),
                    "static constant has no invented instruction offset or empty addr");
            check("fixture.Base.LABEL".equals(((Map<?, ?>) constants.getFirst()).get("firstReferrer")),
                    "static constant firstReferrer is formatted owner.name");
            String external = "Ljava/lang/System;->loadLibrary(Ljava/lang/String;)V";
            check(Boolean.TRUE.equals(engine.resolveStrict(external).get("external")), "external method resolution");
            check(engine.callers(external).size() == 1, "external method callers");
            String stringId = ((Map<?, ?>) strings.getFirst()).get("id").toString();
            Map<?, ?> stringRef = (Map<?, ?>) engine.stringXrefs(stringId).getFirst();
            check(stringRef.get("offset").equals(0) && !stringRef.containsKey("addr")
                    && "Lfixture/Child;->run()V".equals(stringRef.get("method")),
                    "string site offset and method without empty addr");
            List<Object> searchStr = engine.search("example.test", Map.of("kind", "strings"));
            check(!searchStr.isEmpty(), "search strings hit");
            Map<?, ?> strHit = (Map<?, ?>) ((List<?>) ((Map<?, ?>) searchStr.getFirst()).get("hits")).getFirst();
            check("Lfixture/Child;->run()V".equals(strHit.get("addr")) && strHit.get("context") instanceof List<?> cl && !cl.isEmpty(),
                    "search strings hit carries navigable addr and context");
            List<Object> callees = engine.callees("Lfixture/Child;->run()V");
            check(callees.stream().anyMatch(c -> "sink".equals(((Map<?, ?>) c).get("name")) && "fixture.Child".equals(((Map<?, ?>) c).get("owner"))),
                    "callees row carries target name and owner");
            Map<String, Object> rendered = engine.decompile("Lfixture/Child;", Map.of("tokens", true));
            check(rendered.get("methodParametersIndex") instanceof List<?> mpIdx && !mpIdx.isEmpty()
                    && Boolean.TRUE.equals(((Map<?, ?>) mpIdx.getFirst()).get("countMatchesPrototype"))
                    && "present".equals(((Map<?, ?>) mpIdx.getFirst()).get("status")),
                    "class decompile includes methodParametersIndex with prototype match status");
            Map<String, Object> methodRendered = engine.decompile(sink, Map.of("scope", "method"));
            check(methodRendered.get("methodParameters") instanceof Map<?, ?> mpMap
                    && "method-parameters-v1".equals(mpMap.get("schema")),
                    "method decompile includes methodParameters metadata");
            String text = rendered.get("text").toString();
            for (Object obj : (List<?>) rendered.get("tokens")) {
                Map<?, ?> token = (Map<?, ?>) obj;
                String line = text.split("\n", -1)[((Number) token.get("line")).intValue() - 1];
                int start = ((Number) token.get("cs")).intValue(), end = ((Number) token.get("ce")).intValue();
                check(start >= 0 && end > start && end <= line.length(), "token span within rendered text");
            }
            int at = text.indexOf("sink(");
            check(at > 0 && "method".equals(engine.hover("Lfixture/Child;", at).get("kind")), "hover uses method annotation");

            String baseWork = "Lfixture/Base;->work()V", childWork = "Lfixture/Child;->work()V";
            try {
                engine.rename(childWork, "return", "test");
                throw new AssertionError("expected Java keyword rename refusal");
            } catch (dev.workbench.registry.OpException expected) {
                check("E_SYNTAX".equals(expected.code), "Java keyword is rejected by rename()");
            }
            check(engine.renameTargets(childWork).size() == 2, "virtual renameTargets returns the complete override family");
            engine.rename(childWork, "execute", "test");
            check(engine.resolve(baseWork).get("addr").toString().contains("->execute()V")
                    && engine.resolve(childWork).get("addr").toString().contains("->execute()V")
                    && engine.decompile("Lfixture/Child;", Map.of()).get("text").toString().contains("execute()"), "virtual method rename propagates and renders immediately");
            engine.restoreOriginalName(baseWork, "work", "test");
            check(engine.resolve(childWork).get("addr").toString().contains("->work()V"), "restoreOriginalName restores the override family");
            try {
                engine.rename("Lfixture/Base;->count:I", "LABEL", "test");
                throw new AssertionError("expected field collision refusal");
            } catch (dev.workbench.registry.OpException expected) {
                check("E_BUSY".equals(expected.code) && expected.extra.get("conflicts") instanceof List<?>, "same-class field collision is refused with structured conflicts");
            }
            engine.rename("Lfixture/Base;->count:I", "total", "test");
            check(engine.resolve("Lfixture/Base;->count:I").get("addr").toString().contains("->total:I"), "field rename resolves by original signature");
            engine.restoreOriginalName("Lfixture/Base;->count:I", "count", "test");
            engine.rename("Lfixture/Child;", "RenamedChild", "test");
            check("Lfixture/RenamedChild;".equals(engine.resolve("Lfixture/Child;").get("addr"))
                    && "Lfixture/RenamedChild;".equals(engine.resolve("Lfixture/RenamedChild;").get("addr")), "class rename resolves through original and current descriptors");
            engine.restoreOriginalName("Lfixture/Child;", "Child", "test");

            engine.comment("Lfixture/Child;", "class note", "test");
            check(engine.decompile("Lfixture/Child;", Map.of()).get("text").toString().contains("class note")
                    && "class note".equals(engine.hover("Lfixture/Child;", 0).get("comment")), "class comment renders and appears in hover");
            engine.comment(sink, "method note", "test");
            check(engine.decompile(sink, Map.of()).get("text").toString().contains("method note")
                    && "method note".equals(engine.hover(sink, 0).get("comment")), "method comment renders and appears in hover");
            engine.comment("Lfixture/Base;->count:I", "field note", "test");
            check(engine.decompile("Lfixture/Base;", Map.of()).get("text").toString().contains("field note")
                    && "field note".equals(engine.hover("Lfixture/Base;->count:I", 0).get("comment")), "field comment renders and appears in hover");
            String callSite = run + "+2h";
            engine.comment(callSite, "call-site note", "test");
            check(engine.decompile(run, Map.of()).get("text").toString().contains("call-site note")
                    && "call-site note".equals(engine.hover(callSite, 0).get("comment"))
                    && "call-site note".equals(engine.hover(run + "+0x2", 0).get("comment"))
                    && "call-site note".equals(engine.hover(run + "+2", 0).get("comment"))
                    && callSite.equals(engine.resolve(run + "+2").get("addr"))
                    && callSite.equals(engine.resolve(run + "+0x2").get("origSig"))
                    && Boolean.TRUE.equals(engine.resolve(run + "+2").get("site")),
                    "instruction +offset comment and resolve preserve canonical +Nh site identity");
            engine.comment(callSite, "", "test");
            check(!engine.decompile(run, Map.of()).get("text").toString().contains("call-site note")
                    && "".equals(engine.hover(callSite, 0).get("comment")), "instruction comment can be cleared without lingering attributes");
            engine.comment("Lfixture/Child;", "", "test");
            engine.comment(sink, "", "test");
            engine.comment("Lfixture/Base;->count:I", "", "test");

            engine.rename("Lfixture/Base;->count:I", "retainedCount", "test");
            engine.renameLocal(concat, "stringBuilder", "retainedBuilder", "test");
            engine.renameParameters(sink, List.of(Map.of("index", 0, "name", "retainedValue")), "test");
            engine.comment(callSite, "retained site", "test");
            Map<String, Object> save = engine.save();
            Path codeData = root.resolve("sidecar/jadx/code-data.json");
            check(Boolean.TRUE.equals(save.get("saved")) && Files.isRegularFile(codeData)
                    && Files.readString(codeData).contains("retainedCount"), "checkpointed sidecar save writes code-data.json");
            check(!engine.isDirty() && engine.persistenceFile().toPath().equals(codeData)
                    && !engine.contentHashHistory().isEmpty(), "save clears dirty state and records persisted identity");
            engine.close();

            reopened = new JadxEngine("fixture2", "fixture", options.outputDexFile, new java.io.File(options.outputDexFile), root.resolve("sidecar").toFile());
            reopened.openAsync(); waitReady(reopened);
            check(reopened.resolve("Lfixture/Base;->count:I").get("addr").toString().contains("retainedCount")
                    && reopened.resolveLocal(concat, "retainedBuilder") != null
                    && "retainedValue".equals(((Map<?, ?>) ((List<?>) reopened.methodParameters(sink).get("parameters")).getFirst()).get("name"))
                    && "retained site".equals(reopened.hover(callSite, 0).get("comment")), "renames, locals, parameters, and comments reload from sidecar");
            Map<?, ?> reopenedBuilder = reopened.locals(concat).stream().map(x -> (Map<?, ?>) x)
                    .filter(x -> "retainedBuilder".equals(x.get("name"))).findFirst().orElseThrow();
            check("builder".equals(reopenedBuilder.get("originalName")), "reopened local retains unaliased originalName");
            reopened.renameLocal(concat, "retainedBuilder", "builder", "test");
            reopened.save();
            check(!Files.readString(codeData).contains("retainedBuilder") && !Files.readString(codeData).contains("\"newName\":\"builder\""),
                    "reverting local to originalName drops rename entry from code-data.json");
        } finally {
            try { engine.close(); } catch (Exception ignored) {}
            if (reopened != null) reopened.close();
        }

        verifyDiscardAndReplay(root, options.outputDexFile);
        verifySmaliBranchesAndPayloads(root);
    }

    @SuppressWarnings("unchecked")
    private static void verifySmaliBranchesAndPayloads(Path root) throws Exception {
        java.io.File paramsDex = new java.io.File("src/test/fixtures/params/params-fixture.dex");
        if (!paramsDex.isFile()) paramsDex = new java.io.File("backend/src/test/fixtures/params/params-fixture.dex");
        check(paramsDex.isFile(), "params-fixture.dex available for smali verification");
        var engine = new JadxEngine("branches", "branches", paramsDex.getAbsolutePath(), paramsDex, root.resolve("branches-sidecar").toFile());
        try {
            engine.openAsync(); waitReady(engine);
            String cls = "Lfixture/Branches;";
            Map<String, Object> clsSmali = engine.smali(cls);
            List<Map<String, Object>> clsRows = (List<Map<String, Object>>) clsSmali.get("rows");
            check("jadx".equals(clsSmali.get("engine")) && !clsRows.isEmpty()
                    && String.valueOf(clsRows.getFirst().get("text")).startsWith(".class public Lfixture/Branches;"),
                    "class smali renders header and method blocks");

            for (String m : List.of(
                    cls + "->loop(I)I",
                    cls + "->widths(I)I",
                    cls + "->backwards(I)I",
                    cls + "->switches(I)I",
                    cls + "->arrays()[I",
                    cls + "->guarded(Ljava/lang/Object;)I",
                    cls + "->threeToOne(III)V")) {
                Map<String, Object> mSmali = engine.smali(m);
                List<Map<String, Object>> rows = (List<Map<String, Object>>) mSmali.get("rows");
                check(!rows.isEmpty() && mSmali.get("methodParameters") instanceof Map<?, ?>,
                        "method smali includes rows and methodParameters for " + m);
                int lastOff = -1;
                for (Map<String, Object> r : rows) {
                    if (!"insn".equals(r.get("kind"))) continue;
                    int off = Integer.parseInt(String.valueOf(r.get("offset")), 16);
                    check(off > lastOff, "instruction offsets strictly increase in " + m);
                    check(!String.valueOf(r.get("bytes")).isBlank(), "instruction row carries raw opcode bytes in " + m);
                    lastOff = off;
                }
            }

            List<Map<String, Object>> sw = (List<Map<String, Object>>) engine.smali(cls + "->switches(I)I").get("rows");
            List<String> swCases = sw.stream().filter(r -> "case".equals(r.get("kind"))).map(r -> String.valueOf(r.get("text")).trim()).toList();
            check(swCases.equals(List.of(":l_8", ":l_a", "0x100 -> :l_c", "0x200 -> :l_a"))
                    && sw.stream().anyMatch(r -> String.valueOf(r.get("text")).contains(".packed-switch 0x1"))
                    && sw.stream().anyMatch(r -> String.valueOf(r.get("text")).contains(".sparse-switch")),
                    "packed and sparse switch payloads decode targets in code-unit offsets: " + swCases);

            List<Map<String, Object>> arr = (List<Map<String, Object>>) engine.smali(cls + "->arrays()[I").get("rows");
            List<String> arrEls = arr.stream().filter(r -> "case".equals(r.get("kind"))).map(r -> String.valueOf(r.get("text")).trim()).toList();
            check(arr.stream().anyMatch(r -> String.valueOf(r.get("text")).trim().equals(".array-data 4"))
                    && arrEls.equals(List.of("0x1", "0x2", "0x3")),
                    "fill-array-data payload renders width and elements: " + arrEls);

            List<Map<String, Object>> gd = (List<Map<String, Object>>) engine.smali(cls + "->guarded(Ljava/lang/Object;)I").get("rows");
            check(gd.stream().anyMatch(r -> String.valueOf(r.get("text")).trim().equals(".catch Ljava/lang/RuntimeException; {:try_start_0 .. :try_end_4} :catch_5"))
                    && gd.stream().anyMatch(r -> "label".equals(r.get("kind")) && ":catch_5".equals(String.valueOf(r.get("text")).trim())),
                    "try/catch table emits :try_start, :try_end, :catch labels and .catch directive");

            List<Map<String, Object>> pairRows = (List<Map<String, Object>>) engine.smali("Lfixture/Params;->annotOnly(ILjava/lang/String;)I").get("rows");
            check(pairRows.stream().anyMatch(r -> "annotation".equals(r.get("kind")) && String.valueOf(r.get("text")).contains("MethodParameters")),
                    "smali includes retained MethodParameters annotation rows");

            engine.rename(cls, "RenamedBranches", "test");
            List<Map<String, Object>> renamedRows = (List<Map<String, Object>>) engine.smali(cls).get("rows");
            check(String.valueOf(renamedRows.getFirst().get("text")).contains("Lfixture/RenamedBranches;"),
                    "smali reflects live analyst renames via original symbol identity");
        } finally {
            engine.close();
        }
    }

    private static void verifyDiscardAndReplay(Path root, String dexPath) throws Exception {
        Path discardSidecar = root.resolve("discard-sidecar");
        JadxEngine first = new JadxEngine("discard1", "fixture", dexPath, new java.io.File(dexPath), discardSidecar.toFile());
        first.openAsync(); waitReady(first); first.close(); // establish an empty persisted checkpoint
        JadxEngine discarded = new JadxEngine("discard2", "fixture", dexPath, new java.io.File(dexPath), discardSidecar.toFile());
        discarded.openAsync(); waitReady(discarded);
        discarded.rename("Lfixture/Base;->count:I", "discardedCount", "test");
        discarded.discardUnsaved(); discarded.close();
        JadxEngine afterDiscard = new JadxEngine("discard3", "fixture", dexPath, new java.io.File(dexPath), discardSidecar.toFile());
        afterDiscard.openAsync(); waitReady(afterDiscard);
        check("count".equals(afterDiscard.resolve("Lfixture/Base;->count:I").get("name")), "discardUnsaved prevents close from persisting edits");
        afterDiscard.close();

        JadxEngine replay = new JadxEngine("replay", "fixture", dexPath, new java.io.File(dexPath), discardSidecar.toFile());
        replay.primeDirtyReplay(List.of(
                new LinkedHashMap<>(Map.of("op", "rename", "kind", "field", "addr", "Lfixture/Base;->count:I", "newName", "recoveredCount")),
                new LinkedHashMap<>(Map.of("op", "rename", "kind", "parameters", "addr", "Lfixture/Child;->sink(Ljava/lang/String;)V",
                        "changes", List.of(Map.of("index", 0, "oldName", "value", "newName", "recoveredParam")))),
                new LinkedHashMap<>(Map.of("op", "comment", "addr", "Lfixture/Child;->run()V+2h", "newText", "recovered note"))));
        replay.openAsync(); waitReady(replay);
        check(replay.resolve("Lfixture/Base;->count:I").get("addr").toString().contains("recoveredCount")
                && "recoveredParam".equals(((Map<?, ?>) ((List<?>) replay.methodParameters("Lfixture/Child;->sink(Ljava/lang/String;)V").get("parameters")).getFirst()).get("name"))
                && "recovered note".equals(replay.hover("Lfixture/Child;->run()V+2h", 0).get("comment"))
                && Boolean.TRUE.equals(replay.info().get("dirty")) && Integer.valueOf(3).equals(replay.info().get("recoveredEdits")), "primeDirtyReplay re-applies crash-window history and leaves existing sidecar dirty");
        replay.close();

        // Failed replay entry transitions target to error without clobbering sidecar
        JadxEngine badReplay = new JadxEngine("bad-replay", "fixture", dexPath, new java.io.File(dexPath), discardSidecar.toFile());
        badReplay.primeDirtyReplay(List.of(
                new LinkedHashMap<>(Map.of("op", "rename", "kind", "field", "addr", "Lfixture/Base;->nonexistent:I", "newName", "broken"))));
        badReplay.openAsync(); waitReady(badReplay);
        check("error".equals(badReplay.info().get("state")) && Integer.valueOf(1).equals(badReplay.info().get("replayFailed")),
                "failed history replay halts open in error state");
        badReplay.close();

        // Interrupted save marker refuses open
        Path marker = dev.workbench.engine.CheckpointedFileSave.marker(discardSidecar.resolve("jadx/code-data.json"));
        Files.writeString(marker, "{\"status\":\"in-progress\"}");
        try {
            new JadxEngine("marker-check", "fixture", dexPath, new java.io.File(dexPath), discardSidecar.toFile());
            throw new AssertionError("expected E_IO when save-in-progress marker exists");
        } catch (dev.workbench.registry.OpException expected) {
            check("E_IO".equals(expected.code), "interrupted save marker refuses open with E_IO");
        } finally {
            Files.deleteIfExists(marker);
        }

        // Corrupt codeRef in code-data.json is parked aside and transitions open to error instead of silently turning into a method rename
        Path corruptSidecar = root.resolve("corrupt-sidecar");
        Path corruptFile = Files.createDirectories(corruptSidecar.resolve("jadx")).resolve("code-data.json");
        Files.writeString(corruptFile, "{\"format\":1,\"renames\":[{\"nodeRef\":{\"refType\":\"METHOD\",\"declClass\":\"fixture.Child\",\"shortId\":\"sink(Ljava/lang/String;)V\"},\"codeRef\":{\"attachType\":\"BOGUS\",\"index\":0},\"newName\":\"bad\"}],\"comments\":[]}");
        JadxEngine corruptOpen = new JadxEngine("corrupt-check", "fixture", dexPath, new java.io.File(dexPath), corruptSidecar.toFile());
        corruptOpen.openAsync(); waitReady(corruptOpen);
        check("error".equals(corruptOpen.info().get("state")) && !Files.exists(corruptFile),
                "corrupt codeRef in code-data.json parks sidecar and fails open instead of silently mutating method");
        corruptOpen.close();
    }

    private static void waitReady(JadxEngine engine) throws Exception {
        long deadline = System.nanoTime() + 30_000_000_000L;
        while (("loading".equals(engine.info().get("state")) || "idle".equals(engine.info().get("state"))) && System.nanoTime() < deadline) Thread.sleep(20);
    }

    private static void check(boolean ok, String label) { if (!ok) throw new AssertionError(label); System.out.println("PASS " + label); }
}
