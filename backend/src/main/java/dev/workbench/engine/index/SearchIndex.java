package dev.workbench.engine.index;

import dev.workbench.registry.Json;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Background decompiled-text index for one target (VIEWS_SPEC §3), with a
 * persisted per-class gz corpus on disk (ROADMAP major item):
 *
 * - Every indexed class's text is written gzipped to
 *   {@code <artifact>.workbench/search-index/classes/<encoded-addr>.gz} as it is
 *   built, plus a header.json {version, builtAtMs, classes:{addr:fqcn}}.
 *   Filenames are CASE-ESCAPED (encodeAddr): APFS/Windows filesystems are
 *   case-insensitive and obfuscated targets DO contain LTt;/LtT;/Ltt;
 *   collisions (47 classes in GMS) — raw addrs would silently overwrite.
 * - On open, tryWarmLoad() validates the header (engine version) and adopts the
 *   persisted corpus with ZERO decompilation; the in-memory tier starts COLD
 *   and hydrates on the first search (12s measured at GMS scale, instant for
 *   small targets — OFF the monitor, merged with dedupe).
 * - Tracked edits (rename/comment) delete the class's gz and enqueue a
 *   re-index; pending.json records the queue so a crash between edit and
 *   re-index heals on the next open.
 * - Builder supersede safety: a full rebuild can interrupt a builder stuck in
 *   a long decompile (82s monsters don't honor Thread.interrupt); the
 *   generation counter gates the old thread's corpus writes, and the corpus
 *   dir is only wiped when the old builder is confirmed dead.
 *
 * Automatic mode uses one MIN-priority daemon; each native class render takes
 * the engine lock and can last minutes on complex input. On-demand mode starts
 * no unsolicited worker. Explicit searches refresh pending classes, sharing and
 * persisting their results, with native calls outside the index monitor. Clean
 * corpus snapshots are searched without native rendering. Explicit full rebuilds
 * retain their background behavior in both modes.
 *
 * Cancellable via op.cancel (ticket "search-index" / "search-index:<target>");
 * progress is reported through target.info / search.index.
 */
public final class SearchIndex {

    /** WORKBENCH_PROF=1: per-class decompile cost + corpus size logging. */
    private static final boolean PROF = System.getenv("WORKBENCH_PROF") != null;

    /** One indexed class: addr + display name + decompiled lines (snapshot). */
    public record Entry(String addr, String fqcn, String[] lines) {}

    public interface Host {
        List<String[]> classSnapshot();                       // [addr, fqcn] per class
        String decompileText(String classAddr);               // engine-locked decompile
        String classLabel(String classAddr);                  // display name for one class
        void evictDecompiled(String classAddr);               // engine-locked AST product eviction
        Map<String, Object> indexSourceIdentity();             // exact executable bytes + analysis generation
        /** Mtime of the persisted engine state (e.g. .jdb2 or sidecar code-data.json) as WE last wrote it (-1: none). */
        default long persistenceMtime() { return jdb2Mtime(); }
        default long jdb2Mtime() { return -1; }
    }

    /** Decompile slower than this pins the class's products (monsters like the
     *  82s GMS class never get evicted — re-paying their cost is the real risk);
     *  everything cheaper is evicted right after indexing (decompiled products
     *  are the bulk-churn tier; the dex model floor is untouchable). */
    private static final long MONSTER_PIN_MS = 5_000;

    /** Corpus format marker — bumped on any on-disk layout change. v2's escape
     *  scheme passed non-ASCII through (Kelvin sign L\u212A; folded to LK;);
     *  v3 fixed-arity but used Character.isUpperCase (still caught non-ASCII
     *  Lu chars); v4 restricts the uppercase branch to ASCII A-Z. A corpus
     *  without a matching format is never warm-loaded: it would hydrate zero
     *  entries and report a healthy EMPTY index. Absent → rebuild. */
    private static final int CORPUS_FORMAT = 4;

    private final Host host;
    private final String ticket;
    private final File dir;                                   // search-index dir, or null (no persistence)
    private final String engineVersion;
    private final boolean automaticRefresh;
    /** Searches share replacement work without holding the index/engine monitors while waiting. */
    private final Object queryRefresh = new Object();
    private final List<Entry> entries = new CopyOnWriteArrayList<>();
    /** Class addrs invalidated by edits (rename/comment), awaiting re-index.
     *  Drained by the builder thread itself at the end of a full build, or by a
     *  lightweight drain pass when no build is running — no third thread ever
     *  exists, and a rename burst can never start a concurrent second pass. */
    private final Queue<String> reindexQueue = new ConcurrentLinkedQueue<>();
    /** Addrs whose gz was deleted by an edit but not yet re-indexed (crash window). */
    private final Set<String> pending = new LinkedHashSet<>();
    /** A pending class leaves the recovery queue only after both its text and header persist. */
    private final Set<String> readyForHeader = new LinkedHashSet<>();
    private final Map<String,Long> invalidations = new LinkedHashMap<>();
    private long invalidationSequence;
    /** Cancellation never interrupts a filesystem commit halfway through. No engine or index
     * monitor is acquired under this gate, so close() remains free of a lock-order cycle. */
    private final Object interruptGate = new Object();
    private final Set<Thread> committing = new HashSet<>();
    private final Set<Thread> interruptAfterCommit = new HashSet<>();
    /** The corpus's authoritative addr → fqcn map (header content): the build
     *  snapshot at build start, updated by every invalidate(), adopted from the
     *  header on warm load. Header writes always come from THIS, so a mid-build
     *  rename can't orphan a class behind its stale addr. */
    private volatile Map<String, String> live = new LinkedHashMap<>();
    private volatile String state = "idle";                   // idle|building|built|cancelled|failed
    private volatile int built, total;
    private volatile boolean fullScanComplete;
    private volatile long startedAt, finishedAt;
    private volatile long builtAtMs;
    private volatile boolean persisted, warm = true;          // warm=false: corpus on disk only
    private volatile boolean adoptedFromDisk;                 // this corpus came from tryWarmLoad
    private volatile long corpusBuiltAtMs;                    // ORIGINAL build stamp (never advanced by drains)
    private volatile Map<String, Object> sourceIdentity = Map.of();
    private volatile Map<String, Object> builderRuntime = Map.of();
    private volatile String error;
    private volatile boolean cancel;
    private volatile int generation;                          // supersede gate for stuck builders
    private volatile Thread thread;   // cancel() reads it with no lock (review L3)
    /* pending.json's load failed transiently (fix-1-r3 #1): the on-disk queue
       may be healthy — the next savePending parks it as evidence before
       writing, instead of silently dropping the reindex queue it never saw */
    private boolean pendingDegraded;

    /** Reject a warm corpus without starting expensive replacement work. */
    public synchronized void rejectWarm(String reason) {
        ++generation; // a drainer started during adoption must not commit rejected text
        entries.clear(); reindexQueue.clear(); pending.clear(); readyForHeader.clear();
        invalidations.clear();
        live = new LinkedHashMap<>();
        state = "stale"; built = total = 0;
        fullScanComplete = false; warm = true;
        sourceIdentity = Map.of(); builderRuntime = Map.of();
        warmRejectReason = reason; adoptedFromDisk = false; persisted = false;
    }

    /** A rejected corpus supplies addresses to regenerate, NEVER text to adopt. */
    public Set<String> recoveryCandidates() {
        Set<String> out = new LinkedHashSet<>();
        if (dir == null) return out;
        try {
            Object parsed = Json.parse(Files.readString(new File(dir, "header.json").toPath()));
            if (parsed instanceof Map<?, ?> h && h.get("classes") instanceof Map<?, ?> classes)
                for (Object addr : classes.keySet()) out.add(String.valueOf(addr));
        } catch (Exception ignored) { /* validation reports the rejection separately */ }
        out.addAll(loadPending());
        return out;
    }

    /** First edit in on-demand mode must establish the same identity as a full build.
     * Host calls stay outside the index monitor (engine -> index lock order). */
    private void initializeIdentity() {
        if (!sourceIdentity.isEmpty()) return;
        Map<String, Object> identity = host.indexSourceIdentity();
        Map<String, Object> runtime = new LinkedHashMap<>(dev.workbench.server.Workspace.runtimeIdentity());
        runtime.put("engineVersion", engineVersion);
        synchronized (this) {
            if (sourceIdentity.isEmpty()) {
                sourceIdentity = identity; builderRuntime = runtime;
                if (identity.get("analysis_classes_at_open") instanceof Number n) total = n.intValue();
                if ("idle".equals(state) || "stale".equals(state)) state = "partial";
            }
        }
    }

    public SearchIndex(Host host, String ticket, File dir, String engineVersion) {
        this(host, ticket, dir, engineVersion,
                !"on-demand".equalsIgnoreCase(System.getenv("WORKBENCH_INDEX_MODE")));
    }

    public SearchIndex(Host host, String ticket, File dir, String engineVersion, boolean automaticRefresh) {
        this.host = host;
        this.ticket = ticket;
        this.dir = dir;
        this.engineVersion = engineVersion;
        this.automaticRefresh = automaticRefresh;
    }

    public String ticket() { return ticket; }

    public long builtAtMs() { return builtAtMs; }

    /** Why the last tryWarmLoad() rejected an existing header (null = no header
     *  or accepted). Logged by the caller so a 3h rebuild never starts silently. */
    private volatile String warmRejectReason;

    public String warmRejectReason() { return warmRejectReason; }

    // ------------------------------------------------------------ persistence

    /** Case-insensitive-filesystem-safe gz filename: delegates to CorpusEncoding
     *  (fixed-arity, injective under Unicode folding). Kept as a one-line shim
     *  so the persistence code reads locally. */
    public static String encodeAddr(String addr) {
        return CorpusEncoding.encodeAddr(addr);
    }

    private File gzFile(String addr) {
        return new File(dir, "classes/" + encodeAddr(addr) + ".gz");   // addr is path-shaped
    }

    private boolean writeGz(String addr, String text) {
        if (text == null) return false;
        if (dir == null) return true;
        java.nio.file.Path temporary = null;
        try {
            File f = gzFile(addr);
            Files.createDirectories(f.toPath().getParent());
            temporary = Files.createTempFile(f.toPath().getParent(), ".corpus-", ".gz");
            try (OutputStream os = new GZIPOutputStream(Files.newOutputStream(temporary))) {
                os.write(text.getBytes(StandardCharsets.UTF_8));
            }
            dev.workbench.registry.SidecarIO.forceFile(temporary);
            Files.move(temporary, f.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            dev.workbench.registry.SidecarIO.forceDirectory(f.toPath().getParent());
            return true;
        } catch (IOException e) {
            dev.workbench.registry.SidecarIO.writeFailures.incrementAndGet();
            error = "corpus write failed for " + addr + ": " + e;
            System.err.println("workbench: " + error + " in " + dir);
            return false;
        } finally {
            if (temporary != null) try { Files.deleteIfExists(temporary); } catch (IOException ignored) {}
        }
    }

    private String readGz(String addr) {
        try {
            File f = gzFile(addr);
            if (!f.isFile()) return null;
            try (InputStream is = new GZIPInputStream(Files.newInputStream(f.toPath()))) {
                return new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) { return null; }
    }

    private void deleteGz(String addr) {
        if (dir != null && addr != null) gzFile(addr).delete();
    }

    private synchronized boolean savePending() {
        if (dir == null) return true;
        File f = new File(dir, "pending.json");
        if (pendingDegraded) {
            /* never overwrite un-parked evidence: a failed park keeps the flag
               and SKIPS the write (fix-1-r4 #5) */
            if (!dev.workbench.registry.SidecarIO.parkHealthyBeforeOverwrite(f, "pending"))
                return false;
            pendingDegraded = false;
        }
        boolean saved = dev.workbench.registry.SidecarIO.writeAtomic(f,
                Json.write(new ArrayList<>(pending)), "pending");
        if (!saved) error = "Pending index work could not be persisted; keep the target open and repair storage.";
        return saved;
    }

    private List<String> loadPending() {
        File f = new File(dir, "pending.json");
        if (!f.isFile()) return List.of();
        try {
            Object p = Json.parse(Files.readString(f.toPath()));
            if (p instanceof List<?> l) {
                List<String> out = new ArrayList<>();
                for (Object o : l) out.add(String.valueOf(o));
                return out;
            }
            if (!dev.workbench.registry.SidecarIO.parkCorrupt(f, "pending",
                    new IllegalStateException("expected a JSON list")))
                pendingDegraded = true;   /* park FAILED — the file is still in
                    place; the next savePending retries the park before any
                    overwrite (fix-1-r5 #2) */
        } catch (java.io.IOException e) {
            /* transient read hiccup ≠ corruption (fix-1-r2 F2): routed by
               permanence — a charset-parked file is already aside; a transient
               one marks the queue degraded so the next savePending parks the
               healthy original before overwriting it (fix-1-r3 #1) */
            if (!dev.workbench.registry.SidecarIO.readFailedOrPark(f, "pending", e))
                pendingDegraded = true;
        } catch (Exception e) {
            /* loud + park: a torn pending.json silently DROPS the reindex queue —
               renamed classes would never be re-indexed (fix-1 review F3). A
               FAILED park leaves the file in place — mark degraded so the next
               savePending retries the park before overwriting (fix-1-r5 #2) */
            if (!dev.workbench.registry.SidecarIO.parkCorrupt(f, "pending", e))
                pendingDegraded = true;
        }
        return List.of();
    }

    /**
     * Adopt the persisted corpus if it is valid for this engine version —
     * zero decompilation. Returns the corpus build time (ms epoch), or -1 when
     * no usable corpus exists (caller runs a full build via start()).
     * The memory tier starts COLD (hydrated on first search); pending.json
     * classes (crash window) are re-queued for re-index immediately.
     */
    public synchronized long tryWarmLoad() {
        if (dir == null) return -1;
        warmRejectReason = null;   // no stale reason from an earlier attempt (fix-1-r7 F5)
        File hf = new File(dir, "header.json");
        if (!hf.isFile()) return -1;
        /* header.json gets the same discipline as every sidecar (fix-1-r6
           MED-1): a torn header used to cost a SILENT full corpus rebuild —
           catch-all -1, no stderr line, and warmRejectReason left null so even
           the engine's "why rebuilding" log was suppressed. (Parked in-dir: the
           header is a derivable cache, and the rebuild may wipe the dir — the
           stderr line + warmRejectReason are the evidence that matters.) */
        final Map<String, Object> h;
        try {
            Object parsed = Json.parse(Files.readString(hf.toPath()));
            if (!(parsed instanceof Map)) {
                warmRejectReason = "header.json is not a JSON object";
                dev.workbench.registry.SidecarIO.parkCorrupt(hf, "index header",
                        new IllegalStateException("expected a JSON object"));
                return -1;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> hm = (Map<String, Object>) parsed;
            h = hm;
        } catch (java.io.IOException e) {
            dev.workbench.registry.SidecarIO.readFailedOrPark(hf, "index header", e);
            warmRejectReason = "header read failed: " + e;
            return -1;
        } catch (Exception e) {
            dev.workbench.registry.SidecarIO.parkCorrupt(hf, "index header", e);
            warmRejectReason = "header unparsable (parked): " + e;
            return -1;
        }
        try {
            if (!engineVersion.equals(String.valueOf(h.get("version")))) {
                warmRejectReason = "engine version " + h.get("version") + " != " + engineVersion;
                return -1;
            }
            if (!(h.get("format") instanceof Number n) || n.intValue() != CORPUS_FORMAT) {
                warmRejectReason = "corpus format " + h.get("format") + " != " + CORPUS_FORMAT;
                return -1;                                    // unknown layout → full rebuild
            }
            Map<String, Object> expectedIdentity = host.indexSourceIdentity();
            if (!Boolean.TRUE.equals(expectedIdentity.get("measured")) ||
                    !(h.get("sourceIdentity") instanceof Map<?, ?> recordedIdentity) ||
                    !Json.write(new java.util.TreeMap<>(expectedIdentity)).equals(Json.write(new java.util.TreeMap<>(recordedIdentity)))) {
                warmRejectReason = "index executable/analysis identity missing or different; corpus not adopted";
                return -1;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> classes = (Map<String, Object>) h.get("classes");
            if (classes == null || classes.isEmpty()) {
                /* a header that parses but lost its classes map is a torn-header
                   symptom — say so (fix-1-r7 F5: this -1 was silent) */
                warmRejectReason = "header has no classes map (torn header?)";
                return -1;
            }
            long built = ((Number) h.get("builtAtMs")).longValue();
            if (built <= 0) {
                warmRejectReason = "header builtAtMs " + h.get("builtAtMs")
                        + " — never adopt a zero-time header";
                return -1;
            }
            /* load the crash-window queue BEFORE mutating any adoption state:
               if its read failed TRANSIENTLY, adopting is a permanent silent
               hole — the pending classes' gz was deleted at invalidate and the
               header prunes them, so they would vanish from search coverage for
               the life of the corpus. Degrade to a full rebuild (fix-1-r4 #9). */
            List<String> pend = loadPending();
            if (pendingDegraded) {
                /* the rebuild this -1 triggers WIPES the whole corpus dir
                   (start() → mayWipe → renameTo + deleteRecursively) — park the
                   queue OUTSIDE it first, or the evidence dies with the wipe
                   (fix-1-r5 #1: the r4 comment claimed the rebuild's first
                   savePending would park it — by then the file is gone, and
                   parkHealthyBeforeOverwrite no-ops on a missing file). The flag
                   clears ONLY on a successful park or a vanished file — a failed
                   park with a bail-before-wipe would otherwise leave the queue
                   in place and unprotected (fix-1-r6 LOW-1). */
                File pj = new File(dir, "pending.json");
                if (!pj.isFile()) {
                    pendingDegraded = false;
                } else {
                    File dst = new File(dir.getParentFile(),
                            "pending.json.corrupt-degraded-" + System.currentTimeMillis());
                    try {
                        Files.move(pj.toPath(), dst.toPath());
                        System.err.println("pending queue parked outside the wiped corpus dir: " + dst);
                        pendingDegraded = false;
                    } catch (Exception moveErr) {
                        dev.workbench.registry.SidecarIO.parkFailures.incrementAndGet();
                        System.err.println("pending park-before-rebuild FAILED (the wipe takes it): "
                                + pj + " — " + moveErr);
                    }
                }
                warmRejectReason = "pending.json read failed transiently — adoption"
                        + " would drop queued reindexes, rebuilding";
                return -1;
            }
            Map<String, String> map = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : classes.entrySet())
                map.put(e.getKey(), String.valueOf(e.getValue()));
            live = map;
            sourceIdentity = host.indexSourceIdentity();
            if (h.get("builderRuntime") instanceof Map<?, ?> previous) {
                Map<String, Object> copy = new LinkedHashMap<>();
                previous.forEach((k,v) -> copy.put(String.valueOf(k), v));
                builderRuntime = copy;
            }
            builtAtMs = built;
            this.built = map.size();
            total = h.get("totalClasses") instanceof Number count ? count.intValue()
                    : expectedIdentity.get("analysis_classes_at_open") instanceof Number count ? count.intValue() : map.size();
            fullScanComplete = Boolean.TRUE.equals(h.get("completeCoverage"));
            startedAt = finishedAt = builtAtMs;
            state = fullScanComplete ? "built" : "partial";
            persisted = true;
            adoptedFromDisk = true;
            corpusBuiltAtMs = builtAtMs;                      // original stamp survives drains
            warm = false;                                     // hydrate on first search
            for (String a : pend) {
                pending.add(a);
                invalidations.put(a, ++invalidationSequence);
                reindexQueue.offer(a);
            }
            ensureDrainer();
            return builtAtMs;
        } catch (Exception e) {
            /* no silent rebuilds (fix-1-r6 MED-1): a catch-all -1 with a null
               warmRejectReason suppressed even the engine's "why rebuilding"
               log */
            warmRejectReason = "header classes unreadable: " + e;
            return -1;
        }
    }

    private synchronized boolean writeHeader() {
        if (dir == null) { pending.removeAll(readyForHeader); readyForHeader.clear(); return true; }
        Map<String, Object> snapshot;
        synchronized (this) {
            snapshot = new LinkedHashMap<>(live);         // CME guard: copy under the monitor
            // Unfinished classes remain excluded. Completed replacements may join this
            // header, but their durable pending entries clear only after the header lands.
            for (String p : pending) if (!readyForHeader.contains(p)) snapshot.remove(p);
        }
        Map<String, Object> h = new LinkedHashMap<>();
        h.put("version", engineVersion);
        h.put("sourceIdentity", sourceIdentity);
        h.put("builderRuntime", builderRuntime);
        h.put("format", CORPUS_FORMAT);
        h.put("builtAtMs", finishedAt);
        h.put("classes", snapshot);                       // post-edit state, not the snapshot
        h.put("totalClasses", total);
        h.put("completeCoverage", fullScanComplete && snapshot.size() == total);
        /* atomic + loud (fix-1 review F3): header.json is THE index-freshness
           record — a torn or silently-skipped write misrepresents staleness.
           The witness advances ONLY when the header landed (fix-1-r2 review F1:
           stamping it unconditionally inverted fail-safe to fail-unsafe — the
           next open would adopt the PREVIOUS header as current after a failed
           commit, exactly the warm-adopt trap the r38 comment above documents) */
        if (!dev.workbench.registry.SidecarIO.writeAtomic(new File(dir, "header.json"), Json.write(h), "index header")) return false;
        if (!recordPersistenceMtime(host.persistenceMtime())) return false;
        pending.removeAll(readyForHeader);
        readyForHeader.clear();
        return savePending();
    }

    /** The persisted engine state mtime as last written by US (build completion / save / close-save)
     *  — the exact foreign-edit witness: any writer other than this process
     *  leaves file.lastModified() != the recorded value. Max-merged and re-read
     *  at call sites so a concurrent save can't be clobbered by an older capture.
     *  Returns false when the witness could NOT be written — a silent failure
     *  here declares a foreign edit on the next open and rebuilds the full
     *  index for nothing, so the caller must be able to say so (round-14 F6). */
    public boolean recordPersistenceMtime(long mtime) {
        if (dir == null || mtime <= 0) return true;
        try {
            dir.mkdirs();
            Files.writeString(new File(dir, "jdb2-mtime").toPath(),
                    String.valueOf(Math.max(recordedPersistenceMtime(), mtime)));
            return true;
        } catch (Exception e) {
            System.err.println("workbench: jdb2-mtime witness write failed in " + dir + ": " + e
                    + " — the next open will see a phantom foreign edit and re-index");
            return false;
        }
    }

    public boolean recordJdb2Mtime(long mtime) {
        return recordPersistenceMtime(mtime);
    }

    public long recordedPersistenceMtime() {
        try {
            File f = new File(dir, "jdb2-mtime");
            if (f.isFile()) return Long.parseLong(Files.readString(f.toPath()).trim());
        } catch (Exception e) {
            /* the last silent sidecar read (fix-1-r7 F8): a witness read failure
               means the next open sees a phantom foreign edit and re-indexes —
               that must be diagnosable */
            System.err.println("workbench: jdb2-mtime witness read failed in " + dir + ": " + e);
        }
        return -1;
    }

    public long recordedJdb2Mtime() {
        return recordedPersistenceMtime();
    }

    /** Cold → warm: hydrate the in-memory tier from the gz corpus. Single-flight
     *  (concurrent searches wait for the one reader, not N× the read + transient);
     *  disk reads run OFF the monitor so invalidate()/rename() never block; the
     *  merge dedupes against entries the drainer re-added while we read and
     *  skips classes mid-re-index (pending) — absent beats silently stale. */
    private final Object hydLock = new Object();
    private boolean hydrating;

    private void ensureWarm() {
        if (warm || !persisted || dir == null) return;
        synchronized (hydLock) {
            while (!warm && hydrating) {
                try { hydLock.wait(); }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            if (warm) return;
            hydrating = true;
        }
        try {
            Map<String, String> snapshot;
            synchronized (this) {
                snapshot = new LinkedHashMap<>(live);         // CME guard: copy under the monitor
            }
            List<Entry> loaded = new ArrayList<>(snapshot.size());
            Set<String> missing = new LinkedHashSet<>();
            for (Map.Entry<String, String> e : snapshot.entrySet()) {
                String text = readGz(e.getKey());
                if (text != null) loaded.add(new Entry(e.getKey(), e.getValue(), text.split("\n", -1)));
                else missing.add(e.getKey());
            }
            synchronized (this) {
                if (warm) return;
                Set<String> present = new HashSet<>();
                for (Entry en : entries) present.add(en.addr());
                List<Entry> toAdd = new ArrayList<>(loaded.size());
                for (Entry en : loaded)
                    if (live.containsKey(en.addr()) && !pending.contains(en.addr()) && present.add(en.addr()))
                        toAdd.add(en);
                /* ONE bulk add: per-element add() on a CopyOnWriteArrayList is a
                   full-array copy per class — O(N²) memcpy at GMS scale
                   (app-review-opus P1-1) */
                entries.addAll(toAdd);
                for (String addr : missing) {
                    if (!live.containsKey(addr) || present.contains(addr) || pending.contains(addr)) continue;
                    pending.add(addr); invalidations.put(addr, ++invalidationSequence);
                    reindexQueue.offer(addr);
                }
                if (!missing.isEmpty()) savePending();
                warm = true;
            }
            ensureDrainer();
        } finally {
            synchronized (hydLock) {
                hydrating = false;
                hydLock.notifyAll();
            }
        }
    }

    // ------------------------------------------------------------ build

    /** (Re)start the build. A running build is cancelled first; a builder that
     *  survives the 2s join (stuck in a long decompile) is SUPERSEDED — the
     *  generation gate stops its corpus writes, and the corpus dir is wiped
     *  only when the old builder is confirmed dead. NO-OP after kill() (review
     *  F6 r37: the javadoc hid that the call can be a total no-op). */
    public synchronized void start() {
        if (dead) return;   // kill() owns the end state (review finding 1 r36)
        boolean oldAlive = false;
        if (thread != null && thread.isAlive()) {
            cancel = true;
            interruptWorker();
            try { thread.join(2_000); } catch (InterruptedException ignored) {}
            oldAlive = thread.isAlive();
        }
        /* kill() lands with NO index monitor now (round-38) — re-test after
           the join, before the cancel=false overwrite: a killed index never
           respawns, and cancel stays true for any survivor's loop-top test */
        if (dead) return;
        cancel = false;
        int gen = ++generation;
        entries.clear();
        reindexQueue.clear();
        pending.clear();
        readyForHeader.clear();
        invalidations.clear();
        live = new LinkedHashMap<>();
        persisted = false;
        adoptedFromDisk = false;
        corpusBuiltAtMs = 0;
        warm = true;
        builtAtMs = 0;
        /* the corpus wipe moved INTO build(), behind guard 1 (finding 3 r38):
           a start() that never gets its builder running — dead-refused, or a
           reindex raced against close() — used to destroy the persisted
           corpus HERE and rebuild nothing (7/10 race iterations, E_NOTFOUND
           to the caller; "kill() is the backstop" backstopped the header,
           not the wipe) */
        built = 0;
        total = 0;
        fullScanComplete = false;
        error = null;
        startedAt = System.currentTimeMillis();
        finishedAt = 0;
        state = "building";
        final boolean mayWipe = !oldAlive;   // wipe only when the old builder is confirmed dead
        thread = new Thread(() -> {
            try { build(gen, mayWipe); } finally { workerFinished(gen); }
        }, "search-index");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        thread.start();
    }

    private static void deleteRecursively(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteRecursively(k);
        f.delete();
    }

    /**
     * Fine-grained invalidation after an edit (rename/comment): drop the class's
     * stale entries (memory AND gz) and re-queue it for the configured refresh mode.
     * {@code staleAddr} is the class addr BEFORE the edit (a class rename shifts
     * it), {@code freshAddr} the addr to re-index (same for member edits).
     * Never runs a decompile on the caller's thread. The label lookup takes the
     * engine lock OUTSIDE this class's monitor (lock order: engine → index).
     */
    public void invalidate(String staleAddr, String freshAddr) {
        initializeIdentity();
        String label = freshAddr == null ? null : host.classLabel(freshAddr); // engine lock first
        synchronized (this) {
            if (staleAddr != null) {
                entries.removeIf(e -> e.addr().equals(staleAddr));
                deleteGz(staleAddr); live.remove(staleAddr);
                pending.remove(staleAddr); readyForHeader.remove(staleAddr);
                invalidations.put(staleAddr, ++invalidationSequence);
                reindexQueue.removeIf(staleAddr::equals);
            }
            if (freshAddr != null) {
                entries.removeIf(e -> e.addr().equals(freshAddr));
                deleteGz(freshAddr); live.put(freshAddr, label);
                pending.add(freshAddr); readyForHeader.remove(freshAddr);
                invalidations.put(freshAddr, ++invalidationSequence);
                reindexQueue.offer(freshAddr);
            }
            savePending();
        }
        ensureDrainer();
    }

    /** Batch form of {@code invalidate(cc, cc)} over a set: ONE entries.removeIf,
     *  one savePending, one drainer wake — the per-class loop paid a full COW
     *  array copy + a whole-file pending.json write per referrer class, so a
     *  rename over a hot method's ref set ran M×O(N) under the engine lock
     *  (fix-4 review X1). Per-element semantics identical to invalidate(cc,cc). */
    public void invalidateAll(java.util.Collection<String> addrs) {
        if (addrs == null || addrs.isEmpty()) return;
        java.util.Set<String> set = new java.util.LinkedHashSet<>(addrs);
        set.remove(null);
        if (set.isEmpty()) return;
        initializeIdentity();
        Map<String,String> labels = new LinkedHashMap<>();
        for (String a : set) labels.put(a, host.classLabel(a)); // engine lock first
        synchronized (this) {
            entries.removeIf(e -> set.contains(e.addr()));
            for (String a : set) {
                deleteGz(a); live.put(a, labels.get(a));
                pending.add(a); readyForHeader.remove(a);
                invalidations.put(a, ++invalidationSequence);
            }
            reindexQueue.addAll(set);
            savePending();
        }
        ensureDrainer();
    }

    /** Start the drain pass only when no builder is running — a running builder
     *  drains the queue itself at the end of its pass (see build()). The
     *  generation gate stops a superseded drainer from committing stale text. */
    private synchronized void ensureDrainer() {
        if (dead || cancel || !automaticRefresh) return;
        if (thread != null && thread.isAlive()) return;          // builder will drain
        if (reindexQueue.isEmpty()) return;
        final int gen = generation;
        thread = new Thread(() -> {
            try { drainQueue(gen, false); } finally { workerFinished(gen); }
        }, "search-index-reindex");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        thread.start();
    }

    /** Re-index queued classes. Failed, interrupted, or superseded attempts retain the
     * durable pending entry; a successful gzip alone is not a committed header claim. */
    private void drainQueue(int gen, boolean midBuild) {
        boolean didWork = false;
        for (;;) {
            String classAddr;
            long revision;
            synchronized (this) {
                if (cancel || dead || gen != generation) return;
                classAddr = reindexQueue.poll();
                revision = invalidations.getOrDefault(classAddr, 0L);
            }
            if (classAddr == null) break;
            if (cancel || dead || Thread.currentThread().isInterrupted() || gen != generation) return;
            String text;
            try { text = host.decompileText(classAddr); }
            catch (Throwable failure) { text = null; }
            if (text == null) continue;
            if (cancel || dead || Thread.currentThread().isInterrupted() || gen != generation) return;
            String label = host.classLabel(classAddr); // never hold the index while requesting engine
            synchronized (this) {
                if (revision != invalidations.getOrDefault(classAddr, 0L)) continue;
                if (!beginCommit(gen, false)) return;
                try {
                    if (!writeGz(classAddr, text)) continue;
                    entries.removeIf(e -> e.addr().equals(classAddr));
                    entries.add(new Entry(classAddr, label, text.split("\n", -1)));
                    live.put(classAddr, label);
                    readyForHeader.add(classAddr);
                    didWork = true;
                } finally { endCommit(); }
            }
        }
        synchronized (this) {
            if (didWork && !midBuild && beginCommit(gen, false)) {
                try {
                    finishedAt = System.currentTimeMillis();
                    builtAtMs = finishedAt;
                    persisted = writeHeader();
                } finally { endCommit(); }
            }
        }
    }

    /** Hand off under ensureDrainer's monitor, including a full builder's final pass. */
    private synchronized void workerFinished(int gen) {
        if (thread != Thread.currentThread()) return;
        thread = null;
        ensureDrainer(); // a rejected warm generation may have queued fresh recovery work
    }

    private boolean beginCommit(int gen, boolean completingBuild) {
        synchronized (interruptGate) {
            if (gen != generation || (!completingBuild && (cancel || dead || Thread.currentThread().isInterrupted()))) return false;
            Thread current = Thread.currentThread();
            if (Thread.interrupted()) interruptAfterCommit.add(current);
            committing.add(current);
            return true;
        }
    }

    private void endCommit() {
        synchronized (interruptGate) {
            Thread current = Thread.currentThread();
            committing.remove(current);
            if (interruptAfterCommit.remove(current)) current.interrupt();
        }
    }

    private void interruptWorker() {
        synchronized (interruptGate) {
            Thread worker = thread;
            if (worker == null) return;
            if (committing.contains(worker)) interruptAfterCommit.add(worker);
            else worker.interrupt();
        }
    }

    public void cancel() {
        cancel = true;
        interruptWorker();
        if ("building".equals(state)) state = "cancelled";
    }

    /** Permanent shutdown. No index monitor or filesystem I/O is acquired here:
     * close may already own the engine monitor. A short interrupt-control gate
     * defers interruption of an active filesystem commit; pending work otherwise
     * remains recorded for the next open. */
    private volatile boolean dead;
    public void kill() {
        dead = true;
        cancel();
    }

    /* finding 5 r38: the cancelled STATUS write used to run off-monitor — a
       builder descheduled past start()'s 2s join could stamp "cancelled"
       over the live generation's "building". One guarded writer. */
    private void markCancelled(int gen) {
        synchronized (this) {
            if (gen == generation && "building".equals(state)) state = "cancelled";
        }
    }

    private void build(int gen, boolean mayWipe) {
        List<String[]> classes;
        try {
            classes = host.classSnapshot();
        } catch (Throwable t) {
            state = "failed";
            error = t.toString();
            return;
        }
        /* finding 1 r37, guard 1 of 2: classSnapshot() blocks on the ENGINE
           monitor — a close() holding it kill()s us, then clears dexUnits,
           and the snapshot comes back EMPTY: the loop below runs ZERO
           iterations and every in-loop test is skipped. Test HERE... */
        if (cancel || dead || gen != generation) {
            markCancelled(gen);
            return;
        }
        /* finding 5 r39: total stays BELOW the guard — above it, a superseded
           builder's EMPTY snapshot clobbered the live generation's total
           (Case T: status rendered built=225/0); start() already zeroes it,
           so finding 7's motive was wrong */
        total = classes.size();
        /* finding 3 r38: the corpus wipe lives HERE, behind guard 1 — a
           reindex raced against close() used to wipe in start() and rebuild
           nothing. Finding 1 r39: deleteRecursively on the builder thread
           raced the SUCCESSOR's gz writes (a wipe outlasts start()'s 2s
           join at 50k files, ≈26s at GMS scale) and the successor committed
           a header claiming a corpus that no longer existed (0% coverage
           adopted, permanently). Rename is atomic and instant — nothing is
           destroyed until the new corpus exists; the delete runs off every
           monitor. Still gated on the old builder being dead (mayWipe) */
        if (mayWipe && dir != null && dir.isDirectory()) {
            File oldDir = new File(dir.getPath() + ".old-" + gen);
            if (oldDir.isDirectory()) deleteRecursively(oldDir);   // a crashed delete's leftover
            synchronized (this) { dir.renameTo(oldDir); }
            deleteRecursively(oldDir);
        }
        initializeIdentity();
        // WORKBENCH_PROF=1: per-class decompile cost + corpus size (monster hunt)
        long profCount = 0, profSumMs = 0, profMaxMs = 0, profChars = 0;
        String profMaxAddr = "";
        java.util.PriorityQueue<long[]> profTop = new java.util.PriorityQueue<>(
                java.util.Comparator.comparingLong(a -> a[0]));
        for (String[] c : classes) {
            if (cancel || dead || Thread.currentThread().isInterrupted() || gen != generation) {
                markCancelled(gen);
                return;                                   // superseded/killed: touch nothing further
            }
            long revision;
            synchronized (this) { revision = invalidations.getOrDefault(c[0], 0L); }
            String text;
            long d0 = System.nanoTime();
            try {
                text = host.decompileText(c[0]);              // engine-locked burst
            } catch (Throwable t) {
                text = null;
            }
            long dms = (System.nanoTime() - d0) / 1_000_000;
            // cheap classes: evict the decompiled products right after indexing
            // (bulk churn); monsters stay pinned — re-paying 80s+ is the real risk
            if (dms < MONSTER_PIN_MS) host.evictDecompiled(c[0]);
            /* finding 3 r39: gen ONLY — cancel/dead are deliberately NOT
               tested here. close() parked on ENGINE behind the decompile
               that just finished, so the text in hand needs no engine lock
               to be written; testing kill here discarded the whole
               completed-except-this-class corpus for a one-class-early
               close (Case K). The NEXT loop-top guard still stops a killed
               build with classes remaining; the commit protocol decides */
            if (gen != generation) return;            // superseded mid-decompile: commit nothing
            if (PROF) {
                profCount++;
                profSumMs += dms;
                if (dms > profMaxMs) { profMaxMs = dms; profMaxAddr = c[0]; }
                if (text != null) profChars += text.length();
                profTop.offer(new long[] { dms, profCount });
                if (profTop.size() > 10) profTop.poll();
                if (profCount % 5000 == 0)
                    System.err.println("PROF index n=" + profCount + "/" + total
                            + " avgMs=" + profSumMs / Math.max(1, profCount)
                            + " maxMs=" + profMaxMs + " (" + profMaxAddr + ")"
                            + " corpusMb=" + profChars / 1_048_576
                            + " heapUsedMb=" + (Runtime.getRuntime().totalMemory()
                                    - Runtime.getRuntime().freeMemory()) / 1_048_576);
            }
            synchronized (this) {
                if (revision != invalidations.getOrDefault(c[0], 0L)) continue;
                if (!beginCommit(gen, true)) return;
                try {
                    if (text != null && writeGz(c[0], text)) {
                        entries.removeIf(e -> e.addr().equals(c[0]));
                        entries.add(new Entry(c[0], c[1], text.split("\n", -1)));
                        live.put(c[0], c[1]);
                        if (pending.contains(c[0])) readyForHeader.add(c[0]);
                    } else {
                        pending.add(c[0]); readyForHeader.remove(c[0]);
                        invalidations.put(c[0], ++invalidationSequence);
                        savePending();
                    }
                } finally { endCommit(); }
            }
            built++;
            // mid-build drain: at GMS scale the class loop runs for HOURS, so
            // draining only at the end made accepted edits invisible to search
            // for the whole build (GMS MCP exercise). Drain every 500 classes.
            // The header write stays build-end only (gate in drainQueue).
            if (built % 500 == 0 && !reindexQueue.isEmpty()) drainQueue(gen, true);   // mid-build: never a header (finding 2 r38)
        }
        if (PROF)
            System.err.println("PROF index-done n=" + profCount
                    + " avgMs=" + profSumMs / Math.max(1, profCount)
                    + " maxMs=" + profMaxMs + " (" + profMaxAddr + ")"
                    + " corpusMb=" + profChars / 1_048_576
                    + " top10Ms=" + profTop.stream().mapToLong(a -> a[0]).sorted()
                            .mapToObj(String::valueOf)
                            .reduce("", (a, b) -> a.isEmpty() ? b : a + "," + b));
        /* findings 1+4 r38, the commit protocol: SUPERSEDE is the only
           discard — a reindex landing inside the drain must not let this
           builder commit over the live generation (Case J). A KILL is NOT a
           discard: close() blocks on ENGINE behind the in-flight decompile,
           so a kill can only land between completed classes or in the drain
           — the loop COMPLETED, and throwing away a finished corpus at GMS
           scale is hours of decompiles for one late close (da71057's
           behavior, which round-38 regressed). The claim stays gz-honest:
           pending (invalidated, not yet re-written) classes are EXCLUDED in
           writeHeader, and pending.json re-queues them on the next open
           (finding 2's disease was claiming what isn't on disk). One
           monitor-held, gen-tested region, BELOW the drain. */
        if (gen != generation) return;
        drainQueue(gen, true);                                    // mid-build renames; header-less by contract
        synchronized (this) {
            if (gen != generation) return;                        // superseded inside the drain (Case J)
            if (!beginCommit(gen, true)) return;
            try {
                finishedAt = System.currentTimeMillis();
                state = "built";
                fullScanComplete = true;
                builtAtMs = finishedAt;
                corpusBuiltAtMs = finishedAt;
                persisted = writeHeader() && dir != null;
            } finally { endCommit(); }
        }
    }

    /** The snapshot to query against (hydrates from the gz corpus first when
     *  cold; classes indexed so far while building — may be partial). */
    public List<Entry> entries() {
        ensureWarm();
        return entries;
    }

    /** Refresh edited owners only when requested in on-demand mode. Concurrent searches
     * share the result, including its durable text/header commit. A cancelled background
     * worker does not cancel explicit searches. Native calls never hold the index monitor;
     * generation/revision checks reject a render superseded by another edit or close.
     * Persistence failures retain pending work; this query may still use its fresh overlay. */
    public List<Entry> queryEntries() {
        synchronized (queryRefresh) { return refreshForQuery(); }
    }

    private List<Entry> refreshForQuery() {
        ensureWarm();
        Map<String, Long> dirty = new LinkedHashMap<>();
        Map<String, Entry> refreshed = new LinkedHashMap<>();
        int gen;
        synchronized (this) {
            if (dead) return List.of();
            if (pending.isEmpty()) return List.copyOf(entries);
            for (String addr : pending)
                dirty.put(addr, invalidations.getOrDefault(addr, 0L));
            gen = generation;
        }
        for (Map.Entry<String, Long> changed : dirty.entrySet()) {
            String addr = changed.getKey();
            synchronized (this) {
                if (dead || gen != generation || Thread.currentThread().isInterrupted()) return List.of();
                if (!pending.contains(addr) || !changed.getValue().equals(invalidations.getOrDefault(addr, 0L))) continue;
                // A prior query/background pass may have persisted the text but failed
                // its header. Retry that commit without paying native rendering again.
                if (readyForHeader.contains(addr)) {
                    for (Entry e : entries) if (addr.equals(e.addr())) refreshed.put(addr, e);
                    if (refreshed.containsKey(addr)) continue;
                }
            }
            String text, label;
            try { text = host.decompileText(addr); label = host.classLabel(addr); }
            catch (Exception failure) { continue; }
            if (text == null) continue;
            synchronized (this) {
                if (dead || gen != generation || Thread.currentThread().isInterrupted()) return List.of();
                if (!changed.getValue().equals(invalidations.getOrDefault(addr, 0L))) continue;
                Entry entry = new Entry(addr, label, text.split("\n", -1));
                refreshed.put(addr, entry);
                // Explicit query: cancellation of the background worker is irrelevant.
                // Close/interruption were checked above; the gate protects atomic I/O.
                if (!beginCommit(gen, true)) return List.of();
                try {
                    if (!writeGz(addr, text)) continue;
                    entries.removeIf(e -> e.addr().equals(addr)); entries.add(entry);
                    live.put(addr, label); readyForHeader.add(addr);
                    reindexQueue.removeIf(addr::equals);
                } finally { endCommit(); }
            }
        }
        synchronized (this) {
            if (dead || gen != generation || Thread.currentThread().isInterrupted()) return List.of();
            if (!readyForHeader.isEmpty() && beginCommit(gen, true)) {
                try {
                    finishedAt = builtAtMs = System.currentTimeMillis();
                    persisted = writeHeader();
                } finally { endCommit(); }
            }
            Map<String, Entry> snapshot = new LinkedHashMap<>();
            for (Entry e : entries) if (!pending.contains(e.addr())) snapshot.put(e.addr(), e);
            for (var e : refreshed.entrySet())
                if (dirty.get(e.getKey()).equals(invalidations.getOrDefault(e.getKey(), 0L)))
                    snapshot.put(e.getKey(), e.getValue());
            return List.copyOf(snapshot.values());
        }
    }

    public synchronized Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("state", state);
        m.put("sourceIdentity", sourceIdentity);
        m.put("builderRuntime", builderRuntime);
        m.put("engineVersion", engineVersion);
        int pendingCount;
        synchronized (this) { pendingCount = pending.size(); }
        m.put("partialCoverage", !fullScanComplete || live.size() != total || pendingCount > 0);
        m.put("indexedClasses", live.keySet().stream().filter(a -> !pending.contains(a)).count());
        m.put("revision", generation + ":" + invalidationSequence + ":" + builtAtMs);
        m.put("warmRejectReason", warmRejectReason);
        m.put("mode", automaticRefresh ? "automatic" : "on-demand");
        m.put("automaticRefresh", automaticRefresh);
        m.put("cancelRequested", cancel);
        m.put("built", built);
        m.put("total", total);
        m.put("pendingReindex", pendingCount);
        m.put("persisted", persisted);
        m.put("warm", warm);                                  // corpus resident in memory
        m.put("adoptedFromDisk", adoptedFromDisk);            // warm-load adoption (the proof)
        m.put("loadedClasses", entries.size());
        m.put("workerRunning", thread != null && thread.isAlive());
        if (corpusBuiltAtMs > 0) m.put("corpusBuiltAtMs", corpusBuiltAtMs);
        if (builtAtMs > 0) m.put("builtAtMs", builtAtMs);
        if (error != null) m.put("error", error);
        m.put("ticket", ticket);
        if (startedAt > 0) {
            long end = finishedAt > 0 ? finishedAt : System.currentTimeMillis();
            m.put("ms", end - startedAt);
            if (finishedAt > 0) m.put("ageMs", System.currentTimeMillis() - finishedAt);
        }
        return m;
    }
}
