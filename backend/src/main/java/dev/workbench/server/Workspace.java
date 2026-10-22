package dev.workbench.server;

import dev.workbench.engine.Engine;
import dev.workbench.engine.EngineProvider;
import dev.workbench.engine.Engines;
import dev.workbench.engine.MockEngine;
import dev.workbench.registry.OpException;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipFile;

/**
 * Workspace state (WORKBENCH_SPEC §1/§2): open projects, their targets, active selection.
 *
 * The workspace boots EMPTY — no project, no target (2026-09-07: the seeded
 * "privacy-invite-flow" default became mock-fixture-only, WORKBENCH_MOCK-gated).
 * The first target.open auto-creates a project named after the artifact, so the
 * user never conjures a container before pointing at a file.
 * target.open {path, engine?} detects the artifact type from magic bytes and opens a
 * REAL engine session (JEB 5.42 via JebEngine) as an additional concurrent target —
 * each target owns its session state, guarded by that engine's synchronization
 * (WORKBENCH_SPEC §2.5). JEB persists natively to a .jdb2 next to the artifact;
 * sidecar-persist engines (MockEngine/JADX later) use <artifact>.workbench/.
 */
public final class Workspace {

    /** Target identity independent of the engine behind it (mock fixture or real session). */
    private record TargetMeta(String id, String name, String path) {}

    /* Container dir names that describe nothing (auto-project naming, r2b #1 /
       r3 #2): pulled/ is FLAT, and staging dirs would fold strangers' artifacts
       into one name-keyed project. Lowercase, compared case-insensitively. */
    private static final java.util.Set<String> GENERIC_DIRNAMES = java.util.Set.of(
            "pulled", "tmp", "download", "downloads", "desktop", "sdcard", "data", "app");

    /** A candidate project name is usable iff it isn't blank and isn't one of
        the generic artifact/container tokens (r3 #2, r5 F1) — those collide
        across every app and would share one name-keyed map sidecar. */
    private static boolean usableName(String s) {
        if (s == null || s.isBlank()) return false;
        String l = s.toLowerCase(java.util.Locale.ROOT);
        return !GENERIC_DIRNAMES.contains(l) && !l.equals("base") && !l.equals("classes")
                && !l.startsWith("split_");
    }

    private final Map<String, Map<String, Object>> projects = new LinkedHashMap<>();
    private int nextProjectNum = 1;      // monotonic, never recycled; the mock scene re-bases past p1
    private final Map<String, Engine> engines = new LinkedHashMap<>();   // targetId → session
    private final Map<String, TargetMeta> targets = new LinkedHashMap<>();
    /* target-store identity of each open target (2026-09-08): the content key
       (dedupe/navigate) and the source path the artifact was opened FROM
       (display/provenance — TargetMeta.path is the store copy) */
    private final Map<String, String> storeKeyByTid = new LinkedHashMap<>();
    private final Map<String, String> engineByTid = new LinkedHashMap<>();
    private final Map<String, String> originByTid = new LinkedHashMap<>();
    private String activeProject;
    private String activeTarget;
    private int nextTargetNum = 1;        // monotonic; the mock scene re-bases past t1..t4

    public Workspace() {
        /* EMPTY by default (2026-09-07): no seeded project, no targets — the
           shell boots blank and the first target.open auto-creates a project
           named after the artifact. The v7 scene below (project
           "privacy-invite-flow" + four mock targets) is IT/dev-fixture ONLY:
           workbench.sh sets WORKBENCH_MOCK=0 for the interactive server; ITs
           run without it, so the suites keep their fixtures. */
        if (!"0".equals(System.getenv("WORKBENCH_MOCK"))) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("id", "p1");
            p.put("name", "privacy-invite-flow");
            p.put("targetIds", new ArrayList<String>());
            /* NO activeMap seed: the v7-mockup leftover claimed fcm-attack-surface
               was open on every boot regardless of what the user was doing, and
               made map.close unrepresentable (map-lifecycle step 1). Activation is
               per-project state set by map.open and cleared by map.close. */
            projects.put("p1", p);
            activeProject = "p1";
            for (MockEngine.Target t : MockEngine.SCENE_TARGETS) {
                targets.put(t.id(), new TargetMeta(t.id(), t.name(), t.path()));
                engines.put(t.id(), new MockEngine(t));
                @SuppressWarnings("unchecked")
                List<String> ids = (List<String>) p.get("targetIds");
                ids.add(t.id());
            }
            activeTarget = "t1";
            nextProjectNum = 2;     // p1 is seeded
            nextTargetNum = 5;      // t1..t4 are the mock scene
        }
    }

    private final RenameCoordinator renameCoordinator = new RenameCoordinator();
    private final java.util.Set<String> renameClaimsLoaded = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Map<String, Map<String, Object>> saveOutcomes = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String,Integer> pendingSaves = new HashMap<>(); // guarded by saveOutcomes

    public RenameCoordinator renameCoordinator() { return renameCoordinator; }

    /** Gate order is target transaction, then Workspace, then engine; never wait for a
     * transaction while holding the Workspace monitor. Saves share this gate with edits. */
    public <T> T withTargetWrite(String targetParam, RenameCoordinator.Work<T> work) throws OpException {
        String tid;
        synchronized (this) { tid = resolveTargetIdOrActive(targetParam); }
        return renameCoordinator.transact(tid, () -> {
            engineFor(tid); // reject a target closed while this transaction waited
            if (renameClaimsLoaded.add(tid)) {
                try { rebuildRenameClaims(tid); }
                catch (OpException ex) { renameClaimsLoaded.remove(tid); throw ex; }
            }
            requireHistoryPersisted(tid); // comments, locals and other writes must also flush prior failures first
            return work.run();
        });
    }

    /** Rebuild ownership from the recovery log, keyed by original DEX identity. */
    public void rebuildRenameClaims(String tid) throws OpException {
        List<Map<String, Object>> entries;
        java.time.Instant savedAt;
        synchronized (this) {
            TargetMeta tm = targets.get(tid);
            File pf = persistenceFileFor(tid, tm);
            savedAt = databaseTime(pf);
            entries = historyFor(tid).stream().map(LinkedHashMap<String, Object>::new).map(x -> (Map<String,Object>)x).toList();
        }
        List<RenameCoordinator.Claim> claims = new ArrayList<>();
        Engine engine = engineFor(tid);
        List<Map<String,Object>> ordered = new ArrayList<>(entries);
        ordered.sort(java.util.Comparator.comparing(HistoryJournal::lastChange));
        for (Map<String, Object> entry : ordered) {
            if (!"rename".equals(entry.get("op")) || Boolean.TRUE.equals(entry.get("undone"))
                    || "parameters".equals(entry.get("kind"))
                    || ("local".equals(entry.get("kind")) && entry.get("originalSymbol") == null)) continue;
            if (!HistoryJournal.lastChange(entry).isAfter(savedAt)) continue;
            String original = entry.get("originalSymbol") == null ? null : String.valueOf(entry.get("originalSymbol"));
            if (original == null) {
                try {
                    Map<String,Object> sym = engine.resolve(String.valueOf(entry.get("newAddr")));
                    original = stableSymbol(sym, String.valueOf(entry.get("addr")));
                } catch (OpException ignored) { continue; }
            }
            List<String> originals = renameOriginals(entry);
            if (originals.isEmpty()) originals = List.of(original);
            for (String affected : originals) {
                // Out-of-order undo can leave a later history row active while its name is no
                // longer current. Never reconstruct ownership for that stale assignment.
                try {
                    Map<String,Object> current = "local".equals(entry.get("kind"))
                            ? engine.resolveLocal(String.valueOf(entry.getOrDefault("originalMethod", entry.get("addr"))), String.valueOf(entry.get("newName")))
                            : engine.resolve(affected);
                    if (!String.valueOf(entry.get("newName")).equals(String.valueOf(current.get("name")))) continue;
                } catch (OpException unavailable) { continue; }
                claims.add(new RenameCoordinator.Claim(affected, String.valueOf(entry.get("newName")),
                        String.valueOf(entry.get("actor")), String.valueOf(entry.get("id"))));
            }
        }
        renameCoordinator.replace(tid, claims);
    }

    private static String stableSymbol(Map<String,Object> symbol, String fallback) {
        return String.valueOf(symbol.getOrDefault("origSig", symbol.getOrDefault("addr", symbol.getOrDefault("fqcn", fallback))));
    }

    /** Stable identities written by one rename, including JEB's implicit method overrides. */
    private static List<String> renameOriginals(Map<String,Object> entry) {
        List<String> originals = new ArrayList<>();
        if (entry.get("renameTargets") instanceof List<?> rows) {
            for (Object value : rows) {
                if (value instanceof Map<?,?> row && row.get("originalSymbol") instanceof String original)
                    originals.add(original);
            }
        }
        if (originals.isEmpty() && entry.get("originalSymbol") instanceof String original) originals.add(original);
        return originals;
    }

    /** One atomic ownership check, engine mutation, and durable recovery record. */
    public Map<String,Object> renameSymbol(String targetParam, String symbol, String name, String actor,
                                           Object confidence, String expectedName) throws OpException {
        return renameSymbol(targetParam, symbol, name, actor, confidence, expectedName, null, false);
    }

    public Map<String,Object> renameSymbol(String targetParam, String symbol, String name, String actor,
                                           Object confidence, String expectedName, String rationale) throws OpException {
        return renameSymbol(targetParam, symbol, name, actor, confidence, expectedName, rationale, false);
    }

    /* Only the human-only proposal-review operation supplies analystApproved. Its
       agent attribution belongs in history, not in the permission check. */
    Map<String,Object> renameSymbol(String targetParam, String symbol, String name, String actor,
                                   Object confidence, String expectedName, String rationale, boolean analystApproved) throws OpException {
        String permissionActor = analystApproved ? "human" : actor;
        String tid;
        synchronized (this) { tid = resolveTargetIdOrActive(targetParam); }
        return withTargetWrite(tid, () -> {
            requireHistoryPersisted(tid);
            Engine engine = engineFor(tid);
            List<Map<String,Object>> affected = engine.renameTargets(symbol);
            Map<String,Object> before = affected.get(0);
            String original = stableSymbol(before, symbol);
            List<Map<String,Object>> renamed = new ArrayList<>();
            for (Map<String,Object> member : affected) {
                String memberOriginal = stableSymbol(member, symbol);
                String current = String.valueOf(member.getOrDefault("name", member.getOrDefault("fqcn", symbol)));
                renameCoordinator.check(tid, memberOriginal, current, permissionActor,
                        memberOriginal.equals(original) ? expectedName : null);
                if ("agent".equals(EventBus.actorKind(permissionActor)) && humanNamedBy(tid, memberOriginal) != null) {
                    OpException conflict = OpException.scope("The symbol is named '" + current + "' by the analyst; your rename was not applied", "symbol=" + memberOriginal);
                    conflict.extra.put("currentName", current);
                    conflict.extra.put("originalSymbol", memberOriginal);
                    throw conflict;
                }
                Map<String,Object> row = new LinkedHashMap<>();
                row.put("originalSymbol", memberOriginal); row.put("oldName", current); row.put("newName", name);
                renamed.add(row);
            }
            Map<String,Object> result = engine.rename(symbol, name, actor);
            if (Boolean.TRUE.equals(result.get("applied"))) {
                String assigned = String.valueOf(result.getOrDefault("newName", name));
                for (Map<String,Object> member : renamed)
                    renameCoordinator.renamed(tid, String.valueOf(member.get("originalSymbol")), assigned, actor, null);
                Map<String,Object> entry = new LinkedHashMap<>();
                entry.put("op", "rename"); entry.put("actor", actor);
                for (String key : List.of("kind", "addr", "newAddr", "oldName", "newName")) entry.put(key, result.get(key));
                if (entry.get("kind") == null) entry.put("kind", before.get("kind"));
                if (entry.get("addr") == null) entry.put("addr", before.getOrDefault("addr", original));
                if (entry.get("newAddr") == null) entry.put("newAddr", original);
                entry.put("originalSymbol", original);
                entry.put("renameTargets", renamed);
                entry.put("owner", ownerOfAddr(String.valueOf(result.getOrDefault("addr", symbol))));
                if (confidence != null) entry.put("confidence", confidence);
                if (rationale != null) entry.put("rationale", rationale);
                try { recordHistory(tid, entry); }
                finally {
                    for (Map<String,Object> member : renamed)
                        renameCoordinator.renamed(tid, String.valueOf(member.get("originalSymbol")), assigned, actor,
                                entry.get("id") == null ? null : String.valueOf(entry.get("id")));
                }
                result.put("historyId", entry.get("id"));
                result.put("originalSymbol", original);
                result.put("durableHistory", true);
            }
            return result;
        });
    }

    private synchronized void requireHistoryPersisted(String tid) throws OpException {
        // Mock scene targets intentionally have no filesystem. Every real opened target has a store key.
        if (historyFile(tid) == null && !storeKeyByTid.containsKey(tid)) return;
        if (!persistHistory(tid)) {
            OpException error = OpException.io("The edit history could not be written durably; the database has not been saved. Keep this target open and retry after correcting storage.", "target=" + tid);
            error.extra.put("durable", false);
            throw error;
        }
    }

    // ------------------------------------------------------------ projects

    public synchronized List<Object> projectList() {
        List<Object> out = new ArrayList<>();
        for (Map<String, Object> p : projects.values()) {
            Map<String, Object> row = new LinkedHashMap<>(p);
            row.put("active", p.get("id").equals(activeProject));
            out.add(row);
        }
        return out;
    }

    public synchronized String activeProject() { return activeProject; }

    /** The active project's NAME — the keyer of every per-project sidecar — or null. */
    public synchronized String activeProjectName() {
        Map<String, Object> p = activeProject == null ? null : projects.get(activeProject);
        return p == null ? null : String.valueOf(p.get("name"));
    }

    /** Names of every open project (RunsManager.storeOfBp searches them all — r3 M4). */
    public synchronized List<String> openProjectNames() {
        List<String> out = new ArrayList<>();
        for (Map<String, Object> p : projects.values()) out.add(String.valueOf(p.get("name")));
        return out;
    }

    /** The name of the OPEN project holding tid, or null. */
    public synchronized String projectNameOfTarget(String tid) {
        String pid = tid == null ? null : projectOfTarget(tid);
        Map<String, Object> p = pid == null ? null : projects.get(pid);
        return p == null ? null : String.valueOf(p.get("name"));
    }

    /** An open target's DURABLE store identity (targets/<slug>-<hash8>), or null: what runs,
        breakpoints and proposals are keyed on across restarts (target ids remint per boot). */
    public synchronized String targetStoreKey(String tid) { return tid == null ? null : storeKeyByTid.get(tid); }

    public synchronized String targetNameOf(String tid) {
        TargetMeta tm = tid == null ? null : targets.get(tid);
        return tm == null ? null : tm.name();
    }

    /** The artifact file an open target was opened from (the apk/dex), or null for a bare .jdb2
     *  target or a fixture. */
    public synchronized File targetArtifactFile(String tid) {
        TargetMeta tm = tid == null ? null : targets.get(tid);
        if (tm == null || tm.path() == null) return null;
        File f = new File(tm.path());
        if (!f.isFile() || tm.path().toLowerCase(java.util.Locale.ROOT).endsWith(".jdb2")) return null;
        return f;
    }

    // ------------------------------------------------------------ resolution, said (2026-10-09)

    /**
     * The engine's resolve, with what only the Workspace knows: a reference whose parameter TYPES differ from
     * the method found is a HISTORY ADDRESS when every difference is a class rename recorded here (the dex type
     * was renamed since the reference was written) — `lenient: "rename history"`, no contradiction — and a guess
     * otherwise (`parameter types`, a contradiction). A lenient resolution is recorded for the op's envelope
     * (`meta.resolution`) so xrefs, callers and every other op say it without changing their result shape.
     */
    public Map<String, Object> resolveSymbol(String tid, String symbol) throws OpException {
        String s = symbol == null ? "" : symbol.trim();
        /* an xref row's `from` carries the instruction offset ("Lcls;->m()V+634h"): the method resolves, the offset rides along */
        java.util.regex.Matcher om = java.util.regex.Pattern.compile("^(.*->[^+]+)\\+(0x[0-9a-fA-F]+|[0-9a-fA-F]+h)$").matcher(s);
        String offset = null;
        if (om.matches()) { s = om.group(1); offset = om.group(2); }
        Map<String, Object> res = new LinkedHashMap<>(engineFor(tid).resolve(s));
        if (offset != null) res.put("offset", offset);
        if (("parameter types".equals(res.get("lenient")) || "return type".equals(res.get("lenient"))) && res.get("asked") != null && res.get("addr") != null
                && renamesExplain(tid, String.valueOf(res.get("asked")), String.valueOf(res.get("addr")))) {
            res.put("lenient", "rename history");
            res.remove("contradiction");
            res.put("note", "the reference is a history address: the types it names were renamed here since it was written — the same method, not a guess");
        }
        if (Boolean.FALSE.equals(res.get("exact"))) {
            Map<String, Object> rs = new LinkedHashMap<>();
            for (String k : List.of("asked", "addr", "origSig", "lenient", "differs", "contradiction", "note")) if (res.get(k) != null) rs.put(k, res.get(k));
            rs.put("requested", res.get("asked")); rs.put("resolvedOriginal", res.get("origSig") != null ? res.get("origSig") : res.get("addr"));
            rs.put("resolutionMode", Boolean.TRUE.equals(res.get("contradiction")) ? "contradiction" : "lenient");
            res.put("requested", rs.get("requested")); res.put("resolvedOriginal", rs.get("resolvedOriginal")); res.put("resolutionMode", rs.get("resolutionMode"));
            dev.workbench.registry.Dispatcher.RESOLUTION.set(rs);
        } else if (res.get("addr") != null) res.put("resolutionMode", "exact");
        return res;
    }

    /** resolveSymbol with the corpus rule (Engine.resolveStrict): a complete descriptor that contradicts the
     *  method found is refused with that method named — after the history correction above. */
    public Map<String, Object> resolveForEvidence(String tid, String symbol) throws OpException {
        Map<String, Object> r = resolveSymbol(tid, symbol);
        if (Boolean.TRUE.equals(r.get("contradiction"))) {
            String orig = String.valueOf(r.get("origSig") != null ? r.get("origSig") : r.get("addr"));
            String shown = r.get("origSig") != null && !r.get("origSig").equals(r.get("addr")) ? " (displayed here as " + r.get("addr") + ")" : "";
            throw OpException.notFound("no method with the exact descriptor " + r.get("asked") + " — the class has " + orig + shown + " (the " + (r.get("differs") != null ? r.get("differs") : r.get("lenient")) + " differ); cite or export that original descriptor",
                    "symbol=contradiction");
        }
        return r;
    }

    /** Every parameter type that differs between the reference and the method found is explained by a class
     *  rename recorded in this target's history (following rename chains, applied entries only). */
    private synchronized boolean renamesExplain(String tid, String asked, String found) {
        int aa = asked.indexOf("->"), fa = found.indexOf("->");
        if (aa < 0 || fa < 0) return false;
        String an = asked.substring(aa + 2), fn = found.substring(fa + 2);
        int alp = an.indexOf('('), flp = fn.indexOf('(');
        if (alp < 0 || flp < 0 || !an.substring(0, alp).equals(fn.substring(0, flp))) return false;   // the NAME must be the same: a renamed type never explains a different method
        List<String> ap = paramsOf(asked), fp = paramsOf(found);
        if (ap.size() != fp.size()) return false;
        Map<String, String> renamed = new LinkedHashMap<>();   // old class addr → new class addr
        for (Map<String, Object> e : historyFor(tid))
            if ("rename".equals(e.get("op")) && "class".equals(e.get("kind")) && !Boolean.TRUE.equals(e.get("undone")) && e.get("addr") != null && e.get("newAddr") != null)
                renamed.put(String.valueOf(e.get("addr")), String.valueOf(e.get("newAddr")));
        if (renamed.isEmpty()) return false;
        boolean any = false;
        int arp = an.indexOf(')'), frp = fn.indexOf(')');
        List<String> pairsA = new ArrayList<>(ap), pairsF = new ArrayList<>(fp);
        pairsA.add(arp >= 0 ? an.substring(arp + 1) : ""); pairsF.add(frp >= 0 ? fn.substring(frp + 1) : "");   // the return type is a type too
        for (int i = 0; i < pairsA.size(); i++) {
            String a = pairsA.get(i), f = pairsF.get(i);
            if (a.equals(f)) continue;
            if (a.isEmpty() || f.isEmpty()) return false;
            String dimsA = a.replaceAll("[^\\[].*$", ""), dimsF = f.replaceAll("[^\\[].*$", "");   // array dimensions must agree; the element class may be renamed
            if (!dimsA.equals(dimsF)) return false;
            String cur = a.substring(dimsA.length()), want = f.substring(dimsF.length());
            boolean ok = false;
            for (int hop = 0; hop < 64 && renamed.containsKey(cur); hop++) { cur = renamed.get(cur); if (cur.equals(want)) { ok = true; break; } }
            if (!ok) return false;
            any = true;
        }
        return any;
    }

    /** The parameter types of a method descriptor, one entry per parameter (arrays keep their brackets). */
    private static List<String> paramsOf(String desc) {
        List<String> out = new ArrayList<>();
        int lp = desc.indexOf('('), rp = desc.indexOf(')');
        if (lp < 0 || rp < lp) return out;
        String p = desc.substring(lp + 1, rp);
        int i = 0;
        while (i < p.length()) {
            int start = i;
            while (i < p.length() && p.charAt(i) == '[') i++;
            if (i >= p.length()) break;
            if (p.charAt(i) == 'L') { int semi = p.indexOf(';', i); if (semi < 0) break; i = semi + 1; } else i++;
            out.add(p.substring(start, i));
        }
        return out;
    }

    // ------------------------------------------------------------ identity, kept apart (2026-10-09)

    private static final Map<String, String> FILE_SHA = new java.util.concurrent.ConcurrentHashMap<>();

    /** sha256 of a file, cached by path+mtime+size (an APK hashes in about a second; packets ask per call). */
    public static String sha256Of(File f) {
        if (f == null || !f.isFile()) return null;
        String key = f.getAbsolutePath() + "|" + f.lastModified() + "|" + f.length();
        String sha = FILE_SHA.get(key);
        if (sha == null) {
            sha = dev.workbench.device.DeviceManager.localSha256(f);
            if (sha != null) { if (FILE_SHA.size() > 64) FILE_SHA.clear(); FILE_SHA.put(key, sha); }
        }
        return sha;
    }

    /**
     * The three identities a target carries, kept APART (the WhatsApp path investigation, 2026-10-09: a packet
     * from a reopened .jdb2 had no artifactSha256 while its generic note still said that field identified the
     * bytes). `executable` — the APK/DEX bytes: a sha256 with a STATE saying how it is known: `measured` from the
     * file the target was opened from, or from the input embedded in the database (what a reopened .jdb2 still
     * carries); `recorded` by a device pull and NOT re-measured; `conflict` — the file beside the database is not
     * the database's input (both digests given); `missing`. `database` — the .jdb2 the engine works in (file,
     * bytes, modified, store key; its hash is never the executable's). `revision` — this workbench's edit history.
     * `runtime` — the compiled build and the engine that answered. Hashing runs outside the Workspace monitor.
     */
    public Map<String, Object> targetIdentity(String tid) {
        TargetMeta tm; String storeKey; int rev;
        synchronized (this) {
            tm = tid == null ? null : targets.get(tid);
            storeKey = storeKeyByTid.get(tid);
            rev = tm == null ? 0 : historyRevision(tid);
        }
        if (tm == null) return null;
        File path = tm.path() == null ? null : new File(tm.path());
        boolean dbOpen = path != null && isJdb2Name(path.getPath());
        File art = dbOpen || path == null || !path.isFile() ? null : path;
        Map<String, Object> embedded = null;
        boolean loading = false;
        try { Engine e = engineFor(tid); embedded = e == null ? null : e.inputIdentity(); } catch (Exception notReady) { loading = true; }   // still analysing: nothing is KNOWN yet
        String fileSha = art == null ? null : sha256Of(art);
        String inputClass = embedded == null || embedded.get("inputClass") == null ? null : String.valueOf(embedded.get("inputClass"));
        boolean inputIsThisFile = inputClass != null && art != null && embedded.get("name") != null && art.getName().equals(String.valueOf(embedded.get("name"))) && inputClass.toLowerCase(java.util.Locale.ROOT).contains("file");
        String embSha = embedded == null || embedded.get("sha256") == null ? null : String.valueOf(embedded.get("sha256"));
        Map<String, Object> exe = new LinkedHashMap<>();
        if (fileSha != null && embSha != null && !fileSha.equalsIgnoreCase(embSha)) {
            exe.put("state", "conflict");
            exe.put("sha256", embSha); exe.put("file", embedded.get("name")); exe.put("bytes", embedded.get("bytes"));
            exe.put("sibling_file", art.getName()); exe.put("sibling_sha256", fileSha);
            exe.put("basis", "the database's embedded input is NOT the file beside it (" + art.getName() + " hashes " + fileSha.substring(0, 12) + "…) — the classes shown are the database's; sha256 is the database input's");
        } else if (fileSha != null) {
            exe.put("state", "measured");
            exe.put("sha256", fileSha); exe.put("file", art.getName()); exe.put("bytes", art.length());
            exe.put("basis", "sha256 of the artifact file in the workbench's store (the copy this target was opened from)"
                    + (embSha == null ? "" : inputIsThisFile ? "; the engine's input is this same file (" + inputClass + ")" : "; equal to the input the database holds (" + inputClass + ")"));
        } else if (embSha != null) {
            exe.put("state", "measured");
            exe.put("sha256", embSha); exe.put("file", embedded.get("name")); exe.put("bytes", embedded.get("bytes")); exe.put("inputClass", inputClass);
            exe.put("basis", "sha256 of the artifact input JEB's project record points at (" + inputClass
                    + (inputClass != null && inputClass.toLowerCase(java.util.Locale.ROOT).contains("file")
                        ? " — JEB re-reads that file; the database does not keep the bytes" : " — readable input bytes held by the database") + "), hashed now"
                    + (dbOpen ? "; the target was opened from its .jdb2" : ""));
        } else if (loading) {
            exe.put("state", "unknown");
            exe.put("sha256", null);
            exe.put("basis", "the target is still loading: nothing has been measured yet — ask again when analysis completes");
        } else {
            Map<String, Object> prov = null;
            try {
                String jbase = path == null ? null
                        : dbOpen ? path.getPath().substring(0, path.getPath().length() - 5)
                        : jdb2For(path).getPath().replaceAll("(?i)\\.jdb2$", "");
                prov = jbase == null ? null : devices().provFor(jbase);
            } catch (Exception ignored) {}
            if (prov != null && prov.get("sha256") != null) {
                exe.put("state", "recorded");
                exe.put("sha256", prov.get("sha256")); exe.put("recorded_from", prov.get("pulledFrom")); exe.put("recorded_at", prov.get("ts"));
                exe.put("basis", "recorded when the artifact was pulled from a device — NOT re-measured against bytes this process read");
            } else {
                exe.put("state", "missing");
                exe.put("sha256", null);
                if (inputClass != null) exe.put("inputClass", inputClass);
                String why = embedded != null && embedded.get("error") != null ? String.valueOf(embedded.get("error")) : "the engine holds no readable input";
                exe.put("basis", dbOpen ? "the database was opened without its executable: " + why + " — nothing here measured or recorded the bytes"
                        : path == null ? "no artifact path on record for this target"
                        : "the artifact file is no longer at " + path.getPath() + " and " + why);
            }
        }
        Map<String, Object> db = new LinkedHashMap<>();
        File pf;
        synchronized (this) { pf = persistenceFileFor(tid, tm); }
        File j = pf != null ? pf : (path == null ? null : dbOpen ? path : jdb2For(path));
        if (j != null) {
            db.put("file", j.getName());
            if (j.isFile()) { db.put("bytes", j.length()); db.put("modified", java.time.Instant.ofEpochMilli(j.lastModified()).toString()); }
        }
        db.put("storeKey", storeKey);
        if (embedded != null && embedded.get("projectKey") != null) db.put("jebKey", embedded.get("projectKey"));   // the JEB project key inside the database: copies of one database share it
        db.put("note", "the analysis database's identity (file, size, time, store key, JEB key) — never the executable's; a copied or stale database keeps its own provenance and the renames it carries");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("executable", exe);
        out.put("database", db);
        Map<String, Object> revision = new LinkedHashMap<>();
        revision.put("history", rev); revision.put("snapshot_id", tid + "@h" + rev);
        revision.put("note", "this workbench's edit history for this target id; renames carried INSIDE a database opened from elsewhere are the database's, not counted here");
        out.put("revision", revision);
        out.put("runtime", runtimeIdentity());
        return out;
    }

    /* the build this PROCESS runs: read once when the class loads (the classes and this file were written together),
       never re-read — `gradle classes` rewrites the directory a live server runs from (fix-review 2026-10-09 H1) */
    private static final java.util.Properties BOOT_BUILD = readBuildInfo();
    private static final String PROCESS_STARTED_AT = java.time.Instant.now().toString();

    private static java.util.Properties readBuildInfo() {
        java.util.Properties p = new java.util.Properties();
        try (java.io.InputStream in = Workspace.class.getClassLoader().getResourceAsStream("build-info.properties")) { if (in != null) p.load(in); } catch (Exception ignored) {}
        return p;
    }

    /** The running build and engine — a compact session manifest. A source Git SHA alone does not say which
     *  compiled runtime is live: build-info.properties is written when the classes are compiled (gradle buildInfo)
     *  and read ONCE at boot; `build_file_changed_since_start` says when the directory was rebuilt under a running
     *  process (the classes answering are still the boot's). */
    public static Map<String, Object> runtimeIdentity() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("build_sha", BOOT_BUILD.getProperty("git.sha", "unknown"));
        m.put("build_dirty", "true".equals(BOOT_BUILD.getProperty("git.dirty")) ? Boolean.TRUE : "false".equals(BOOT_BUILD.getProperty("git.dirty")) ? Boolean.FALSE : "unknown");
        m.put("built_at", BOOT_BUILD.getProperty("built.at", "unknown"));
        m.put("built_at_basis", BOOT_BUILD.getProperty("built.at.basis", "unknown"));
        m.put("source_inputs_sha256", BOOT_BUILD.getProperty("source.inputs.sha256", "unknown"));
        m.put("process_started_at", PROCESS_STARTED_AT);
        m.put("process_id", ProcessHandle.current().pid());
        m.put("java_version", System.getProperty("java.version"));
        m.put("java_home", System.getProperty("java.home"));
        java.util.Properties now = readBuildInfo();
        boolean changed = !now.isEmpty() && (!String.valueOf(now.getProperty("git.sha")).equals(BOOT_BUILD.getProperty("git.sha")) || !String.valueOf(now.getProperty("built.at")).equals(BOOT_BUILD.getProperty("built.at")));
        m.put("build_file_changed_since_start", changed);
        if (changed) m.put("note", "the classes directory was rebuilt after this process started (" + now.getProperty("git.sha", "?") + "): the answers come from the BOOT build above — restart to serve the new one");
        m.put("engine", Engines.runtimeSummary());
        return m;
    }

    /** The `executable` block of targetIdentity, or a missing one. */
    public Map<String, Object> executableIdentity(String tid) {
        Map<String, Object> id = targetIdentity(tid);
        @SuppressWarnings("unchecked") Map<String, Object> exe = id != null && id.get("executable") instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
        return exe != null ? exe : Map.of("state", "missing", "basis", "no identity record");
    }

    /** THE digest every evidence surface cites for "which bytes": the executable's when it was MEASURED — the file
     *  the target was opened from or the input the database holds (in a conflict, the database input's: the
     *  classes came from it) — null otherwise (recorded, missing, unknown): never the file beside a database. */
    public String executableSha(String tid) {
        Map<String, Object> exe = executableIdentity(tid);
        String st = String.valueOf(exe.get("state"));
        return ("measured".equals(st) || "conflict".equals(st)) && exe.get("sha256") != null ? String.valueOf(exe.get("sha256")) : null;
    }

    /** The target's history revision: the count of entries ever recorded on it (ids run tN-h1,
     *  tN-h2 …) — the "snapshot revision" a mission records at spawn and diffs against. */
    public synchronized int historyRevision(String tid) {
        if (tid == null) return 0;
        Integer n = historySeq.get(tid);
        if (n != null) return n;
        int max = 0;
        for (Map<String,Object> e : historyFor(tid)) {
            max = Math.max(max, historyNum(e));
            for (Map<String,Object> event : HistoryJournal.reversals(e)) max = Math.max(max, historyNum(event));
        }
        return max;
    }

    /** The history entries recorded after a revision (newest last), each a copy. */
    public synchronized List<Map<String, Object>> historySince(String tid, int afterRevision) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (tid == null) return out;
        for (Map<String,Object> e : historyFor(tid)) {
            if (historyNum(e) > afterRevision) out.add(new LinkedHashMap<>(e));
            for (Map<String,Object> event : HistoryJournal.reversals(e))
                if (historyNum(event) > afterRevision) out.add(new LinkedHashMap<>(event));
        }
        out.sort(java.util.Comparator.comparingInt(Workspace::historyNum));
        return out;
    }

    private static int historyNum(Map<String, Object> e) {
        String id = String.valueOf(e.get("id"));
        int h = id.lastIndexOf("-h");
        try { return h < 0 ? 0 : Integer.parseInt(id.substring(h + 2)); } catch (NumberFormatException ex) { return 0; }
    }

    /** Who hears of every history entry as it is recorded (a capture) — null = nobody. */
    private volatile java.util.function.BiConsumer<String, Map<String, Object>> historyListener;
    public void setHistoryListener(java.util.function.BiConsumer<String, Map<String, Object>> l) { this.historyListener = l; }

    /** projects/<slug> for a project NAME through dirFor's claim walk — the ONE keyer for every
        per-project sidecar (maps, runs, proposals, session). */
    public synchronized java.io.File projectDir(String projectName) { return dirFor(projectName); }

    /** project.known: the ON-DISK projects (sidecar dirs carrying a .project-name
        marker), open or not — the project menu's loadable list (2026-09-07:
        projects are in-memory only, so a restart forgot them and the menu
        offered nothing to load). Name-keyed: project.open(name) rebinds the
        same sidecar through dirFor's claim walk, so maps AND the persisted
        activeMap come back. TARGETS do not resurrect — they are live engine
        sessions; reopen them via target.open. The FS scan runs OFF the
        Workspace monitor (r2b #9); only the open-flag pass needs the hold. */
    public List<Object> knownProjects() {
        if (workbenchRoot == null) return List.of();
        java.io.File[] dirs = new java.io.File(workbenchRoot, "projects")
                .listFiles(java.io.File::isDirectory);
        if (dirs == null) return List.of();
        java.util.Arrays.sort(dirs, java.util.Comparator.comparing(java.io.File::getName));
        List<Object> out = new ArrayList<>();
        for (java.io.File d : dirs) {
            /* parked dirs keep their marker inside — without the suffix filter
               a forgotten/deleted/migrated project still listed (r2b #3/#4) */
            String dn = d.getName();
            if (dn.contains(".forgotten-") || dn.contains(".deleted-") || dn.endsWith(".migrated"))
                continue;
            java.io.File marker = new java.io.File(d, ".project-name");
            if (!marker.isFile()) continue;   // migration litter / never-claimed: not loadable
            String name;
            try {
                name = java.nio.file.Files.readString(marker.toPath()).trim();
            } catch (Exception e) {
                continue;                       // unreadable marker: skip, never guess the slug
            }
            if (name.isEmpty()) continue;
            /* dedupe by name (r2b #4): one row per project name, and only the
               dir the claim walk would actually resolve — a shadowed twin's map
               count would describe a dir no open can reach */
            java.io.File live = dirForReadOnly(name);
            if (live == null || !live.equals(d)) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", name);
            String[] mapFiles = new java.io.File(d, "maps")
                    .list((dir, fn) -> fn.endsWith(".map.json"));
            row.put("maps", mapFiles == null ? 0 : mapFiles.length);
            out.add(row);
        }
        synchronized (this) {
            for (Object o : out) {
                @SuppressWarnings("unchecked")
                Map<String, Object> row = (Map<String, Object>) o;
                boolean open = false;
                for (Map<String, Object> p : projects.values())
                    if (row.get("name").equals(p.get("name"))) { open = true; break; }
                row.put("open", open);
            }
        }
        return out;
    }

    /** dirFor's walk WITHOUT the claim (r2b #3/#4): the dir the claim walk
        would resolve for this name, or null. A marker mismatch continues to
        the hashed candidates exactly like dirFor; a marker-less existing dir
        BLOCKS the walk as dirFor would claim it — but we refuse to guess at
        unmarked contents and return null instead. */
    private java.io.File dirForReadOnly(String projectName) {
        java.io.File base = new java.io.File(workbenchRoot, "projects");
        String slug = slug(projectName);
        String hash = String.format(java.util.Locale.ROOT, "%08x", projectName.hashCode());
        for (int i = 0; ; i++) {
            String key = i == 0 ? slug : slug + "-" + hash + (i == 1 ? "" : "-" + i);
            java.io.File dir = new java.io.File(base, key);
            java.io.File marker = new java.io.File(dir, ".project-name");
            try {
                if (marker.isFile()) {
                    if (projectName.equals(java.nio.file.Files.readString(marker.toPath()).trim()))
                        return dir;
                    continue;   // someone else's slug twin — the walk continues
                }
                if (dir.exists()) return null;   // claimable-by-use, not listable/forgettable
                return null;                     // fresh candidate: nothing claimed
            } catch (Exception e) {
                return null;
            }
        }
    }

    /** Delete a just-created project sidecar iff it is VIRGIN — only the claim
        marker plus at most an empty maps/ (the auto-create rollback, r2b #3).
        Anything more means real data landed meanwhile: keep the dir. Never
        throws; a refusal is stderr-loud, matching the house park discipline. */
    private void deleteVirginSidecar(String projectName) {
        try {
            java.io.File dir = dirFor(projectName);
            String[] entries = dir.list();
            if (entries == null) return;
            for (String e : entries)
                if (!e.equals(".project-name") && !e.equals("maps")) return;
            String[] mapFiles = new java.io.File(dir, "maps").list();
            if (mapFiles != null && mapFiles.length > 0) return;
            new java.io.File(dir, ".project-name").delete();
            new java.io.File(dir, "maps").delete();
            dir.delete();
        } catch (Exception e) {
            System.err.println("auto-create rollback: could not remove virgin sidecar for "
                    + projectName + " — " + e);
        }
    }

    /** project.forget {name}: park a CLOSED on-disk project — the ON DISK
        list's forget gesture. Renames projects/<slug> → <slug>.forgotten-<ts>;
        the maps survive inside, parked — exactly like map.delete's parked
        file, never an unlink. Refuses an OPEN project (close it first): a
        live project's map store must not lose its dir under it. */
    public synchronized Map<String, Object> forgetProject(String name) throws OpException {
        if (name == null || name.isBlank()) throw OpException.syntax("project.forget needs a name", "");
        String trimmed = name.trim();
        if (workbenchRoot == null)
            throw OpException.notFound("no on-disk projects (no workbench root)", "");   // r3 #7
        for (Map<String, Object> p : projects.values())
            if (trimmed.equals(p.get("name")))
                throw OpException.busy("project '" + trimmed + "' is open — close it first", "");
        java.io.File dir = dirForReadOnly(trimmed);
        if (dir == null)
            throw OpException.notFound("no on-disk project named '" + trimmed + "'", "");
        /* ':' is legal on POSIX but hostile elsewhere — MapStore's parked-name
           convention, and the recovery move is meant to be typed by hand */
        String ts = java.time.Instant.now().toString().replace(':', '-');
        java.io.File dst = new java.io.File(dir.getParentFile(), dir.getName() + ".forgotten-" + ts);
        try {
            java.nio.file.Files.move(dir.toPath(), dst.toPath(),
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            throw OpException.io("could not park " + dir.getName() + ": " + e, "");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", trimmed);
        out.put("parked", dst.getAbsolutePath());
        return out;
    }

    /** the open target registered under this store key (never an error/closed
        one — those fall through to a fresh open, the old dedupe rule) */
    /** The OPEN target with this durable store key, or null — public since batch 2: the apply
        path of a proposal resolves its target by key first (review b2-r1 H2). */
    public synchronized String tidForStoreKey(String key) {
        return tidForStoreKey(key, null);
    }

    /** The OPEN target with this durable store key and optional engine ("jeb", "jadx"), or null. */
    public synchronized String tidForStoreKey(String key, String engine) {
        for (Map.Entry<String, String> e : storeKeyByTid.entrySet()) {
            if (!e.getValue().equals(key)) continue;   // THE key — the first live target is not a match
            if (engine != null && !engine.equalsIgnoreCase(engineOfTid(e.getKey()))) continue;
            Engine en = engines.get(e.getKey());
            if (en == null) continue;
            Object st = en.info().get("state");
            if ("error".equals(st) || "closed".equals(st)) continue;
            return e.getKey();
        }
        return null;
    }

    private String engineOfTid(String tid) {
        String eng = engineByTid.get(tid);
        if (eng != null) return eng;
        Engine e = engines.get(tid);
        if (e != null) {
            Object inf = e.info().get("engine");
            if (inf != null) return String.valueOf(inf).toLowerCase(java.util.Locale.ROOT);
        }
        return "jeb";
    }

    /** project.delete {name} — WIPE the project dir (vs forget's park): maps,
        session, membership, everything under projects/<slug>. Refuses an open
        project (close first, same as forget). Store entries are shared and NOT
        touched — the return reports which of this project's members are now
        referenced by no project at all (informational; the user cleans the
        store by hand). (2026-09-08, user directive) */
    public Map<String, Object> deleteProject(String name) throws OpException {
        final String trimmed;
        java.io.File dir;
        java.util.List<String> doomedKeys = new ArrayList<>();
        /* collect under the monitor; wipe + scan OFF it (950876a-r1 #10 — the
           closeProject/P0-1 rule: a recursive delete + walks must not serialize
           every op in the process) */
        synchronized (this) {
            if (name == null || name.isBlank()) throw OpException.syntax("project.delete needs a name", "");
            trimmed = name.trim();
            if (workbenchRoot == null)
                throw OpException.notFound("no on-disk projects (no workbench root)", "");
            for (Map<String, Object> p : projects.values())
                if (trimmed.equals(p.get("name")))
                    throw OpException.busy("project '" + trimmed + "' is open — close it first", "");
            dir = dirForReadOnly(trimmed);
            if (dir == null)
                throw OpException.notFound("no on-disk project named '" + trimmed + "'", "");
            for (Map<String, Object> m : readMembers(trimmed)) doomedKeys.add(String.valueOf(m.get("key")));
            deletingProjects.add(trimmed);   // held until the wipe settles (r2 F7)
        }
        java.util.concurrent.atomic.AtomicInteger deleteFailures = new java.util.concurrent.atomic.AtomicInteger();
        try {
        try (var walk = java.nio.file.Files.walk(dir.toPath())) {
            walk.sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> { try { java.nio.file.Files.delete(p); }
                                    catch (java.io.IOException e) { deleteFailures.incrementAndGet(); } });
        } catch (Exception e) {
            throw OpException.io("could not wipe " + dir.getName() + ": " + e, "");
        }
        /* a partial wipe must not report deleted:true (950876a-r1 #10): a
           surviving .project-name keeps the "deleted" project listed */
        if (deleteFailures.get() > 0)
            throw OpException.io("project '" + trimmed + "' only partially deleted — "
                    + deleteFailures.get() + " entr" + (deleteFailures.get() == 1 ? "y" : "ies")
                    + " survived in " + dir.getName(), "");
        /* orphan report: a member is orphaned when NO project — open or on
           disk — lists its key any more */
        java.util.Set<String> referenced = new java.util.HashSet<>();
        synchronized (this) {
            for (Map<String, Object> p : projects.values())
                for (Map<String, Object> m : readMembers(String.valueOf(p.get("name"))))
                    referenced.add(String.valueOf(m.get("key")));
        }
        java.io.File[] known = new java.io.File(workbenchRoot, "projects").listFiles();
        if (known != null) for (java.io.File kd : known) {
            if (!kd.isDirectory()) continue;
            java.io.File marker = new java.io.File(kd, ".project-name");
            if (!new java.io.File(kd, "targets.json").isFile() || !marker.isFile()) continue;
            try {
                String kn = java.nio.file.Files.readString(marker.toPath()).trim();
                for (Map<String, Object> m : readMembers(kn)) referenced.add(String.valueOf(m.get("key")));
            } catch (Exception ignored) {}
        }
        java.util.List<Object> orphans = new ArrayList<>();
        for (String k : doomedKeys) {
            if (referenced.contains(k)) continue;
            java.io.File te = new java.io.File(storeRoot(), k);
            if (!te.isDirectory()) continue;
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("key", k);
            o.put("sizeBytes", dirSize(te));
            orphans.add(o);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("deleted", true);
        out.put("name", trimmed);
        out.put("orphanedTargets", orphans);
        return out;
        } finally {
            /* the guard covers the WIPE AND the report (r3 M3): a racing
               project.targets on the doomed name must not re-materialize the
               dir while we still answer for it */
            synchronized (this) { deletingProjects.remove(trimmed); }
        }
    }

    private static long dirSize(java.io.File d) {
        long n = 0;
        try (var walk = java.nio.file.Files.walk(d.toPath())) {
            for (java.nio.file.Path p : (Iterable<java.nio.file.Path>) walk::iterator)
                /* a .part staging file is an in-flight copy, not content (r2 nit) */
                if (java.nio.file.Files.isRegularFile(p) && !p.toString().endsWith(".part"))
                    n += p.toFile().length();
        } catch (Exception ignored) {}
        return n;
    }

    /** project.targets {project?} — the member list for the project editor:
        persisted membership + live engine state for open members + store size. */
    public Map<String, Object> projectTargets(String projectParam) throws OpException {
        String projName = resolveProjectName(projectParam);
        List<Map<String, Object>> members = readMembers(projName);
        List<Object> items = new ArrayList<>();
        Map<String, String> liveByKey = new LinkedHashMap<>();   // storeKey → live tid (storeKeyByTid is tid → key)
        Map<String, String> liveByKeyEngine = new LinkedHashMap<>();
        String projId = null;
        final java.util.Set<String> memberTids = new java.util.HashSet<>();
        synchronized (this) {
            storeKeyByTid.forEach((tid, k) -> {
                liveByKey.put(k, tid);
                liveByKeyEngine.put(k + ":" + engineOfTid(tid).toLowerCase(java.util.Locale.ROOT), tid);
            });
            for (Map<String, Object> p : projects.values())
                if (projName.equals(p.get("name"))) projId = String.valueOf(p.get("id"));
            /* ONE membership read for the whole render (r2 nit): per-member
               projectTargetIds was N monitor acquisitions */
            if (projId != null) {
                Map<String, Object> pp = projects.get(projId);
                if (pp != null)
                    memberTids.addAll((List<String>) pp.get("targetIds"));
            }
        }
        for (Map<String, Object> m : members) {
            Map<String, Object> row = new LinkedHashMap<>();
            String key = String.valueOf(m.get("key"));
            row.put("key", key);
            row.put("name", m.get("name"));
            row.put("storePath", m.get("storePath"));
            if (m.get("engine") != null) row.put("engine", m.get("engine"));
            java.io.File dir = new java.io.File(storeRoot(), key);
            /* size = the jdb2's length — ONE stat, not a recursive dirSize walk
               per member per render (r2 F9: the WhatsApp entry is 2175+ files /
               1.4 GB). The orphan report keeps whole-entry dirSize (reclaim
               semantics) — two bases, each documented at its site */
            long sz = 0;
            boolean present = false;
            if (dir.isDirectory()) {
                java.io.File[] fs = dir.listFiles((d, n) -> n.endsWith(".jdb2"));
                if (fs != null && fs.length > 0) { sz = fs[0].length(); present = true; }
                else {
                    /* no jdb2 yet (the first analysis is running) — show the
                       artifact copy's footprint rather than a 0 that reads as
                       "nothing there" (r3 nit) */
                    java.io.File art = new java.io.File(dir, String.valueOf(m.get("name")));
                    if (art.isFile()) sz = art.length();
                }
            } else {
                java.io.File sp = new java.io.File(String.valueOf(m.get("storePath")));
                sz = sp.isFile() ? sp.length() : 0;
                present = sp.isFile();
            }
            row.put("sizeBytes", sz);
            row.put("hasJdb2", present);
            String mEng = m.get("engine") == null ? null : String.valueOf(m.get("engine")).toLowerCase(java.util.Locale.ROOT);
            String liveTid = mEng != null ? liveByKeyEngine.get(key + ":" + mEng) : liveByKey.get(key);
            boolean open = false;
            String state = "closed";
            if (liveTid != null) {
                Engine en;
                synchronized (this) { en = engines.get(liveTid); }
                if (en != null) {
                    /* scoped to THIS project (950876a-r1 #6): a member live in
                       ANOTHER project must not wear this editor's green LED */
                    row.put("target", liveTid);
                    if (memberTids.contains(liveTid)) {
                        open = true;
                        state = String.valueOf(en.info().get("state"));
                    } else {
                        /* ship the REAL state under the elsewhere label (r3 nit):
                           an errored engine reads vermillion, never the blue of
                           a live one */
                        String realState = String.valueOf(en.info().get("state"));
                        if ("error".equals(realState)) {
                            state = "error";
                        } else {
                            state = "open elsewhere";
                            row.put("openElsewhere", true);
                        }
                    }
                }
            }
            row.put("open", open);
            row.put("state", state);
            items.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("project", projName);
        out.put("items", items);
        return out;
    }

    /** target.remove {project?, target} — drop the MEMBERSHIP row (targets.json)
        and close the session if it's open in that project. The store entry
        survives — a re-add is one click and the analysis is intact. */
    public Map<String, Object> removeTarget(String projectParam, String idOrKey) throws OpException {
        String tid = null;
        String key;
        String removedEngine = null;
        String liveName = null;
        boolean liveIsActive = false;
        /* ONE hold for the whole read picture (r2 F5): three off-monitor reads
           of shared maps raced closes — projects.get(liveProject) NPE'd into a
           500 on a mid-remove project.close */
        synchronized (this) {
            try { tid = resolveTargetId(idOrKey); } catch (OpException notAnOpenId) { /* a store key */ }
            if (tid == null)
                for (Map.Entry<String, String> e : storeKeyByTid.entrySet())
                    if (e.getValue().equals(idOrKey)) { tid = e.getKey(); break; }
            if (tid != null) removedEngine = engineOfTid(tid);
            key = tid != null ? storeKeyByTid.get(tid) : idOrKey;
            if (key == null) throw OpException.notFound("no such target: " + idOrKey, "");
            if (tid != null) {
                String liveProject = projectOfTarget(tid);
                if (liveProject != null) {
                    Map<String, Object> lp = projects.get(liveProject);
                    if (lp == null)
                        throw OpException.busy("the target's project closed mid-gesture — retry",
                                "target=" + tid);
                    liveName = String.valueOf(lp.get("name"));
                    liveIsActive = liveProject.equals(activeProject);
                }
            }
        }
        String projName = resolveProjectName(projectParam);
        boolean closedEngine = false;
        if (liveName != null) {
            /* the gate is the EDITED project, not the active one (950876a-r1
               #3): the old check closed a session that belonged to another
               project whenever it happened to be active */
            if (!liveName.equals(projName))
                throw OpException.busy("target " + tid + " is open in project " + liveName
                        + " — remove it there", "target=" + tid);
            if (!liveIsActive)
                throw OpException.busy("target " + tid + " is open in project " + liveName
                        + " — switch to it and close it first", "target=" + tid);
            closeTarget(tid);
            closedEngine = true;
        }
        boolean removed = memberRemove(projName, key, removedEngine);
        /* r2 F4: the polarity that matters — a torn-down engine with no row
           dropped is the failure; a key that was never a member AND nothing
           live is the plain not-found */
        if (!removed && closedEngine)
            throw OpException.io("closed the engine but " + projName + " had no membership row for "
                    + key + " — the session is closed; the row was already gone", "");
        if (!removed && !closedEngine)
            throw OpException.notFound("not a member of " + projName + ": " + key, "");
        return state();
    }

    private String resolveProjectName(String projectParam) throws OpException {
        synchronized (this) {
            if (projectParam == null || projectParam.isBlank()) {
                if (activeProject == null) throw OpException.notFound("no active project", "");
                return String.valueOf(projects.get(activeProject).get("name"));
            }
            for (Map<String, Object> p : projects.values())
                if (projectParam.equals(p.get("id")) || projectParam.equals(p.get("name")))
                    return String.valueOf(p.get("name"));
        }
        if (dirForReadOnly(projectParam) == null)
            throw OpException.notFound("no such project: " + projectParam, "");
        return projectParam;   // a closed on-disk project — the param IS the name
    }

    public synchronized void switchProject(String idOrName) throws OpException {
        for (Map<String, Object> p : projects.values()) {
            if (p.get("id").equals(idOrName) || p.get("name").equals(idOrName)) {
                activeProject = (String) p.get("id");
                @SuppressWarnings("unchecked")
                List<String> ids = (List<String>) p.get("targetIds");
                if (!ids.contains(activeTarget)) activeTarget = ids.isEmpty() ? null : ids.get(0);
                swapMapsLocked(p, null);   // maps are per-project (GRAPHS_SPEC §3)
                return;
            }
        }
        throw OpException.notFound("project not found: " + idOrName, "open projects: " + projects.size());
    }

    /** Navigate to an already-open target in ANY project (user directive 2026-09-08 —
        an artifact already open takes you there): owner lookup, state re-verify,
        switchProject + activateTarget under THIS ONE hold (review a811842-r1 F3 —
        the pull twin used to take three separate holds, and a project.close in the
        gap escaped as a raw E_NOTFOUND naming an internal id). A failure RESTORES
        activeProject/activeTarget and the map view (F1: switching first and failing
        later silently relocated the user into a project they never gestured to).
        Returns the engine's fresh info grafted with deduped:true + the note. */
    public synchronized Map<String, Object> navigateToTarget(String tid, String gesture) throws OpException {
        String owner = projectOfTarget(tid);
        Engine e = engines.get(tid);
        Object st = e == null ? null : e.info().get("state");
        if (owner == null || e == null || "error".equals(st) || "closed".equals(st))
            throw OpException.busy("the target's state just changed — retry the " + gesture, "target=" + tid);
        String prevProject = activeProject, prevTarget = activeTarget;
        try {
            switchProject(owner);
            if (!activateTarget(tid))
                throw OpException.busy("the target's state just changed — retry the " + gesture, "target=" + tid);
        } catch (OpException | RuntimeException fail) {
            activeProject = prevProject;
            activeTarget = prevTarget;
            Map<String, Object> back = prevProject == null ? null : projects.get(prevProject);
            if (back != null && !prevProject.equals(owner)) swapMapsLocked(back, null);
            else if (back == null) setMaps(null);   // a811842-r2 F-C: the empty-active rollback idiom
            throw fail;
        }
        Map<String, Object> info = e.info();
        info.put("deduped", true);
        info.put("note", "already open in project " + owner + " — switched there");
        return info;
    }

    /** project.open {name}: create an empty project and make it active.
     *  Idempotent on name — opening an existing project just switches to it. */
    public synchronized Map<String, Object> openProject(String name) throws OpException {
        if (name == null || name.isBlank()) throw OpException.syntax("project.open needs a name", "");
        String trimmed = name.trim();
        /* r2 F7: a wipe in flight owns the name — an open that slipped between
           deleteProject's open-check and its recursive delete watched its own
           project re-materialize under the wipe ("1 entry survived") */
        if (deletingProjects.contains(trimmed))
            throw OpException.busy("project '" + trimmed + "' is being deleted — retry shortly", "");
        for (Map<String, Object> p : projects.values())
            if (p.get("name").equals(trimmed)) {
                switchProject(String.valueOf(p.get("id")));
                return state();
            }
        /* MONOTONIC ids, like targets (round-26 F1: 'first free id' recycled a
           closed project's id to the next opener — an open captured against the
           CLOSED project then resolved to the impostor, and the proj==null
           guard that was supposed to catch exactly this never fired). The put
           is guarded too (round-27 F9: a future id-carrying import must never
           silently replace a live project's map entry) */
        String id = "p" + (nextProjectNum++);
        if (projects.containsKey(id))
            throw OpException.busy("project id collision on " + id
                    + " — refusing to replace a live project's map entry", "id=" + id);
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("id", id);
        p.put("name", trimmed);
        p.put("targetIds", new ArrayList<String>());
        projects.put(id, p);
        activeProject = id;
        activeTarget = null;
        swapMapsLocked(p, null);   // a fresh project's map.list is honestly EMPTY
        /* the project's saved missions (records, transcripts, rows) come back with it — resolved
           here under the Workspace monitor, the manager locks itself inside (Workspace → stores) */
        try { agents().loadArchivedMissions(name, dirFor(name)); } catch (Exception e) { System.err.println("[agents] archive load failed: " + e); }
        return state();
    }

    /** project.close {project}: close the project AND all its targets (engines
     *  released, chips gone), then activate a remaining project. Closing the
     *  LAST project returns the workspace to the empty boot state (no active
     *  project, maps unwired) — empty is a legal state (2026-09-07), not an
     *  error. Map surgery happens under the Workspace monitor;
     *  the engine closes do NOT (review #3: closing inside it stalled every op
     *  in the workbench behind OPEN_LOCK), and each jdb2 path is reserved for
     *  the whole unload so a concurrent open waits it out (round-10 #1). */
    public Map<String, Object> closeProject(String idOrName) throws OpException {
        Map<String, Object> victim;
        List<Engine> toClose = new ArrayList<>();
        List<String> reserved = new ArrayList<>();
        String closedName = null;
        List<String> closedIds = List.of();
        /* try/finally around the WHOLE close (round-14 F10): a throw mid-surgery
           must still close what was detached and release what was reserved —
           otherwise the engines orphan in JEB holding the .jdb2 lock, and the
           next open of that key walks into the locked database with no E_BUSY */
        try {
            synchronized (this) {
                victim = null;
                for (Map<String, Object> p : projects.values())
                    if (p.get("id").equals(idOrName) || p.get("name").equals(idOrName)) { victim = p; break; }
                if (victim == null) throw OpException.notFound("project not found: " + idOrName,
                        "open projects: " + projects.size());
                @SuppressWarnings("unchecked")
                List<String> ids = new ArrayList<>((List<String>) victim.get("targetIds"));
                for (String tid : ids) {
                    Engine e = engines.remove(tid);
                    TargetMeta tm = targets.remove(tid);
                    String eng = engineByTid.remove(tid);
                    storeKeyByTid.remove(tid);
                    originByTid.remove(tid);
                    if (tm != null) {
                        String key = reservationKeyOf(tm.path(), eng != null ? eng : "jeb");
                        reserveClosing(key);       // AT removal time: detached engines are never unreserved
                        reserved.add(key);
                    }
                    if (e != null) toClose.add(e);
                }
                projects.remove(victim.get("id"));
                closedName = String.valueOf(victim.get("name"));
                closedIds = ids;
                if (presence != null) presence.clearPositions(String.valueOf(victim.get("id")));
                if (victim.get("id").equals(activeProject)) {
                    if (projects.isEmpty()) {
                        /* the LAST project just closed: back to the empty boot
                           state — no active project, map store unwired (map ops
                           fail honestly until the next open swaps one in) */
                        activeProject = null;
                        activeTarget = null;
                        setMaps(null);
                    } else {
                        activeProject = projects.keySet().iterator().next();
                        @SuppressWarnings("unchecked")
                        List<String> rest = (List<String>) projects.get(activeProject).get("targetIds");
                        activeTarget = rest.isEmpty() ? null : rest.get(0);
                        swapMapsLocked(projects.get(activeProject), null);   // maps follow the newly active project
                    }
                }
            }
            /* the closed project's runtime sessions end with it and its per-project stores
               leave the caches — OFF the monitor (frida round trips) */
            if (runs != null) runs.projectClosed(closedName, closedIds);
            if (agents != null) agents.evictProposals(closedName);
        } finally {
            try {
                for (Engine e : toClose)
                    try { e.close(); } catch (Throwable ignored) {}
            } finally {
                synchronized (this) {
                    for (String k : reserved) releaseClosing(k);   // releaseClosing notifies
                }
            }
        }
        return state();
    }

    /** The reservation key for an artifact path: the .jdb2 the JEB lock is on —
        an .apk target and its sibling .jdb2 resolve to the SAME key (round-10
        #1). Canonical when the file exists (exact case, per-volume correct);
        when it does NOT (a derived sibling not yet written), the caller folds
        ONLY IF the parent's volume is case-insensitive (round-23 F1:
        canonicalization is a no-op on nonexistent paths — two engines landed
        on one database through a case-variant). */
    private static boolean isJdb2Name(String path) {
        return path.toLowerCase(java.util.Locale.ROOT).endsWith(".jdb2");
    }

    private static String reservationKeyOf(String artifactPath, String engine) {
        if (engine != null && !"jeb".equalsIgnoreCase(engine))
            return engine.toLowerCase(java.util.Locale.ROOT) + ":" + jdb2KeyOf(artifactPath);
        return jdb2KeyOf(artifactPath);
    }

    private static String jdb2KeyOf(String artifactPath) {
        /* the KEY is internal-only and must be STABLE across existence flips
           (round-25 N6: jdb2KeyOf returned lowercase-folded when the file was
           absent and canonical when present, so a materializing jdb2 flipped
           the key and a concurrent open missed the reservation entirely).
           Hence: canonical parent always; leaf folded whenever the parent's
           volume probes case-insensitive — existence-independent. Display
           names come from canonical paths, never from this key. */
        String p = isJdb2Name(artifactPath) ? artifactPath
                : jdb2For(new File(artifactPath)).getAbsolutePath();
        File f = new File(p);
        File parent = f.getParentFile();
        String canonParent;
        try { canonParent = parent.getCanonicalPath(); } catch (IOException e) { canonParent = parent.getAbsolutePath(); }
        String leaf = f.getName();
        return canonParent + "/" + (ciVolume(parent) ? leaf.toLowerCase(java.util.Locale.ROOT) : leaf);
    }

    /** Path equality that is right per-volume: canonical compare first; when
        either side can't resolve (a nonexistent derived path or a renamed-
        away stale path), fold ONLY if the volume of a's parent is case-
        insensitive — probed once per directory, not assumed (round-23 F1/F3).
        Blank is not a path (round-24 F8: canon("") is the process CWD). */
    static boolean samePath(String a, String b) {
        if (a == null || b == null || a.isBlank() || b.isBlank()) return false;
        if (canon(a).equals(canon(b))) return true;
        return ciVolume(new File(a).getParentFile()) && canon(a).equalsIgnoreCase(canon(b));
    }

    /* per-directory case-sensitivity probe, cached — pulledDir probe's Workspace
       twin; the probe must not LATCH the answer (round-23 F5), and a probe
       FAILURE must not latch anything (round-24 F3: a missing dir cached
       'case-sensitive' forever — the pulled dir is created on first use) */
    /* COMPLETED probes cache forever per directory; a probe FAILURE answers
       conservatively WITHOUT caching (round-28 F2: the 60s failure TTL pinned
       a stale 'false' and split one jdb2 into two live engines). */
    private static final java.util.concurrent.ConcurrentHashMap<String, Boolean> ciCache =
            new java.util.concurrent.ConcurrentHashMap<>();

    public static boolean ciVolume(File dir) {
        if (dir == null) return false;
        /* a missing dir: probe the nearest EXISTING ancestor's volume (round-26
           F4: answering false-uncached flipped the fold across the create
           boundary). */
        File d = dir;
        while (d != null && !d.isDirectory()) d = d.getParentFile();
        if (d == null) return false;
        Boolean cached = ciCache.get(d.getAbsolutePath());
        if (cached != null) return cached;
        File probe = null;
        try {
            probe = File.createTempFile(".ci-probe-", null, d);
            boolean ci = new File(probe.getParent(),
                    probe.getName().toUpperCase(java.util.Locale.ROOT)).exists();
            ciCache.put(d.getAbsolutePath(), ci);   // COMPLETED probe: permanent
            return ci;
        } catch (Exception e) {
            return false;   // failure: conservative, NOT cached — re-probe next call
        } finally {
            if (probe != null) probe.delete();
        }
    }

    private static String canon(String absPath) {
        try { return new File(absPath).getCanonicalPath(); } catch (IOException e) { return absPath; }
    }

    // ------------------------------------------------------------ targets

    public synchronized List<Object> targetList() throws OpException {
        List<Object> out = new ArrayList<>();
        for (String tid : currentProjectTargetIds()) {
            Engine e = engines.get(tid);
            Map<String, Object> row = new LinkedHashMap<>(e.info());
            row.put("active", tid.equals(activeTarget));
            row.put("key", targetStoreKey(tid));   // the durable store identity (b2-r6 L3: the client matches runs of this target across boots)
            /* a re-keyed database's predecessor is still on disk until someone deletes it: a fact
               about this target the strip can show for as long as it is true (not only on a card) */
            TargetMeta tmRow = targets.get(tid);
            File asideRow = tmRow != null && "jeb".equalsIgnoreCase(engineOfTid(tid))
                    ? newestAside(jdb2FileOf(tmRow.path())) : null;
            if (asideRow != null) {
                Map<String, Object> rk = new LinkedHashMap<>();
                rk.put("aside", asideRow.getName());
                rk.put("asidePath", asideRow.getAbsolutePath());
                rk.put("asideBytes", asideRow.length());
                row.put("rekeyed", rk);
            }
            out.add(row);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private List<String> currentProjectTargetIds() throws OpException {
        /* empty workspace (fresh boot, or the last project just closed): NO ids,
           not an error — workspace.state must render the blank shell */
        if (activeProject == null) return List.of();
        return projectTargetIds(activeProject);
    }

    /* ids of an EXPLICIT project — the scans of an open scope to the project
       the gesture was captured against, not whatever is active NOW (round-26
       F2: the entry capture desynchronized every post-wait scan) */
    private List<String> projectTargetIds(String projectId) throws OpException {
        Map<String, Object> p = projects.get(projectId);
        if (p == null) throw OpException.notFound("project not found: " + projectId, "");
        return (List<String>) p.get("targetIds");
    }

    /* the project whose targetIds hold tid (or null) — the cross-project dedupe
       NAVIGATES to it (user directive 2026-09-08: an artifact already open in
       some project takes you there, never an E_BUSY refusal) */
    public synchronized String projectOfTarget(String tid) {
        for (Map<String, Object> p : projects.values()) {
            @SuppressWarnings("unchecked")
            List<String> ids = (List<String>) p.get("targetIds");
            if (ids != null && ids.contains(tid)) return String.valueOf(p.get("id"));
        }
        return null;
    }

    public synchronized String activeTarget() { return activeTarget; }

    /** Ids of the current project's open targets (search allTargets fan-out). */
    public synchronized List<String> openTargetIds() throws OpException {
        return new ArrayList<>(currentProjectTargetIds());
    }

    /** Resolve a target param (id or name) or fall back to the active target. */
    public synchronized Engine engineFor(String targetParam) throws OpException {
        String tid = targetParam;
        if (tid == null || tid.isBlank()) {
            tid = activeTarget;
        } else if (!targets.containsKey(tid)) {
            // name match (may be ambiguous: two gmail.apk targets → prefer active, else first)
            List<TargetMeta> matches = new ArrayList<>();
            for (TargetMeta t : targets.values())
                if (t.name().equals(targetParam)) matches.add(t);
            if (matches.isEmpty())
                throw OpException.notFound("target not found: " + targetParam,
                        "open targets: " + targets.size());
            tid = matches.stream().anyMatch(t -> t.id().equals(activeTarget))
                    ? activeTarget : matches.get(0).id();
        }
        Engine e = engines.get(tid);
        /* the null-active state names itself (round-26 F6: 'target not open:
           null' is what every default-target op prints from an empty project) */
        if (e == null)
            throw OpException.notFound(tid == null
                    ? "no active target — open one or switch to a project that has one"
                    : "target not open: " + tid, "");
        return e;
    }

    public synchronized void switchTarget(String idOrName) throws OpException {
        String tid = resolveTargetId(idOrName);
        if (!currentProjectTargetIds().contains(tid))
            throw OpException.notFound("target not in current project: " + idOrName,
                    "project=" + activeProject);
        activeTarget = tid;
    }

    /**
     * target.open {path, engine?}: detect the artifact type from magic bytes (never the
     * extension — WORKBENCH_SPEC §2.4), pick the engine, open a concurrent session, make
     * it active. Analysis runs in the background; poll target.info for state.
     */
    /** Global variant: the live target backing this path in
        ANY project (the filesystem doesn't care about project scopes — review R7:
        a pull from p2 overwrote bytes under p1's engine and only then failed).
        LOOKUP ONLY — it never activates: a caller that goes on to REFUSE the
        gesture (cross-project E_BUSY) must not re-point activeTarget as a side
        effect of the refusal (review #12). The info carries 'currentProject' so
        the caller decides. */
    public synchronized Map<String, Object> liveTargetForPathAnyProject(String absolutePath) throws OpException {
        return liveTargetForPathForProject(absolutePath, activeProject);
    }

    /** Same lookup, scoped to a CALLER-SUPPLIED project id (round-28 F1: the
        dedupe gate computed 'currentProject' from activeProject but decided on
        behalf of the captured openProject — after a mid-open switch the two
        contradicted: it refused about the gesture's OWN project, or stole a
        cross-project session when the new one won the race). */
    public synchronized Map<String, Object> liveTargetForPathForProject(String absolutePath,
            String projectId) throws OpException {
        return liveTargetForPathForProject(absolutePath, projectId, null);
    }

    public synchronized Map<String, Object> liveTargetForPathForProject(String absolutePath,
            String projectId, String engineFilter) throws OpException {
        String want = canon(absolutePath);
        /* projectId null = EMPTY workspace (2026-09-07): the live scan is GLOBAL
           anyway; "current project" is the empty set, not an exception. This was
           the device.pull killer — 'project not found: null' on the very first
           gesture against the empty default (fix-review r1 #2) */
        java.util.Set<String> cur = projectId == null
                ? java.util.Set.of()
                : new java.util.HashSet<>(projectTargetIds(projectId));
        for (TargetMeta tm : targets.values()) {
            if (engineFilter != null && !engineFilter.equalsIgnoreCase(engineOfTid(tm.id()))) continue;
            /* the store keeps the COPY's path in tm.path(); the user's gesture
               carries the SOURCE path — match both (950876a-r1 #7: the cheap
               path arm went dead for source-path re-opens — every re-open paid
               a full hash and fell through to the navigation arm) */
            String origin = originByTid.get(tm.id());
            boolean originHit = false;
            if (origin != null) {
                /* parse from the RIGHT — '|' is a legal filename byte (r3 nit) */
                int p2 = origin.lastIndexOf('|');
                int p1 = origin.lastIndexOf('|', p2 - 1);
                if (p1 > 0) {
                    /* the witness must hold NOW (r2 F11): a source file rewritten
                       since the open is a different artifact wearing the same path */
                    java.io.File of = new java.io.File(origin.substring(0, p1));
                    originHit = of.isFile()
                            && of.length() == Long.parseLong(origin.substring(p1 + 1, p2))
                            && of.lastModified() == Long.parseLong(origin.substring(p2 + 1))
                            && samePath(origin.substring(0, p1), want);
                }
            }
            if (!samePath(tm.path(), want) && !originHit) continue;
            Engine e = engines.get(tm.id());
            if (e == null) continue;
            /* ONE walk (review N-1: the state test and the payload each ran
               info() — the same double-walk defect review #2/#3 raised for the
               dedupe region, and this is the walk that throws FIRST in the
               ready&&error replay window, ahead of the guarded one) */
            Map<String, Object> info = e.info();
            Object st = info.get("state");
            if ("error".equals(st) || "closed".equals(st)) continue;
            info.put("currentProject", cur.contains(tm.id()));
            return info;
        }
        return null;
    }

    /** Deliberate activation — separated from every lookup (review #12), and
        re-verified on entry: the analysis thread can flip a target to error, or
        a project.switch can move it out of scope, in the lookup→activate gap
        (review F8). False when the target no longer qualifies. */
    public synchronized boolean activateTarget(String id) throws OpException {
        Engine e = engines.get(id);
        if (e == null) return false;
        Object st = e.info().get("state");
        if ("error".equals(st) || "closed".equals(st)) return false;
        if (!currentProjectTargetIds().contains(id)) return false;
        activeTarget = id;
        return true;
    }

    /** The live target whose jdb2 KEY is this jdb2 path — an .apk target and its
        sibling .jdb2 collide on ONE key (round-10 #1), so a pull of a sibling
        extension (base.odex → base.jdb2) must see the live base.apk target too,
        or the invalidation yanks the file under it (round-14 F3). Lookup only,
        never activates. */
    public synchronized Map<String, Object> liveTargetForJdb2Key(String jdb2AbsPath) throws OpException {
        String want = jdb2KeyOf(jdb2AbsPath);   // the KEY comparison is canonical on both sides
        java.util.Set<String> cur = new java.util.HashSet<>(currentProjectTargetIds());
        for (TargetMeta tm : targets.values()) {
            if (!"jeb".equalsIgnoreCase(engineOfTid(tm.id()))) continue;
            /* engine-less targets skip the key syscall (review finding 4 nit) */
            Engine e = engines.get(tm.id());
            if (e == null) continue;
            /* the KEY filter BEFORE the info() build, and ONE build (review F4:
               the old order state-tested first — a SECOND info() on the winner,
               on every pull and invalidation. Post-ff087a6 info() is walk-free;
               the fold now stands so a throwing future-info() can't poison the
               lookup on non-matching engines — review finding 11) */
            if (!samePath(jdb2KeyOf(tm.path()), want)) continue;
            Map<String, Object> info = e.info();
            Object st = info.get("state");
            if ("error".equals(st) || "closed".equals(st)) continue;
            info.put("currentProject", cur.contains(tm.id()));
            return info;
        }
        return null;
    }

    /** A leading `~` (or `~/`) means the SERVER's home — the browser can't expand it
        (browse B4). Only the File construction uses this; messages keep the typed path. */
    public static String expandHome(String p) {
        if (p != null && (p.equals("~") || p.startsWith("~/")))
            return System.getProperty("user.home") + p.substring(1);
        return p;
    }

    public Map<String, Object> openTarget(String path, String engineName) throws OpException {
        return openTarget(path, engineName, "human");
    }

    /** The actor decides what a heap shortfall does (see heapAdmission): a human's open proceeds
     *  with a warning; an agent's or an MCP client's is refused with the numbers and the way out. */
    public Map<String, Object> openTarget(String path, String engineName, String actor)
            throws OpException {
        return openTarget(path, engineName, actor, false);
    }

    /** `rekey`: the caller's consent to a re-key when the saved database shares its JEB key with
     *  an open target (rekeyForOpen) — sent by the workbench UI's own open gestures, honoured for
     *  a human actor only, and never forwarded by the MCP tool surface (ToolFacade strips it): an
     *  actor string is declared by the client, and a client registered as human:<name> is still
     *  a model deciding on its own (review 2026-10-05). */
    public Map<String, Object> openTarget(String path, String engineName, String actor, boolean rekey)
            throws OpException {
        openActor.set(actor == null ? "human" : actor);
        openRekey.set(rekey && "human".equals(EventBus.actorKind(actor)));
        try { return openTargetImpl(path, engineName); }
        finally { openActor.remove(); openRekey.remove(); }
    }

    private final ThreadLocal<String> openActor = new ThreadLocal<>();
    private final ThreadLocal<Boolean> openRekey = new ThreadLocal<>();

    /** The workbench's open gesture met an artifact whose saved database carries the JEB key of an
     *  open target. JEB holds one project per key and cannot change a database's key, so the two
     *  can only be open together if this one becomes a NEW database: set the old one aside (kept,
     *  with its key record — the way back, rekeyUndo), leave the replay marker. The open then
     *  goes on as a fresh analysis, mints a key of its own, re-applies the edit history and only
     *  then writes the new database (dirtyReplayFor, JebEngine.firstSaveAfterReplay). The same
     *  steps as backend/jdb2-rekey.sh. Returns what was done, for the open's answer. */
    private Map<String, Object> rekeyForOpen(File jdb2, String key, List<String> with) throws OpException {
        File lock = new File(jdb2.getPath() + ".lock");
        if (lock.exists()) {
            /* JEB keeps touching the lock of a database it has loaded: a fresh one is somebody's */
            long idle = (System.currentTimeMillis() - lock.lastModified()) / 1000;
            throw OpException.busy(jdb2.getName() + " shares its internal JEB key (" + key + ") with " + String.join(", ", rekeyNames(with)) + " and would be analysed again"
                    + " under a key of its own — but " + lock.getName() + " is there, " + (idle < 30
                            ? "written " + idle + " s ago: another workbench or JEB has this database open. Close it there first"
                            : "untouched for " + (idle < 120 ? idle + " s" : idle / 60 + " min") + ": left behind by a workbench that was killed. Delete that file and open again"),
                    "jdb2=" + jdb2.getPath());
        }
        String at = java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString();
        String stamp = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(java.time.ZoneOffset.UTC).format(java.time.Instant.now());
        File aside = new File(jdb2.getPath() + ".rekeyed-" + stamp);
        File marker = Engines.replayMarker(jdb2);
        long bytes = jdb2.length();
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("at", at);
        r.put("key", key);
        r.put("with", rekeyNames(with));
        r.put("aside", aside.getName());
        r.put("asidePath", aside.getAbsolutePath());
        r.put("asideBytes", bytes);
        r.put("by", "open");
        try {
            java.nio.file.Files.writeString(marker.toPath(), dev.workbench.registry.Json.write(r) + "\n");
            java.nio.file.Files.move(jdb2.toPath(), aside.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            marker.delete();   // the database was not moved: no half re-key
            throw OpException.io("could not set " + jdb2.getName() + " aside to analyse it again under a JEB key of its own (" + e + ") — nothing was changed", "jdb2=" + jdb2.getPath());
        }
        /* the key record goes WITH the database it describes: after an undo it is on record again */
        File rec = new File(jdb2.getPath() + ".key");
        try { if (rec.isFile()) java.nio.file.Files.move(rec.toPath(), new File(aside.getPath() + ".key").toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
        catch (IOException e) { rec.delete(); }   // without it the old database is "unknown" again: safe
        /* edits made while the .jdb2 itself was the opened target are recorded in ITS sidecar, not
           the artifact's: they are not in the history that gets re-applied — counted and said */
        int notCarried = 0;
        try {
            File direct = new File(sidecarFor(jdb2), "history.json");
            if (direct.isFile()) {
                Object root = dev.workbench.registry.Json.parse(java.nio.file.Files.readString(direct.toPath()));
                List<?> entries = root instanceof List<?> l ? l : root instanceof Map<?, ?> m && m.get("entries") instanceof List<?> l2 ? l2 : List.of();
                for (Object o : entries)
                    if (o instanceof Map<?, ?> e && !Boolean.TRUE.equals(e.get("undone")) && ("rename".equals(e.get("op")) || "comment".equals(e.get("op")))) notCarried++;
            }
        } catch (Exception ignored) {}
        r.put("notCarried", notCarried);
        r.put("edits", 0);   // the count of history entries to re-apply, set when the target is registered
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("jdb2", jdb2.getPath()); ctx.put("aside", aside.getName()); ctx.put("with", with); ctx.put("bytes", bytes);
        Log.warn("target.open", jdb2.getName() + " re-keyed on open: it shared " + key + " with " + String.join(", ", rekeyNames(with))
                + "; the old database is kept as " + aside.getName() + ", the artifact is analysed again and its history re-applied", ctx);
        return r;
    }

    /** The re-key a marker beside a missing database describes (written by rekeyForOpen or by
     *  backend/jdb2-rekey.sh), or null when there is none. */
    private Map<String, Object> rekeyInFlight(File jdb2) {
        File marker = Engines.replayMarker(jdb2);
        if (!marker.isFile()) return null;
        Map<String, Object> r = new LinkedHashMap<>();
        try { r.putAll(dev.workbench.registry.Json.parseObject(java.nio.file.Files.readString(marker.toPath()).trim())); }
        catch (Exception ignored) {}   // an old or hand-made marker: the re-key goes on, with less to tell
        r.put("resumed", true);
        r.putIfAbsent("with", List.of());
        r.putIfAbsent("edits", 0);
        r.putIfAbsent("notCarried", 0);
        File aside = newestAside(jdb2);
        if (aside != null) { r.put("aside", aside.getName()); r.put("asidePath", aside.getAbsolutePath()); r.put("asideBytes", aside.length()); }
        return r;
    }

    /** The database a re-key set aside beside this one — the newest, when there are several. */
    private static File newestAside(File jdb2) {
        File dir = jdb2 == null ? null : jdb2.getParentFile();
        String[] names = dir == null ? null : dir.list((d, n) -> n.startsWith(jdb2.getName() + ".rekeyed-") && !n.endsWith(".key"));
        if (names == null || names.length == 0) return null;
        java.util.Arrays.sort(names);   // the suffix is a UTC timestamp: the last one is the newest
        return new File(dir, names[names.length - 1]);
    }

    /** True when every target the clash is about is leaving: `closing`, or already out of the maps
     *  with its engine's close still queued. */
    private synchronized boolean holderLeaving(String[] clash) {
        if ("closing".equals(clash[0])) return true;
        if (!"same-key".equals(clash[0]) || clash.length <= 3) return false;
        for (int i = 3; i < clash.length; i++) if (targets.containsKey(clash[i])) return false;
        return true;
    }

    /** What the open's answer says about a re-key — read on the open card by someone who clicked
     *  OPEN and got an analysis instead of a load: what is happening and why, what it costs, what
     *  is kept and where, and the way back. */
    private static String rekeyNote(Map<String, Object> r) {
        int edits = ((Number) r.getOrDefault("edits", 0)).intValue(), notCarried = ((Number) r.getOrDefault("notCarried", 0)).intValue();
        List<?> with = r.get("with") instanceof List<?> l ? l : List.of();
        StringBuilder b = new StringBuilder();
        b.append(Boolean.TRUE.equals(r.get("resumed")) ? "Finishing a re-key" + (r.get("at") != null ? " begun " + r.get("at") : "") + ": analysed again instead of loaded"
                : "Analysed again instead of loaded — minutes rather than seconds for a large app");
        if (!with.isEmpty())
            b.append(". Its saved database has the same internal JEB key").append(r.get("key") != null && !String.valueOf(r.get("key")).isBlank() ? " (" + r.get("key") + ")" : "").append(" as ")
             .append(String.join(", ", with.stream().map(String::valueOf).toList())).append(", and JEB cannot hold both");
        b.append(". ").append(edits == 0 ? "No edits in its history to re-apply" : edits == 1 ? "1 edit to re-apply from its history" : edits + " edits to re-apply from its history");
        if (notCarried > 0) b.append(" (").append(notCarried).append(" made while the .jdb2 itself was the opened target are not among them)");
        b.append("; decompilations are redone on demand");
        if (r.get("asidePath") != null)
            b.append(". The old database is kept: ").append(r.get("asidePath")).append(" (").append(humanBytes(((Number) r.getOrDefault("asideBytes", 0)).longValue()))
             .append(") — UNDO RE-KEY puts it back");
        return b.toString();
    }

    /** target.rekeyUndo: the way back from a re-key. The target is closed WITHOUT writing the new
     *  database and without marking anything discarded — edits made since the re-key stay applied
     *  in the history, newer than the old database's last save, so they replay onto it when it is
     *  opened next. The new database (an analysis plus that same history) is deleted, the old one
     *  and its key record go back in place. The two databases share their JEB key again: the
     *  restored one opens when the other target is closed — or is re-keyed again beside it. */
    public Map<String, Object> rekeyUndo(String targetParam, String actor) throws OpException {
        if (!"human".equals(EventBus.actorKind(actor)))
            throw OpException.scope("target.rekeyUndo replaces a database — it is the operator's", "actor=" + actor);
        String tid, name;
        File jdb2;
        Engine e;
        synchronized (this) {
            tid = resolveTargetIdOrActive(targetParam);
            TargetMeta tm = targets.get(tid);
            if (tm == null) throw OpException.notFound("no such target: " + targetParam, "");
            /* everything that can refuse the close is checked BEFORE the engine is told to drop
               its edits: a latch set on a target that then stays open would refuse its saves */
            if (!currentProjectTargetIds().contains(tid))
                throw OpException.notFound(tm.name() + " is not in the current project — open its project to undo its re-key", "target=" + tid);
            name = tm.name();
            jdb2 = jdb2FileOf(tm.path());
            e = engines.get(tid);
        }
        File aside = newestAside(jdb2);
        if (aside == null)
            throw OpException.notFound(name + " was not re-keyed: there is no " + jdb2.getName() + ".rekeyed-<time> beside its database", "target=" + tid);
        if (e != null) try { e.discardUnsaved(); } catch (Throwable ignored) {}   // close() then unloads without writing
        closeTarget(tid, null);   // synchronous: the project is unloaded and its lock released when this returns
        /* the new database leaves only once the old one is back in its place: step aside, swap,
           then delete — a failure in the middle puts the new one back and changes nothing */
        File parked = new File(jdb2.getPath() + ".undo-tmp");
        boolean hadNew = jdb2.isFile();
        try {
            if (hadNew) java.nio.file.Files.move(jdb2.toPath(), parked.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            try {
                java.nio.file.Files.move(aside.toPath(), jdb2.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException ex) {
                if (hadNew) java.nio.file.Files.move(parked.toPath(), jdb2.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                throw ex;
            }
        } catch (IOException ex) {
            throw OpException.io(name + " is closed, but putting its old database back failed (" + ex + ") — nothing was replaced. The old database is still "
                    + aside.getAbsolutePath() + "; open the target again to go on with the new one", "target=" + tid);
        }
        File ak = new File(aside.getPath() + ".key"), rec = new File(jdb2.getPath() + ".key");
        try {
            if (ak.isFile()) java.nio.file.Files.move(ak.toPath(), rec.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            else java.nio.file.Files.deleteIfExists(rec.toPath());   // the record there was the NEW database's
        } catch (IOException ex) { rec.delete(); }                   // no record is safe: the key is read again when it is opened alone
        parked.delete();
        Engines.replayMarker(jdb2).delete();
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("target", tid); ctx.put("jdb2", jdb2.getPath()); ctx.put("from", aside.getName());
        Log.warn("target.rekeyUndo", name + ": the database from before the re-key is back (" + aside.getName() + " → " + jdb2.getName() + "); the target is closed", ctx);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("target", tid);
        out.put("closed", true);
        out.put("restored", jdb2.getName());
        out.put("note", "the database from before the re-key is back and " + name + " is closed. It shares its JEB key with the other target again:"
                + " it opens when that one is closed — opened beside it, it is re-keyed again");
        return out;
    }

    /** "t1 (base.apk)" for each id — the names the user sees on the strip; with the project when
     *  it is not the one in front of him. */
    private synchronized List<String> rekeyNames(List<?> ids) {
        List<String> out = new ArrayList<>();
        for (Object id : ids) {
            TargetMeta t = targets.get(String.valueOf(id));
            String pid = t == null ? null : projectOfTarget(t.id());
            Map<String, Object> pr = pid == null ? null : projects.get(pid);
            out.add(t == null ? String.valueOf(id)
                    : id + " (" + t.name() + (pr != null && !pid.equals(activeProject) ? ", in project \"" + pr.get("name") + "\"" : "") + ")");
        }
        return out;
    }

    private static String humanBytes(long n) {
        return n >= 1L << 30 ? String.format("%.1f GB", n / 1073741824.0) : n >= 1L << 20 ? (n >> 20) + " MB" : (n >> 10) + " KB";
    }

    /** Heap admission for a JEB open. A WhatsApp-size apk (125 MB, 82k classes) sits at ~3.2 GB
     *  live once analysed and needs more at the analysis peak; a cached load of the same size is
     *  ~5.5 GB with its decompiled classes. An open that does not fit dies with OutOfMemoryError
     *  minutes later in the analysis thread, after the op answered ok — and a heap at its ceiling
     *  makes every later open fail too (a researcher cycling nine WhatsApp builds through a 16 GB
     *  heap, 2026-09-21: three opens in a row dead at the dex parser, the server alive but useless).
     *  So the need is estimated up front against what the collector could free (the old
     *  generation's size after its last collection plus the young generation now): short → a
     *  human is told and proceeds, any other actor is refused with the open targets to close and
     *  the WORKBENCH_XMX way out. Returns the warning text for a human, null when it fits. */
    private String heapAdmission(File artifact, File jdb2, String name) throws OpException {
        long size = artifact != null && artifact.isFile() ? artifact.length() : (jdb2 != null && jdb2.isFile() ? jdb2.length() / 8 : 0);
        boolean cached = jdb2 != null && jdb2.isFile();
        long need = Math.max(512L << 20, size * (cached ? 44 : 32));
        long max = Runtime.getRuntime().maxMemory();
        long headroom = max - heapLiveEstimate();
        if (headroom >= need) return null;
        /* short on the estimate: G1 leaves the old generation full of promoted garbage after a big
           load (WhatsApp reopened: 25 GB "live" of 32 with 6 GB really live), so before refusing
           anything, collect once and measure the real live set — a few seconds, once per open */
        System.gc();
        try { Thread.sleep(300); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        Runtime rt = Runtime.getRuntime();
        headroom = max - Math.min(rt.totalMemory() - rt.freeMemory(), heapLiveEstimate());
        if (headroom >= need) return null;
        StringBuilder open = new StringBuilder();
        synchronized (this) {
            for (TargetMeta t : targets.values()) {
                if (open.length() > 0) open.append(", ");
                open.append(t.id()).append(' ').append(t.name());
            }
        }
        String msg = String.format("heap headroom %.1f GB of %.0f GB; %s of %s (%d MB) needs about %.1f GB — close targets you no longer need (open: %s)%s",
                headroom / 1073741824.0, max / 1073741824.0, cached ? "a cached load" : "a fresh analysis", name, size >> 20, need / 1073741824.0,
                open.length() == 0 ? "none" : open, "; the operator can raise WORKBENCH_XMX (now " + (max >> 30) + "g)");
        String actor = openActor.get();
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("actor", actor); ctx.put("artifact", name); ctx.put("needBytes", need); ctx.put("headroomBytes", headroom); ctx.put("heapMaxBytes", max);
        if (actor == null || actor.startsWith("human")) {
            Log.warn("target.open", "heap short, proceeding for a human: " + msg, ctx);
            return msg;
        }
        Log.warn("target.open", "heap short, refused: " + msg, ctx);
        throw OpException.busy(msg, "need=" + (need >> 20) + "MB headroom=" + (headroom >> 20) + "MB");
    }

    /** What the heap holds that a collection would not free — the old generation's size after its
     *  last collection plus the young generation now (the raw used figure counts garbage). */
    static long heapLiveEstimate() {
        Runtime rt = Runtime.getRuntime();
        long usedNow = rt.totalMemory() - rt.freeMemory();
        try {
            long oldAfterGc = -1, young = 0;
            for (java.lang.management.MemoryPoolMXBean pool : java.lang.management.ManagementFactory.getMemoryPoolMXBeans()) {
                if (pool.getType() != java.lang.management.MemoryType.HEAP) continue;
                String pn = pool.getName().toLowerCase();
                if (pn.contains("old") || pn.contains("tenured")) {
                    java.lang.management.MemoryUsage cu = pool.getCollectionUsage();
                    if (cu != null && cu.getUsed() > 0) oldAfterGc = cu.getUsed();
                } else if (pool.getUsage() != null) young += pool.getUsage().getUsed();
            }
            if (oldAfterGc >= 0) return Math.min(usedNow, oldAfterGc + young);
        } catch (Throwable ignored) {}
        return usedNow;
    }

    /** {maxBytes, liveBytes, headroomBytes}: the heap as a client should read it before opening
     *  another target (workspace.state; the `workspace` tool). */
    static Map<String, Object> heapRow() {
        Map<String, Object> h = new LinkedHashMap<>();
        long max = Runtime.getRuntime().maxMemory(), live = heapLiveEstimate();
        h.put("maxBytes", max); h.put("liveBytes", live); h.put("headroomBytes", Math.max(0, max - live));
        h.put("note", "liveBytes is an estimate from the last collection and reads high after a big load; an open that looks short collects once before deciding");
        return h;
    }

    private Map<String, Object> openTargetImpl(String path, String engineName)
            throws OpException {
        if (path == null || path.isBlank())
            throw OpException.syntax("target.open needs a path", "");
        /* canonicalize ONCE, up front: the candidate exists, so this resolves
           the on-disk spelling per-volume — two case-variants of one file are
           one path here, and two distinct files on a case-sensitive volume stay
           distinct (round-21 N1 + round-22 F5: equalsIgnoreCase folded
           DIFFERENT files together on a case-sensitive fs, and lowercased keys
           printed names no directory contains). Every downstream compare is
           exact .equals and always right. */
        File f;
        try {
            f = new File(expandHome(path)).getCanonicalFile();
        } catch (IOException e) {
            throw OpException.io("cannot resolve " + path + ": " + e, "");
        }
        if (!f.isFile()) throw OpException.notFound("artifact not found: " + path, "");
        String type = detectType(f);
        String engine = (engineName == null || engineName.isBlank() ? defaultEngineFor(type) : engineName)
                .trim().toLowerCase(java.util.Locale.ROOT);
        if (!engine.equals("jeb") && !engine.equals("jadx"))
            throw OpException.unsupported("engine '" + engine + "' is not available in M1",
                    "available: jeb, jadx (real) + the 4 mock fixtures");
        if (!type.equals("apk") && !type.equals("dex") && !type.equals("jdb2"))
            throw OpException.unsupported("no engine for artifact type '" + type + "' yet",
                    "M1: apk/dex/jdb2 via jeb, apk/dex via jadx; elf/mach-o arrive with the MCP-bridge engines");
        if ("jdb2".equals(type) && !"jeb".equals(engine))
            throw OpException.unsupported("a .jdb2 database is a JEB project file and cannot be opened with engine '" + engine + "'",
                    "engine=" + engine);

        /* The empty-workspace auto-project name, decided OFF the monitor (the
           generic-name path may read the pull-provenance sidecar). Generic split
           names collide across every app (base/classes/split_*), so: pull
           provenance's package dir first, then the parent dir — EXCEPT generic
           container dirs (pulled is FLAT, so its name describes nothing and
           every pull would collapse into one project — r2b #1) — then a
           path-keyed suffix that is stable for the same file. */
        String autoName;
        {
            String base = f.getName();
            int dot = base.lastIndexOf('.');
            if (dot > 0) base = base.substring(0, dot);
            if (base.equals("base") || base.equals("classes") || base.startsWith("split_")) {
                String resolved = null;
                if (devices != null) {   // wired at server boot; null only in bare harnesses
                    String jb = "jdb2".equals(type) ? f.getAbsolutePath()
                            : jdb2For(f).getAbsolutePath();
                    if (isJdb2Name(jb)) jb = jb.substring(0, jb.length() - 5);
                    Map<String, Object> prov = devices().provFor(jb);
                    Object from = prov == null ? null : prov.get("pulledFrom");
                    if (from != null) {   // "serial:/data/app/<pkg>-<hash>==/base.apk"
                        String dp = String.valueOf(from);
                        int c = dp.indexOf(':');
                        if (c >= 0) dp = dp.substring(c + 1);
                        int sl = dp.lastIndexOf('/');
                        if (sl > 0) {
                            String pkg = dp.substring(dp.lastIndexOf('/', sl - 1) + 1, sl);
                            /* strip the per-install suffix only when the
                               PRE-DASH part looks like a package (has a dot) or
                               the segment carries the volatile "==" token —
                               testing the WHOLE segment truncated "my-app-v2.1"
                               to "my" (r5 F1) */
                            int d = pkg.indexOf('-');
                            if (d > 0 && (pkg.substring(0, d).indexOf('.') >= 0
                                    || pkg.endsWith("==")))
                                pkg = pkg.substring(0, d);
                            /* the PROV branch needs the generic filter as much
                               as the parent arm — /data/local/tmp and
                               /sdcard/Download pulls would fold every staging
                               pull into one shared project (r3 #2) */
                            if (usableName(pkg)) resolved = pkg;
                        }
                    }
                }
                if (resolved == null) {
                    String par = f.getParentFile() == null ? "" : f.getParentFile().getName();
                    if (usableName(par)) resolved = par;
                }
                if (resolved == null)
                    resolved = base + "-" + String.format(java.util.Locale.ROOT, "%08x",
                            f.getAbsolutePath().hashCode());   // path-keyed, stable per file
                base = resolved;
            }
            autoName = base.isBlank() ? f.getName() : base;
        }

        /* a close of this JDB2 may still be unloading off-monitor: its maps
           dropped but the engine is registered in JEB, holding the lock —
           opening now dies with 'JDB2 database is locked' (round-9 F2, keyed on
           the jdb2: round-10 #1). Wait the reservation out on a nanoTime
           DEADLINE — notifyAll from unrelated closes must not drain the budget
           (round-10 #2: six unrelated notifies released a waiter 2.7s early).
           The monitor covers ONLY the wait + the scans (round-23 F2: the whole
           method used to be synchronized, making even the rollback close
           monitor-held) */
        String waitKey = reservationKeyOf(f.getAbsolutePath(), engine);   // same normalization as the reservation
        long deadline = System.nanoTime() + 180_000_000_000L;   // 3 min hard cap
        String prevActive;
        String openProject;
        Map<String, Object> live;
        File artifact = type.equals("jdb2") ? null : f;
        File jdb2 = "jeb".equals(engine) ? (type.equals("jdb2") ? f : jdb2For(f)) : null;
        String storeJdb2Key = null;   // reserved alongside waitKey once the store entry exists
        synchronized (this) {
            /* the project is captured at ENTRY (round-24 F4), BEFORE the wait:
               wait() releases the monitor, so a project.switch during the wait
               would otherwise be captured instead (round-25 N2: the wait is the
               largest mid-open window in the method) */
            openProject = activeProject;
            /* and paths with an OPEN in flight (round-24 F1: the desynchronized
               method let two concurrent opens of one fresh path both pass the
               dedupe scans and both register two engines on one database) */
            while (closingPaths.containsKey(waitKey) || openingPaths.contains(waitKey)) {
                long leftMs = (deadline - System.nanoTime()) / 1_000_000;
                if (leftMs <= 0) {
                    String blocker = closingPaths.containsKey(waitKey) ? "a close" : "an open";
                    /* a spoken E_BUSY naming the ACTUAL blocker (round-25 N8:
                       the loop blocks on opens too, not only closes) */
                    throw OpException.busy(blocker + " of " + jdb2For(f).getName()
                            + " is still in flight — retry shortly", "jdb2=" + waitKey);
                }
                try { wait(Math.min(30_000, leftMs)); }
                catch (InterruptedException e) {
                    /* no re-arm: handler threads are POOLED (cachedThreadPool), so a
                       set flag would fire a spurious E_BUSY into the NEXT request on
                       this thread (round-14 F9). Just unwind with the spoken error. */
                    String blocker = closingPaths.containsKey(waitKey) ? "a close" : "an open";
                    throw OpException.busy("interrupted while " + blocker + " of "
                            + jdb2For(f).getName()
                            + " is still in flight — retry shortly", "jdb2=" + waitKey);
                }
            }
            /* read-only statements FIRST (round-31 F1: the F-1 fix put the
               lookup AFTER the add and still outside the try — any unchecked
               throw from it bricked the key the exact same way, one line down.
               The lookup is read-only, so moving it above the reservation is
               semantically free; the add goes LAST so nothing after it can throw) */
            /* null openProject = EMPTY workspace at entry (2026-09-07): no
               "closed mid-open" to guard against — the project is created at
               the registration hold, after every early failure exit */
            if (openProject != null && projects.get(openProject) == null)
                throw OpException.notFound("project " + openProject + " closed mid-open"
                        + " — the target was not registered", "project=" + openProject);
            live = liveTargetForPathForProject(f.getAbsolutePath(), openProject, engine);
            openingPaths.add(waitKey);   // LAST statement of the block — nothing after it can throw
        }
        try {
            /* an already-open artifact returns its LIVE session: two JEB engines on one
               jdb2 is a file-lock fight plus a double heap from the hungriest process
               we run. device.pull double-click lands here — activating the existing
               session is exactly the "take me there" the gesture means. An ERRORED or
           closed session is not "open": fall through and open fresh (the error
           path in JebEngine has already released the jdb2). Scoped to the CURRENT
           project: a target from another project must never absorb the gesture
           (review: opening p1's artifact from p2 silently re-pointed activeTarget). */
            if (live != null) {
                /* user directive (2026-09-08): already open in ANOTHER project →
                   NAVIGATE, never a refusal (the old E_BUSY "close it there or
                   switch projects" died here). navigateToTarget takes the whole
                   gesture — owner lookup, state re-verify, switch + activate,
                   failure-rollback — under ONE hold (a811842-r1 F1/F3). round-27
                   F1's refusal arm is retired. */
                if (!Boolean.TRUE.equals(live.get("currentProject")))
                    return navigateToTarget(String.valueOf(live.get("id")), "open");
                /* activate on a same-project dedupe only when the captured
                   project is still active — the N1 rule, applied to the dedupe
                   path (round-26 F2: liveTargetForPath activated unconditionally).
                   The wire shape is the old one (round-27 F4): deduped:true, no
                   internal currentProject flag. The check + activate + note run
                   ATOMICALLY under the monitor (round-29 F2: stillActive and the
                   activate were not atomic — a switch between them failed the
                   activate with a message blaming the target, and the note was
                   skipped) */
                RuntimeException infoFailure = null;
                try {
                synchronized (this) {
                    /* re-verify existence in THIS hold too (round-30 F-3: a
                       project.close in the lookup→activate gap would otherwise
                       return ok:true 'your existing session' in a project that
                       no longer exists. The TARGET disjunct has its own sentence
                       below, round-32 F2) */
                    if (projects.get(openProject) == null)
                        throw OpException.notFound("project " + openProject + " closed mid-open"
                                + " — the target was not registered", "project=" + openProject);
                    /* round-32 F2: a TARGET-only close with the project alive must
                       not wear the "project closed" sentence — the second disjunct
                       above spoke the wrong story over the accurate retry hatch */
                    if (!targets.containsKey(String.valueOf(live.get("id"))))
                        throw OpException.busy("target " + live.get("id")
                                + " closed mid-gesture — retry the open",
                                "target=" + live.get("id"));
                    /* round-32 F3: registration is not health. An ERRORED engine
                       stays registered, so the arm that skips activateTarget
                       (!stillActive) returned ok:true 'your existing session'
                       plus a stale ready snapshot for a DEAD engine.
                       Round-33: ONE info() build serves the state test AND the
                       return value below (review #2/#3: it ran twice to test
                       two string constants, and the return was still the :477
                       snapshot). The catch guards a call through the Engine
                       INTERFACE — post-ff087a6 info() walks nothing mutable,
                       so no current throw site; the guard stays for any future
                       engine whose info() can fail (review finding 11: do not
                       re-derive the retired dexUnits walk from this comment). */
                    Engine liveEngine = engines.get(String.valueOf(live.get("id")));
                    Map<String, Object> ei;
                    try {
                        ei = liveEngine == null ? null : liveEngine.info();
                    } catch (RuntimeException infoWalk) {
                        /* STASH, don't print (review F3): printing here would run
                           under the Workspace monitor — stderr on a pipe with a
                           stalled reader would block every op behind a diagnostic.
                           The catch outside the monitor prints it; the ops channel
                           still records only the message (review N-2) */
                        infoFailure = infoWalk;
                        throw OpException.busy("the target's state just changed — retry the open",
                                "target=" + live.get("id"));
                    }
                    if (ei == null
                            || "error".equals(ei.get("state"))
                            || "closed".equals(ei.get("state")))
                        throw OpException.busy("the target's state just changed — retry the open",
                                "target=" + live.get("id"));
                    boolean stillActive = openProject.equals(activeProject);
                    /* activateTarget stays a live guard, not dead code — but for
                       ONE reason only (review N-3 narrows the rebuttal): engine-null
                       and membership loss are pre-empted by the containsKey /
                       engines.get checks earlier in THIS hold; the live case is a
                       LOADING target flipping to error on its ANALYSIS thread, off
                       this monitor, between the read above and the activate */
                    if (stillActive && !activateTarget(String.valueOf(live.get("id"))))
                        throw OpException.busy("the target's state just changed — retry the open",
                                "target=" + live.get("id"));
                    /* graft onto the FRESH info: the wire keeps the round-27 F4
                       shape (deduped:true, no currentProject flag) while the
                       payload is the state this hold just certified */
                    ei.put("deduped", true);
                    if (!stillActive)
                        ei.put("note", "your existing session, in project " + openProject
                                + " — the active project changed mid-open (round-27 F5)");
                    live = ei;
                }
                } catch (OpException propagate) {
                    /* the print runs AFTER the monitor dropped (review F3); any
                       OpException without a stashed walk-failure rethrows clean */
                    if (infoFailure != null) {
                        System.err.println("workbench: info() walk failed in the dedupe guard, target "
                                + live.get("id") + ":");
                        infoFailure.printStackTrace(System.err);
                    }
                    throw propagate;
                }
                return live;
            }

            /* ---- target store (2026-09-08, user directive): ONE content-keyed
               entry per distinct artifact — targets/<slug>-<hash8>/ holding the
               artifact copy + its jdb2; projects own MEMBERSHIP, not databases.
               The candidate hash is the entry price (off-monitor, after the
               cheap path hit above): same content already open → navigate; a
               fresh open copies into the store and migrates a legacy
               artifact-adjacent jdb2 in. The old artifact-adjacent jdb2KeyOf
               dedupe arms below still run — they now mostly serve direct
               .jdb2 opens and the foreign-rewrite guards. */
            String mineHash0 = null;   // a jdb2 candidate's hash serves both the key and the scan (round-15 F12)
            String aHash;
            if ("jdb2".equals(type)) {
                mineHash0 = sha1Hex(f);
                aHash = mineHash0;
            } else {
                aHash = sha1Hex(f);
            }
            if (aHash == null) {
                /* vanished mid-open is terminal; unhashable is a hiccup (the
                   fix-8-r4 LOW-3 rule, preserved at the hoisted site) */
                if (!f.isFile())
                    throw OpException.notFound("the candidate vanished mid-open: " + f.getName(),
                            "path=" + f.getAbsolutePath());
                throw OpException.busy("couldn't hash the candidate " + f.getName()
                        + " — retry the open", "path=" + f.getAbsolutePath());
            }
            String storeKey = storeKeyFor(f, type, aHash);
            String liveTid = tidForStoreKey(storeKey, engine);
            if (liveTid != null)
                return navigateToTarget(liveTid, "open");

            /* a target from ANOTHER project backed by the same artifact (path or
               jdb2-content): refuse clearly. Two engines on JEB's one internal project
               identity poison each other — closing either tears down the survivor's
               decompiler pool (verified live on byte-identical jdb2s). The review's
               alternative (silently returning the other project's session) stole the
               gesture; an explicit error names both escape hatches. */
        /* app-review-opus P0-1, reworked per its review (fix-8-r2): NO whole-file
           hashing under the Workspace monitor — a multi-GB SHA-1 inside the hold
           serializes every op in the process. First hold: liveness + a
           targets/engines/state/history-size snapshot. OFF the monitor: the
           candidate hashes (AFTER liveness — a doomed open must not stream whole
           files first, fix-8-r2 F6) and a per-target path→sha1 cache (null hashes
           are NEVER stored — a hiccup reads as a cache miss, fix-8-r2 F2). Second
           hold: MEMBERSHIP churn (target added/removed/swapped, project
           membership changed) aborts with a spoken E_BUSY — the loops below
           iterate the snapshot and would MISS a new registration entirely;
           membership moves are user gestures, so this is rare (fix-8-r2 F1: a
           GLOBAL abort on any state flip starved opens — a sibling's
           "loading"→"ready" on the analysis thread is the NORMAL lifecycle, and
           the abort probability scaled with exactly the hashing time this fix
           removed from the monitor). Per-target churn (a state flip, or a SAVE
           growing contentHashHistory — fix-8-r2 F7) only invalidates THAT
           target's cached hashes: the get sites then re-hash live under the
           hold. The honest bound (fix-8-r3, review #3): one file PER RACING
           TARGET — N siblings flipping mid-open means N monitor-held re-hashes,
           i.e. the pre-P0-1 cost in the worst case, never worse; steady state
           (no churn) pays nothing under the monitor. The openingPaths
           reservation pins the candidate's own key meanwhile. */
        List<TargetMeta> snap;
        Map<String, Engine> engSnap;
        Map<String, Object> stateSnap;
        Map<String, Integer> histSnap;
        java.util.Set<String> openIds;
        synchronized (this) {
            /* the captured project's health FIRST (round-27 F6: checking late
               burned nextTargetNum and SHA-1'd a 90MB jdb2 under the monitor).
               null openProject = EMPTY workspace (2026-09-07): no project exists
               to have closed — membership is the empty set by construction */
            if (openProject != null && projects.get(openProject) == null)
                throw OpException.notFound("project " + openProject + " closed mid-open"
                        + " — the target was not registered", "project=" + openProject);
            snap = new ArrayList<>(targets.values());
            engSnap = new HashMap<>(engines);
            stateSnap = new HashMap<>();
            histSnap = new HashMap<>();
            for (TargetMeta tm : snap) {
                Engine e = engSnap.get(tm.id());
                stateSnap.put(tm.id(), e == null ? null : e.info().get("state"));   // info() is walk-free
                /* a SAVE appends the new byte-state to the content history — its
                   size is a save counter the validity check below can see
                   (fix-8-r2 F7: identity+state alone were blind to a mid-open save) */
                histSnap.put(tm.id(), e == null ? -1 : e.contentHashHistory().size());
            }
            openIds = openProject == null ? new java.util.HashSet<>()
                    : new java.util.HashSet<>(projectTargetIds(openProject));
        }
        String mineHash = mineHash0 != null ? mineHash0
                : (jdb2 != null && jdb2.isFile() ? sha1Hex(jdb2) : null);
        /* aHash is computed ONCE up front now (the target-store key needs it
           before the scan; for jdb2 candidates it IS mineHash — the round-15
           F12 double-hash waste rule holds) */
        if ("jdb2".equals(type) && mineHash == null) {
            /* an UNREADABLE jdb2 candidate disables BOTH dedupe gates: aHash ==
               mineHash (the twin loop), and conflict declines on a null mineHash
               — the open would proceed blind into the two-engines-one-identity
               poisoning this method exists to prevent (fix-8-r4, review #8).
               The isFile() at entry is up to the 180s openingPaths wait STALE —
               re-test: a vanished file is a terminal notFound, a present one is
               a transient hash hiccup → retryable E_BUSY (fix-8-r4 LOW-3). */
            if (!f.isFile())
                throw OpException.notFound("the candidate vanished mid-open: " + f.getName(),
                        "path=" + f.getAbsolutePath());
            throw OpException.busy("couldn't hash the candidate " + f.getName()
                    + " — retry the open", "path=" + f.getAbsolutePath());
        }
        /* OFF the monitor: every whole-file hash the decision block can reach —
           the key-twin artifact compare and the live re-hash behind
           contentHashHistory. */
        Map<String, String> pathHashes = new HashMap<>();
        for (TargetMeta tm : snap) {
            if (!engine.equalsIgnoreCase(engineOfTid(tm.id()))) continue;
            Engine e = engSnap.get(tm.id());
            if (e == null) continue;
            Object st = stateSnap.get(tm.id());
            if ("error".equals(st) || "closed".equals(st)) continue;
            if (samePath(tm.path(), f.getAbsolutePath())) continue;
            if (aHash != null && samePath(reservationKeyOf(tm.path(), engine), waitKey)
                    && !("jdb2".equals(type) && f.getAbsolutePath().equals(waitKey))) {
                String k = new File(tm.path()).getAbsolutePath();
                /* one hash per FILE — the guard must precede the HASH, not just
                   the map write (fix-8-r3 #2: r2's putIfAbsent only discarded the
                   second ~90MB digest AFTER both sites had computed it) */
                if (!pathHashes.containsKey(k))
                    putHash(pathHashes, k, sha1Hex(new File(tm.path())));
            }
            /* a recorded-history hit answers the conflict compare WITHOUT the
               disk bytes (fix-8-r2 F3: the pre-scan re-read ~90MB per target even
               when the history would short-circuit the compare). The history is
               CHM-backed and only grows — an off-monitor contains is safe and a
               mid-read add only ever ADDS a match. */
            if (mineHash != null && !e.contentHashHistory().contains(mineHash)) {
                File tj = isJdb2Name(tm.path()) ? new File(tm.path()) : jdb2For(new File(tm.path()));
                if (!tj.getAbsolutePath().equals(f.getAbsolutePath()) && tj.isFile()
                        && !pathHashes.containsKey(tj.getAbsolutePath()))
                    putHash(pathHashes, tj.getAbsolutePath(), sha1Hex(tj));
            }
        }
        synchronized (this) {
            /* membership currency BEFORE any decision (P0-1): the off-monitor
               work describes exactly this registry's membership, or nothing.
               null openProject (empty workspace): still-empty means no churn */
            if (openProject != null && projects.get(openProject) == null)
                throw OpException.notFound("project " + openProject + " closed mid-open"
                        + " — the target was not registered", "project=" + openProject);
            boolean members = snap.size() == targets.size()
                    && openIds.equals(openProject == null ? java.util.Set.of()
                            : new java.util.HashSet<>(projectTargetIds(openProject)));
            for (TargetMeta tm : snap) {
                if (!members) break;
                if (targets.get(tm.id()) != tm || engines.get(tm.id()) != engSnap.get(tm.id())) {
                    members = false; break;
                }
            }
            if (!members)
                throw OpException.busy("the target set changed mid-open — retry the open",
                        "path=" + f.getName());
            /* cached-hash validity is PER TARGET (fix-8-r2 F1): a state flip or
               a save mid-open bypasses that target's cache entries, never aborts
               the open. A bypassed/missing entry re-hashes LIVE, under this hold
               — the pre-P0-1 cost per racing target (fix-8-r3, review #3: not a
               global bound; N racing siblings = N live re-hashes, still never
               worse than the pre-P0-1 open). */
            java.util.function.BiFunction<TargetMeta, String, String> hashOf = (tm, hashPath) -> {
                Engine e = engines.get(tm.id());
                Object now = e == null ? null : e.info().get("state");
                boolean valid = java.util.Objects.equals(now, stateSnap.get(tm.id()))
                        && (e == null ? -1 : e.contentHashHistory().size())
                                == histSnap.getOrDefault(tm.id(), -2);
                if (valid) {
                    String cached = pathHashes.get(hashPath);
                    if (cached != null) return cached;
                }
                return sha1Hex(new File(hashPath));
            };

        /* JEB keys a loaded project by the jdb2's INTERNAL identity, not its path:
           two byte-identical jdb2s are ONE project to the registry — closing either
           twin tears down the survivor's units (verified live). Both checks below
           compare against the hash the engine LOADED (review R6: a copy taken
           before a later save escapes a live re-hash; identity is decided at load
           time). One candidate hash, computed once, for the whole scan (R11). */
        /* JEB keys a loaded project by the jdb2's INTERNAL identity, not its path:
           two byte-identical jdb2s are ONE project to the registry — closing either
           twin tears down the survivor's units (verified live). A twin copy matches
           one of MANY byte states: the engine writes a new jdb2 at every save, so
           the compare is its full content-hash HISTORY (review #4: a copy between
           any two saves), plus the current disk bytes for foreign rewrites (F1).
           Deterministic two passes (F8): cross-project refusal first, dedupe 2nd. */
        /* jdb2-KEY collisions: two artifacts whose jdb2For resolves to one file
           (L.apk/L.dex/L.bin → L.jdb2). The ONLY honest dedupe test for a
           colliding candidate is ARTIFACT identity — comparing the candidate's
           jdb2 against the shared jdb2 file is self-comparing when they resolve
           to the same path (round-14 F1). A candidate that IS the shared jdb2
           opens the engine's own database — dedupe, but only when its bytes are
           in the engine's recorded history: a FOREIGN rewrite of that file is
           otherwise invisible (round-14 F6). */
        java.util.Set<TargetMeta> keyTwins = new java.util.HashSet<>();
        java.util.Map<TargetMeta, String> twinNote = new java.util.HashMap<>();
        for (TargetMeta tm : snap) {
            if (!engine.equalsIgnoreCase(engineOfTid(tm.id()))) continue;
            if (samePath(tm.path(), f.getAbsolutePath())) continue;
            Engine e = engines.get(tm.id());
            if (e == null) continue;
            Object st = e.info().get("state");
            if ("error".equals(st) || "closed".equals(st)) continue;
            if (!samePath(reservationKeyOf(tm.path(), engine), waitKey)) continue;
            if (aHash == null)
                /* the CANDIDATE itself is unhashable — it passed isFile() at
                   entry, so this is a transient read failure, and every key-twin
                   verdict below needs the candidate's bytes: a retryable
                   NOT-KNOWN, never the false "different content" refusal or the
                   "foreign rewrite" accusation (fix-8-r3, review #4) */
                throw OpException.busy("couldn't hash the candidate " + f.getName()
                        + " — retry the open", "path=" + f.getAbsolutePath());
            if ("jdb2".equals(type) && f.getAbsolutePath().equals(waitKey)) {
                /* a not-ready engine's history isn't populated yet (doOpen
                   records on the analysis thread), so containment proves NOTHING
                   — a mid-open double-click got accused of a foreign rewrite
                   that never happened (round-16 F4). Retry shortly instead. */
                if (!"ready".equals(st))
                    throw OpException.busy(tm.id() + " is still analyzing " + tm.name()
                            + " — open " + f.getName() + " when it is ready", "target=" + tm.id());
                /* the candidate IS the shared database file — dedupe only when
                   its bytes are in the engine's recorded history; otherwise it
                   was rewritten outside the workbench and that must be SPOKEN
                   (round-15 F4) */
                if (e.contentHashHistory().contains(aHash)) {
                    keyTwins.add(tm);
                    twinNote.put(tm, "the database of " + tm.id() + " (" + tm.path() + ") — one session, one file");
                } else
                    throw OpException.busy(f.getName() + " was rewritten outside the workbench"
                            + " (its bytes are not in " + tm.id() + "'s recorded history) — close that"
                            + " target to reload it fresh, or restore the original bytes",
                            "target=" + tm.id());
                continue;
            }
            String twinHash = hashOf.apply(tm, new File(tm.path()).getAbsolutePath());
            if (twinHash == null) {
                if (!new File(tm.path()).isFile())
                    /* the twin's artifact is GONE (deleted, renamed, unmounted) —
                       permanence, not a hiccup: a bare "retry the open" would
                       spin forever (fix-8-r3, review #1). The actionable refusal
                       names the close hatch. */
                    throw OpException.busy(tm.name() + " (" + tm.id() + ") resolves to the same .jdb2 ("
                            + jdb2For(f).getName() + ") but its artifact " + tm.path()
                            + " is no longer on disk to compare against — close that target"
                            + " first (or restore the artifact)", "target=" + tm.id());
                /* present but unhashable (IO hiccup, mid-write bytes): a
                   retryable NOT-KNOWN — never the false "different content"
                   refusal below (fix-8-r2 F2) */
                throw OpException.busy("couldn't hash " + tm.path() + " to compare against "
                        + f.getName() + " — retry the open", "target=" + tm.id());
            }
            if (aHash.equals(twinHash)) {
                keyTwins.add(tm);
                twinNote.put(tm, "byte-identical artifact of " + tm.id() + " (" + tm.path()
                        + ") — edits land on that session");           // same app, different filename
                continue;
            }
            boolean tmIsJdb2 = isJdb2Name(tm.path());
            throw OpException.busy(tm.name() + " and " + f.getName()
                    + " resolve to the same .jdb2 (" + jdb2For(f).getName()
                    + ") with different content — " + (tmIsJdb2
                        ? "if " + f.getName() + " is the app that database was built from, note that"
                          + " opening it will NOT re-analyze the artifact — you get that database's"
                          + " classes under this name. Close the " + tm.id() + " target first if you"
                          + " want the artifact itself analyzed; if these are different apps, rename one"
                        : "rename one artifact"), "target=" + tm.id());
        }
        java.util.function.Predicate<TargetMeta> conflict = tm -> {
            if (!engine.equalsIgnoreCase(engineOfTid(tm.id()))) return false;
            if (keyTwins.contains(tm)) return true;
            if (samePath(tm.path(), f.getAbsolutePath())) return true;
            if (mineHash == null) return false;
            Engine e = engines.get(tm.id());
            if (e == null) return false;
            if (e.contentHashHistory().contains(mineHash)) return true;
            /* content-keyed compare, no metadata keying (review F2: length is
               invariant across saves and mtime can be preserved by cp -p/rsync -t
               — a metadata-keyed cache served a stale hash and landed the open on
               the WRONG session). hashOf serves the off-monitor cache when this
               target didn't race the open, a live re-hash when it did
               (fix-8-r2 F1/F7). */
            File tj = isJdb2Name(tm.path()) ? new File(tm.path()) : jdb2For(new File(tm.path()));
            /* tj IS the candidate when they share the key — comparing the file
               to itself is unconditionally true and bypassed the history gate
               (round-15 F4). The pre-scan decides those pairs; here: no match */
            if (tj.getAbsolutePath().equals(f.getAbsolutePath())) return false;
            /* a hashOf null (IO hiccup on a live re-hash) declines the dedupe —
               the pre-existing failure-soft direction */
            return tj.isFile() && mineHash.equals(hashOf.apply(tm, tj.getAbsolutePath()));
        };
        for (TargetMeta tm : snap) {
            if (openIds.contains(tm.id())) continue;
            Engine e = engines.get(tm.id());
            if (e == null) continue;
            Object st = e.info().get("state");
            if ("error".equals(st) || "closed".equals(st)) continue;
            if (!conflict.test(tm)) continue;
            /* user directive (2026-09-08): a target from ANOTHER project backed by
               the same artifact NAVIGATES — navigateToTarget owns the switch +
               activate + rollback under its hold (a811842-r1 F1/F3). The
               two-engines-one-identity poisoning the old refusal guarded against
               stays impossible: no second engine ever opens — you land on the one
               that exists. navigateToTarget re-runs the owner lookup and throws
               the same busy on a mid-gesture flip (fail-closed, r1 F2). The twin's
               note rides along so a content-twin keeps its 'edits land there' story */
            Map<String, Object> navInfo = navigateToTarget(tm.id(), "open");
            String tnote = twinNote.get(tm);
            if (tnote != null) navInfo.put("note", navInfo.get("note") + " · " + tnote);
            return navInfo;
        }
        for (TargetMeta tm : snap) {
            if (!openIds.contains(tm.id())) continue;
            Engine e = engines.get(tm.id());
            if (e == null) continue;
            Object st = e.info().get("state");
            if ("error".equals(st) || "closed".equals(st)) continue;
            if (!conflict.test(tm)) continue;
            boolean samePathHit = samePath(tm.path(), f.getAbsolutePath());
            /* and the N1 rule HERE too (round-27 F2: the loop's membership moved
               to openIds but the assignment stayed unconditional — a dedupe
               after a mid-open switch re-dangled activeTarget onto the captured
               project's session, with no switchProject to heal it) */
            if (openProject.equals(activeProject)) activeTarget = tm.id();
            Map<String, Object> info = e.info();
            info.put("deduped", true);
            if (!samePathHit) {
                info.put("contentTwin", true);
                /* an HONEST twin label (round-14 F4): 'content identical' was a
                   lie for the own-database and same-app cases F1 was written for */
                String note = twinNote.get(tm);
                info.put("note", note != null ? note
                        : "content identical to " + tm.id() + " (" + tm.path() + ") — edits land on that session");
            }
            if (!openProject.equals(activeProject))
                info.put("note", (info.get("note") == null ? "" : info.get("note") + " · ")
                        + "in project " + openProject + " — the active project changed mid-open (round-27 F5)");
            return info;
        }
            prevActive = activeTarget;
            }
            /* the store reservation WAITS, and runs BEFORE the copy (950876a-r1
               #8): the first hold's waitKey is artifact-adjacent, but
               closeTarget reserves the STORE jdb2 — an open during a same-entry
               close must wait the close out, not refuse it (round-9 F2's rule,
               re-keyed), and the copy into an entry being closed is the exact
               hazard the reservation exists for */
            String storeJdb2Key0 = "jdb2".equals(type) ? jdb2KeyOf(f.getAbsolutePath())
                    : reservationKeyOf(new java.io.File(new java.io.File(storeRoot(), storeKey),
                            baseNoExt(f.getName()) + ".jdb2").getAbsolutePath(), engine);
            synchronized (this) {
                long dl = System.nanoTime() + 180_000_000_000L;
                while (closingPaths.containsKey(storeJdb2Key0)
                        || (openingPaths.contains(storeJdb2Key0) && !storeJdb2Key0.equals(waitKey))) {
                    /* r2 F1: for a jdb2-direct open the store key IS waitKey —
                       the first hold reserved it FOR US; waiting on it is a
                       self-deadlock. openingPaths membership by our own key is
                       skipped; a CLOSE in flight on it still blocks. */
                    long leftMs = (dl - System.nanoTime()) / 1_000_000;
                    if (leftMs <= 0)
                        throw OpException.busy("an open/close of this target is still in flight"
                                + " — retry shortly", "jdb2=" + storeJdb2Key0);
                    try { wait(Math.min(30_000, leftMs)); }
                    catch (InterruptedException e) {
                        throw OpException.busy("interrupted while an open/close of this target"
                                + " is in flight — retry shortly", "jdb2=" + storeJdb2Key0);
                    }
                }
                /* r2 F6: the wait must RE-SCAN when it wakes (the first hold's
                   live lookup does the same at :1010) — a concurrent same-content
                   open from another path registered while we slept; registering a
                   second engine on the store's jdb2 is the round-24 F1 poisoning */
                if (!storeJdb2Key0.equals(waitKey)) {
                    String nowLive = tidForStoreKey(storeKey, engine);
                    if (nowLive != null)
                        return navigateToTarget(nowLive, "open");
                }
                storeJdb2Key = storeJdb2Key0;
                openingPaths.add(storeJdb2Key);
            }
            /* every refusal is behind us — claim the store entry (copy + legacy
               jdb2 migration, off-monitor) and open the STORE's artifact: the
               store jdb2 is the one database from here on */
            StoreEntry se = prepareStoreEntry(f, type, storeKey, aHash);
            File fStore = "jdb2".equals(type) ? se.jdb2() : se.artifact();
            return openAndRegister(fStore, "jdb2".equals(type) ? null : se.artifact(),
                    "jeb".equals(engine) ? se.jdb2() : null, prevActive, openProject, autoName, storeKey, f, engine);
        } finally {
            synchronized (this) {
                openingPaths.remove(waitKey);
                if (storeJdb2Key != null) openingPaths.remove(storeJdb2Key);
                notifyAll();
            }
        }
    }

    /** Registration + analysis start for a fresh open. The map surgery and the
        sidecar reads are under the Workspace monitor; the close-on-error is
        NOT (round-22 F8: engine.close() contends OPEN_LOCK for minutes —
        closeTarget/closeProject go off-monitor for exactly this reason, and a
        fresh-open rollback must not secretly hold it). A tail failure
        unregisters (no ghost chip, round-21 N3) and restores the PREVIOUS
        active target (round-22 F7). Errors from Engines.openJeb propagate
        BEFORE registration — no ghost is possible there either (round-22 F2). */
    private Map<String, Object> openAndRegister(File f, File artifact, File jdb2,
            String prevActive, String openProject, String autoName,
            String storeKey, File origin, String engine) throws OpException {
        /* the id is minted HERE, not before the scans (round-29 F5: allocating
           it up front permanently burned one on every refused open — a retry
           loop inflated the counter by thousands with no target created) */
        /* the database about to be opened is the one a live target already works on — reached
           when that target is the store's .jdb2 opened by itself and this is its apk from another
           path, which no earlier dedupe sees. It is the SAME database, not a clash: go to it. Never
           past this point, where a "clash" with it would set a live database aside (review M1). */
        if ("jeb".equals(engine) && jdb2 != null && jdb2.isFile()) {
            Map<String, Object> same = liveTargetForJdb2Key(jdb2.getAbsolutePath());
            if (same != null) return navigateToTarget(String.valueOf(same.get("id")), "open");
        }
        /* a saved database whose JEB key an open target holds would displace it. No lock behind this
           check — target.open answers at once even while another target loads, analyses or saves.
           The workbench's own open gesture is not refused for it (2026-10-05, the user's rule: an
           explicit action is carried out, with the facts shown and the way back kept): the database
           is set aside and the artifact analysed again under a key of its own, its edit history
           re-applied — rekeyForOpen. Everyone else is refused before anything is minted, and so is
           every clash a re-analysis cannot resolve (a bare .jdb2, a key not on record). */
        final boolean gesture = Boolean.TRUE.equals(openRekey.get());
        final String asked = origin == null ? f.getName() : origin.getName();
        String[] clash = "jeb".equals(engine) ? Engines.preflightJeb(jdb2, artifact == null, asked) : null;
        /* the holder is on its way out — closing, or detached with its close still queued behind a
           save: the user closed it and opened this. Not a clash to re-key around (minutes of
           analysis for a target that is seconds from gone), and not a refusal: wait for it, the
           way an open waits for a close of its own path, then load plainly (review H2). */
        for (long dl = System.nanoTime() + 180_000_000_000L; "jeb".equals(engine) && gesture && clash != null && holderLeaving(clash); ) {
            if (System.nanoTime() > dl)
                throw OpException.busy(asked + " shares its internal JEB key with a target that is still closing after 3 minutes"
                        + " — open it again when the workspace no longer lists a closing database", "jdb2=" + jdb2.getPath());
            try { Thread.sleep(250); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
            clash = Engines.preflightJeb(jdb2, artifact == null, asked);
        }
        Map<String, Object> rekeyed = null;
        if (clash != null) {
            List<String> with = java.util.Arrays.asList(clash).subList(3, clash.length);
            if ("same-key".equals(clash[0]) && gesture && artifact != null && artifact.isFile()) {
                rekeyed = rekeyForOpen(jdb2, clash[1], with);
            } else {
                String msg = clash[2];
                /* target.list and target.close see the current project only, and a target whose
                   close is still queued has already left the maps: say where each one is */
                synchronized (this) {
                    for (String tid : with) {
                        String pid = projectOfTarget(tid);
                        Map<String, Object> pr = pid == null ? null : projects.get(pid);
                        if (!targets.containsKey(tid) || pr == null)
                            msg += ". " + tid + " is already closing — retry when `workspace` no longer lists it under `closing`";
                        else if (!pid.equals(activeProject))
                            msg += ". " + tid + " belongs to the project \"" + pr.get("name") + "\" — open that project to close it";
                    }
                }
                throw OpException.busy(msg, "jdb2=" + jdb2.getPath());
            }
        } else if ("jeb".equals(engine) && jdb2 != null && !jdb2.isFile()) {
            /* no database but a re-key marker: a re-key begun earlier (an open that was interrupted,
               or backend/jdb2-rekey.sh) is finished by THIS open — it tells the same story */
            rekeyed = rekeyInFlight(jdb2);
        }
        String heapWarning = heapAdmission(artifact, jdb2, f.getName());   // refuses a non-human open that will not fit
        String id;
        synchronized (this) { id = "t" + (nextTargetNum++); }
        File sidecarDir = artifact != null ? sidecarFor(artifact) : sidecarFor(f);
        Engine session = Engines.open(engine, new EngineProvider.EngineOpenRequest(
                id, f.getName(), f.getAbsolutePath(), artifact, jdb2, sidecarDir, Map.of()));
        Map<String, Object> proj = null;          // null/default on the attachFail path — never read there
        boolean sameProject = false;
        boolean autoCreated = false;   // this open created its own project (empty-boot path)
        Throwable attachFail = null;   // OpException/RuntimeException/Error — all close the engine the same way (r4 L3, r6 L1)
        synchronized (this) {
            /* the project attach runs BEFORE any registration (r3 H2): the F7
               deleting-in-flight throw from openProject must not leave a
               constructed engine + registry ghosts behind — every later
               target.open of the artifact then navigated to a dead session,
               forever, with a retry-its-way-out message. The throw is CAUGHT
               here and rethrown after the hold — the engine closes OFF the
               monitor (r4 L3: close() contends OPEN_LOCK for minutes behind a
               sibling's analysis). */
            if (openProject == null) {
                if (activeProject == null) {
                    try { openProject(autoName); }
                    catch (Throwable e) { attachFail = e; }   // Exception|Error parity with the :1768 sibling (r6 L1)
                    autoCreated = attachFail == null;
                }
                if (attachFail == null) openProject = activeProject;
            }
            if (attachFail == null) {
            targets.put(id, new TargetMeta(id, f.getName(), f.getAbsolutePath()));
            engines.put(id, session);
            engineByTid.put(id, engine);
            storeKeyByTid.put(id, storeKey);
            if (origin != null)
                /* the path PLUS a size/mtime witness (r2 F11): the origin match
                   must not dedupe onto a session whose source file was REWRITTEN
                   on disk since (a re-pull into pulled/ is exactly that) */
                originByTid.put(id, origin.getAbsolutePath() + "|" + origin.length()
                        + "|" + origin.lastModified());
            /* the EMPTY-workspace attach moved ABOVE the registration puts
               (r3 H2) — the comment block's rules stand: the name was computed
               OFF the monitor at entry (r2b #1), and the project exists only
               after every earlier failure exit (r1 #3). */
            /* into the project the gesture was made against (round-24 F4: a
               switch mid-open used to land the target in the NEW project) */
            proj = projects.get(openProject);
            /* captured ONCE under the hold and reused at the note site
               (round-29 F4: two reads of activeProject, the second off-monitor,
               could disagree into the silent no-chip-no-note case) */
            sameProject = openProject.equals(activeProject);
            if (proj != null) {
                @SuppressWarnings("unchecked")
                List<String> ids = (List<String>) proj.get("targetIds");
                ids.add(id);
                /* activeTarget moves ONLY when the captured project is still
                   the active one (round-25 N1: setting it unconditionally left
                   a dangling pointer from an empty project) */
                if (sameProject) activeTarget = id;
                session.primeIndexReplay(indexReplayMap(id));      // corpus warm-load freshness
                File pf = persistenceFileFor(id, targets.get(id));
                List<Map<String, Object>> replay = dirtyReplayFor(id, pf);   // crash-window recovery — after a re-key, the whole history
                session.primeDirtyReplay(replay);
                if (rekeyed != null) rekeyed.put("edits", replay.size());
            } else {
                /* project closed mid-open: unregister NOW (the throw below
                   happens after the monitor drops, so the close can run off it) */
                targets.remove(id);
                engines.remove(id);
                engineByTid.remove(id);
            }
            }
        }
        if (attachFail != null) {
            try { session.close(); } catch (Throwable ignored) {}   // OFF the Workspace monitor (R4)
            if (attachFail instanceof OpException oe) throw oe;
            if (attachFail instanceof Error err) throw err;
            throw (RuntimeException) attachFail;
        }
        if (proj == null) {
            try { session.close(); } catch (Throwable ignored) {}   // OFF the Workspace monitor (R4)
            throw OpException.notFound("project " + openProject + " closed mid-open"
                    + " — the target was not registered", "project=" + openProject);
        }
        try {
            Engines.startAnalysis(session);
            Map<String, Object> info = session.info();
            /* the adoption marker the pull machinery guards elsewhere (round-15 F7):
               an artifact whose sibling jdb2's recorded pull basis differs from its
               bytes PROBABLY loads that database under this name — a marker, not a
               verdict (round-17 F7: the prov describes the family's last PULL, not
               the jdb2's actual basis; overstating it was its own lie). Never fires
               when the artifact itself can't be hashed. */
            if (artifact != null && jdb2 != null && jdb2.isFile()) {
                /* decision 16: the pull record is now CARRIED beside the store jdb2
                   (prepareStoreEntry), so read the store's copy first — it survives the reaping of
                   the staging copy. Fall back to the origin's record for the window before the
                   carry or a direct (non-store-adjacent) open. */
                String storeJ = jdb2.getAbsolutePath();
                if (isJdb2Name(storeJ)) storeJ = storeJ.substring(0, storeJ.length() - 5);
                Map<String, Object> prov = devices().provFor(storeJ);
                if (prov == null && origin != null) {
                    String oj = jdb2For(origin).getAbsolutePath();
                    if (isJdb2Name(oj)) oj = oj.substring(0, oj.length() - 5);
                    prov = devices().provFor(oj);
                }
                String provSha = prov == null || prov.get("sha256") == null ? null
                        : String.valueOf(prov.get("sha256"));
                String artifactSha = dev.workbench.device.DeviceManager.localSha256(artifact);
                if (provSha != null && artifactSha != null && !provSha.equals(artifactSha))
                    info.put("basisWarn", "sibling " + jdb2.getName() + "'s recorded pull basis differs"
                            + " from these bytes (prov records " + prov.get("pulledFrom") + ") — the classes"
                            + " shown may be that database's, not this artifact's");
            }
            /* the gesture landed somewhere the user isn't looking (round-26 F5):
               ok:true with no chip is the only misdirection that's silent here */
            if (!sameProject)
                info.put("note", "opened in project " + openProject
                        + " — the active project changed mid-open; no chip here");
            /* membership (2026-09-08): the project records its target in
               targets.json — persisted only AFTER the analysis thread started,
               so a failed open never lists a ghost member */
            memberAdd(String.valueOf(proj.get("name")), storeKey, f.getName(), f.getAbsolutePath(), engine);
            info.put("stored", "targets/" + storeKey);
            if (heapWarning != null) info.put("warning", heapWarning);   // a human's open that may not fit: said, not refused
            if (rekeyed != null) {
                /* what was done instead of refusing, in the open's own answer: the card shows it */
                info.put("rekeyed", rekeyed);
                String said = rekeyNote(rekeyed);
                info.put("note", info.get("note") == null ? said : info.get("note") + " · " + said);
            }
            return info;
        } catch (Exception | Error e) {
            synchronized (this) {
                targets.remove(id);
                engines.remove(id);
                engineByTid.remove(id);
                storeKeyByTid.remove(id);
                originByTid.remove(id);
                try {
                    /* from the CAPTURED project's list (round-25 N4: removing via
                       currentProjectTargetIds() after a mid-open switch leaves
                       the ghost id in the other project's list → NPE/500) */
                    Map<String, Object> rp = projects.get(openProject);
                    if (rp != null) {
                        @SuppressWarnings("unchecked")
                        List<String> ids = (List<String>) rp.get("targetIds");
                        ids.remove(id);
                    }
                    if (id.equals(activeTarget))
                        /* prevActive must still be a LIVE registered target
                           (round-24 F5) and not errored (round-25 N7: an
                           errored engine stays registered) — else fall back to
                           the project's first */
                        activeTarget = prevActive != null && targets.containsKey(prevActive)
                                && !"error".equals(engines.get(prevActive).info().get("state"))
                                && !"closed".equals(engines.get(prevActive).info().get("state"))
                                ? prevActive
                                : (currentProjectTargetIds().isEmpty() ? null
                                    : currentProjectTargetIds().get(0));
                } catch (OpException ignored) {}
                /* an auto-created project whose only target just failed to start
                   is an orphan (empty-boot r1 #3): drop it iff it is STILL empty
                   — a concurrent open that landed in it keeps it */
                if (autoCreated) {
                    Map<String, Object> ap = projects.get(openProject);
                    if (ap != null) {
                        @SuppressWarnings("unchecked")
                        List<String> aIds = (List<String>) ap.get("targetIds");
                        if (aIds.isEmpty()) {
                            projects.remove(openProject);
                            if (presence != null) presence.clearPositions(openProject);   // r2b #5: no orphan pins
                            if (openProject.equals(activeProject)) {
                                activeProject = null;
                                activeTarget = null;
                                setMaps(null);   // back to the honest empty state
                            }
                            /* the claim walk created projects/<slug> on sight — a
                               FAILED open must not litter the ON DISK list with a
                               project that never opened (r2b #3). Deletes iff
                               virgin; real data landed → keep the dir. */
                            deleteVirginSidecar(String.valueOf(ap.get("name")));
                        }
                    }
                }
            }
            try { session.close(); } catch (Throwable ignored) {}   // OFF the Workspace monitor (R4)
            if (e instanceof OpException oe) throw oe;
            if (e instanceof Error err) throw err;
            throw OpException.io("open failed: " + e, "target=" + id);
        }
    }

    /** Native save times retain filesystem precision; millisecond truncation could drop an
     * edit accepted later in the same millisecond. Checkpoint rollback preserves this time. */
    private static java.time.Instant databaseTime(File database) {
        if (database == null || !database.isFile()) return java.time.Instant.MIN;
        try { return Files.getLastModifiedTime(database.toPath()).toInstant(); }
        catch (IOException ex) { return java.time.Instant.ofEpochMilli(database.lastModified()); }
    }

    /** Replay actual mutations newer than the DB: original writes and durable undo/redo events.
     * A missing database only replays when an explicit rekey marker requests reconstruction. */
    private List<Map<String, Object>> dirtyReplayFor(String tid, File jdb2) {
        if (jdb2 == null || (!jdb2.isFile() && !Engines.replayMarker(jdb2).isFile())) return List.of();
        return HistoryJournal.replay(historyFor(tid), databaseTime(jdb2));
    }

    /* ---------------------------------------------------------- view.json
       The human's last position on an artifact — class, caret, scroll, open tabs — persisted
       beside history.json in the artifact's own sidecar, so it follows the APK across a project
       close/reopen, a page reload, a backend restart and machines (decision 17's rule for the
       chrome prefs). Written by the UI only (target.view.set); the reversing agent never touches
       it. A missing or unreadable file is "no position", never an error on a landing path. */
    private File viewFile(String tid) {
        TargetMeta meta = targets.get(tid);
        if (meta == null) return null;
        File artifact = new File(meta.path());
        if (!artifact.isFile()) return null;                    // mock fixture target
        return new File(sidecarFor(artifact), "view.json");
    }

    public synchronized Map<String, Object> targetView(String targetParam) throws OpException {
        String tid = resolveTargetIdOrActive(targetParam);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("target", tid);
        Object view = null;
        File f = viewFile(tid);
        if (f != null && f.isFile()) {
            try {
                Object parsed = dev.workbench.registry.Json.parse(java.nio.file.Files.readString(f.toPath()));
                if (parsed instanceof Map<?, ?> m && m.get("class") != null) view = parsed;
            } catch (Exception e) {
                dev.workbench.registry.SidecarIO.readFailed(f, "view", e);   // loud, then "no position"
            }
        }
        out.put("view", view);
        return out;
    }

    private static final int VIEW_MAX_CHARS = 16_384;

    public synchronized Map<String, Object> setTargetView(String targetParam, Map<String, Object> view) throws OpException {
        String tid = resolveTargetIdOrActive(targetParam);
        File f = viewFile(tid);
        if (f == null) throw OpException.unsupported("this target has no sidecar to remember a position in", "target=" + tid);
        if (view.get("class") == null || String.valueOf(view.get("class")).isBlank())
            throw OpException.syntax("view.class is required (the open class)", "target=" + tid);
        Map<String, Object> v = new LinkedHashMap<>(view);
        v.put("ts", java.time.Instant.now().toString());
        String json = dev.workbench.registry.Json.write(v);
        if (json.length() > VIEW_MAX_CHARS)
            throw OpException.syntax("view too large (" + json.length() + " chars; max " + VIEW_MAX_CHARS + ") — fewer tabs", "target=" + tid);
        boolean ok = dev.workbench.registry.SidecarIO.writeAtomic(f, json, "view");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("target", tid);
        out.put("saved", ok);
        return out;
    }

    /** target.changes: what a close would WRITE — the unsaved (post-last-save) history,
     *  counted as DISTINCT symbols, split by op. Drives the close dialog: 0 → close
     *  silently; else "N renamed, M commented — save?". Computed by the same rule the
     *  crash-window replay uses (dirtyReplayFor), so the count and the replay can never
     *  disagree about what "unsaved" means. */
    public synchronized Map<String, Object> targetChanges(String targetParam) throws OpException {
        String tid = resolveTargetIdOrActive(targetParam);
        TargetMeta tm = targets.get(tid);
        File pf = persistenceFileFor(tid, tm);
        java.util.Set<String> renamed = new java.util.LinkedHashSet<>(), commented = new java.util.LinkedHashSet<>(), all = new java.util.LinkedHashSet<>();
        /* JEB addrs embed the name: a rename MOVES the symbol to newAddr, and a later comment (or a
           second rename) lands on that addr — counted naively that is two "symbols" for one method
           (re-review B3). Resolve every addr to the root of its rename chain so the count is the
           user's mental model: distinct symbols touched. */
        Map<String, String> alias = new java.util.HashMap<>();   // post-rename addr → the chain's first addr
        /* only against a database that exists: with none yet (an analysis in flight, a re-key's
           replay, a mock target) nothing is "unsaved" in the dialog's sense — SAVE could not write
           it and DISCARD could not drop it */
        for (Map<String, Object> e : pf != null && pf.isFile() ? dirtyReplayFor(tid, pf) : List.<Map<String, Object>>of()) {
            String addr = String.valueOf(e.get("addr"));
            for (int hop = 0; hop < 64 && alias.containsKey(addr); hop++) addr = alias.get(addr);
            all.add(addr);
            if ("rename".equals(e.get("op"))) {
                renamed.add(addr);
                String na = e.get("newAddr") == null ? null : String.valueOf(e.get("newAddr"));
                if (na != null && !na.equals(addr)) alias.put(na, addr);
            } else if ("comment".equals(e.get("op"))) commented.add(addr);
        }
        Engine e = engines.get(tid);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("target", tid);
        out.put("symbols", all.size());
        out.put("renamed", renamed.size());
        out.put("commented", commented.size());
        out.put("decompiled", e == null ? 0 : e.unsavedDecompilations());   // newly decompiled since the last save (the user's rule: worth saving too)
        out.put("dirty", e != null && Boolean.TRUE.equals(e.info().get("dirty")));
        out.put("lastSaved", pf != null && pf.isFile() ? java.time.Instant.ofEpochMilli(pf.lastModified()).toString() : null);
        return out;
    }

    /** Discard only actions newer than the saved DB, including reversals of older entries.
     * Failed journal replacement leaves the original in-memory history and target open. */
    private synchronized boolean discardUnsavedHistory(String tid, File pf) {
        if (pf == null || !pf.isFile()) return true;
        List<Map<String,Object>> previous = historyFor(tid);
        List<Map<String,Object>> discarded = HistoryJournal.discardAfter(previous, databaseTime(pf));
        history.put(tid, discarded);
        if (persistHistory(tid)) return true;
        history.put(tid, previous);
        return false;
    }

    /** The persistence file for a target: engine.persistenceFile() when available, else the .jdb2 sibling for JEB. */
    private File persistenceFileFor(String tid, TargetMeta tm) {
        Engine e = tid == null ? null : engines.get(tid);
        if (e != null) {
            File pf = e.persistenceFile();
            if (pf != null) return pf;
            if (!"jeb".equalsIgnoreCase(engineOfTid(tid))) return null;
        }
        return tm == null ? null : jdb2FileOf(tm.path());
    }

    /** The .jdb2 a target persists to: the artifact's sibling, or the path itself for a bare .jdb2 target. */
    private static File jdb2FileOf(String path) {
        File f = new File(path);
        return path.toLowerCase(java.util.Locale.ROOT).endsWith(".jdb2") ? f : jdb2For(f);
    }

    /** history.json → {addr: editTsEpochMs} for the search-index warm-load replay:
     *  applied (non-undone) rename/comment entries, both addr and newAddr; an
     *  unparseable/missing ts maps to 0 (= always replayed, conservative). */
    private Map<String, Long> indexReplayMap(String tid) {
        Map<String, Long> out = new LinkedHashMap<>();
        for (Map<String,Object> e : HistoryJournal.replay(historyFor(tid), java.time.Instant.MIN)) {
            long ts = HistoryJournal.time(e.get("ts")).toEpochMilli();
            if (e.get("addr") != null) out.merge(String.valueOf(e.get("addr")), ts, Math::max);
            if (e.get("newAddr") != null) out.merge(String.valueOf(e.get("newAddr")), ts, Math::max);
            if (e.get("originalSymbol") != null) out.merge(String.valueOf(e.get("originalSymbol")), ts, Math::max);
            if (e.get("renameTargets") instanceof List<?> family)
                for (Object member : family)
                    if (member instanceof Map<?, ?> row && row.get("originalSymbol") != null)
                        out.merge(String.valueOf(row.get("originalSymbol")), ts, Math::max);
        }
        return out;
    }

    public void closeTarget(String idOrName) throws OpException { closeTarget(idOrName, null); }

    /** save: null = the standing rule (the engine writes when dirty); TRUE = the same;
     *  FALSE = DISCARD — the engine forgets its dirty flag so close() skips the write, AND
     *  the unsaved history entries are marked so they do not replay on the next open.
     *  Without the second half a "don't save" would silently resurrect the edits. */
    public void closeTarget(String idOrName, Boolean save) throws OpException {
        Detached d = detachTarget(idOrName, save);
        finishClose(d, save);
    }

    /** A target taken out of the maps, its engine still to be closed. */
    private record Detached(String tid, String name, Engine engine, String path) {}

    /** The synchronous half of a close: the maps drop, the active cursor moves, the jdb2 is
     *  reserved. Under the Workspace monitor, milliseconds. */
    private Detached detachTarget(String idOrName, Boolean save) throws OpException {
        String tid;
        synchronized (this) { tid = resolveTargetId(idOrName); }
        return withTargetWrite(tid, () -> detachTargetLocked(tid, save));
    }

    private Detached detachTargetLocked(String idOrName, Boolean save) throws OpException {
        Engine e;
        String path = null;
        String tid;
        String name = null;
        synchronized (this) {
            tid = resolveTargetId(idOrName);
            if (!currentProjectTargetIds().contains(tid))            // CHECK before mutating — a discard-persist failure below must leave the target fully open
                throw OpException.notFound("target not in current project: " + idOrName, "");
            /* DISCARD (save==FALSE): mark the unsaved history undone+discarded and PERSIST it
               here, while the target is still registered (historyFile resolves via the targets
               map) — and BEFORE any removal, so a failed write can abort with NOTHING mutated.
               If the discard cannot be recorded durably, REFUSE the close rather than silently
               drop-then-resurrect the edits on reopen (review c7395d2 A2). */
            if (Boolean.FALSE.equals(save)) {
                TargetMeta tmD = targets.get(tid);
                if (tmD != null && !discardUnsavedHistory(tid, persistenceFileFor(tid, tmD)))
                    throw OpException.io("could not record the discard durably (history.json not written) — the target is unchanged and still open; save it, or retry close",
                            "target=" + tid);
            }
            currentProjectTargetIds().remove(tid);
            e = engines.remove(tid);
            TargetMeta tm = targets.remove(tid);
            String eng = engineByTid.remove(tid);
            /* close ≠ remove-from-project: membership (targets.json) survives a
               close — the store identity maps die with the live session only */
            storeKeyByTid.remove(tid);
            originByTid.remove(tid);
            if (tid.equals(activeTarget)) {
                List<String> ids = currentProjectTargetIds();
                activeTarget = ids.isEmpty() ? null : ids.get(0);
            }
            /* reserve the JDB2 for the whole close: the maps drop here but the
               engine unloads off-monitor — an open in that window meets a
               locked .jdb2 (round-9 F2). Keyed on the jdb2 so an .apk target
               and its sibling .jdb2 collide on ONE key (round-10 #1). LAST in
               the hold: a throw above must never leak the refcount (round-13
               F7 — a leaked reservation hangs every later open for 3 minutes) */
            if (tm != null) {
                name = tm.name();
                path = reservationKeyOf(tm.path(), eng != null ? eng : "jeb");
                reserveClosing(path);
            }
        }
        return new Detached(tid, name, e, path);
    }

    /** The slow half: engine.close() contends OPEN_LOCK — potentially minutes behind another
     *  target's analysis, and a dirty target writes its jdb2 here. The Workspace monitor must NOT
     *  be held across it: every op resolves engines through that monitor (review R4 measured
     *  target.info on an unrelated ready target at 1.58s during a close). */
    private void finishClose(Detached d, Boolean save) throws OpException {
        try {
            if (d.engine() != null) {
                /* DISCARD: clear the engine's dirty flag so close() unloads WITHOUT writing
                   the jdb2 (the history was already marked+persisted under the monitor above) */
                if (Boolean.FALSE.equals(save)) d.engine().discardUnsaved();
                d.engine().close(); // failures reach synchronous callers and the async error log
            }
        } catch (RuntimeException failure) {
            OpException error = OpException.io("Target closed with a save failure: " + failure.getMessage()
                    + ". Keep its database, checkpoint and history files; reopen with history to recover accepted edits.", "target=" + d.tid());
            error.extra.put("closed", true); error.extra.put("saved", false);
            throw error;
        } finally {
            if (d.path() != null)
                synchronized (this) { releaseClosing(d.path()); }
        }
    }

    /* jdb2 paths with a close in flight (maps dropped, engine still unloading) —
       openTarget waits these out rather than opening into a locked .jdb2.
       REFCOUNTED (round-11 F3): N targets can share one jdb2 key (K.apk and
       K.dex resolve to the same K.jdb2), and the first close to finish must
       not release a reservation the other still needs. */
    private final Map<String, Integer> closingPaths = new HashMap<>();
    /* names with a project.delete wipe in flight — openProject refuses them
       (r2 F7: an open slipping into the check→wipe gap re-materialized the
       doomed dir under the recursive delete). Concurrent set: dirFor reads it
       OFF the monitor by design (membersFile/sessionFile callers), and without
       a happens-before edge the read could miss the add (r4 M1). Still TOCTOU
       by construction (check → mkdirs is not atomic) — it narrows, not closes,
       and it keys on the NAME: a slug twin's dirFor claim during the wipe is
       the narrow residue (r4 L2), not a bug that loses data. */
    private final java.util.Set<String> deletingProjects =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
    /* jdb2 keys with an OPEN in flight (scans done, not yet registered) — a
       concurrent open of the SAME key waits rather than registering a second
       engine on one database (round-24 F1: the desynchronized openTarget let
       two curls at one fresh path both pass the dedupe and both register) */
    private final java.util.Set<String> openingPaths = new java.util.HashSet<>();
    /* callers hold the monitor today; the markers keep a future caller from
       an IllegalMonitorStateException (review nit) */
    private synchronized void reserveClosing(String key) {
        closingPaths.merge(key, 1, Integer::sum);
    }
    private synchronized void releaseClosing(String key) {
        closingPaths.compute(key, (k, n) -> n == null || n <= 1 ? null : n - 1);
        notifyAll();
    }

    /** Persist history and native state under the same transaction as symbol edits. */
    public Map<String, Object> saveTarget(String idOrName) throws OpException {
        String tid;
        synchronized (this) { tid = resolveTargetIdOrActive(idOrName); }
        long startedAt = System.currentTimeMillis();
        reserveSave(tid, startedAt);
        return saveTargetNow(tid, startedAt);
    }

    private void reserveSave(String tid, long startedAt) {
        synchronized (saveOutcomes) {
            int pending = pendingSaves.merge(tid, 1, Integer::sum);
            if (pending == 1) saveOutcomes.put(tid, Map.of("saving", true, "startedAt", startedAt));
        }
    }

    private void completeSave(String tid, Map<String,Object> last) {
        synchronized (saveOutcomes) {
            int pending = pendingSaves.getOrDefault(tid, 1) - 1;
            if (pending <= 0) {
                pendingSaves.remove(tid);
                saveOutcomes.put(tid, Map.of("saving", false, "last", last));
            } else {
                pendingSaves.put(tid, pending);
                Object startedAt = saveOutcomes.get(tid).get("startedAt");
                saveOutcomes.put(tid, Map.of("saving", true, "startedAt", startedAt, "last", last));
            }
        }
    }

    private Map<String,Object> saveTargetNow(String tid, long startedAt) throws OpException {
        // Keep publication in native-write order too: an older completion must not overwrite
        // a newer save's failure after releasing the target gate.
        return renameCoordinator.transact(tid, () -> {
            try {
                Map<String,Object> result = withTargetWrite(tid, () -> {
                    requireHistoryPersisted(tid);
                    Map<String,Object> saved = new LinkedHashMap<>(engineFor(tid).save());
                    if (!Boolean.TRUE.equals(saved.get("saved")))
                        throw OpException.io("Native save did not confirm a written database", "target=" + tid);
                    renameCoordinator.saved(tid);
                    saved.put("historyRevision", historyRevision(tid));
                    saved.put("durableHistory", true);
                    return saved;
                });
                Map<String,Object> last = new LinkedHashMap<>();
                last.put("ok", true); last.put("at", System.currentTimeMillis());
                last.put("ms", System.currentTimeMillis() - startedAt);
                last.put("bytes", result.getOrDefault("bytes", 0));
                last.put("historyRevision", result.get("historyRevision"));
                completeSave(tid, last);
                return result;
            } catch (Throwable error) {
                Map<String,Object> last = new LinkedHashMap<>();
                last.put("ok", false); last.put("at", System.currentTimeMillis());
                last.put("ms", System.currentTimeMillis() - startedAt); last.put("error", String.valueOf(error));
                completeSave(tid, last);
                if (error instanceof OpException op) throw op;
                if (error instanceof Error fatal) throw fatal;
                throw OpException.io("Save failed: " + error, "target=" + tid);
            }
        });
    }

    /** Reserve an asynchronous save before starting its thread; polling includes journal failures. */
    public Map<String, Object> saveTargetAsync(String idOrName) throws OpException {
        String tid;
        synchronized (this) { tid = resolveTargetIdOrActive(idOrName); }
        engineFor(tid);
        Map<String,Object> out = new LinkedHashMap<>();
        out.put("target", tid);
        out.put("pollTool", Map.of("tool", "target", "args", Map.of("action", "save_status", "target", tid)));
        out.put("pollOp", Map.of("op", "target.saveStatus", "params", Map.of("target", tid)));
        out.put("poll", "Wait for saving:false AND last.at >= startedAt, then require last.ok:true. Missing or older last is not completion; last.ok:false includes the failure.");
        long startedAt;
        synchronized (saveOutcomes) {
            Map<String,Object> status = saveStatus(tid);
            if (Boolean.TRUE.equals(status.get("saving"))) {
                out.put("started", false); out.put("alreadySaving", true); out.putAll(status); return out;
            }
            startedAt = System.currentTimeMillis();
            reserveSave(tid, startedAt);
        }
        Thread thread = new Thread(() -> {
            try { saveTargetNow(tid, startedAt); }
            catch (Throwable error) { Log.error("target.save (async)", error, Map.of("target", tid)); }
        }, "save-" + tid);
        thread.setDaemon(true); thread.start();
        out.put("started", true); out.put("startedAt", startedAt);
        return out;
    }

    /** target.close {async: true}: the close (and its save when the target is dirty) runs on its
     *  own thread; the target leaves the list at once and the write finishes behind it. workspace
     *  state lists the jdb2 under `closing` until it is done; a close-save that fails is not a
     *  loss — the edits replay from the sidecar history on the next open. */
    public Map<String, Object> closeTargetAsync(String idOrName, Boolean save) throws OpException {
        String tid = resolveTargetIdOrActive(idOrName);
        boolean dirty = false;
        try { Object d = engineFor(tid).info().get("dirty"); dirty = Boolean.TRUE.equals(d); } catch (Throwable ignored) {}
        Detached d = detachTarget(tid, save);          // synchronous: the maps drop, the cursor moves, the jdb2 is reserved
        Thread t = new Thread(() -> {
            try { finishClose(d, save); }
            catch (Throwable ex) {
                Map<String, Object> ctx = new LinkedHashMap<>();
                ctx.put("target", tid);
                Log.error("target.close (async)", ex, ctx);
            }
        }, "close-" + tid);
        t.setDaemon(true);
        t.start();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("closing", true);
        out.put("closed", tid);
        out.put("name", d.name());
        out.put("willSave", dirty && !Boolean.FALSE.equals(save));
        if (d.engine() != null) out.put("closeCapabilities", d.engine().closeCapabilities());
        Map<String, Object> st = state();
        out.put("activeTarget", st.get("activeTarget"));
        out.put("targets", st.get("targets"));
        if (st.get("closing") != null) out.put("stillClosing", st.get("closing"));
        out.put("poll", "workspace state lists the jdb2 under `closing` while the write runs; the target is already out of the list; a close-save that fails replays the edits from history on the next open");
        return out;
    }

    /** target.saveStatus: the save in flight (the GUI polls it for the saving box's bar). */
    public Map<String, Object> saveStatus(String idOrName) throws OpException {
        String tid;
        synchronized (this) { tid = resolveTargetIdOrActive(idOrName); }
        Map<String,Object> result = new LinkedHashMap<>(engineFor(tid).saveStatus());
        Map<String,Object> own = saveOutcomes.get(tid);
        if (own != null) result.putAll(own);
        return result;
    }

    /** op.cancel: cancel a background task by ticket (search-index:<target>). When a
     *  target is given, only its engine is asked; otherwise all engines are polled. */
    public synchronized Map<String, Object> cancelTask(String ticket, String targetParam)
            throws OpException {
        if (ticket == null || ticket.isBlank())
            throw OpException.syntax("op.cancel needs a ticket", "");
        boolean cancelled = false;
        if (targetParam != null && !targetParam.isBlank()) {
            cancelled = engineFor(targetParam).cancelTask(ticket);
        } else {
            for (Engine e : engines.values()) cancelled |= e.cancelTask(ticket);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ticket", ticket);
        out.put("cancelled", cancelled);
        return out;
    }

    /** Close every session (server shutdown). Journal failure keeps its target open, and
     * failed shutdown saves are logged explicitly. Use target.save before stopping the host. */
    public void closeAll() {
        List<String> ids;
        synchronized (this) { ids = new ArrayList<>(engines.keySet()); }
        for (String tid : ids) {
            try { withTargetWrite(tid, () -> { engineFor(tid).close(); return null; }); }
            catch (Throwable failure) { Log.error("target.close (shutdown)", failure, Map.of("target", tid)); }
        }
    }

    private String resolveTargetId(String idOrName) throws OpException {
        if (targets.containsKey(idOrName)) return idOrName;
        List<String> matches = new ArrayList<>();
        for (TargetMeta t : targets.values())
            if (t.name().equals(idOrName)) matches.add(t.id());
        if (matches.isEmpty()) throw OpException.notFound("target not found: " + idOrName, "");
        if (matches.size() > 1)
            throw OpException.syntax("ambiguous target: " + idOrName,
                    "matches=" + matches + " — use target id");
        return matches.get(0);
    }

    // ------------------------------------------------------------ artifact typing (magic bytes)

    /** Type from magic bytes: apk (zip with classes.dex/AndroidManifest.xml), dex, jdb2
     *  (extension fallback — JEB project files have no fixed magic), elf, macho, zip, unknown. */
    static String detectType(File f) {
        if (f.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".jdb2")) return "jdb2";
        byte[] head = new byte[8];
        int n;
        try (InputStream in = Files.newInputStream(f.toPath())) {
            n = in.read(head);
        } catch (IOException e) {
            return "unknown";
        }
        if (n >= 2 && head[0] == 'P' && head[1] == 'K') {
            try (ZipFile z = new ZipFile(f)) {
                if (z.getEntry("classes.dex") != null || z.getEntry("AndroidManifest.xml") != null)
                    return "apk";
            } catch (IOException ignored) {}
            return "zip";
        }
        if (n >= 4 && head[0] == 'd' && head[1] == 'e' && head[2] == 'x' && head[3] == '\n') return "dex";
        if (n >= 4 && head[0] == 0x7F && head[1] == 'E' && head[2] == 'L' && head[3] == 'F') return "elf";
        if (n >= 4 && (u32(head) == 0xFEEDFACFL || u32(head) == 0xCEFAEDFEL || u32(head) == 0xBEBAFECAL))
            return "macho";
        return "unknown";
    }

    private static long u32(byte[] b) {
        return ((b[0] & 0xFFL) << 24) | ((b[1] & 0xFFL) << 16) | ((b[2] & 0xFFL) << 8) | (b[3] & 0xFFL);
    }

    private static String defaultEngineFor(String type) {
        if ("jdb2".equals(type)) return "jeb";
        String env = System.getenv("WORKBENCH_DEFAULT_ENGINE");
        if (env != null && !env.isBlank()) return env.trim().toLowerCase(java.util.Locale.ROOT);
        if ("apk".equals(type) || "dex".equals(type)) {
            if (!Engines.jebAvailable() && Engines.jadxAdapterAvailable()) return "jadx";
            return "jeb";
        }
        return "jeb";   // engine picker lands in a later milestone; jeb is the default real one
    }

    /** JEB native persistence: <name>.jdb2 next to the artifact (old Jeeves convention). */
    static File jdb2For(File artifact) {
        String p = artifact.getAbsolutePath();
        int dot = p.lastIndexOf('.');
        int slash = Math.max(p.lastIndexOf('/'), p.lastIndexOf(File.separatorChar));
        String base = dot > slash ? p.substring(0, dot) : p;
        return new File(base + ".jdb2");
    }

    /* ---------------------------------------------------------- target store
       (2026-09-08, user directive): ONE home per distinct artifact — a
       content-keyed entry under <workbench>/targets/<slug>-<sha1[0:8]>/
       holding a copy of the original artifact + its .jdb2 (+ the engine's
       .jdb2.workbench search sidecar, which follows the jdb2 path). Projects
       no longer own databases (per-project jdb2 copies duplicate gigabytes for
       a shared artifact); they own MEMBERSHIP — a mapping into the store,
       persisted at projects/<slug>/targets.json. */

    private java.io.File storeRoot() {
        return new java.io.File(workbenchRoot, "targets");
    }

    /** the store key for a candidate: slug of the artifact base name + the
        first 8 of its content hash. Same bytes → same key → same entry,
        whatever path the open came from. */
    private static String storeKeyOf(String artifactName, String contentHash) {
        String base = artifactName;
        int dot = base.lastIndexOf('.');
        if (dot > 0) base = base.substring(0, dot);
        return slug(base) + "-" + contentHash.substring(0, 8);
    }

    /** the artifact's file name minus its last extension (test_visionocr.apk →
        test_visionocr) — the store entry's jdb2 name base */
    private static String baseNoExt(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    /** the store key for a candidate: jdb2-direct opens are PATH-stable (their
        bytes change on every save — a content key relocates identity per save,
        r2 F3); artifacts are content-keyed (slug of the base + hash[0:8]) */
    private static String storeKeyFor(File f, String type, String contentHash) {
        if ("jdb2".equals(type))
            return "jdb2-" + sha1HexString(f.getAbsolutePath()).substring(0, 8);
        return storeKeyOf(f.getName(), contentHash);
    }

    private record StoreEntry(String key, java.io.File dir, java.io.File artifact, java.io.File jdb2) {}

    /** Claim (or reuse) the store entry for a candidate. Copies the artifact in
        when absent (bytes identical by construction — the key carries the hash;
        an identity file pins the full hash so a truncated-key collision is a
        fresh key, never a shared entry). MIGRATION: the old layout's
        artifact-adjacent files are MOVED in (rename — same volume, instant):
        the .jdb2 AND its sidecar(s) — anchored on the ARTIFACT for apk/dex
        opens (<apk>.workbench: tags/bookmarks/flows/history — rev 950876a-r1 #1:
        anchoring on the jdb2 silently orphaned them) and on the jdb2 for a
        jdb2 direct open. A move failure is loud, never a silent re-analysis.

        jdb2-DIRECT opens stay IN PLACE (rev #4): the database's bytes change on
        every save, so a content key would relocate the entry per save; a jdb2
        IS its own store entry. The key is then path-stable (content of the
        PATH string, not the file). */
    /* the annotation payloads the engine's sidecar holds (Annotations.java +
       history) — the legacy-layout files a store migration must NOT strand */
    private static final String[] ANNOTATION_FILES = {
            "tags.json", "bookmarks.json", "flows.json", "families.json", "seq.json", "history.json" };

    private StoreEntry prepareStoreEntry(File f, String type, String key, String contentHash) throws OpException {
        if ("jdb2".equals(type))
            return new StoreEntry(key, f.getParentFile(), null, f);   // in place (r2 F3)
        java.io.File dir = new java.io.File(storeRoot(), key);
        /* the artifact already sits in a store entry — this home's or another's (its dir carries
           the .content-sha1 marker with this very hash): open it THERE. Importing it would MOVE
           its jdb2 and sidecar into this home's store, which is what a mis-resolved home did to
           the real WhatsApp store on 2026-09-15 (backend/backend/.workbench). */
        java.io.File srcDir = f.getAbsoluteFile().getParentFile();
        java.io.File srcMarker = srcDir == null ? null : new java.io.File(srcDir, ".content-sha1");
        if (srcMarker != null && srcMarker.isFile() && !srcDir.getAbsoluteFile().equals(dir.getAbsoluteFile())) {
            try {
                if (contentHash.equals(java.nio.file.Files.readString(srcMarker.toPath()).trim())) {
                    java.io.File inPlaceJdb2 = new java.io.File(srcDir, baseNoExt(f.getName()) + ".jdb2");
                    System.err.println("workbench: opening " + f.getName() + " in its own store entry " + srcDir + " (not imported into " + storeRoot() + ")");
                    return new StoreEntry(key, srcDir, f.getAbsoluteFile(), inPlaceJdb2);
                }
            } catch (java.io.IOException ignored) {}
        }
        java.io.File artifact = new java.io.File(dir, f.getName());
        /* the jdb2 keeps the artifact's ORIGINAL base name (the key already
           disambiguates the dir) — slugging it would read foreign to anyone
           who knew the file before the store */
        java.io.File jdb2 = new java.io.File(dir, baseNoExt(f.getName()) + ".jdb2");
        /* every consumer anchors the sidecar on the ARTIFACT path
           (sidecarDirFor → sidecarFor(meta.path())) — the r1 fix moved to the
           jdb2 anchor and nothing reads there (r2 F2) */
        java.io.File storeSide;
        try {
            java.io.File sc = new java.io.File(artifact.getAbsolutePath() + ".workbench");
            try { sc = sc.getCanonicalFile(); }
            catch (java.io.IOException ignored) { sc = sc.getAbsoluteFile(); }   // r4 L6: a canonicalize hiccup must not kill the open
            storeSide = sc;
            java.nio.file.Files.createDirectories(dir.toPath());
            java.io.File idf = new java.io.File(dir, ".content-sha1");
            if (idf.isFile()
                    && !contentHash.equals(java.nio.file.Files.readString(idf.toPath()).trim()))
                throw OpException.busy("target store key collision at " + key
                        + " (different content) — rename one artifact", "store=" + dir.getAbsolutePath());
            /* atomic fills (rev #9): a crash between copy and marker must not
               leave a truncated artifact the next open accepts — copy to .part,
               rename, and the .content-sha1 marker goes down LAST (it is the
               completeness witness; its absence re-copies) */
            if (!artifact.isFile() || !idf.isFile()) {
                java.io.File part = new java.io.File(dir, artifact.getName() + ".part");
                java.nio.file.Files.copy(f.toPath(), part.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                java.nio.file.Files.move(part.toPath(), artifact.toPath(),
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            }
            if (!jdb2.isFile()) {
                java.io.File legacy = jdb2For(f);
                if (legacy.isFile() && !legacy.getAbsolutePath().equals(jdb2.getAbsolutePath())) {
                    movePreferAtomic(legacy.toPath(), jdb2.toPath());
                    /* its key record follows it (a record left behind would speak for whatever
                       database lands at that path next); none → the store copy has none either */
                    java.io.File lk = new java.io.File(legacy.getPath() + ".key"), sk = new java.io.File(jdb2.getPath() + ".key");
                    try {
                        if (lk.isFile()) movePreferAtomic(lk.toPath(), sk.toPath());
                        else java.nio.file.Files.deleteIfExists(sk.toPath());
                    } catch (Exception ex) { lk.delete(); sk.delete(); }   // no record is safe: the key is read again on a solo open
                    System.err.println("workbench: migrated " + legacy.getName()
                            + " into the target store at " + key);
                }
            }
            /* legacy sidecars — de-gated from the jdb2 move (r2 F2's self-heal:
               a half-migrated entry has the jdb2 already; its annotations still
               belong here). The annotation jsons move when the store lacks them;
               a legacy search-index moves WHOLE only when the store sidecar has
               none of its own — file-level unions of two index generations are
               not a thing (r2 F8). The legacy skeleton is PARKED
               (<name>.migrated-<ts>, the codebase's own idiom), never left as a
               ghost dir. */
            /* reopening FROM the store makes a legacy candidate that IS the
               live sidecar — a canonical-identity skip, or the loop parks the
               home it lives in (r3 H1: every editor reopen silently migrated
               the sidecar to .migrated) */
            for (java.io.File side0 : new java.io.File[]{
                    new java.io.File(f.getAbsolutePath() + ".workbench"),
                    new java.io.File(jdb2For(f).getAbsolutePath() + ".workbench")}) {
                if (!side0.isDirectory()) continue;
                java.io.File side = side0.getCanonicalFile();
                if (side.equals(storeSide)) continue;
                java.nio.file.Files.createDirectories(storeSide.toPath());
                boolean movedAny = false;
                for (String af : ANNOTATION_FILES) {
                    java.io.File src = new java.io.File(side, af);
                    java.io.File dst = new java.io.File(storeSide, af);
                    if (src.isFile() && !dst.exists()) {
                        movePreferAtomic(src.toPath(), dst.toPath());
                        movedAny = true;
                    }
                }
                java.io.File legacyIndex = new java.io.File(side, "search-index");
                java.io.File storeIndex = new java.io.File(storeSide, "search-index");
                if (legacyIndex.isDirectory() && !storeIndex.isDirectory()) {
                    movePreferAtomic(legacyIndex.toPath(), storeIndex.toPath());
                    movedAny = true;
                }
                String[] left = side.list();
                boolean empty = left == null || left.length == 0;
                if (empty) {
                    side.delete();
                } else if (movedAny) {
                    /* park the residue (the codebase's .migrated idiom) — only
                       when something actually moved: an untouched foreign
                       sidecar is left where it lies */
                    java.io.File parked = new java.io.File(side.getParentFile(),
                            side.getName() + ".migrated");
                    int n = 0;
                    while (parked.exists()) parked = new java.io.File(side.getParentFile(),
                            side.getName() + ".migrated-" + (++n));
                    movePreferAtomic(side.toPath(), parked.toPath());
                }
                if (movedAny)
                    System.err.println("workbench: migrated sidecar " + side.getName()
                            + " into the target store at " + key);
            }
            /* the marker goes down LAST — see the atomic-fill comment above */
            if (!idf.isFile()) java.nio.file.Files.writeString(idf.toPath(), contentHash);
            /* decision 16: carry the pull provenance beside the store jdb2, so auto-naming,
               basisWarn and re-pull dedupe read the store's record after the staging copy in
               pulled/ is reaped. A non-pulled open has no source prov — copyProv no-ops. */
            if (devices != null) {
                String fromJ = jdb2For(f).getAbsolutePath();
                if (isJdb2Name(fromJ)) fromJ = fromJ.substring(0, fromJ.length() - 5);
                String toJ = jdb2.getAbsolutePath();
                if (isJdb2Name(toJ)) toJ = toJ.substring(0, toJ.length() - 5);
                devices().copyProv(fromJ, toJ);
            }
        } catch (java.io.IOException e) {
            throw OpException.io("target store failed for " + key + ": " + e, "dir=" + dir);
        }
        return new StoreEntry(key, dir, artifact, jdb2);
    }

    private static void movePreferAtomic(java.nio.file.Path from, java.nio.file.Path to) throws java.io.IOException {
        try {
            java.nio.file.Files.move(from, to, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            java.nio.file.Files.move(from, to);   // cross-volume: plain move (copy+delete)
        }
    }

    private static String sha1HexString(String s) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-1");
            byte[] d = md.digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(Character.forDigit((b >> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }

    /* membership: projects/<slug>/targets.json — [{key, name, storePath, addedAt}].
       Target ids (t<N>) are per-JVM and meaningless across restarts; the store
       key is the persistent identity. Read fresh per call (small file; the
       editor's op cadence is human). */
    private java.io.File membersFile(String projectName) {
        return new java.io.File(dirFor(projectName), "targets.json");
    }

    @SuppressWarnings("unchecked")
    private java.util.List<Map<String, Object>> readMembers(String projectName) {
        java.io.File f = membersFile(projectName);
        if (!f.isFile()) return new ArrayList<>();
        try {
            Object p = dev.workbench.registry.Json.parse(java.nio.file.Files.readString(f.toPath()));
            if (p instanceof Map && ((Map<?, ?>) p).get("targets") instanceof java.util.List)
                return new ArrayList<>((java.util.List<Map<String, Object>>) ((Map<?, ?>) p).get("targets"));
        } catch (Exception e) {
            System.err.println("workbench: unreadable targets.json for " + projectName + " — " + e);
        }
        return new ArrayList<>();
    }

    private void writeMembers(String projectName, java.util.List<Map<String, Object>> members) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("targets", members);
        dev.workbench.registry.SidecarIO.writeAtomic(membersFile(projectName),
                dev.workbench.registry.Json.write(m), "targets");
    }

    private void memberAdd(String projectName, String key, String name, String storePath) {
        memberAdd(projectName, key, name, storePath, "jeb");
    }

    private void memberAdd(String projectName, String key, String name, String storePath, String engine) {
        String normEng = (engine == null || engine.isBlank() ? "jeb" : engine).toLowerCase(java.util.Locale.ROOT);
        java.util.List<Map<String, Object>> ms = readMembers(projectName);
        for (Map<String, Object> m : ms) {
            String mEng = m.get("engine") == null ? "jeb" : String.valueOf(m.get("engine")).toLowerCase(java.util.Locale.ROOT);
            if (key.equals(m.get("key")) && normEng.equals(mEng)) return;      // already a member with this engine
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("key", key);
        row.put("name", name);
        row.put("storePath", storePath);
        row.put("engine", normEng.toUpperCase(java.util.Locale.ROOT));
        row.put("addedAt", java.time.Instant.now().toString());
        ms.add(row);
        writeMembers(projectName, ms);
    }

    private boolean memberRemove(String projectName, String key) {
        return memberRemove(projectName, key, null);
    }

    private boolean memberRemove(String projectName, String key, String engine) {
        java.util.List<Map<String, Object>> ms = readMembers(projectName);
        boolean removed = ms.removeIf(m -> key.equals(m.get("key"))
                && (engine == null || engine.equalsIgnoreCase(String.valueOf(m.getOrDefault("engine", "jeb")))));
        if (removed) writeMembers(projectName, ms);
        return removed;
    }

    /** Streaming SHA-1 for the content-twin check (null on any IO hiccup — the
        check simply declines to dedupe then). */
    private static String sha1Hex(File f) {
        try (java.io.InputStream in = new java.io.BufferedInputStream(new java.io.FileInputStream(f))) {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-1");
            byte[] buf = new byte[1 << 20];
            for (int n; (n = in.read(buf)) > 0; ) md.update(buf, 0, n);
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /** Cache a hash only when non-null (fix-8-r2 F2: a stored null reads as
        "different content" at the key-twin refusal — a hiccup must be a MISS)
        and only once per file (fix-8-r2 F4: a jdb2-path target's two put sites
        key on the same file — the second hash of the same bytes was waste). */
    private static void putHash(Map<String, String> m, String key, String hash) {
        if (hash != null) m.putIfAbsent(key, hash);
    }

    /** Sidecar dir for persist:sidecar engines (MockEngine/JADX later) — WORKBENCH_SPEC §3.3. */
    static File sidecarFor(File artifact) {
        return new File(artifact.getAbsolutePath() + ".workbench");
    }

    /** Sidecar dir for a target, or null for fixture targets without a real artifact. */
    File sidecarDirFor(String tid) {
        TargetMeta meta = targets.get(tid);
        if (meta == null) return null;
        File artifact = new File(meta.path());
        if (!artifact.isFile()) return null;
        return sidecarFor(artifact);
    }

    /** All open target ids (annotation cross-target resolution). */
    public synchronized java.util.Set<String> targetIds() {   // map-r5 F24: was the only unsynchronized accessor
        return new java.util.LinkedHashSet<>(targets.keySet());   // map-r6 #14: copy the keys, not the map
    }

    private final Annotations annotations = new Annotations(this);

    /** Tags/bookmarks/flows store (VIEWS_SPEC §4). */
    public Annotations annotations() { return annotations; }

    private RunsManager runs;

    /** Runs wiring (VIEWS_SPEC §2) — set by the server at boot. */
    public void setRuns(RunsManager r) { this.runs = r; }

    private volatile MapStore maps;   // swapped on project switch (maps are per-project)
    private AgentManager agents;
    private dev.workbench.device.DeviceManager devices;

    /** backend/.workbench root — set by the server at boot; per-project map
        stores live under it (GRAPHS_SPEC §3: maps are project-level artifacts). */
    private java.io.File workbenchRoot;

    public void setWorkbenchRoot(java.io.File f) { this.workbenchRoot = f; }

    /** Per-project map store, keyed on the project NAME via dirFor's claim
        walk (never the session id — map-r5 F1, map-r6 #1/#2). */
    private MapStore mapsFor(String projectName) {
        return new MapStore(this, new java.io.File(dirFor(projectName), "maps"));
    }

    /** The project's sidecar dir (projects/<slug>). The dir carries a
        .project-name marker with the exact trimmed name; a marker mismatch
        (two projects slugging alike, or non-ASCII names collapsing to
        "project") steps through slug-<hash8>[-N] candidates until a marker
        matches or a fresh dir is claimed (map-r6 #4). Same name → same scan →
        same dir: reopening "alpha" tomorrow recovers alpha's maps. Marker-less
        dirs are claimed — they arise only from the boot migration or a crash
        mid-claim. Shared by mapsFor AND the boot migration, so the migration
        can't fold maps into a dir the walk would consider a foreign project's
        (map-r7 F2). */
    private java.io.File dirFor(String projectName) {
        /* a null root resolved "projects/" against the process cwd — a hermetic test's server
           wrote a fixture sidecar into the REPO root that way (2026-09-10) */
        if (workbenchRoot == null)
            throw new IllegalStateException("workbench root not set — project sidecars have no home yet");
        java.io.File base = new java.io.File(workbenchRoot, "projects");
        String slug = slug(projectName);
        String hash = String.format(java.util.Locale.ROOT, "%08x", projectName.hashCode());   // stable, specified
        /* a name mid-WIPE is never claimed (r3 M3: dirFor's mkdirs+marker is the
           primitive that re-materialized a doomed project under the recursive
           delete — readers (membersFile/sessionFile/mapsFor) now degrade to a
           path that exists only if the wipe hasn't reached it yet) */
        boolean beingDeleted = deletingProjects.contains(projectName);
        for (int i = 0; ; i++) {
            String key = i == 0 ? slug : slug + "-" + hash + (i == 1 ? "" : "-" + i);
            java.io.File dir = new java.io.File(base, key);
            java.io.File marker = new java.io.File(dir, ".project-name");
            try {
                if (marker.isFile()
                        && !projectName.equals(java.nio.file.Files.readString(marker.toPath()).trim()))
                    continue;                                    // someone else's slug twin
                if (!marker.isFile() && !beingDeleted) {
                    dir.mkdirs();
                    java.nio.file.Files.writeString(marker.toPath(), projectName);
                }
            } catch (Exception e) {
                /* map-r7 F9b: a failed claim must be LOUD — returning the
                   unmarked dir leaves it claimable by the next slug twin */
                System.err.println("project map dir claim failed: " + dir + " — " + e
                        + " (collision protection DISABLED for this dir)");
            }
            return dir;
        }
    }

    private static String slug(String name) {
        String s = (name == null ? "" : name).trim().toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        return s.isEmpty() ? "project" : s;
    }

    /* ---------------------------------------------------------- session.json

       Per-project activation, persisted at projects/<slug>/session.json as
       {"activeMap": "...", "ts": "..."}. Resolved through the SAME dirFor
       claim walk the map store uses — a second path keyer would let slug twins
       cross-contaminate each other's activation (map-r6 #4's trap, one layer
       up). Projects themselves are not persisted, so this restores activation
       for the boot project and for any project reopened by name.

       Writes happen OUTSIDE the Workspace monitor (see openMap/closeMap): it
       is pure filesystem, and every op in the workbench queues behind that
       monitor. Two threads claiming the same dir concurrently is harmless —
       dirFor's mkdirs + marker write are idempotent for a given name. */

    /** Project ids whose session.json has already been consulted. session.json
        is a RESTORE seam, not a mirror: a project that is open RIGHT NOW keeps
        its activation in memory, and re-reading the file on every project
        switch would let a failed write resurrect a map the user closed. Ids
        are monotonic and never recycled, so a reopened project (fresh id) does
        get its activation back. */
    private final java.util.Set<String> sessionRead = new java.util.HashSet<>();

    private java.io.File sessionFile(String projectName) {
        return new java.io.File(dirFor(projectName), "session.json");
    }

    /** Persist (or clear, with null) the project's activeMap. Atomic + loud via
        the shared sidecar discipline — a plain writeString truncates in place,
        and a boot racing the write would read a torn file. A degraded read
        parks the possibly-healthy original before overwriting (fix-1-r6 MED-2). */
    private void persistSession(String projectName, String activeMap) {
        if (workbenchRoot == null || projectName == null) return;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("activeMap", activeMap);
        m.put("ts", java.time.Instant.now().toString());
        java.io.File f = sessionFile(projectName);
        if (sessionDegraded.contains(projectName)) {
            if (!dev.workbench.registry.SidecarIO.parkHealthyBeforeOverwrite(f, "session"))
                return;                                    // flag kept (fix-1-r4 #5)
            sessionDegraded.remove(projectName);
        }
        dev.workbench.registry.SidecarIO.writeAtomic(f,
                dev.workbench.registry.Json.write(m), "session");
    }

    /** The persisted activeMap for a project name, or null (no file, no value,
        or an unreadable one — a broken session must degrade to "nothing open",
        never to an exception on the boot path). Read failures follow the shared
        discipline (fix-1-r6 MED-2: this was the series' one skipped file — a
        transient hiccup read as "nothing open" and the next map activation
        overwrote the healthy record with no evidence). */
    private String readSession(String projectName) {
        if (workbenchRoot == null || projectName == null) return null;
        java.io.File f = sessionFile(projectName);
        if (!f.isFile()) return null;
        try {
            Object p = dev.workbench.registry.Json.parse(java.nio.file.Files.readString(f.toPath()));
            if (!(p instanceof Map)) {
                if (!dev.workbench.registry.SidecarIO.parkCorrupt(f, "session",
                        new IllegalStateException("expected a JSON object")))
                    sessionDegraded.add(projectName);
                return null;
            }
            Object am = ((Map<?, ?>) p).get("activeMap");
            return am == null || String.valueOf(am).isBlank() ? null : String.valueOf(am);
        } catch (java.io.IOException e) {
            /* transient read hiccup ≠ corruption: routed by permanence; a
               transient failure (or a failed charset park) marks the session
               degraded so the next persistSession parks before overwriting */
            if (!dev.workbench.registry.SidecarIO.readFailedOrPark(f, "session", e))
                sessionDegraded.add(projectName);
            return null;
        } catch (Exception e) {
            if (!dev.workbench.registry.SidecarIO.parkCorrupt(f, "session", e))
                sessionDegraded.add(projectName);
            return null;
        }
    }

    /** Store swap + phantom-activeMap drop, called under the Workspace monitor.
        The store is UNPUBLISHED until setMaps, so its lock can't contend here —
        the Workspace monitor must never wait on a SHARED MapStore lock while
        MapStore.addNode → normalizeSymbol → engineFor takes the locks in the
        opposite order (opus map-r5 F14). */
    private void swapMapsLocked(Map<String, Object> p, Map<String, Object> seedIfEmpty) {
        String name = String.valueOf(p.get("name"));
        foldLegacyRunsAndProposals(name);   // first activation after the batch-2 upgrade
        stampLegacyIdentityOnce(name);      // pre-batch-2 rows carry no durable target key
        MapStore store = mapsFor(name);
        if (seedIfEmpty != null && store.isEmpty()) store.seed(seedIfEmpty);
        /* first sight of this project row (boot, or a reopen that minted a new
           id): restore the persisted activation. Filesystem under the monitor
           is the existing precedent here — mapsFor above already read every
           map file — and it is the WRITES that stay outside (map-lifecycle §2) */
        if (sessionRead.add(String.valueOf(p.get("id"))) && p.get("activeMap") == null) {
            String saved = readSession(name);
            if (saved != null) p.put("activeMap", saved);
        }
        Object am = p.get("activeMap");
        if (am != null && !store.has(String.valueOf(am)))
            p.remove("activeMap");   // a phantom activeMap bypasses clear-on-switch (map-r5 F2)
        setMaps(store);
    }

    /* runs/ and proposals.json were workspace-global until batch 2 (2026-09-10). The FIRST
       project activated after the upgrade receives them — the boot project when the fixture
       scene exists, else the first project.open / target.open (the reopen after the restart).
       Per file, no overwrite (a skipped file stays inside the parked source); runs/ is parked
       as runs.migrated once processed, never after a failed move. Said on stderr with the
       project's name, so a fold into the wrong project is diagnosable and recoverable by hand. */
    private boolean legacyFolded;

    /* the fold and the identity stamp touch the operator's REAL sidecar: only the interactive
       server (WORKBENCH_MOCK=0) or a hermetic home (WORKBENCH_HOME) may run them — an IT booting
       the fixture scene against the live backend/.workbench must never fold the user's runs into
       "privacy-invite-flow" (review b2-r1 M2; the 2026-09-09 M10aIT incident, generalised) */
    private static boolean legacyOpsAllowed() {
        String home = System.getenv("WORKBENCH_HOME");
        return (home != null && !home.isBlank()) || "0".equals(System.getenv("WORKBENCH_MOCK"));
    }

    private void foldLegacyRunsAndProposals(String projectName) {
        if (legacyFolded || workbenchRoot == null) return;
        legacyFolded = true;
        java.io.File srcRuns = new java.io.File(workbenchRoot, "runs");
        java.io.File srcProps0 = new java.io.File(workbenchRoot, "proposals.json");
        if (!legacyOpsAllowed()) {
            if (srcRuns.isDirectory() || srcProps0.isFile())
                System.err.println("legacy runs/proposals NOT folded: this is a fixture-scene server on the live sidecar (set WORKBENCH_HOME, or run the interactive server)");
            return;
        }
        /* a fold that failed part-way must resume into the SAME project next boot, not into
           whatever activates first (review b2-r1 M3): the destination is written beside the
           source before the first move and honoured on the retry */
        java.io.File marker = new java.io.File(srcRuns, ".folded-into");
        try {
            if (marker.isFile()) {
                String pinned = java.nio.file.Files.readString(marker.toPath()).trim();
                if (!pinned.equals(projectName)) {
                    if (dirForReadOnly(pinned) == null) {
                        System.err.println("runs migration: earlier fold was into '" + pinned + "', which no longer exists on disk — left in place");
                        return;
                    }
                    System.err.println("runs migration: resuming the earlier fold into '" + pinned + "'");
                    projectName = pinned;
                }
            } else if (srcRuns.isDirectory()) {
                java.nio.file.Files.writeString(marker.toPath(), projectName);
            }
        } catch (Exception e) {
            System.err.println("runs migration: fold marker unreadable/unwritable — retried next boot: " + e);
            return;
        }
        java.io.File dst = dirFor(projectName);
        java.io.File[] files = srcRuns.isDirectory() ? srcRuns.listFiles(f -> f.isFile() && !f.getName().equals(".folded-into")) : null;
        if (files != null && files.length > 0) {
            java.io.File dstRuns = new java.io.File(dst, "runs");
            boolean failed = !dstRuns.isDirectory() && !dstRuns.mkdirs();
            int moved = 0;
            for (java.io.File f : failed ? new java.io.File[0] : files) {
                java.io.File to = new java.io.File(dstRuns, f.getName());
                if (to.exists()) {
                    System.err.println("runs migration: " + f.getName() + " skipped (" + to + " exists)");
                    continue;
                }
                try {
                    java.nio.file.Files.move(f.toPath(), to.toPath());
                    moved++;
                } catch (Exception e) {
                    failed = true;
                    System.err.println("runs migration: " + f.getName() + " failed (retried next boot): " + e);
                }
            }
            System.err.println("runs migration: " + moved + " of " + files.length + " file(s) folded into project '"
                    + projectName + "' (" + dstRuns + ")");
            if (failed) return;   // retried next boot; nothing below applies to a half-moved dir
        }
        /* park once EVERYTHING has moved — also on a later boot whose runs/ holds only the marker
           after a park that failed before (r3 L3) */
        java.io.File[] left = srcRuns.isDirectory() ? srcRuns.listFiles(f -> f.isFile() && !f.getName().equals(".folded-into")) : null;
        java.io.File park = new java.io.File(workbenchRoot, "runs.migrated");
        if (srcRuns.isDirectory() && (left == null || left.length == 0) && !park.exists() && !srcRuns.renameTo(park))
            System.err.println("runs migration: could not park " + srcRuns);
        if (srcRuns.isDirectory() && park.exists() && (left == null || left.length == 0)) {
            new java.io.File(srcRuns, ".folded-into").delete();   // an empty leftover behind an existing park: nothing to keep
            srcRuns.delete();
        }
        java.io.File srcProps = new java.io.File(workbenchRoot, "proposals.json");
        if (srcProps.isFile()) {
            java.io.File to = new java.io.File(dst, "proposals.json");
            if (to.exists()) {
                System.err.println("proposals migration: skipped (" + to + " exists; the legacy file stays at " + srcProps + ")");
            } else {
                try {
                    dst.mkdirs();
                    java.nio.file.Files.move(srcProps.toPath(), to.toPath());
                    System.err.println("proposals migration: proposals.json folded into project '" + projectName + "' (" + to + ")");
                } catch (Exception e) {
                    System.err.println("proposals migration failed (retried next boot): " + e);
                }
            }
        }
        if (runs != null) runs.evict(projectName);            // a store created before the fold would be stale
        if (agents != null) agents.evictProposals(projectName);
    }

    /* Pre-batch-2 rows (runs, breakpoints, proposals) carry only the target id of the boot
       that recorded them — reminted since, so no [target] filter can find them and the RUNTIME
       dock would show "no runs" over 28 recorded ones. When the project holds exactly ONE
       target-store member, every legacy row unambiguously belongs to it: stamp targetKey /
       targetName (marked identityStampedAtMigration) and, on proposals, the project. A project
       with several members leaves its rows unstamped (project-wide views still list them).
       Once per project per process; idempotent on disk. */
    private final java.util.Set<String> stampedProjects = new java.util.HashSet<>();
    /* the stamp flag is VERSIONED (b2-r5 H1): a row marked with this string was stamped by the
       decided single-entry rule and is a settled historical fact — it is NEVER un-stamped on a
       later boot just because a second artifact was opened (which flips the store-entry count and,
       under the old boolean flag, silently stripped every correct stamp). The old boolean `true`
       (the pre-r5 over-stamp) is the ONLY thing a later pass removes, and only on a file that names
       several legacy target ids. */
    private static final String STAMP_V1 = "single-entry";

    private void stampLegacyIdentityOnce(String projectName) {
        if (workbenchRoot == null || !legacyOpsAllowed() || !stampedProjects.add(projectName)) return;
        java.io.File dir = dirFor(projectName);
        String key = null, name = null;
        /* the legacy store was WORKSPACE-global and ids remint per boot: "the file names one id"
           says nothing about WHICH artifact. Only when the whole target store holds exactly one
           entry can the rows belong to nothing else (review r4 H1 — this machine holds two). */
        java.io.File[] entries = new java.io.File(workbenchRoot, "targets").listFiles(java.io.File::isDirectory);
        int storeEntries = entries == null ? 0 : entries.length;
        try {
            List<Map<String, Object>> members = readMembers(projectName);
            if (members.size() == 1 && storeEntries == 1) {
                Map<String, Object> m = members.get(0);
                key = m.get("key") == null ? null : String.valueOf(m.get("key"));
                Object n = m.get("name");
                if (n == null && m.get("path") != null) n = new java.io.File(String.valueOf(m.get("path"))).getName();
                name = n == null ? null : String.valueOf(n);
                /* the lone store entry must BE this member (b2-r5 H1): its directory name is the
                   key. A mismatch means the one entry is some other project's artifact and the
                   rows' identity is not this one — leave them unstamped. */
                if (key != null && !entries[0].getName().equals(key)) {
                    System.err.println("migration: the lone store entry '" + entries[0].getName()
                            + "' is not member key '" + key + "' — rows of '" + projectName + "' stay unstamped");
                    key = null; name = null;
                }
            }
        } catch (Exception e) {
            System.err.println("migration: members of '" + projectName + "' unreadable — rows stay unstamped: " + e);
        }
        if (key == null && storeEntries > 1)
            System.err.println("migration: the target store holds " + storeEntries + " entries — legacy rows of '" + projectName
                    + "' get no target identity (they list project-wide; re-add breakpoints on the open target)");
        stampRows(new java.io.File(new java.io.File(dir, "runs"), "index.json"), key, name, null);
        stampRows(new java.io.File(new java.io.File(dir, "runs"), "bps.json"), key, name, null);
        stampRows(new java.io.File(dir, "proposals.json"), key, name, projectName);
    }

    @SuppressWarnings("unchecked")
    private static void stampRows(java.io.File f, String key, String name, String project) {
        if (!f.isFile()) return;
        try {
            Object parsed = dev.workbench.registry.Json.parse(java.nio.file.Files.readString(f.toPath()));
            if (!(parsed instanceof List<?> rows)) return;
            /* the legacy store was WORKSPACE-global: a file whose rows name more than one
               target id holds several targets' history, and "the project has one member" says
               nothing about which rows are that member's (review b2-r1 H1 — 12 distinct ids in
               the real proposals.json). Stamp the identity only when the file names at most one;
               un-stamp what an earlier pass stamped on a many-target file. */
            /* count target ids over LEGACY rows ONLY — rows with no durable targetKey that are not
               already versioned-stamped. Post-batch-2 rows remint their target id every boot, so
               counting them would flip this decision on an unrelated reopen and strip good stamps
               (b2-r5 H1). */
            java.util.Set<String> named = new java.util.HashSet<>();
            for (Object o : rows)
                if (o instanceof Map<?, ?> m0 && m0.get("target") != null
                        && m0.get("targetKey") == null
                        && !STAMP_V1.equals(m0.get("identityStampedAtMigration")))
                    named.add(String.valueOf(m0.get("target")));
            boolean stampKey = key != null && named.size() == 1;   // exactly one legacy target; rows naming none are never stamped (r2 L3)
            /* a versioned stamp is permanent — the ONLY removal is the one-time cleanup of the
               pre-r5 over-stamp (boolean `true`) on a file that names several legacy ids (the
               review b2-r1 H1 bug: every row stamped to one key). Never keyed on key==null /
               store-entry count any more (b2-r5 H1: that stripped correct stamps on every reopen). */
            boolean cleanupOldOverstamp = named.size() > 1;
            List<Object> out = new ArrayList<>();
            int n = 0, un = 0;
            for (Object o : rows) {
                if (!(o instanceof Map<?, ?> m0)) { out.add(o); continue; }
                Map<String, Object> m = new LinkedHashMap<>((Map<String, Object>) m0);
                boolean changed = false;
                if (project != null && m.get("project") == null) { m.put("project", project); changed = true; }
                if (cleanupOldOverstamp && Boolean.TRUE.equals(m.get("identityStampedAtMigration"))) {
                    m.remove("targetKey"); m.remove("targetName"); m.remove("identityStampedAtMigration");
                    un++; changed = true;
                }
                if (stampKey && m.get("target") != null && m.get("targetKey") == null) {
                    m.put("targetKey", key);
                    if (name != null && m.get("targetName") == null) m.put("targetName", name);
                    m.put("identityStampedAtMigration", STAMP_V1);
                    changed = true;
                }
                if (changed) n++;
                out.add(m);
            }
            if (cleanupOldOverstamp)
                System.err.println("migration: " + f.getName() + " names " + named.size() + " legacy target ids"
                        + " — no identity stamp" + (un > 0 ? "; " + un + " pre-r5 over-stamp(s) removed" : ""));
            if (n == 0) return;
            dev.workbench.registry.SidecarIO.writeAtomic(f, dev.workbench.registry.Json.write(out), "migration");
            System.err.println("migration: " + n + " legacy row(s) in " + f.getName()
                    + (stampKey || project != null ? " stamped with" + (stampKey ? " target " + key : "") + (project != null ? " project '" + project + "'" : "")
                                                    : " rewritten (stamps removed)"));
        } catch (Exception e) {
            System.err.println("migration: could not stamp " + f + " — " + e);
        }
    }

    /** Boot wiring: migrate the legacy GLOBAL maps dir into p1's sidecar, then
        activate p1's store (seeded when empty). Projects created later swap
        stores on open/switch/close — a mapless project's map.list is honestly
        EMPTY (the global store made every project inherit every map, so the
        GUI's clear-on-switch path never fired — map-r4 verification A). */
    public synchronized void initMaps(Map<String, Object> seed) {
        Map<String, Object> p1 = projects.get("p1");
        /* empty boot (WORKBENCH_MOCK=0): no boot project exists, so there is
           nothing to fold legacy maps INTO and nothing to seed — the store
           stays unwired until the first project.open / target.open swaps one
           in (2026-09-07 empty-default) */
        if (p1 == null) return;
        migrateLegacyMaps(String.valueOf(p1.get("name")));
        swapMapsLocked(p1, seed);
    }

    /** Legacy layout migration, run at boot. Two sources fold into the boot
        project's NAME-keyed sidecar — resolved through dirFor's claim walk, so
        a foreign marker on projects/privacy-invite-flow deflects the fold to
        the hashed candidate instead of swapping two projects' maps (map-r7
        F2). ORDER MATTERS (map-r7 F1): the id-keyed intermediate (the user's
        r4/r5-era edits) folds FIRST so its copies win; the older pre-map-r4
        global dir folds second and same-named files skip as duplicates. Per-
        file, no-overwrite; each source is parked as <name>.migrated once
        processed (skipped files stay recoverable inside); a move FAILURE
        leaves the source in place for next boot. */
    private void migrateLegacyMaps(String bootName) {
        java.io.File bootBase = dirFor(bootName);
        java.io.File p1dir = new java.io.File(new java.io.File(workbenchRoot, "projects"), "p1");
        if (!p1dir.equals(bootBase))
            migrateSidecar(p1dir, bootBase, p1dir,
                    new java.io.File(p1dir.getParentFile(), "p1.migrated"));
        migrateSidecar(workbenchRoot, bootBase,
                new java.io.File(workbenchRoot, "maps"),
                new java.io.File(workbenchRoot, "maps.migrated"));
        /* an unparked p1 leftover (failed park) stays claimable by a project
           NAMED 'p1' — lock it to the boot project with a marker (map-r7 F9a) */
        if (!p1dir.equals(bootBase) && p1dir.isDirectory()
                && !new java.io.File(p1dir, ".project-name").isFile()) {
            try {
                java.nio.file.Files.writeString(
                        new java.io.File(p1dir, ".project-name").toPath(), bootName);
            } catch (Exception e) {
                System.err.println("map migration: could not lock " + p1dir + " — " + e);
            }
        }
    }

    private void migrateSidecar(java.io.File srcBase, java.io.File dstBase,
                                java.io.File parkDir, java.io.File parkTo) {
        java.io.File srcMaps = new java.io.File(srcBase, "maps");
        if (!srcMaps.isDirectory()) return;
        boolean failed = false;
        java.io.File srcTypes = new java.io.File(srcBase, "maptypes.json");
        java.io.File dstMaps = new java.io.File(dstBase, "maps");
        try {
            java.io.File[] old = srcMaps.listFiles((d, n) -> n.endsWith(".map.json"));
            if (old == null) {
                failed = true;
                System.err.println("map migration: cannot list " + srcMaps);
            } else {
                dstMaps.mkdirs();
                for (java.io.File f : old) {
                    java.io.File dst = new java.io.File(dstMaps, f.getName());
                    if (dst.exists()) {
                        System.err.println("map migration: " + f.getName()
                                + " skipped (" + dst + " exists)");
                        continue;
                    }
                    java.nio.file.Files.move(f.toPath(), dst.toPath());
                }
            }
            java.io.File dstTypes = new java.io.File(dstBase, "maptypes.json");
            if (srcTypes.isFile()) {
                if (dstTypes.isFile())
                    System.err.println("map migration: " + srcTypes
                            + " skipped (" + dstTypes + " exists)");
                else {
                    dstBase.mkdirs();
                    java.nio.file.Files.move(srcTypes.toPath(), dstTypes.toPath());
                }
            }
        } catch (Exception e) {
            failed = true;
            System.err.println("map migration failed for " + srcBase + " (retried next boot): " + e);
        }
        /* park the source so the check self-terminates — but never over a
           failed move (un-migrated maps would vanish into the parked dir), and
           never re-attempt a park whose destination already exists (it fails
           forever, one stderr line per boot — map-r8 #9) */
        if (failed || !parkDir.isDirectory() || parkTo.exists()) return;
        if (!parkDir.renameTo(parkTo)){
            System.err.println("map migration: could not park " + parkDir);
            return;
        }
        /* a skipped duplicate maptypes.json rides INTO the parked dir — the
           global source's types file is a SIBLING of the parked maps dir and
           would otherwise sit orphaned at the sidecar root forever (map-r8 #8) */
        if (srcTypes.isFile()){
            try {
                java.nio.file.Files.move(srcTypes.toPath(),
                        new java.io.File(parkTo, "maptypes.json").toPath());
            } catch (Exception e) {
                System.err.println("map migration: could not park " + srcTypes + " — " + e);
            }
        }
    }

    /** Device wiring (DEVICE_SPEC) — set by the server at boot. */
    public void setDevices(dev.workbench.device.DeviceManager d) { this.devices = d; }

    public dev.workbench.device.DeviceManager devices() {
        if (devices == null) throw new IllegalStateException("devices not wired");
        return devices;
    }

    /** Agents wiring (AGENTS_SPEC) — set by the server at boot. */
    public void setAgents(AgentManager a) { this.agents = a; }

    public AgentManager agents() {
        if (agents == null) throw new IllegalStateException("agents not wired");
        return agents;
    }

    /** Maps wiring (GRAPHS_SPEC) — set by the server at boot. */
    public void setMaps(MapStore m) { this.maps = m; }

    /** The ACTIVE project's store. An empty workspace (fresh boot, last project
        closed) gets an honest E_NOTFOUND naming what's missing — not a 500. */
    public MapStore maps() throws OpException {
        if (maps == null) throw OpException.notFound(
                "no project open — map ops need an active project",
                "open one via project.open, or target.open on an empty workspace");
        return maps;
    }

    private Presence presence;

    /** Co-presence wiring (MCP_SPEC §5) — set by the server at boot. */
    public void setPresence(Presence p) { this.presence = p; }

    public Presence presence() {
        if (presence == null) throw new IllegalStateException("presence not wired");
        return presence;
    }

    private LayoutStore layouts;

    /** Named-layouts wiring (WORKBENCH_SPEC §4 roadmap) — set by the server at boot. */
    public void setLayouts(LayoutStore l) { this.layouts = l; }

    private UiPrefs uiPrefs;
    public void setUiPrefs(UiPrefs p) { this.uiPrefs = p; }
    public UiPrefs uiPrefs() {
        if (uiPrefs == null) throw new IllegalStateException("ui prefs not wired");
        return uiPrefs;
    }

    /** target.recent [--limit]: the target store's entries by last use (WORKBENCH_SPEC §2.4
        zone 1, audit decision 18) — every on-disk project's members merged by store key, with
        the projects that hold each, whether the artifact is still in the store, and the live
        target id when it is open now. Last use = the newest mtime in the store entry (the jdb2
        is rewritten by analysis and save), else the membership's addedAt. Parked project dirs
        (.migrated / .forgotten-*) are skipped. */
    public List<Object> recentTargets(int limit) {   // NOT synchronized: file walks stay off the monitor (r4 L3); tidForStoreKey takes it briefly
        Map<String, Map<String, Object>> byKey = new LinkedHashMap<>();
        if (workbenchRoot == null) return new ArrayList<>();
        java.io.File base = new java.io.File(workbenchRoot, "projects");
        java.io.File[] dirs = base.isDirectory() ? base.listFiles(java.io.File::isDirectory) : null;
        if (dirs != null) for (java.io.File d : dirs) {
            String dn = d.getName();
            if (dn.contains(".migrated") || dn.contains(".forgotten-") || dn.contains(".corrupt-")) continue;
            java.io.File marker = new java.io.File(d, ".project-name");
            java.io.File members = new java.io.File(d, "targets.json");
            if (!marker.isFile() || !members.isFile()) continue;
            String pname;
            try { pname = java.nio.file.Files.readString(marker.toPath()).trim(); } catch (Exception e) { continue; }
            Object parsed;
            try { parsed = dev.workbench.registry.Json.parse(java.nio.file.Files.readString(members.toPath())); }
            catch (Exception e) { continue; }
            if (!(parsed instanceof Map<?, ?> pm) || !(pm.get("targets") instanceof List<?> ml)) continue;
            for (Object o : ml) {
                if (!(o instanceof Map<?, ?> m) || m.get("key") == null) continue;
                String key = String.valueOf(m.get("key"));
                Map<String, Object> row = byKey.computeIfAbsent(key, k -> new LinkedHashMap<>());
                row.putIfAbsent("key", key);
                if (m.get("name") != null) row.putIfAbsent("name", m.get("name"));
                if (m.get("storePath") != null) row.putIfAbsent("storePath", m.get("storePath"));
                if (m.get("engine") != null) row.putIfAbsent("engine", m.get("engine"));
                @SuppressWarnings("unchecked")
                List<String> projs = (List<String>) row.computeIfAbsent("projects", k -> new ArrayList<String>());
                if (!projs.contains(pname)) projs.add(pname);
                long added = 0;
                try { if (m.get("addedAt") != null) added = java.time.Instant.parse(String.valueOf(m.get("addedAt"))).toEpochMilli(); }
                catch (Exception ignored) {}
                row.put("lastUsed", Math.max(((Number) row.getOrDefault("lastUsed", 0L)).longValue(), added));
            }
        }
        for (Map<String, Object> row : byKey.values()) {
            long last = ((Number) row.getOrDefault("lastUsed", 0L)).longValue();
            /* presence and recency come from the STORE ENTRY (targets/<key>/), never from the
               path the artifact was opened from — that path may be a deleted download, and its
               folder's newest file is not this target's last use (review r3 H2) */
            java.io.File entry = new java.io.File(new java.io.File(workbenchRoot, "targets"), String.valueOf(row.get("key")));
            java.io.File[] fs = entry.isDirectory() ? entry.listFiles(java.io.File::isFile) : null;
            boolean present = false;
            java.io.File openable = null;
            if (fs != null) for (java.io.File x : fs) {
                String n = x.getName().toLowerCase(java.util.Locale.ROOT);
                if (n.endsWith(".jdb2") || n.endsWith(".apk") || n.endsWith(".dex") || n.endsWith(".so")) present = true;
                if (openable == null && (n.endsWith(".apk") || n.endsWith(".dex") || n.endsWith(".so"))) openable = x;
                last = Math.max(last, x.lastModified());
            }
            if (openable != null) {
                java.io.File jadxCodeData = new java.io.File(sidecarFor(openable), "jadx/code-data.json");
                if (jadxCodeData.isFile()) last = Math.max(last, jadxCodeData.lastModified());
            }
            String sp = row.get("storePath") == null ? null : String.valueOf(row.get("storePath"));
            if (sp == null || !new java.io.File(sp).isFile()) {
                java.io.File jdb2 = null;
                if (fs != null) for (java.io.File x : fs) if (x.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".jdb2")) { jdb2 = x; break; }
                if (openable != null) row.put("storePath", openable.getAbsolutePath());   // the store copy is what the click opens
                else if (jdb2 != null) row.put("storePath", jdb2.getAbsolutePath());       // a database-only entry opens as itself
                else present = false;                                                     // nothing openable: no click (r4 L2)
            }
            row.put("present", present);
            row.put("lastUsed", last);
            row.put("lastUsedIso", java.time.Instant.ofEpochMilli(last).toString());
            String rowEng = row.get("engine") == null ? null : String.valueOf(row.get("engine"));
            String tid = tidForStoreKey(String.valueOf(row.get("key")), rowEng);
            if (tid == null) tid = tidForStoreKey(String.valueOf(row.get("key")));
            if (tid != null) row.put("open", tid);
            row.putIfAbsent("engine", "JEB");
        }
        List<Map<String, Object>> rows = new ArrayList<>(byKey.values());
        rows.sort((a, b) -> Long.compare(((Number) b.get("lastUsed")).longValue(), ((Number) a.get("lastUsed")).longValue()));
        int cap = limit <= 0 ? 9 : Math.min(limit, 50);
        return new ArrayList<>(rows.subList(0, Math.min(rows.size(), cap)));
    }

    public LayoutStore layouts() {
        if (layouts == null) throw new IllegalStateException("layouts not wired");
        return layouts;
    }

    /** map.open: activate a map in the UI (agent→human handoff). The activation
        is PER-PROJECT — each project remembers its own open map. */
    public Map<String, Object> openMap(String id) throws OpException {
        /* existence check OUTSIDE the Workspace monitor: MapStore.addNode →
           normalizeSymbol → engineFor locks MapStore→Workspace, so the reverse
           order here was a lock-order inversion (opus map-r5 F14) */
        MapStore s = maps();
        s.summary(id);                                       // existence check (E_NOTFOUND)
        String pname = null;
        synchronized (this) {
            /* a project switch mid-check swapped the store — writing the OLD
               store's map id into the NEW project's activeMap would re-admit
               the phantom F2 removed. Degrade honestly: skip the put — the
               open simply doesn't take, and the map stays closed (map-r6 #5;
               the frontend's map.list[0] fallback that used to cover this is
               gone — map-lifecycle step 1) */
            if (s == maps) {
                Map<String, Object> p = projects.get(activeProject);
                if (p != null) {
                    p.put("activeMap", id);
                    pname = String.valueOf(p.get("name"));
                }
            }
        }
        /* OUTSIDE the monitor: session.json is pure filesystem and every op
           queues behind this lock (same discipline as the state() read) */
        if (pname != null) persistSession(pname, id);
        return state();
    }

    /** map.close {id?}: clear the project's activeMap (GRAPHS_SPEC §5.2).
        Close is ACTIVATION, not existence — it touches no map file, and the
        map keeps its place in map.list and the projmenu inventory.
        IDEMPOTENT: closing with nothing open is a success, not E_NOTFOUND, so
        a client can close blind. The bare form closes whatever is active; with
        {id} it only closes when that id is the one open (a stale close from a
        client that has moved on must not shut the map the user just opened). */
    public Map<String, Object> closeMap(String id) throws OpException {
        String pname = null;
        synchronized (this) {
            Map<String, Object> p = projects.get(activeProject);
            Object cur = p == null ? null : p.get("activeMap");
            if (cur != null && (id == null || id.isBlank() || id.equals(String.valueOf(cur)))) {
                p.remove("activeMap");
                pname = String.valueOf(p.get("name"));
            }
        }
        /* persist the CLOSE too, off-monitor — otherwise the next boot restores
           the map the user just closed (map-lifecycle §2) */
        if (pname != null) persistSession(pname, null);
        return state();
    }

    /** map.delete {id, confirm:true}: remove the map from the store and park
        its file (MapStore.delete). Deleting the OPEN map implies close — an
        activeMap pointing at a parked file is exactly the phantom the store
        swap exists to prevent. Returns {id, parked} — the parked path IS the
        recovery story, so it goes back to the caller.
        HUMAN-ONLY by omission: map.delete is deliberately absent from
        AgentLoop.TOOL_TO_OP, so no agent tool maps to it. */
    public Map<String, Object> deleteMap(String id) throws OpException {
        /* resolve the store ONCE and act on that instance — a re-read of
           maps() after a mid-flight project switch would park the file against
           the wrong project's sidecar (openMap's guard, one op over). Same
           documented lock order: MapStore → Workspace, never the reverse. */
        MapStore s = maps();
        String parked = s.delete(id);
        String pname = null;
        synchronized (this) {
            Map<String, Object> p = projects.get(activeProject);
            if (s == maps && p != null && id.equals(String.valueOf(p.get("activeMap")))) {
                p.remove("activeMap");
                pname = String.valueOf(p.get("name"));
            }
        }
        if (pname != null) persistSession(pname, null);      // off-monitor, as ever
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("parked", parked);
        return out;
    }

    public RunsManager runs() {
        if (runs == null) throw new IllegalStateException("runs not wired");
        return runs;
    }

    // ------------------------------------------------------------ history (rename/comment undo log)

    /**
     * Per-target undoable-op log (MCP_SPEC §4.6). Engine-neutral: the rename/comment ops
     * record every applied write here with its actor; undo = rename back to the recorded
     * original (comment: restore prior text), redo re-applies. Persisted to the target's
     * sidecar (<artifact>.workbench/history.json) when the artifact is a real file —
     * mock targets keep an in-memory log.
     */
    private final Map<String, List<Map<String, Object>>> history = new LinkedHashMap<>();
    private final Map<String, Integer> historySeq = new LinkedHashMap<>();
    /* targets whose history.json load hit a TRANSIENT read failure (fix-1-r3
       #1): the on-disk undo log may be healthy — the first persistHistory
       parks it aside as evidence instead of silently overwriting unseen
       entries (and restarting the -h ids it never saw) */
    private final java.util.Set<String> degradedHistory = new java.util.HashSet<>();
    /* projects whose session.json read failed transiently (fix-1-r6 MED-2): the
       on-disk record may be healthy — the next persistSession for that project
       parks it aside as evidence before overwriting. CHM set (fix-1-r7 F3):
       readSession runs UNDER the Workspace monitor (swapMapsLocked) while
       persistSession runs deliberately OFF it — a plain HashSet's add could be
       invisible to the guard, silently re-opening the hole MED-2 closed. */
    private final java.util.Set<String> sessionDegraded = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public String resolveTargetIdOrActive(String targetParam) throws OpException {
        if (targetParam == null || targetParam.isBlank()) {
            if (activeTarget == null) throw OpException.notFound("no active target", "");
            return activeTarget;
        }
        return resolveTargetId(targetParam);
    }

    /** Record an applied rename/comment. Returns the entry (id/ts/target/undone stamped). */
    public synchronized Map<String, Object> recordHistory(String targetParam, Map<String, Object> entry)
            throws OpException {
        String tid = resolveTargetIdOrActive(targetParam);
        int n = historySeq.merge(tid, 1, Integer::sum);
        entry.put("id", tid + "-h" + n);
        entry.put("ts", java.time.Instant.now().toString());
        entry.put("target", tid);
        entry.put("undone", false);
        entry.put("journalVersion", 2);
        // Canonical identity keeps comments and argument maps replayable after owner renames.
        if (entry.get("originalSymbol") == null && !"local".equals(entry.get("kind"))) {
            try { entry.put("originalSymbol", stableSymbol(engineFor(tid).resolve(String.valueOf(entry.get("addr"))), String.valueOf(entry.get("addr")))); }
            catch (OpException ignored) {} // legacy/unsupported engines retain their address-based record
        }
        historyFor(tid).add(entry);
        try { requireHistoryPersisted(tid); }
        catch (OpException failure) {
            failure.extra.put("applied", true);
            failure.extra.put("historyId", entry.get("id"));
            failure.extra.put("recovery", "The edit remains in memory. Keep the target open and retry target.save after correcting storage.");
            throw failure;
        }
        java.util.function.BiConsumer<String, Map<String, Object>> hl = historyListener;
        if (hl != null) try { hl.accept(tid, new LinkedHashMap<>(entry)); } catch (Throwable t) { Log.warn("history listener", String.valueOf(t), null); }
        if ("rename".equals(entry.get("op"))) {
            String kind = String.valueOf(entry.get("kind"));
            if ("class".equals(kind) || "method".equals(kind))
                entry.put("followed", followRename(tid, kind, String.valueOf(entry.get("addr")), String.valueOf(entry.get("newAddr")), String.valueOf(entry.get("actor"))));
            else if ("local".equals(kind) && entry.get("auto") == null)
                dropLocalBinding(tid, String.valueOf(entry.get("addr")), String.valueOf(entry.get("oldName")));
        }
        return entry;
    }

    /* ---------------------------------------------------------------- provenance in an agent's reads
       Agent writes apply at once (2026-09-18), so an agent reads its own — and other agents' —
       names back. To keep a guess from passing as ground truth, every read an AGENT makes marks
       the agent-assigned names it contains: decompile/outline/smali answers get
       agentNames:[{name, was, addr, by, confidence?}]; row-shaped answers (xrefs, callers, callees,
       search, string xrefs, symbol search/tree/resolve) get was/namedBy on a row whose address is
       agent-named, classWas when only its class is. Humans' reads are untouched. */
    private static final java.util.Set<String> PROVENANCE_OPS = java.util.Set.of(
            "code.decompile", "code.outline", "code.smali", "xref.to", "xref.from", "callers", "callees",
            "symbol.search", "symbol.tree", "symbol.resolve", "search", "string.xrefs", "locals.list", "code.params");

    /* ---------------------------------------------------------------- locals named after their types
       locals.auto names a method's generated locals after their declared types (DownloadHelper v5
       → downloadHelper). Each such naming is a BINDING {method, local, type}: when the type's class
       is renamed later (by anyone — a human, an agent, an undo), the local follows
       (DownloadThreadRunner → downloadThreadRunner). A local renamed by hand, or undone, leaves the
       binding. Persisted beside history.json as local-bindings.json. */
    private final Map<String, List<Map<String, Object>>> localBindings = new java.util.HashMap<>();

    private File localBindingsFile(String tid) {
        File h = historyFile(tid);
        return h == null ? null : new File(h.getParentFile(), "local-bindings.json");
    }
    private List<Map<String, Object>> bindingsFor(String tid) {
        List<Map<String, Object>> b = localBindings.get(tid);
        if (b == null) {
            b = new ArrayList<>();
            File f = localBindingsFile(tid);
            if (f != null && f.isFile()) {
                try {
                    Object parsed = dev.workbench.registry.Json.parse(Files.readString(f.toPath()));
                    if (parsed instanceof List<?> l) for (Object o : l) if (o instanceof Map<?, ?> m) {
                        @SuppressWarnings("unchecked") Map<String, Object> e = (Map<String, Object>) m;
                        b.add(new LinkedHashMap<>(e));
                    }
                } catch (Exception ignored) {}
            }
            localBindings.put(tid, b);
        }
        return b;
    }
    private void persistBindings(String tid) {
        File f = localBindingsFile(tid);
        if (f != null) dev.workbench.registry.SidecarIO.writeAtomic(f, dev.workbench.registry.Json.write(bindingsFor(tid)), "local-bindings");
    }

    /** A name put back by undo or redo: a name the engine's rename rule admits (a Java identifier — the
     *  rule that admitted it when it was typed, keywords included) goes through rename like any other;
     *  a name that rule refuses can only be the dex's own (an obfuscated "1cq") and goes through the
     *  validated restoration — the engine checks it IS the original before touching anything. */
    private static void applyName(Engine e, String addr, String name) throws OpException {
        if (name != null && name.matches("[A-Za-z_$][A-Za-z0-9_$]*")) e.rename(addr, name, "human");
        else e.restoreOriginalName(addr, name, "human");
    }

    /** The engine changes that reverse (undo) or replay (redo) a `parameters` History entry: a name an
     *  analyst had set before is set back, a name the engine had chosen is restored by clearing. */
    private static List<Map<String, Object>> parameterChanges(Map<String, Object> entry, boolean reverse) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!(entry.get("changes") instanceof List<?> l)) return out;
        for (Object o : l) {
            if (!(o instanceof Map<?, ?> ch)) continue;
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("index", ch.get("index"));
            boolean cleared = Boolean.TRUE.equals(ch.get("cleared"));
            if (reverse) {
                if (cleared || "analyst".equals(ch.get("oldSource"))) c.put("name", ch.get("oldName"));
                else c.put("clear", true);
            } else {
                if (cleared) c.put("clear", true);
                else c.put("name", ch.get("newName"));
            }
            out.add(c);
        }
        return out;
    }

    /** The analyst's explicit parameter mappings on a method, applied WHOLE through the engine's own
     *  parameter binding and recorded as ONE History entry (kind parameters) — the record of the
     *  decision with its provenance: the source of the names (the dex's MethodParameters annotation or
     *  the analyst's own), the annotation entry each name came from, old and new names, the actor. An
     *  accepted mapping is the analyst's choice, not a compiler-verified correspondence, and the
     *  entry says so. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> renameParameters(String targetParam, String methodSymbol, List<Map<String, Object>> changes, String source, String actor) throws OpException {
        return renameParameters(targetParam, methodSymbol, changes, source, actor, null);
    }

    /** expectCurrent (nullable): what the analyst SAW when choosing — [{index, name}]; an argument whose
     *  current name differs (another edit landed since the preview) refuses the whole mapping, so a stale
     *  selection never renames a different argument. */
    public Map<String,Object> renameParameters(String targetParam, String methodSymbol, List<Map<String, Object>> changes, String source, String actor, List<Map<String, Object>> expectCurrent) throws OpException {
        String tid;
        synchronized (this) { tid = resolveTargetIdOrActive(targetParam); }
        return withTargetWrite(tid, () -> {
            requireHistoryPersisted(tid);
            try { return renameParametersLocked(tid, methodSymbol, changes, source, actor, expectCurrent); }
            finally { rebuildRenameClaims(tid); }
        });
    }

    private synchronized Map<String, Object> renameParametersLocked(String targetParam, String methodSymbol, List<Map<String, Object>> changes, String source, String actor, List<Map<String, Object>> expectCurrent) throws OpException {
        String tid = resolveTargetIdOrActive(targetParam);
        Engine e = engineFor(tid);
        Map<String, Object> meta = e.methodParameters(methodSymbol);
        List<?> ps = meta.get("parameters") instanceof List<?> pl ? pl : List.of();
        if (expectCurrent != null && !expectCurrent.isEmpty()) {
            for (Map<String, Object> ex : expectCurrent) {
                int ix = ex.get("index") instanceof Number n ? n.intValue() : -1;
                if (ix < 0 || ix >= ps.size()) throw OpException.busy("the method's arguments changed since the preview (argument " + ex.get("index") + " is gone) — look again before applying", "symbol=" + methodSymbol);
                Object now = ((Map<?, ?>) ps.get(ix)).get("name");
                if (ex.get("name") != null && !String.valueOf(ex.get("name")).equals(String.valueOf(now)))
                    throw OpException.busy("argument " + ix + " is now named '" + now + "', not '" + ex.get("name") + "' as previewed — the mapping was not applied; look again", "symbol=" + methodSymbol);
            }
        }
        /* the provenance a client claims is checked against the dex: annotation entry k must carry this very
           name, or the change is the analyst's own (fix-review 2026-10-08 M6) */
        List<?> annNames = meta.get("annotation") instanceof Map<?, ?> am && am.get("names") instanceof List<?> nl ? nl : List.of();
        for (Map<String, Object> c : changes) {
            Object ai = c.get("annotationIndex");
            if (ai == null) continue;
            int k = ai instanceof Number n ? n.intValue() : ai instanceof String s && s.matches("\\d+") ? Integer.parseInt(s) : -1;
            if (k < 0 || k >= annNames.size() || c.get("name") == null || !String.valueOf(c.get("name")).equals(String.valueOf(annNames.get(k)))) c.remove("annotationIndex");
        }
        Map<String, Object> res = e.renameParameters(methodSymbol, changes, actor);
        if (!Boolean.TRUE.equals(res.get("applied"))) return res;
        List<Object> applied = new ArrayList<>();
        List<String> olds = new ArrayList<>(), news = new ArrayList<>();
        int fromAnnotation = 0;
        for (Object o : (List<?>) res.get("changes")) {
            Map<String, Object> ch = new LinkedHashMap<>((Map<String, Object>) o);
            for (Map<String, Object> c : changes)
                if (c.get("index") != null && String.valueOf(c.get("index")).equals(String.valueOf(ch.get("index"))) && c.get("annotationIndex") != null) ch.put("annotationIndex", c.get("annotationIndex"));
            if (ch.get("annotationIndex") != null) fromAnnotation++;
            ch.put("from", ch.get("annotationIndex") != null ? "annotation entry " + ch.get("annotationIndex") : Boolean.TRUE.equals(ch.get("cleared")) ? "engine (cleared)" : "analyst");
            olds.add(String.valueOf(ch.get("oldName")));
            news.add(Boolean.TRUE.equals(ch.get("cleared")) ? "(engine name)" : String.valueOf(ch.get("newName")));
            applied.add(ch);
        }
        Map<String, Object> h = new LinkedHashMap<>();
        h.put("op", "rename");
        h.put("kind", "parameters");
        h.put("actor", actor == null ? "human" : actor);
        h.put("addr", res.get("addr"));
        h.put("newAddr", res.get("newAddr"));
        h.put("original_method", meta.get("original_method"));   // the dex's own identity: the mapping follows the method through renames
        h.put("dex", meta.get("dex"));
        h.put("oldName", String.join(", ", olds));
        h.put("newName", String.join(", ", news));
        h.put("changes", applied);
        h.put("source", fromAnnotation == 0 ? "analyst"
                : fromAnnotation == applied.size() ? (source == null || source.isBlank() ? "dex_method_parameters_annotation" : source)
                : "mixed — annotation entries and the analyst's own names (changes[].from)");
        h.put("argumentMapping", "analyst-selected — not compiler-verified");
        h.put("owner", ownerOfAddr(String.valueOf(res.get("addr"))));
        recordHistory(tid, h);
        res.put("historyId", h.get("id"));
        res.put("mappings", parameterMappings(tid, String.valueOf(res.get("addr")), String.valueOf(meta.get("original_method"))));
        return res;
    }

    /** What a bulk mapping WOULD do — every entry with the destination argument's position, current
     *  name and type beside the proposed name, the reasons the engine would refuse, whether the whole
     *  can be applied — and nothing changes. */
    public synchronized Map<String, Object> previewParameterMapping(String targetParam, String methodSymbol, List<Map<String, Object>> changes) throws OpException {
        String tid = resolveTargetIdOrActive(targetParam);
        Engine e = engineFor(tid);
        Map<String, Object> meta = e.methodParameters(methodSymbol);
        List<?> params = meta.get("parameters") instanceof List<?> pl ? pl : List.of();
        List<Object> rows = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        Map<String, Integer> proposer = new java.util.HashMap<>();   // a name → the first position it is proposed for
        java.util.Set<Integer> idxs = new java.util.HashSet<>();
        java.util.Set<String> locals = new java.util.HashSet<>();   // the body's own names: a mapping onto one is a collision the engine would refuse
        if (meta.get("locals") instanceof List<?> ll) for (Object o : ll) if (o != null) locals.add(String.valueOf(o));
        int effective = 0;
        for (Map<String, Object> c : changes) {
            Map<String, Object> row = new LinkedHashMap<>(c);
            int ix = c.get("index") instanceof Number n ? n.intValue() : c.get("index") instanceof String s && s.matches("\\d+") ? Integer.parseInt(s) : -1;
            if (ix < 0 || ix >= params.size()) { row.put("problem", "no formal argument at position " + c.get("index") + " (the method has " + params.size() + ")"); problems.add(String.valueOf(row.get("problem"))); rows.add(row); continue; }
            Map<?, ?> p = (Map<?, ?>) params.get(ix);
            row.put("position", ix); row.put("currentName", p.get("name")); row.put("currentNameSource", p.get("nameSource")); row.put("type", p.get("type"));
            Object nm = c.get("name");
            if (nm == null || String.valueOf(nm).isEmpty()) { row.put("noop", "a null/empty entry — the argument keeps its name"); rows.add(row); continue; }
            String name = String.valueOf(nm);
            if (!Engine.usableName(name)) row.put("problem", "'" + name + "' is not a usable Java identifier" + (Engine.JAVA_KEYWORDS.contains(name) ? " (a keyword)" : ""));
            else if (!idxs.add(ix)) row.put("problem", "argument " + ix + " is named twice in this list");
            else if (proposer.containsKey(name)) { row.put("problem", "'" + name + "' is also proposed for argument " + proposer.get(name)); row.put("collidesWith", proposer.get(name)); }
            else if (name.equals(p.get("name")) && "analyst".equals(p.get("nameSource"))) { proposer.put(name, ix); row.put("noop", "already your name — not a change, not recorded"); }
            else if (name.equals(p.get("name"))) { proposer.put(name, ix); row.put("adopts", "the same name as JEB's pick — adopts it as your mapping (recorded as yours; undo gives it back to the engine)"); }   // never gate: an explicit acceptance of the engine's unverified pick is a decision worth recording
            else {
                proposer.put(name, ix);
                for (int i = 0; i < params.size(); i++) {
                    Map<?, ?> q = (Map<?, ?>) params.get(i);
                    boolean mapped = false;
                    for (Map<String, Object> c2 : changes) if (c2.get("index") != null && String.valueOf(c2.get("index")).equals(String.valueOf(i)) && c2.get("name") != null && !String.valueOf(c2.get("name")).isEmpty()) mapped = true;
                    if (i != ix && !mapped && name.equals(q.get("name"))) { row.put("problem", "'" + name + "' is already the name of argument " + i + " (" + shortType(q.get("type")) + ") — swap them, or choose another name"); row.put("collidesWith", i); }
                }
                if (row.get("problem") == null && locals.contains(name)) row.put("problem", "'" + name + "' is the name of a local in this method — rename the local first, or choose another name");
                if ("analyst".equals(p.get("nameSource")) && row.get("problem") == null) row.put("overwrites", "argument " + ix + " already carries your name '" + p.get("name") + "' — applying replaces it (undo restores it)");
            }
            if (row.get("problem") != null) problems.add(String.valueOf(row.get("problem")));
            else if (row.get("noop") == null) effective++;
            rows.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("method", meta.get("method"));
        out.put("original_method", meta.get("original_method"));
        out.put("rows", rows);
        out.put("problems", problems);
        out.put("effective", effective);
        out.put("applicable", problems.isEmpty() && effective > 0);
        if (problems.isEmpty() && effective == 0) out.put("why", changes.isEmpty() ? "nothing chosen — every argument keeps its name" : "nothing to change — every argument already has the proposed name");
        out.put("argumentMapping", "unverified — a positional alignment of the annotation to the current arguments is the analyst's assumption, not a verified correspondence (the counts matching proves nothing)");
        out.put("note", "nothing was applied — param.rename.bulk without preview applies exactly these rows, atomically, as one undoable entry");
        return out;
    }

    /** The analyst's parameter mappings recorded on a method: the History entries of kind parameters
     *  (not undone), with their provenance. */
    public synchronized List<Object> parameterMappings(String tid, String methodAddr, String originalMethod) {
        List<Object> out = new ArrayList<>();
        if (tid == null || methodAddr == null) return out;
        for (Map<String, Object> e : historyFor(tid)) {
            if (!"rename".equals(e.get("op")) || !"parameters".equals(e.get("kind")) || Boolean.TRUE.equals(e.get("undone"))) continue;
            boolean byOriginal = originalMethod != null && !"null".equals(originalMethod) && originalMethod.equals(String.valueOf(e.get("original_method")));   // the dex's identity survives a rename of the method or its class
            if (!byOriginal && !methodAddr.equals(String.valueOf(e.get("addr"))) && !methodAddr.equals(String.valueOf(e.get("newAddr")))) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            for (String k : List.of("id", "actor", "ts", "source", "changes", "argumentMapping", "original_method")) if (e.get(k) != null) row.put(k, e.get(k));
            out.add(row);
        }
        return out;
    }

    /** A dex type signature as a dialog shows it: the simple class name, the primitive's keyword. */
    private static String shortType(Object t) {
        String s = t == null ? "?" : String.valueOf(t);
        if (s.startsWith("L") && s.endsWith(";")) return s.substring(1, s.length() - 1).replaceAll("^.*/", "").replace('$', '.');
        return switch (s) { case "I" -> "int"; case "J" -> "long"; case "Z" -> "boolean"; case "B" -> "byte"; case "S" -> "short"; case "C" -> "char"; case "F" -> "float"; case "D" -> "double"; case "V" -> "void"; default -> s; };
    }

    /** locals.auto: name the generated locals of a method after their types, record one History
     *  entry per local (kind local, undoable) and a binding per local so a later class rename is
     *  followed. Returns the engine's {addr, renames, skipped} plus historyIds. */
    public Map<String,Object> autoNameLocals(String targetParam, String methodSymbol, boolean all, String actor) throws OpException {
        String tid;
        synchronized (this) { tid = resolveTargetIdOrActive(targetParam); }
        return withTargetWrite(tid, () -> {
            requireHistoryPersisted(tid);
            try { return autoNameLocalsLocked(tid, methodSymbol, all, actor); }
            finally { rebuildRenameClaims(tid); }
        });
    }

    private synchronized Map<String, Object> autoNameLocalsLocked(String targetParam, String methodSymbol, boolean all, String actor) throws OpException {
        String tid = resolveTargetIdOrActive(targetParam);
        Engine e = engineFor(tid);
        // Preflight the entire affected set before the engine's bulk mutation. A target
        // gate prevents a race, but it does not itself stop another actor overwriting a name.
        Map<String,Map<String,Object>> identities = new LinkedHashMap<>();
        for (Object value : e.locals(methodSymbol)) {
            if (!(value instanceof Map<?,?> local)) continue;
            String current = String.valueOf(local.get("name"));
            if ("this".equals(current) || (!all && !current.matches("(v|arg|p|var|local)\\d+(_\\d+)?"))) continue;
            String type = local.get("type") == null ? null : String.valueOf(local.get("type"));
            String proposed = dev.workbench.engine.TypeNames.nameForType(type);
            if (proposed == null || proposed.equals(current)) continue;
            Map<String,Object> identity = e.resolveLocal(methodSymbol, current);
            renameCoordinator.check(tid, String.valueOf(identity.get("originalSymbol")), current, actor, null);
            identities.put(current, identity);
        }
        Map<String, Object> res = e.autoNameLocals(methodSymbol, all, actor);
        // Every engine-applied local gets a claim BEFORE the first journal flush can fail.
        for (Object value : (List<?>)res.get("renames")) {
            Map<?,?> rename = (Map<?,?>)value;
            Map<String,Object> identity = identities.get(String.valueOf(rename.get("local")));
            renameCoordinator.renamed(tid, String.valueOf(identity.get("originalSymbol")), String.valueOf(rename.get("name")), actor, null);
        }
        OpException persistenceFailure = null;
        List<Object> ids = new ArrayList<>();
        List<Map<String, Object>> bindings = bindingsFor(tid);
        for (Object o : (List<?>) res.get("renames")) {
            Map<?, ?> rn = (Map<?, ?>) o;
            Map<String, Object> h = new LinkedHashMap<>();
            h.put("op", "rename");
            h.put("kind", "local");
            h.put("actor", actor == null ? "human" : actor);
            h.put("addr", res.get("addr"));
            h.put("newAddr", res.get("addr"));
            h.put("oldName", rn.get("local"));
            h.put("newName", rn.get("name"));
            h.put("owner", ownerOfAddr(String.valueOf(res.get("addr"))));
            h.put("auto", "type");
            Map<String,Object> identity = identities.get(String.valueOf(rn.get("local")));
            h.put("originalSymbol", identity.get("originalSymbol"));
            h.put("originalMethod", identity.get("originalMethod"));
            try { recordHistory(tid, h); }
            catch (OpException failure) { if (persistenceFailure == null) persistenceFailure = failure; }
            finally { renameCoordinator.renamed(tid, String.valueOf(identity.get("originalSymbol")),
                    String.valueOf(rn.get("name")), actor, h.get("id") == null ? null : String.valueOf(h.get("id"))); }
            ids.add(h.get("id"));
            // one binding per (method, local); a re-run replaces
            bindings.removeIf(b -> String.valueOf(res.get("addr")).equals(b.get("method")) && String.valueOf(rn.get("name")).equals(b.get("local")));
            if (rn.get("type") != null && String.valueOf(rn.get("type")).startsWith("L")) {
                Map<String, Object> b = new LinkedHashMap<>();
                b.put("method", res.get("addr"));
                b.put("local", rn.get("name"));
                b.put("type", rn.get("type"));
                bindings.add(b);
            } else if (rn.get("type") != null && String.valueOf(rn.get("type")).startsWith("[L")) {
                Map<String, Object> b = new LinkedHashMap<>();
                b.put("method", res.get("addr"));
                b.put("local", rn.get("name"));
                b.put("type", String.valueOf(rn.get("type")).substring(1));
                b.put("array", true);
                bindings.add(b);
            }
        }
        persistBindings(tid);
        res.put("historyIds", ids);
        res.put("bindings", bindings.stream().filter(b -> String.valueOf(res.get("addr")).equals(b.get("method"))).count());
        if (persistenceFailure != null) {
            persistenceFailure.extra.put("historyIds", ids);
            persistenceFailure.extra.put("appliedRenames", res.get("renames"));
            throw persistenceFailure;
        }
        return res;
    }

    private static String ownerOfAddr(String addr) {
        int i = addr.indexOf("->");
        String cls = i >= 0 ? addr.substring(0, i) : addr;
        return cls.startsWith("L") && cls.endsWith(";") ? cls.substring(1, cls.length() - 1).replace('/', '.').replace('$', '.') : cls;
    }

    /** A local renamed by hand (or undone) leaves its binding: the human's word wins over the type. */
    public synchronized void dropLocalBinding(String tid, String method, String local) {
        List<Map<String, Object>> b = bindingsFor(tid);
        if (b.removeIf(x -> method.equals(x.get("method")) && local.equals(x.get("local")))) persistBindings(tid);
    }

    /** Follow type-bound local names after a class rename. Conflicting locals stay unchanged
     * and are reported in the parent history entry; applied local writes get stable claims
     * and recovery records. A failed journal write is returned to the caller explicitly. */
    public synchronized List<Object> followRename(String tid, String kind, String oldAddr, String newAddr, String actor) throws OpException {
        List<Object> followed = new ArrayList<>();
        if (oldAddr == null || newAddr == null || oldAddr.equals(newAddr)) return followed;
        List<Map<String, Object>> bindings = bindingsFor(tid);
        boolean changed = false;
        OpException persistenceFailure = null;
        if ("class".equals(kind)) {
            for (Map<String, Object> binding : bindings) {
                String method = String.valueOf(binding.get("method"));
                if (method.contains(oldAddr)) { binding.put("method", method.replace(oldAddr, newAddr)); changed = true; }
                if (oldAddr.equals(binding.get("type"))) { binding.put("type", newAddr); changed = true; }
            }
            Engine engine = engineFor(tid);
            String base = dev.workbench.engine.TypeNames.nameForType(newAddr);
            for (Map<String, Object> binding : new ArrayList<>(bindings)) {
                if (!newAddr.equals(binding.get("type")) || base == null) continue;
                String method = String.valueOf(binding.get("method")), local = String.valueOf(binding.get("local"));
                String desired = Boolean.TRUE.equals(binding.get("array")) ? (base.endsWith("s") ? base + "Array" : base + "s") : base;
                if (desired.equals(local)) continue;
                Map<String,Object> identity;
                try {
                    identity = engine.resolveLocal(method, local);
                    renameCoordinator.check(tid, String.valueOf(identity.get("originalSymbol")), String.valueOf(identity.get("name")), actor, null);
                } catch (OpException conflict) {
                    followed.add(Map.of("method", method, "local", local, "status", "skipped", "errorDetails", conflict.toMap()));
                    continue;
                }
                String picked = null;
                OpException renameFailure = null;
                for (int n = 1; n <= 20 && picked == null; n++) {
                    String candidate = n == 1 ? desired : desired + "_" + n;
                    if (candidate.equals(local)) { picked = candidate; break; }
                    try { engine.renameLocal(method, local, candidate, actor); picked = candidate; }
                    catch (OpException failure) { renameFailure = failure; if (!"E_BUSY".equals(failure.code)) break; }
                }
                if (picked == null) {
                    Map<String,Object> skipped = new LinkedHashMap<>();
                    skipped.put("method", method); skipped.put("local", local); skipped.put("status", "skipped");
                    if (renameFailure != null) skipped.put("errorDetails", renameFailure.toMap());
                    followed.add(skipped); continue;
                }
                if (picked.equals(local)) continue;
                String followActor = (actor == null ? "human" : actor) + " (follows class rename)";
                String original = String.valueOf(identity.get("originalSymbol"));
                renameCoordinator.renamed(tid, original, picked, followActor, null);
                binding.put("local", picked); changed = true;
                Map<String, Object> historyEntry = new LinkedHashMap<>();
                historyEntry.put("op", "rename"); historyEntry.put("kind", "local");
                historyEntry.put("actor", followActor); historyEntry.put("addr", method); historyEntry.put("newAddr", method);
                historyEntry.put("originalSymbol", original); historyEntry.put("originalMethod", identity.get("originalMethod"));
                historyEntry.put("oldName", local); historyEntry.put("newName", picked);
                historyEntry.put("owner", ownerOfAddr(method)); historyEntry.put("auto", "type");
                try { recordHistory(tid, historyEntry); }
                catch (OpException failure) { if (persistenceFailure == null) persistenceFailure = failure; }
                finally { renameCoordinator.renamed(tid, original, picked, followActor,
                        historyEntry.get("id") == null ? null : String.valueOf(historyEntry.get("id"))); }
                followed.add(Map.of("method", method, "local", local, "name", picked, "historyId", historyEntry.get("id")));
            }
        } else if ("method".equals(kind)) {
            for (Map<String, Object> binding : bindings)
                if (oldAddr.equals(binding.get("method"))) { binding.put("method", newAddr); changed = true; }
        }
        if (changed) persistBindings(tid);
        if (persistenceFailure != null) {
            persistenceFailure.extra.put("followed", followed);
            throw persistenceFailure;
        }
        return followed;
    }

    /** The comments agents wrote on a target and did not lose to an undo, newest per address:
     *  [{addr, text, by, confidence?}] — the code view marks a `//` line carrying one of these
     *  texts as an agent's and wraps it. */
    public synchronized List<Object> agentComments(String targetParam) {
        List<Object> out = new ArrayList<>();
        try {
            String tid = resolveTargetIdOrActive(targetParam);
            Map<String, Map<String, Object>> byAddr = new LinkedHashMap<>();
            for (Map<String, Object> e : historyFor(tid)) {
                if (!"comment".equals(e.get("op"))) continue;
                String addr = String.valueOf(e.get("newAddr") != null ? e.get("newAddr") : e.get("addr"));
                if (Boolean.TRUE.equals(e.get("undone"))) { byAddr.remove(addr); continue; }
                String actor = String.valueOf(e.get("actor"));
                if (!actor.startsWith("agent:")) { byAddr.remove(addr); continue; }   // a human's later comment replaced it
                String text = e.get("newText") == null ? "" : String.valueOf(e.get("newText"));
                if (text.isBlank()) { byAddr.remove(addr); continue; }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("addr", addr);
                row.put("text", text);
                row.put("by", actor.replaceFirst("^agent:", "").replaceFirst(" (auto-applied|accepted by human)$", ""));
                row.put("historyId", e.get("id"));      // the view deletes one comment by undoing its entry
                if (e.get("confidence") != null) row.put("confidence", e.get("confidence"));
                byAddr.put(addr, row);
            }
            out.addAll(byAddr.values());
        } catch (Exception ignored) {}
        return out;
    }

    private record NameLedgers(Map<String, Map<String, Object>> agents,
                               Map<String, Map<String, Object>> humans) {}

    /** Current attribution, keyed by original identity before display addresses are attached.
     * History may predate owner renames or omit an SDK-propagated method rename. Such a row
     * must not label an obsolete name as current merely because that word appears in code. */
    private NameLedgers currentNameLedgers(String tid, String resultText, Map<String,Object> params, Object data) {
        List<Map<String,Object>> entries;
        synchronized (this) {
            entries = historyFor(tid).stream().map(LinkedHashMap<String,Object>::new)
                    .map(row -> (Map<String,Object>)row).toList();
        }
        List<Map<String,Object>> ordered = new ArrayList<>(entries);
        ordered.sort(java.util.Comparator.comparing(HistoryJournal::lastChange));
        Map<String,Map<String,Object>> currentByLookup = new java.util.HashMap<>();
        Map<String,Map<String,Object>> byOriginal = new LinkedHashMap<>();
        Map<String,List<Map<String,Object>>> localsByMethod = new java.util.HashMap<>();
        Map<String,Map<String,Object>> paramsByMethod = new java.util.HashMap<>();
        java.util.Set<String> scopes = null;
        Engine engine;
        try { engine = engineFor(tid); }
        catch (OpException unavailable) { return new NameLedgers(Map.of(), Map.of()); }
        for (Map<String,Object> entry : ordered) {
            if (!"rename".equals(entry.get("op")) || Boolean.TRUE.equals(entry.get("undone"))
                    || Boolean.TRUE.equals(entry.get("discarded")) || entry.get("newName") == null) continue;
            boolean local = "local".equals(entry.get("kind")), parameters = "parameters".equals(entry.get("kind"));
            if (local || parameters) {
                List<Map<String,Object>> changes = new ArrayList<>();
                if (parameters && entry.get("changes") instanceof List<?> list) {
                    for (Object value : list) if (value instanceof Map<?,?> change) {
                        Map<String,Object> copy = new LinkedHashMap<>();
                        change.forEach((key,value2) -> copy.put(String.valueOf(key), value2)); changes.add(copy);
                    }
                } else if (local) changes.add(entry);
                if (changes.stream().noneMatch(change -> Boolean.TRUE.equals(change.get("cleared"))
                        || change.get("newName") instanceof String name && resultText.contains(name))) continue;
                String method = entry.get(local ? "originalMethod" : "original_method") instanceof String s ? s : null;
                if (method == null || !method.contains("->") || (local && !(entry.get("originalSymbol") instanceof String))) continue;
                if (scopes == null) scopes = provenanceScopes(engine, params, data);
                if (!inProvenanceScope(method, scopes)) continue;
                Map<String,Object> currentMethod = currentByLookup.get(method);
                if (!currentByLookup.containsKey(method)) {
                    try { currentMethod = engine.resolve(method); }
                    catch (OpException unavailable) { currentMethod = null; }
                    currentByLookup.put(method, currentMethod);
                }
                if (currentMethod == null || !method.equals(stableSymbol(currentMethod, method))
                        || Boolean.TRUE.equals(currentMethod.get("contradiction"))) continue;
                if (!localsByMethod.containsKey(method)) {
                    List<Map<String,Object>> rows = new ArrayList<>();
                    try {
                        for (Object value : engine.locals(method)) if (value instanceof Map<?,?> row) {
                            Map<String,Object> copy = new LinkedHashMap<>();
                            row.forEach((key,value2) -> copy.put(String.valueOf(key), value2)); rows.add(copy);
                        }
                    } catch (OpException unavailable) { /* No native local identity: do not guess from a name. */ }
                    localsByMethod.put(method, rows);
                }
                for (Map<String,Object> change : changes) {
                    Map<String,Object> current = null;
                    for (Map<String,Object> row : localsByMethod.get(method)) {
                        if (local ? entry.get("originalSymbol").equals(row.get("originalSymbol"))
                                : change.get("index") != null && String.valueOf(change.get("index")).equals(String.valueOf(row.get("parameterIndex")))) {
                            current = row; break;
                        }
                    }
                    String original = current == null ? null : String.valueOf(current.get("originalSymbol"));
                    if (parameters && current == null) {
                        // Abstract/unused arguments may have no actionable text item. The formal
                        // index is still a native identity; never bind it by a coincidental name.
                        if (!paramsByMethod.containsKey(method)) {
                            Map<String,Object> meta = null;
                            try { meta = engine.methodParameters(method); } catch (OpException unavailable) {}
                            paramsByMethod.put(method, meta);
                        }
                        Map<String,Object> meta = paramsByMethod.get(method);
                        if (meta != null && method.equals(meta.get("original_method")) && meta.get("parameters") instanceof List<?> ps)
                            for (Object value : ps) if (value instanceof Map<?,?> row && change.get("index") != null
                                    && String.valueOf(change.get("index")).equals(String.valueOf(row.get("index")))) {
                                current = new LinkedHashMap<>(); current.put("name", row.get("name"));
                                current.put("parameterIndex", row.get("index")); original = method + "#parameter:" + row.get("index"); break;
                            }
                    }
                    if (original == null) continue;
                    if (Boolean.TRUE.equals(change.get("cleared"))) { byOriginal.remove(original); continue; }
                    if (current == null || !java.util.Objects.equals(change.get("newName"), current.get("name"))) continue;
                    Map<String,Object> row = new LinkedHashMap<>();
                    row.put("name", current.get("name")); row.put("originalSymbol", original);
                    row.put("originalMethod", method); row.put("addr", currentMethod.get("addr"));
                    row.put("kind", current.containsKey("parameterIndex") ? "parameter" : "local");
                    if (current.get("originalName") != null) row.put("was", current.get("originalName"));
                    else row.put("previousName", change.get("oldName"));
                    for (String key : List.of("itemId", "parameterIndex", "line"))
                        if (current.get(key) != null) row.put(key, current.get(key));
                    provenanceAuthor(row, entry);
                    byOriginal.put(original, row);
                }
                continue;
            }
            // Attribution can only decorate a name/address present in this answer.
            // Avoid resolving the entire historical rename corpus for every read.
            // This is a conservative substring filter, not cached native state:
            // every possible match still resolves against the current engine.
            if (!resultText.contains(String.valueOf(entry.get("newName")))) continue;
            List<String> originals = renameOriginals(entry);
            boolean stable = !originals.isEmpty();
            if (!stable) {
                if (!(entry.get("newAddr") instanceof String address)) continue;
                originals = List.of(address);
            }
            for (String lookup : originals) {
                if (!currentByLookup.containsKey(lookup)) {
                    Map<String,Object> resolved = null;
                    try {
                        Map<String,Object> found = engine.resolve(lookup);
                        boolean contradiction = Boolean.TRUE.equals(found.get("contradiction"));
                        if (contradiction && ("parameter types".equals(found.get("lenient")) || "return type".equals(found.get("lenient")))
                                && found.get("asked") != null && found.get("addr") != null)
                            contradiction = !renamesExplain(tid, String.valueOf(found.get("asked")), String.valueOf(found.get("addr")));
                        if (!contradiction) resolved = found;
                    } catch (OpException unavailable) { /* Missing legacy alias: no attribution can be proved. */ }
                    // Do not call resolveSymbol here: its thread-local envelope belongs to the main read.
                    currentByLookup.put(lookup, resolved);
                }
                Map<String,Object> current = currentByLookup.get(lookup);
                if (current == null || !String.valueOf(entry.get("newName")).equals(current.get("name"))) continue;
                String original = stableSymbol(current, lookup);
                if (stable && !lookup.equals(original)) continue;
                Map<String,Object> row = new LinkedHashMap<>();
                row.put("name", current.get("name"));
                row.put("was", current.get("origName") != null ? current.get("origName") : provenanceOriginalName(original));
                row.put("addr", current.getOrDefault("addr", current.getOrDefault("fqcn", lookup)));
                row.put("originalSymbol", original);
                provenanceAuthor(row, entry);
                byOriginal.put(original, row);
            }
        }
        Map<String,Map<String,Object>> agents = new LinkedHashMap<>(), humans = new LinkedHashMap<>();
        for (Map<String,Object> row : byOriginal.values())
            (row.containsKey("by") ? agents : humans).put(String.valueOf(row.get(row.containsKey("originalMethod") ? "originalSymbol" : "addr")), row);
        return new NameLedgers(agents, humans);
    }

    private static void provenanceAuthor(Map<String,Object> row, Map<String,Object> entry) {
        String actor = String.valueOf(entry.get("actor"));
        row.put("historyId", entry.get("id"));
        if ("agent".equals(EventBus.actorKind(actor))) {
            row.put("by", actor.replaceFirst("^(agent|llm):", "").replaceFirst(" (auto-applied|accepted by human)$", ""));
            if (entry.get("confidence") != null) row.put("confidence", entry.get("confidence"));
        }
    }

    /** Scope local attribution to methods actually returned by the read, or its requested
     * class. A common word such as 'value' must not decompile every historical method. */
    private static java.util.Set<String> provenanceScopes(Engine engine, Map<String,Object> params, Object data) {
        java.util.Set<String> scopes = new java.util.LinkedHashSet<>(), lookups = new java.util.LinkedHashSet<>();
        collectProvenanceScopes(data, scopes, lookups);
        if (params != null && params.get("symbol") instanceof String symbol) lookups.add(symbol);
        for (String lookup : lookups) {
            if (scopes.contains(lookup)) continue;
            try {
                Map<String,Object> resolved = engine.resolve(lookup);
                if (!Boolean.TRUE.equals(resolved.get("contradiction")) && resolved.get("origSig") instanceof String original) scopes.add(original);
            } catch (OpException unavailable) { /* No proven scope: omit attribution. */ }
        }
        return scopes;
    }

    private static void collectProvenanceScopes(Object value, java.util.Set<String> scopes, java.util.Set<String> lookups) {
        if (value instanceof List<?> list) { for (Object row : list) collectProvenanceScopes(row, scopes, lookups); }
        else if (value instanceof Map<?,?> map) {
            for (String key : List.of("origSig", "original_method", "originalMethod"))
                if (map.get(key) instanceof String original) scopes.add(original);
            if (!map.containsKey("origSig") && !map.containsKey("original_method") && !map.containsKey("originalMethod"))
                for (String key : List.of("addr", "method", "from", "to"))
                    if (map.get(key) instanceof String address && address.startsWith("L")) lookups.add(address);
            for (Map.Entry<?,?> entry : map.entrySet()) {
                // A method's owner is navigation context, not a class-sized code read.
                if ("owner".equals(entry.getKey())) continue;
                Object row = entry.getValue();
                if (row instanceof Map<?,?> || row instanceof List<?>) collectProvenanceScopes(row, scopes, lookups);
            }
        }
    }

    private static boolean inProvenanceScope(String method, java.util.Set<String> scopes) {
        for (String scope : scopes) {
            if (method.equals(scope)) return true;
            if (!scope.contains("->") && scope.endsWith(";")
                    && (method.startsWith(scope + "->") || method.startsWith(scope.substring(0,scope.length()-1) + "$"))) return true;
        }
        return false;
    }

    private static String provenanceOriginalName(String original) {
        int arrow = original.indexOf("->");
        if (arrow >= 0) {
            String member = original.substring(arrow + 2);
            int end = member.indexOf('('); if (end < 0) end = member.indexOf(':');
            return end < 0 ? member : member.substring(0, end);
        }
        String owner = original.endsWith(";") ? original.substring(0, original.length() - 1) : original;
        int end = Math.max(owner.lastIndexOf('/'), owner.lastIndexOf('.'));
        return owner.substring(end >= 0 ? end + 1 : owner.startsWith("L") ? 1 : 0);
    }

    private static void appendProvenanceStrings(Object value, StringBuilder text) {
        if (value instanceof String string) text.append(string).append('\n');
        else if (value instanceof Map<?,?> map) {
            for (Object item : map.values()) appendProvenanceStrings(item, text);
        } else if (value instanceof List<?> list) {
            for (Object item : list) appendProvenanceStrings(item, text);
        }
    }

    @SuppressWarnings("unchecked")
    public Object decorateForAgent(String opName, Map<String, Object> params, String actor, Object data) {
        if (!PROVENANCE_OPS.contains(opName) || !"agent".equals(EventBus.actorKind(actor)) || data == null) return null;
        String tid;
        try { tid = resolveTargetIdOrActive(params == null || params.get("target") == null ? null : String.valueOf(params.get("target"))); }
        catch (Exception e) { return null; }
        if (tid == null) return null;
        StringBuilder resultText = new StringBuilder();
        appendProvenanceStrings(data, resultText);
        NameLedgers namesByActor = currentNameLedgers(tid, resultText.toString(), params, data);
        Map<String, Map<String, Object>> ledger = namesByActor.agents();
        Map<String, Map<String, Object>> humans = namesByActor.humans();
        if (ledger.isEmpty() && humans.isEmpty()) return null;
        if (data instanceof List<?> l) return decorateRows((List<Object>) l, ledger, humans, null);
        if (!(data instanceof Map<?, ?>)) return null;
        Map<String, Object> out = new LinkedHashMap<>((Map<String, Object>) data);
        // symbol.resolve returns the symbol itself, not a nested symbol/rows
        // block. Attribute only that exact current identity; its owner and
        // same-name override siblings are not authors of this lookup result.
        Map<String,Object> resolvedProvenance = null;
        if (opName.equals("symbol.resolve") && out.get("addr") instanceof String address) {
            Map<String,Object> candidate = ledger.containsKey(address) ? ledger.get(address) : humans.get(address);
            if (candidate != null && java.util.Objects.equals(out.get("origSig"), candidate.get("originalSymbol"))
                    && java.util.Objects.equals(out.get("name"), candidate.get("name"))) {
                resolvedProvenance = candidate;
                if (candidate.containsKey("was")) out.put("was", candidate.get("was"));
                out.put("namedBy", candidate.getOrDefault("by", "human"));
                out.put("nameProvenance", candidate);
            }
        }
        for (String k : List.of("rows", "items", "hits", "groups", "methods", "fields", "parameters"))
            if (out.get(k) instanceof List<?> rl) out.put(k, decorateRows((List<Object>) rl, ledger, humans,
                    "parameters".equals(k) ? String.valueOf(out.get("original_method")) : null));
        if (opName.startsWith("code.") || opName.equals("symbol.resolve")) {
            String text = out.get("text") == null ? "" : String.valueOf(out.get("text"));
            String json = dev.workbench.registry.Json.write(out.get("symbol")) + dev.workbench.registry.Json.write(out.get("methods"));
            List<Object> names = new ArrayList<>();
            for (Map<String, Object> r : ledger.values()) {
                String nm = String.valueOf(r.get("name"));
                boolean hit = r == resolvedProvenance
                        || opName.equals("code.params") && java.util.Objects.equals(out.get("original_method"),r.get("originalMethod"))
                        || json.contains(String.valueOf(r.get("addr")))
                        || (!text.isEmpty() && java.util.regex.Pattern.compile("(?<![\\w$])" + java.util.regex.Pattern.quote(nm) + "(?![\\w$])").matcher(text).find());
                if (hit) names.add(r);
                if (names.size() >= 60 && !dev.workbench.llm.Depth.unlimitedTurns()) break;
            }
            if (!names.isEmpty()) {
                out.put("agentNames", names);
                out.put("agentNamesNote", "names in this result that an agent assigned, including locals and arguments — working hypotheses, not ground truth; keep supported names and revise only with evidence; 'was' is the original name when known");
            }
            List<Object> hn = new ArrayList<>();
            for (Map<String, Object> r : humans.values()) {
                String nm = String.valueOf(r.get("name"));
                boolean hit = r == resolvedProvenance
                        || opName.equals("code.params") && java.util.Objects.equals(out.get("original_method"),r.get("originalMethod"))
                        || json.contains(String.valueOf(r.get("addr")))
                        || (!text.isEmpty() && java.util.regex.Pattern.compile("(?<![\\w$])" + java.util.regex.Pattern.quote(nm) + "(?![\\w$])").matcher(text).find());
                if (hit) hn.add(r);
                if (hn.size() >= 60 && !dev.workbench.llm.Depth.unlimitedTurns()) break;
            }
            if (!hn.isEmpty()) {
                out.put("humanNames", hn);
                out.put("humanNamesNote", "names in this result a human assigned — preserve them; symbol names and explicitly mapped arguments are protected by rename checks; local labels report authorship");
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> decorateRows(List<Object> rows, Map<String, Map<String, Object>> ledger,
            Map<String,Map<String,Object>> humans, String parameterMethod) {
        List<Object> out = new ArrayList<>(rows.size());
        for (Object o : rows) {
            if (!(o instanceof Map<?, ?> m)) { out.add(o); continue; }
            Map<String, Object> row = null;
            Map<String,Object> local = null;
            if (m.get("originalSymbol") instanceof String identity && m.containsKey("originalMethod"))
                local = ledger.containsKey(identity) ? ledger.get(identity) : humans.get(identity);
            if (parameterMethod != null && m.get("index") != null) {
                for (Map<String,Map<String,Object>> candidates : List.of(ledger, humans))
                    for (Map<String,Object> candidate : candidates.values())
                        if (parameterMethod.equals(candidate.get("originalMethod"))
                                && String.valueOf(m.get("index")).equals(String.valueOf(candidate.get("parameterIndex")))) local = candidate;
            }
            if (local != null && java.util.Objects.equals(local.get("name"), m.get("name"))) {
                row = new LinkedHashMap<>((Map<String,Object>)m);
                if (local.containsKey("was")) row.put("was", local.get("was"));
                row.put("namedBy", local.getOrDefault("by", "human"));
                row.put("nameProvenance", local);
                out.add(row); continue;
            }
            // The containing method/type may have been named, but that is not
            // authorship of an untouched variable or an argument at this index.
            if (m.containsKey("originalMethod") || parameterMethod != null) { out.add(o); continue; }
            for (Object v : m.values()) {
                if (!(v instanceof String sv) || sv.length() < 4 || sv.charAt(0) != 'L') continue;
                Map<String, Object> hit = ledger.get(sv);
                int semi = sv.indexOf(';');
                Map<String, Object> cls = hit == null && semi > 0 && semi < sv.length() - 1 ? ledger.get(sv.substring(0, semi + 1)) : null;
                if (hit == null && cls == null) continue;
                row = new LinkedHashMap<>((Map<String, Object>) m);
                if (hit != null) { row.put("was", hit.get("was")); row.put("namedBy", hit.get("by")); }
                else row.put("classWas", cls.get("was"));
                break;
            }
            out.add(row == null ? o : row);
        }
        return out;
    }

    private static boolean byAgent(Map<String, Object> e, String agentId) {
        String actor = String.valueOf(e.get("actor"));
        return agentId != null && !agentId.isBlank() && "agent".equals(EventBus.actorKind(actor))
                && agentId.equals(RenameCoordinator.owner(actor));
    }

    /** Fresh write counts for every agent in one history pass. Mission polling must
     * not rescan the same target once per historical mission while holding this
     * monitor: that starves native operations and mission persistence. Owner
     * normalization is shared only within this snapshot, never across edits. */
    public synchronized Map<String, Integer> writeCountsByAgent(String tid) {
        Map<String, Integer> counts = new java.util.HashMap<>();
        Map<String, String> owners = new java.util.HashMap<>();
        for (Map<String, Object> entry : historyFor(tid)) {
            if (Boolean.TRUE.equals(entry.get("undone")) || Boolean.TRUE.equals(entry.get("discarded"))) continue;
            String actor = String.valueOf(entry.get("actor"));
            if (!"agent".equals(EventBus.actorKind(actor))) continue;
            String owner = owners.computeIfAbsent(actor, RenameCoordinator::owner);
            if (!owner.isBlank()) counts.merge(owner, 1, Integer::sum);
        }
        return counts;
    }

    /** How many applied (not undone) History entries an agent's writes account for on a target. */
    public synchronized int writesBy(String tid, String agentId) {
        int n = 0;
        for (Map<String, Object> e : historyFor(tid))
            if (!Boolean.TRUE.equals(e.get("undone")) && !Boolean.TRUE.equals(e.get("discarded")) && byAgent(e, agentId)) n++;
        return n;
    }

    /** Undo everything an agent wrote on a target, NEWEST FIRST — a later class rename must come
     *  off before the member renames recorded under the class's earlier address can resolve.
     *  Returns {reverted, failed:[{id, reason}]}; a failure never stops the walk. */
    public Map<String, Object> revertBy(String tid, String agentId) {
        List<String> ids = new ArrayList<>();
        synchronized (this) {
            for (Map<String, Object> e : historyFor(tid))
                if (!Boolean.TRUE.equals(e.get("undone")) && !Boolean.TRUE.equals(e.get("discarded")) && byAgent(e, agentId)) ids.add(String.valueOf(e.get("id")));
        }
        java.util.Collections.reverse(ids);
        int ok = 0;
        List<Object> failed = new ArrayList<>();
        for (String id : ids) {
            try { historyUndo(tid, id); ok++; }
            catch (Exception ex) { failed.add(Map.of("id", id, "reason", String.valueOf(ex.getMessage()))); }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("reverted", ok);
        out.put("failed", failed);
        return out;
    }

    /** The History entry id when the symbol's CURRENT name came from a human rename (the newest
     *  non-undone rename landing on its current address has a human actor); null otherwise —
     *  never renamed, or named by an agent (an accepted or auto-applied proposal carries the
     *  agent in its actor string). */
    public String humanNamedBy(String tid, String symbol) {
        String cur, original, currentName;
        try {
            Map<String,Object> resolved = engineFor(tid).resolve(symbol);
            cur = String.valueOf(resolved.get("addr"));
            original = stableSymbol(resolved, symbol);
            currentName = String.valueOf(resolved.get("name"));
        } catch (Exception e) { return null; }
        // Resolve in the engine before taking the history monitor. Holding the
        // workspace monitor across a native lookup would invert the write lock order.
        synchronized (this) {
            Map<String,Object> latest = null;
            for (Map<String,Object> entry : historyFor(tid)) {
                if (!"rename".equals(entry.get("op")) || Boolean.TRUE.equals(entry.get("undone"))) continue;
                if ("local".equals(entry.get("kind")) || "parameters".equals(entry.get("kind"))) continue;
                if (!cur.equals(String.valueOf(entry.get("newAddr"))) && !renameOriginals(entry).contains(original)) continue;
                if (!currentName.equals(String.valueOf(entry.get("newName")))) continue;
                if (latest == null || !HistoryJournal.lastChange(entry).isBefore(HistoryJournal.lastChange(latest))) latest = entry;
            }
            if (latest == null) return null;
            String actor = String.valueOf(latest.get("actor"));
            return "agent".equals(EventBus.actorKind(actor)) || actor.startsWith("agent:") ? null : String.valueOf(latest.get("id"));
        }
    }

    private List<Map<String, Object>> historyFor(String tid) {
        List<Map<String, Object>> h = history.get(tid);
        if (h == null) {
            h = loadHistory(tid);
            /* cache only for a REGISTERED target: a read for an archived mission's tid (a mission's report,
               historyRevision) must not park an empty log under an id the next open will be minted —
               its first rename would then persist that empty list over the real history.json
               (fix-review 2026-10-07) */
            if (targets.containsKey(tid)) history.put(tid, h);
        }
        return h;
    }

    /** history.list [target|all] [--limit] — newest first. */
    public synchronized List<Object> historyList(String targetParam, int limit) throws OpException {
        List<Map<String, Object>> all = new ArrayList<>();
        if ("all".equals(targetParam)) {
            for (String tid : targets.keySet()) all.addAll(historyFor(tid));
            all.sort((a, b) -> String.valueOf(b.get("ts")).compareTo(String.valueOf(a.get("ts"))));
        } else {
            all.addAll(historyFor(resolveTargetIdOrActive(targetParam)));
            all.sort((a, b) -> String.valueOf(b.get("ts")).compareTo(String.valueOf(a.get("ts"))));
        }
        int cap = limit <= 0 ? 200 : Math.min(limit, 2000);
        return new ArrayList<>(all.subList(0, Math.min(cap, all.size())));
    }

    /** history.undo [id]: revert an entry (newest non-undone when no id). */
    public Map<String, Object> historyUndo(String targetParam, String id) throws OpException { return historyUndo(targetParam, id, "human"); }

    /** The analyst's own decisions — an argument mapping, a restored original name — are theirs to
     *  reverse: an agent gets a refusal, not a silent re-application (fix-review
     *  2026-10-08 M4). */
    private static void analystsReversal(Map<String, Object> entry, String actor, String what) throws OpException {
        if (actor == null || "human".equals(EventBus.actorKind(actor))) return;
        if ("parameters".equals(entry.get("kind")) || Boolean.TRUE.equals(entry.get("revert")))
            throw OpException.scope("History " + entry.get("id") + " is the analyst's own decision (an argument mapping or a restored original name) — not an agent's to " + what, "actor=" + actor);
    }

    public Map<String,Object> historyUndo(String targetParam, String id, String actor) throws OpException {
        String tid;
        synchronized (this) { tid = resolveTargetIdOrActive(targetParam); }
        return withTargetWrite(tid, () -> {
            requireHistoryPersisted(tid);
            try { return historyUndoLocked(tid, id, actor); }
            finally { rebuildRenameClaims(tid); }
        });
    }

    private synchronized Map<String, Object> historyUndoLocked(String targetParam, String id, String actor) throws OpException {
        String tid = resolveTargetIdOrActive(targetParam);
        Map<String, Object> entry = findHistory(tid, id, true);
        analystsReversal(entry, actor, "undo");
        Engine e = engineFor(tid);
        String original = entry.get("originalSymbol") == null ? null : String.valueOf(entry.get("originalSymbol"));
        String method = entry.get("originalMethod") == null ? String.valueOf(entry.get("addr")) : String.valueOf(entry.get("originalMethod"));
        switch (String.valueOf(entry.get("op"))) {
            case "rename" -> {
                if ("local".equals(entry.get("kind"))) {
                    e.renameLocal(method, String.valueOf(entry.get("newName")), String.valueOf(entry.get("oldName")), "human");
                } else if ("parameters".equals(entry.get("kind"))) {
                    e.renameParameters(original == null ? String.valueOf(entry.get("newAddr")) : original, parameterChanges(entry, true), "human");   // whole or not at all: the engine rolls back a refusal
                } else {
                    applyName(e, original == null ? String.valueOf(entry.get("newAddr")) : original, String.valueOf(entry.get("oldName")));
                }
            }
            case "comment" ->
                e.comment(original == null ? String.valueOf(entry.get("newAddr")) : original,
                        entry.get("oldText") == null ? "" : String.valueOf(entry.get("oldText")), "human");
            default -> throw OpException.syntax("not an undoable op: " + entry.get("op"), "id=" + id);
        }
        noteHistoryReversal(tid, entry, "undo", actor);
        if ("rename".equals(entry.get("op"))) {
            if ("local".equals(entry.get("kind"))) dropLocalBinding(tid, String.valueOf(entry.get("addr")), String.valueOf(entry.get("newName")));
            else if (!"parameters".equals(entry.get("kind"))) followRename(tid, String.valueOf(entry.get("kind")), String.valueOf(entry.get("newAddr")), String.valueOf(entry.get("addr")), "human");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("undone", entry);
        out.put("history", historyList(tid, 200));
        return out;
    }

    /** Reversals consume their own revision and are persisted atomically with the UI flag.
     * The engine mutation already happened: a write failure keeps it in memory and is explicit. */
    private void noteHistoryReversal(String tid, Map<String,Object> entry, String what, String reversalActor) throws OpException {
        int n = historySeq.merge(tid, 1, Integer::sum);
        Map<String,Object> event = new LinkedHashMap<>();
        event.put("id", tid + "-h" + n); event.put("op", what); event.put("of", entry.get("id"));
        for (String k : List.of("kind", "addr", "newAddr", "oldName", "newName", "oldText", "newText", "changes", "source", "original_method", "revert", "originalSymbol", "originalMethod", "renameTargets"))
            if (entry.get(k) != null) event.put(k, entry.get(k));
        event.put("reversedActor", entry.get("actor"));
        event.put("actor", reversalActor == null ? "human" : reversalActor);
        event.put("ts", java.time.Instant.now().toString()); event.put("target", tid);
        HistoryJournal.appendReversal(entry, event);
        entry.put("undone", "undo".equals(what));
        try { requireHistoryPersisted(tid); }
        catch (OpException failure) {
            failure.extra.put("applied", true); failure.extra.put("historyId", event.get("id"));
            failure.extra.put("recovery", "The reversal remains in memory. Keep the target open and retry target.save after correcting storage.");
            throw failure;
        }
        java.util.function.BiConsumer<String,Map<String,Object>> listener = historyListener;
        if (listener != null) try { listener.accept(tid, new LinkedHashMap<>(event)); }
        catch (Throwable failure) { Log.warn("history listener", String.valueOf(failure), null); }
    }

    /** history.redo [id]: re-apply an undone entry (newest undone when no id). */
    public Map<String, Object> historyRedo(String targetParam, String id) throws OpException { return historyRedo(targetParam, id, "human"); }

    public Map<String,Object> historyRedo(String targetParam, String id, String actor) throws OpException {
        String tid;
        synchronized (this) { tid = resolveTargetIdOrActive(targetParam); }
        return withTargetWrite(tid, () -> {
            requireHistoryPersisted(tid);
            try { return historyRedoLocked(tid, id, actor); }
            finally { rebuildRenameClaims(tid); }
        });
    }

    private synchronized Map<String, Object> historyRedoLocked(String targetParam, String id, String actor) throws OpException {
        String tid = resolveTargetIdOrActive(targetParam);
        Map<String, Object> entry = findHistory(tid, id, false);
        analystsReversal(entry, actor, "redo");
        Engine e = engineFor(tid);
        String original = entry.get("originalSymbol") == null ? null : String.valueOf(entry.get("originalSymbol"));
        String method = entry.get("originalMethod") == null ? String.valueOf(entry.get("addr")) : String.valueOf(entry.get("originalMethod"));
        switch (String.valueOf(entry.get("op"))) {
            case "rename" -> {
                if ("local".equals(entry.get("kind")))
                    e.renameLocal(method, String.valueOf(entry.get("oldName")), String.valueOf(entry.get("newName")), "human");
                else if ("parameters".equals(entry.get("kind")))
                    e.renameParameters(original == null ? String.valueOf(entry.get("addr")) : original, parameterChanges(entry, false), "human");
                else {
                    applyName(e, original == null ? String.valueOf(entry.get("addr")) : original, String.valueOf(entry.get("newName")));
                }
            }
            case "comment" ->
                e.comment(original == null ? String.valueOf(entry.get("addr")) : original,
                        entry.get("newText") == null ? "" : String.valueOf(entry.get("newText")), "human");
            default -> throw OpException.syntax("not a redoable op: " + entry.get("op"), "id=" + id);
        }
        noteHistoryReversal(tid, entry, "redo", actor);
        if ("rename".equals(entry.get("op")) && !"local".equals(entry.get("kind")) && !"parameters".equals(entry.get("kind")))
            followRename(tid, String.valueOf(entry.get("kind")), String.valueOf(entry.get("addr")), String.valueOf(entry.get("newAddr")), "human");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("redone", entry);
        out.put("history", historyList(tid, 200));
        return out;
    }

    /** Whether a history entry is undone — null when the target is not open or the id is unknown. */
    public synchronized Boolean historyUndone(String tid, String id) {
        if (!targets.containsKey(tid)) return null;
        // An exact lookup is not a UI page: proposals can point to any older edit.
        for (Map<String,Object> entry : historyFor(tid))
            if (id.equals(String.valueOf(entry.get("id")))) return Boolean.TRUE.equals(entry.get("undone"));
        return null;
    }

    private Map<String, Object> findHistory(String tid, String id, boolean wantApplied) throws OpException {
        List<Map<String, Object>> h = historyFor(tid);
        if (id != null && !id.isBlank()) {
            for (Map<String, Object> e : h)
                if (id.equals(e.get("id"))) {
                    if (wantApplied && Boolean.TRUE.equals(e.get("undone")))
                        throw OpException.syntax("already undone: " + id, "");
                    if (!wantApplied && !Boolean.TRUE.equals(e.get("undone")))
                        throw OpException.syntax("not undone: " + id, "");
                    return e;
                }
            throw OpException.notFound("history entry not found: " + id, "target=" + tid);
        }
        for (int i = h.size() - 1; i >= 0; i--) {
            Map<String, Object> e = h.get(i);
            if (wantApplied != Boolean.TRUE.equals(e.get("undone"))) return e;
        }
        throw OpException.notFound(wantApplied ? "nothing to undo" : "nothing to redo", "target=" + tid);
    }

    // ---- persistence: <artifact>.workbench/history.json (real artifacts only)

    private File historyFile(String tid) {
        TargetMeta meta = targets.get(tid);
        if (meta == null) return null;
        File artifact = new File(meta.path());
        if (!artifact.isFile()) return null;                    // mock fixture target
        return new File(sidecarFor(artifact), "history.json");
    }

    /** @return true when history.json was durably written; false when the write was skipped
     *  (degraded park failed) or the atomic write failed. Callers that MUST know the edit is
     *  recorded on disk must check this result before confirming success. */
    private boolean persistHistory(String tid) {
        File f = historyFile(tid);
        if (f == null) return false;
        /* atomic + loud via the shared sidecar discipline (app-review-opus P0-4):
           history.json feeds dirtyReplayFor and indexReplayMap — a torn write
           makes the index treat stale classes as fresh. A degraded load (the
           read failed transiently) parks the possibly-healthy original as
           evidence before overwriting it (fix-1-r3 #1). */
        if (degradedHistory.contains(tid)) {
            /* never overwrite un-parked evidence: a failed park keeps the flag
               and SKIPS the write (fix-1-r4 #5) */
            if (!dev.workbench.registry.SidecarIO.parkHealthyBeforeOverwrite(f, "history"))
                return false;
            degradedHistory.remove(tid);
        }
        return dev.workbench.registry.SidecarIO.writeAtomic(f,
                dev.workbench.registry.Json.write(historyFor(tid)), "history");
    }

    private List<Map<String, Object>> loadHistory(String tid) {
        File f = historyFile(tid);
        if (f != null && f.isFile()) {
            try {
                Object parsed = dev.workbench.registry.Json.parse(Files.readString(f.toPath()));
                if (parsed instanceof List<?> l) {
                    List<Map<String, Object>> out = new ArrayList<>();
                    int max = 0;
                    for (Object o : l)
                        if (o instanceof Map<?, ?> m) {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> e = (Map<String, Object>) m;
                            out.add(new LinkedHashMap<>(e));
                            max = Math.max(max, historyNum(e));
                            for (Map<String,Object> event : HistoryJournal.reversals(e)) max = Math.max(max, historyNum(event));
                        }
                    historySeq.merge(tid, max, Math::max);
                    return out;
                }
                /* right file, wrong shape — as corrupt as torn JSON. A FAILED
                   park leaves it in place — mark degraded so the next
                   persistHistory retries the park before overwriting
                   (fix-1-r5 #2) */
                if (!dev.workbench.registry.SidecarIO.parkCorrupt(f, "history",
                        new IllegalStateException("expected a JSON list")))
                    degradedHistory.add(tid);
            } catch (java.io.IOException e) {
                /* transient read hiccup ≠ corruption (fix-1-r2 F2): routed by
                   permanence — a charset-parked file is already aside; a
                   transient one marks the target degraded so the first
                   persistHistory parks the healthy original before overwriting */
                if (!dev.workbench.registry.SidecarIO.readFailedOrPark(f, "history", e))
                    degradedHistory.add(tid);
            } catch (Exception e) {
                /* LOUD + park (fix-1 review F1): torn history silently became an
                   empty undo log, told indexReplayMap that stale classes were
                   fresh — and the next recordHistory then overwrote the evidence.
                   A FAILED park leaves the file in place — mark degraded
                   (fix-1-r5 #2) */
                if (!dev.workbench.registry.SidecarIO.parkCorrupt(f, "history", e))
                    degradedHistory.add(tid);
            }
        }
        return new ArrayList<>();
    }

    // ------------------------------------------------------------ state

    public Map<String, Object> state() throws OpException {
        /* map rows are read OUTSIDE the Workspace monitor — MapStore.addNode →
           normalizeSymbol → engineFor locks MapStore→Workspace, so holding the
           monitor while taking the store lock could deadlock (map-r5 F14) */
        MapStore ms = maps;
        List<Object> mapRows = ms == null ? List.of() : ms.list();
        synchronized (this) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("projects", projectList());
            m.put("activeProject", activeProject);
            m.put("targets", targetList());
            m.put("activeTarget", activeTarget);
            m.put("heap", heapRow());          // a client reads headroomBytes before opening another target (2026-09-21)
            m.put("runtime", runtimeIdentity());   // WHICH compiled build and engine answer (a client never infers runtime support from a source checkout — observer follow-up 2026-10-09)
            List<String> closing = new ArrayList<>();
            for (String k : closingPaths.keySet()) closing.add(new File(k).getName());
            if (!closing.isEmpty()) m.put("closing", closing);   // closes still writing their jdb2 (async close)
            m.put("maps", mapRows);
            Map<String, Object> ap = projects.get(activeProject);
            m.put("activeMap", ap == null ? null : ap.get("activeMap"));   // per-project (map-r4 fix)
            m.put("views", List.of("explorer"));
            /* sidecar persist-discipline issues (fix-1-r5 #4): a skip-write or
               a failed park is otherwise invisible outside stderr — the user
               would believe their work was saved. Omitted when zero. */
            long sidecarIssues = dev.workbench.registry.SidecarIO.writeFailures.get()
                    + dev.workbench.registry.SidecarIO.parkFailures.get()
                    + dev.workbench.registry.SidecarIO.loadFailures.get();
            if (sidecarIssues > 0) m.put("sidecarIssues", sidecarIssues);
            return m;
        }
    }
}
