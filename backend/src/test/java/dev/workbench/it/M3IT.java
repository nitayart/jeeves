package dev.workbench.it;

import dev.workbench.registry.Json;
import dev.workbench.server.WorkbenchServer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * M3 integration test (plain main-class, no JUnit): Strings two-layer classification,
 * background search index (+ cancellation + non-blocking decompile), classinfo.
 * Runs against the real test APK (.jdb2 from M1/M2 makes open fast).
 * Prints PASS/FAIL per step; exit 1 on any failure. Run: gradle m3IT.
 */
public final class M3IT {

    private static int failures = 0, checks = 0;
    private static final HttpClient http = HttpClient.newHttpClient();
    private static final String BASE = "http://127.0.0.1:" + WorkbenchServer.PORT;

    public static void main(String[] args) throws Exception {
        String apk = args.length > 0 ? args[0]
                : System.getProperty("user.home") + "/jeb/test_visionocr.apk";
        WorkbenchServer server = new WorkbenchServer(Path.of("web"));
        server.start();
        System.out.println("server up on " + BASE + " · apk=" + apk);

        Map<String, Object> opened = data(post("target.open", Map.of("path", apk)));
        String tid = String.valueOf(opened.get("id"));
        try {
            awaitReady(tid);
            Map<String, Object> info = data(post("target.info", Map.of("target", tid)));
            System.out.println("  target " + tid + " ready, classes=" + info.get("classes"));

            // ---- 1. index builds without blocking a concurrent decompile
            Map<String, Object> idx = indexStatus(tid);
            check(idx.get("ticket") != null && String.valueOf(idx.get("ticket")).contains("search-index"),
                    "index ticket advertised (" + idx.get("ticket") + ")");
            long t0 = System.nanoTime();
            Map<String, Object> anyClass = firstClass(tid);
            long decMs = 0;
            if (anyClass != null) {
                long d0 = System.nanoTime();
                data(post("code.decompile", Map.of("target", tid, "symbol", anyClass.get("addr"))));
                decMs = (System.nanoTime() - d0) / 1_000_000;
            }
            idx = indexStatus(tid);
            System.out.println("  decompile while index " + idx.get("state") + ": " + decMs
                    + " ms (built " + idx.get("built") + "/" + idx.get("total") + ")");
            check(decMs < 10_000, "decompile NOT blocked by indexing (" + decMs + " ms, index "
                    + idx.get("state") + " " + idx.get("built") + "/" + idx.get("total") + ")");
            System.out.println("  (search.index poll: " + ((System.nanoTime() - t0) / 1_000_000) + " ms)");

            // ---- 2. op.cancel cancels the index build
            post("search.reindex", Map.of("target", tid));          // ensure a build is running
            Thread.sleep(1_000);
            Map<String, Object> cancel = data(post("op.cancel",
                    Map.of("ticket", "search-index:" + tid, "target", tid)));
            check(Boolean.TRUE.equals(cancel.get("cancelled")), "op.cancel → index build cancelled");
            idx = indexStatus(tid);
            check("cancelled".equals(idx.get("state")) || "built".equals(idx.get("state")),
                    "index state after cancel: " + idx.get("state"));

            // rebuild and wait for a complete index
            post("search.reindex", Map.of("target", tid));
            awaitIndex(tid);
            idx = indexStatus(tid);
            check("built".equals(String.valueOf(idx.get("state"))),
                    "index built: " + idx.get("built") + "/" + idx.get("total")
                            + " in " + idx.get("ms") + " ms");

            // ---- 3. strings: classification, filters, paging
            Map<String, Object> st = data(post("strings", Map.of("target", tid, "count", 200)));
            @SuppressWarnings("unchecked")
            List<Object> rows = (List<Object>) st.get("rows");
            int totalAll = ((Number) st.get("totalAll")).intValue();
            check(rows.size() == 200 && totalAll > 200,
                    "strings page: 200 rows of " + totalAll + " total");
            boolean hasTypes = false, hasNonPlain = false, hasUsage = false;
            for (Object ro : rows) {
                @SuppressWarnings("unchecked")
                List<Object> types = (List<Object>) ((Map<String, Object>) ro).get("types");
                if (types != null && !types.isEmpty()) {
                    hasTypes = true;
                }
            }
            check(hasTypes, "rows carry types[] {name,confidence,why}");
            // classification depth: scan the full table (first page is pool-order plain)
            Map<String, Object> deep = data(post("strings", Map.of("target", tid, "count", 2000)));
            for (Object ro : (List<?>) deep.get("rows")) {
                for (Object to : (List<?>) ((Map<?, ?>) ro).get("types")) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> tt = (Map<String, Object>) to;
                    if (!"plain".equals(tt.get("name"))) hasNonPlain = true;
                    if ("usage".equals(tt.get("confidence"))) hasUsage = true;
                }
            }
            check(hasNonPlain, "non-plain lexical types exist in the table");
            check(hasUsage, "usage-confirmed classification exists in the table");

            // filters: substring
            Map<String, Object> f1 = data(post("strings", Map.of("target", tid, "filter", "http", "count", 500)));
            boolean allHttp = true;
            for (Object ro : (List<?>) f1.get("rows"))
                if (!String.valueOf(((Map<?, ?>) ro).get("value")).toLowerCase().contains("http")) allHttp = false;
            check(allHttp && !((List<?>) f1.get("rows")).isEmpty(),
                    "substring filter 'http' → " + f1.get("total") + " rows, all matching");
            // filters: type multi-select
            Map<String, Object> f2 = data(post("strings", Map.of("target", tid, "type", "url,content-uri", "count", 500)));
            boolean typeOk = !((List<?>) f2.get("rows")).isEmpty();
            for (Object ro : (List<?>) f2.get("rows")) {
                boolean has = false;
                for (Object to : (List<?>) ((Map<?, ?>) ro).get("types")) {
                    String n = String.valueOf(((Map<?, ?>) to).get("name"));
                    if (n.equals("url") || n.equals("content-uri")) has = true;
                }
                if (!has) typeOk = false;
            }
            check(typeOk, "type filter url,content-uri → " + f2.get("total") + " rows, all typed");
            // filters: unreachable-only
            Map<String, Object> f3 = data(post("strings", Map.of("target", tid, "unreachable", true, "count", 50)));
            boolean unreachOk = true;
            for (Object ro : (List<?>) f3.get("rows"))
                if (((Number) ((Map<?, ?>) ro).get("xrefCount")).intValue() != 0) unreachOk = false;
            check(unreachOk, "unreachable-only → " + f3.get("total") + " rows, all xrefCount==0");
            // paging continuity
            Map<String, Object> p2 = data(post("strings", Map.of("target", tid, "offset", 200, "count", 50)));
            check(((List<?>) p2.get("rows")).size() == 50
                    && !((Map<?, ?>) ((List<?>) p2.get("rows")).get(0)).get("id")
                            .equals(((Map<?, ?>) rows.get(0)).get("id")),
                    "paging: offset=200 continues with different rows (truncated=" + st.get("truncated") + ")");

            // meta.truncated LIFT (P0-3 + fix-7 review F1): a capped strings call must
            // set BOTH data.truncated and meta.truncated — the dispatcher's
            // unconditional meta put used to overwrite the lift
            Map<String, Object> capped = post("strings", Map.of("target", tid, "count", 1));
            check(Boolean.TRUE.equals(((Map<?, ?>) capped.get("data")).get("truncated")),
                    "capped strings: data.truncated true");
            check(Boolean.TRUE.equals(((Map<?, ?>) capped.get("meta")).get("truncated")),
                    "capped strings: meta.truncated lifted (dispatcher putIfAbsent)");
            Map<String, Object> uncapped = post("strings", Map.of("target", tid, "count", 100000));
            check(Boolean.FALSE.equals(((Map<?, ?>) uncapped.get("meta")).get("truncated")),
                    "uncapped strings: meta.truncated stays false (no false positives)");

            // ---- 4. search over the built index
            Map<String, Object> s1 = timed(() -> post("search",
                    Map.of("target", tid, "query", "new", "kind", "code")));
            @SuppressWarnings("unchecked")
            List<Object> groups = (List<Object>) s1.get("data");
            check(!groups.isEmpty(), "search 'new' → " + groups.size() + " groups in " + s1.get("_ms") + " ms");
            @SuppressWarnings("unchecked")
            Map<String, Object> g0 = (Map<String, Object>) groups.get(0);
            @SuppressWarnings("unchecked")
            List<Object> h0 = (List<Object>) g0.get("hits");
            @SuppressWarnings("unchecked")
            Map<String, Object> hit0 = (Map<String, Object>) h0.get(0);
            @SuppressWarnings("unchecked")
            List<Object> ctx = (List<Object>) hit0.get("context");
            check(ctx != null && ctx.size() >= 5,
                    "hit carries ±3-line context (" + (ctx == null ? 0 : ctx.size()) + " lines) · "
                            + g0.get("symbol") + ":" + hit0.get("line"));
            boolean hitMarked = false;
            for (Object c : ctx) if (Boolean.TRUE.equals(((Map<?, ?>) c).get("hit"))) hitMarked = true;
            check(hitMarked, "context marks the hit line");
            check(g0.get("addr") != null && String.valueOf(g0.get("addr")).startsWith("L"),
                    "group carries class addr for navigation");
            // regex + case toggles
            @SuppressWarnings("unchecked")
            List<Object> rx = (List<Object>) post("search",
                    Map.of("target", tid, "query", "new\\s+\\w+\\(", "regex", true)).get("data");
            check(!rx.isEmpty(), "regex search 'new\\s+\\w+\\(' → " + rx.size() + " groups");
            @SuppressWarnings("unchecked")
            List<Object> cs = (List<Object>) post("search",
                    Map.of("target", tid, "query", "NEW", "case", true)).get("data");
            @SuppressWarnings("unchecked")
            List<Object> ci = (List<Object>) post("search",
                    Map.of("target", tid, "query", "NEW", "case", false)).get("data");
            check(ci.size() >= cs.size(),
                    "case toggle: insensitive (" + ci.size() + ") ≥ sensitive (" + cs.size() + ")");
            // kind filter: strings only
            @SuppressWarnings("unchecked")
            List<Object> ks = (List<Object>) post("search",
                    Map.of("target", tid, "query", "http", "kind", "strings")).get("data");
            boolean onlyStrings = !ks.isEmpty();
            for (Object g : ks)
                for (Object h : (List<?>) ((Map<?, ?>) g).get("hits"))
                    if (!"strings".equals(((Map<?, ?>) h).get("kind"))) onlyStrings = false;
            check(onlyStrings, "kind=strings → " + ks.size() + " group(s), string hits only");

            // ---- 4b. rename/comment → search freshness matrix (ROADMAP major
            // item: the index serves LIVE text — declaration, call sites,
            // comments, stale-text purge; gold invariant vs live decompile)
            {
                // pick a class+method with a distinctive name AND external callers
                // (M2IT-style multi-candidate loop: one class may not have both)
                String owner = null, mAddr = null, mName = null;
                List<String> callerClasses = new ArrayList<>();
                @SuppressWarnings("unchecked")
                List<Object> cands = dataList(post("symbol.search", Map.of("target", tid, "query", "a")));
                for (int cci = 0; cci < Math.min(48, cands.size()) && mAddr == null; cci++) {
                    Map<?, ?> cls = (Map<?, ?>) cands.get(cci);
                    if (!"class".equals(cls.get("kind"))) continue;
                    Map<String, Object> ol;
                    try {
                        ol = data(post("code.outline",
                                Map.of("target", tid, "class", cls.get("addr"))));
                    } catch (IllegalStateException e) { continue; }
                    @SuppressWarnings("unchecked")
                    List<Object> ms = (List<Object>) ol.get("methods");
                    for (Object mo : ms) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> mm = (Map<String, Object>) mo;
                        String nm = String.valueOf(mm.get("name"));
                        // distinctive names only: the rename-back check searches the
                        // ORIGINAL name, and a 1-2 char obfuscated name exceeds the
                        // 50-group search cap
                        if (nm.length() < 5 || nm.startsWith("<")
                                || !nm.matches("[A-Za-z_$][A-Za-z0-9_$]*")) continue;
                        String cand = String.valueOf(mm.get("addr"));
                        @SuppressWarnings("unchecked")
                        List<Object> refs = dataList(post("xref.to",
                                Map.of("target", tid, "symbol", cand)));
                        List<String> owners = new ArrayList<>();
                        for (Object ro : refs) {
                            String site = String.valueOf(((Map<?, ?>) ro).get("method"));
                            int cut = site.indexOf("->");
                            if (cut < 0) continue;
                            String cc = site.substring(0, cut);
                            if (!cc.equals(cls.get("addr")) && !owners.contains(cc)) owners.add(cc);
                        }
                        if (!owners.isEmpty()) {
                            // the rename-back check searches the ORIGINAL name globally
                            // — reject names too common to surface under the 50-group cap
                            if (searchGroups(tid, nm).size() > 20) continue;
                            owner = String.valueOf(cls.get("addr"));
                            mAddr = cand;
                            mName = nm;
                            callerClasses = owners;
                            break;
                        }
                    }
                }
                check(mAddr != null, "renamable method with external callers (" + mName + " · "
                        + callerClasses.size() + " caller classes)");
                if (mAddr != null) {
                    String probe = "zzM3Probe";
                    Map<String, Object> ren = data(post("rename",
                            Map.of("target", tid, "symbol", mAddr, "name", probe)));

                    // (1) declaration freshness: the owner class serves the new name
                    check(pollSearchHas(tid, probe, owner, 90_000),
                            "declaration freshness: owner serves " + probe + " with no reindex");
                    // (2) call-site freshness: caller classes serve it too (their
                    // decompiled text changed — owner-only invalidation is NOT enough)
                    for (int i = 0; i < Math.min(3, callerClasses.size()); i++) {
                        String cc = callerClasses.get(i);
                        check(pollSearchHas(tid, probe, cc, 90_000),
                                "call-site freshness: " + shortName(cc) + " serves " + probe);
                    }
                    // (3) gold invariant: a caller's served context lines all appear
                    // verbatim in its LIVE decompiled text
                    if (!callerClasses.isEmpty()) {
                        String cc = callerClasses.get(0);
                        String liveText = String.valueOf(data(post("code.decompile",
                                Map.of("target", tid, "symbol", cc))).get("text"));
                        boolean gold = false;
                        for (Object g : searchGroups(tid, probe)) {
                            Map<?, ?> gm = (Map<?, ?>) g;
                            if (!cc.equals(gm.get("addr"))) continue;
                            for (Object h : (List<?>) gm.get("hits")) {
                                boolean all = true;
                                for (Object cl : (List<?>) ((Map<?, ?>) h).get("context"))
                                    if (!liveText.contains(String.valueOf(((Map<?, ?>) cl).get("t")))) {
                                        all = false;
                                        break;
                                    }
                                if (all) { gold = true; break; }
                            }
                            if (gold) break;
                        }
                        check(gold, "gold invariant: served context for " + shortName(cc)
                                + " == live decompiled text");
                    }
                    // (4) comment freshness: new comment text is searchable
                    data(post("comment", Map.of("target", tid, "symbol", owner, "text", "zzM3Note")));
                    check(pollSearchHas(tid, "zzM3Note", owner, 90_000),
                            "comment freshness: zzM3Note searchable after edit");
                    // (5) rename-back: the probe name is unique, so ZERO hits after
                    // settle is a strong stale-purge signal
                    data(post("rename", Map.of("target", tid,
                            "symbol", String.valueOf(ren.get("newAddr")), "name", mName)));
                    check(pollSearchGone(tid, probe, 90_000),
                            "stale purge: " + probe + " gone from the index after rename-back");
                    check(pollSearchHas(tid, mName, owner, 90_000),
                            "original name searchable again after rename-back");
                    // (6) the queue gauge is advertised
                    Map<String, Object> idx2 = indexStatus(tid);
                    check(idx2.get("pendingReindex") != null,
                            "search.index advertises pendingReindex (" + idx2.get("pendingReindex") + ")");
                }
            }

            // ---- 5. classinfo real values
            if (anyClass != null) {
                Map<String, Object> ci5 = data(post("code.classinfo",
                        Map.of("target", tid, "class", anyClass.get("addr"))));
                check(ci5.get("super") != null && ((Number) ci5.get("methods")).intValue() >= 0
                        && ci5.get("interfaces") != null && ci5.get("xrefCount") != null,
                        "code.classinfo: super=" + ci5.get("super") + " methods=" + ci5.get("methods")
                                + " fields=" + ci5.get("fields") + " xrefs=" + ci5.get("xrefCount"));
            }

            // ---- 4c. persisted gz corpus across a server restart (ROADMAP major
            // item): close (releases the jdb2 lock), restart, reopen — the index
            // must warm-load with zero decompilation; the 4b edits (ts > build)
            // exercise the history-replay path, NOT the foreign-rebuild path
            data(post("target.close", Map.of("target", tid)));
            server.stop();
            server = new WorkbenchServer(Path.of("web"));
            server.start();
            System.out.println("  server restarted on " + BASE);
            Map<String, Object> ro = data(post("target.open", Map.of("path", apk)));
            String tid2 = String.valueOf(ro.get("id"));
            try {
                awaitReady(tid2);
                Map<String, Object> widx = indexStatus(tid2);
                check("built".equals(String.valueOf(widx.get("state"))),
                        "corpus warm-loaded after restart (state=" + widx.get("state") + ")");
                check(Boolean.TRUE.equals(widx.get("persisted"))
                                && ((Number) widx.get("built")).intValue() > 0,
                        "warm load: persisted corpus, " + widx.get("built") + " classes, no rebuild");
                check(!searchGroups(tid2, "onCreate").isEmpty(),
                        "search works against the warm-loaded corpus (cold→warm hydration)");
                // corpus integrity: case-escaped gz filenames must be collision-free
                // (obfuscated targets contain LTt;/LtT;/Ltt; — raw addrs overwrite on APFS)
                java.nio.file.Path idxDir = java.nio.file.Path.of(System.getProperty("user.home"),
                        "jeb", "test_visionocr.apk.workbench", "search-index");
                @SuppressWarnings("unchecked")
                Map<String, Object> hdr = (Map<String, Object>) Json.parse(
                        java.nio.file.Files.readString(idxDir.resolve("header.json")));
                @SuppressWarnings("unchecked")
                Map<String, Object> hcls = (Map<String, Object>) hdr.get("classes");
                java.util.Set<String> seen = new java.util.HashSet<>();
                boolean collision = false;
                for (String a : hcls.keySet()) if (!seen.add(encodeAddr(a))) collision = true;
                check(!collision, "case-escaped corpus filenames collision-free ("
                        + hcls.size() + " classes)");
                // encoding assertions on the PRODUCTION implementation: goldens
                // lock the wire format (a scheme change must bump CORPUS_FORMAT);
                // equalsIgnoreCase asserts the FILESYSTEM property (APFS folds
                // case; Unicode folding folds the Kelvin sign to ASCII K)
                check("_l_tt;".equals(encodeAddr("LTt;")) && "_ltt;".equals(encodeAddr("Ltt;"))
                                && "_l_a_1_b;".equals(encodeAddr("LA_B;"))
                                && "_la_1b;".equals(encodeAddr("La_b;"))
                                && "_l_0212A;".equals(encodeAddr("L\u212A;")),
                        "encodeAddr goldens (wire format lock)");
                check(!encodeAddr("LTt;").equalsIgnoreCase(encodeAddr("Ltt;"))
                                && !encodeAddr("LA_B;").equalsIgnoreCase(encodeAddr("La_b;"))
                                && !encodeAddr("L\u212A;").equalsIgnoreCase(encodeAddr("LK;")),
                        "encodeAddr: case/underscore/Kelvin-fold aliases stay distinct");
            } finally {
                try { post("target.close", Map.of("target", tid2)); } catch (Exception ignored) {}
            }
        } finally {
            try { post("target.close", Map.of("target", tid)); } catch (Exception ignored) {}
        }

        // ---- 6. mock side: strings types[] + classinfo fixture
        data(post("target.switch", Map.of("target", "t1")));
        Map<String, Object> mst = data(post("strings", Map.of("target", "t1")));
        @SuppressWarnings("unchecked")
        List<Object> mrows = (List<Object>) mst.get("rows");
        check(mrows.size() == 20 && ((Map<?, ?>) mrows.get(0)).get("types") != null,
                "mock strings → 20 rows with types[]");
        Map<String, Object> mci = data(post("code.classinfo",
                Map.of("target", "t1", "class", "MessageSyncHandler")));
        check("java.lang.Object".equals(mci.get("super")) && ((Number) mci.get("methods")).intValue() == 7,
                "mock classinfo: SUPER java.lang.Object · METHODS 7");

        // ---- 7. allTargets fan-out is READ-ONLY (QA-verify: active target must not move)
        Map<String, Object> stB = data(post("workspace.state", Map.of()));
        String activeBefore = String.valueOf(stB.get("activeTarget"));
        @SuppressWarnings("unchecked")
        List<Object> fan = (List<Object>) post("search",
                Map.of("query", "cipher", "allTargets", true)).get("data");
        Map<String, Object> stA = data(post("workspace.state", Map.of()));
        check(activeBefore.equals(String.valueOf(stA.get("activeTarget"))),
                "allTargets search read-only: active target " + activeBefore + " unchanged");
        check(!fan.isEmpty() && fan.stream().allMatch(g -> ((Map<?, ?>) g).get("target") != null),
                "allTargets fan-out: " + fan.size() + " groups, all badged with target");

        System.out.println();
        if (failures > 0) {
            System.out.println("M3 IT FAILED: " + failures + "/" + checks + " checks failed");
            System.exit(1);
        }
        System.out.println("M3 IT PASSED: " + checks + " checks green");
        System.exit(0);
    }

    // ------------------------------------------------------------ helpers

    private static Map<String, Object> indexStatus(String tid) throws Exception {
        return data(post("search.index", Map.of("target", tid)));
    }

    /** Raw search groups (default kinds) for a query. */
    private static List<Object> searchGroups(String tid, String query) throws Exception {
        @SuppressWarnings("unchecked")
        List<Object> groups = (List<Object>) post("search",
                Map.of("target", tid, "query", query)).get("data");
        return groups;
    }

    private static boolean groupsContain(List<Object> groups, String classAddr) {
        for (Object g : groups)
            if (classAddr.equals(((Map<?, ?>) g).get("addr"))) return true;
        return false;
    }

    /** Poll until the index serves `query` from classAddr (freshness assert). */
    private static boolean pollSearchHas(String tid, String query, String classAddr, long budgetMs)
            throws Exception {
        long deadline = System.currentTimeMillis() + budgetMs;
        while (System.currentTimeMillis() < deadline) {
            if (groupsContain(searchGroups(tid, query), classAddr)) return true;
            Thread.sleep(1_000);
        }
        return false;
    }

    /** Poll until the index serves NO groups for `query` (stale-purge assert). */
    private static boolean pollSearchGone(String tid, String query, long budgetMs) throws Exception {
        long deadline = System.currentTimeMillis() + budgetMs;
        while (System.currentTimeMillis() < deadline) {
            if (searchGroups(tid, query).isEmpty()) return true;
            Thread.sleep(1_000);
        }
        return false;
    }

    private static String shortName(String classAddr) {
        int slash = classAddr.lastIndexOf('/');
        return slash < 0 ? classAddr : classAddr.substring(slash + 1);
    }

    /** The production encoder (no test copy — the corpus wire format is asserted
     *  via goldens in the corpus checks). */
    private static String encodeAddr(String addr) {
        return dev.workbench.engine.index.CorpusEncoding.encodeAddr(addr);
    }

    private static Map<String, Object> firstClass(String tid) throws Exception {
        @SuppressWarnings("unchecked")
        List<Object> syms = dataList(post("symbol.search", Map.of("target", tid, "query", "a")));
        for (Object o : syms) {
            @SuppressWarnings("unchecked")
            Map<String, Object> s = (Map<String, Object>) o;
            if ("class".equals(s.get("kind"))) return s;
        }
        return null;
    }

    private static void awaitReady(String tid) throws Exception {
        long deadline = System.currentTimeMillis() + 600_000;
        while (System.currentTimeMillis() < deadline) {
            Map<String, Object> info = data(post("target.info", Map.of("target", tid)));
            String st = String.valueOf(info.get("state"));
            if ("ready".equals(st)) return;
            if ("error".equals(st)) throw new IllegalStateException("analysis failed: " + info.get("error"));
            Thread.sleep(2_000);
        }
        throw new IllegalStateException("analysis timeout for " + tid);
    }

    private static void awaitIndex(String tid) throws Exception {
        long deadline = System.currentTimeMillis() + 600_000;
        while (System.currentTimeMillis() < deadline) {
            Map<String, Object> idx = indexStatus(tid);
            if ("built".equals(String.valueOf(idx.get("state")))) return;
            if ("failed".equals(String.valueOf(idx.get("state"))))
                throw new IllegalStateException("index failed: " + idx.get("error"));
            Thread.sleep(2_000);
        }
        throw new IllegalStateException("index build timeout for " + tid);
    }

    private static Map<String, Object> timed(IoSupplier<Map<String, Object>> call) throws Exception {
        long t0 = System.nanoTime();
        Map<String, Object> r = call.get();
        r.put("_ms", (System.nanoTime() - t0) / 1_000_000);
        return r;
    }

    @FunctionalInterface
    private interface IoSupplier<T> { T get() throws Exception; }

    private static void check(boolean ok, String label) {
        checks++;
        System.out.println((ok ? "PASS " : "FAIL ") + label);
        if (!ok) failures++;
    }

    private static Map<String, Object> post(String op, Map<String, Object> params) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("params", params);
        body.put("actor", "human");
        HttpRequest req = HttpRequest.newBuilder(URI.create(BASE + "/api/" + op))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body)))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        return Json.parseObject(resp.body());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(Map<String, Object> resp) {
        if (!Boolean.TRUE.equals(resp.get("ok")))
            throw new IllegalStateException("op failed: " + resp.get("error"));
        return (Map<String, Object>) resp.get("data");
    }

    @SuppressWarnings("unchecked")
    private static List<Object> dataList(Map<String, Object> resp) {
        if (!Boolean.TRUE.equals(resp.get("ok")))
            throw new IllegalStateException("op failed: " + resp.get("error"));
        return (List<Object>) resp.get("data");
    }
}
