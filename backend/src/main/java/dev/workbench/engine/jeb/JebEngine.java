package dev.workbench.engine.jeb;

import com.pnfsoftware.jeb.core.IEnginesContext;
import com.pnfsoftware.jeb.core.IRuntimeProject;
import com.pnfsoftware.jeb.core.RuntimeProjectUtil;
import com.pnfsoftware.jeb.core.output.text.IObjectLocation;
import com.pnfsoftware.jeb.core.actions.ActionContext;
import com.pnfsoftware.jeb.core.actions.ActionRenameData;
import com.pnfsoftware.jeb.core.actions.ActionOverridesData;
import com.pnfsoftware.jeb.core.actions.Actions;
import com.pnfsoftware.jeb.core.output.text.ITextDocumentPart;
import com.pnfsoftware.jeb.core.properties.IPropertyManager;
import com.pnfsoftware.jeb.core.units.IUnit;
import com.pnfsoftware.jeb.core.units.code.IDecompilerUnit;
import com.pnfsoftware.jeb.core.units.code.ISourceUnit;
import com.pnfsoftware.jeb.core.units.code.android.DalvikCallgraphBuilder;
import com.pnfsoftware.jeb.core.units.code.android.IDexDecompilerUnit;
import com.pnfsoftware.jeb.core.units.code.android.IDexReferenceManager;
import com.pnfsoftware.jeb.core.units.code.android.IDexUnit;
import com.pnfsoftware.jeb.core.units.code.android.dex.DexPoolType;
import com.pnfsoftware.jeb.core.units.code.android.dex.IDexAddress;
import com.pnfsoftware.jeb.core.units.code.android.dex.IDalvikInstruction;
import com.pnfsoftware.jeb.core.units.code.android.dex.IDexClass;
import com.pnfsoftware.jeb.core.units.code.android.dex.IDexCodeItem;
import com.pnfsoftware.jeb.core.units.code.android.dex.IDexItem;
import com.pnfsoftware.jeb.core.units.code.android.dex.IDexField;
import com.pnfsoftware.jeb.core.units.code.android.dex.IDexMethod;
import com.pnfsoftware.jeb.core.units.code.android.dex.IDexMethodData;
import com.pnfsoftware.jeb.core.units.code.android.dex.IDexString;
import com.pnfsoftware.jeb.core.units.code.android.dex.IDexAnnotation;
import com.pnfsoftware.jeb.core.units.code.android.dex.IDexAnnotationElement;
import com.pnfsoftware.jeb.core.units.code.android.dex.IDexAnnotationItem;
import com.pnfsoftware.jeb.core.units.code.android.dex.IDexAnnotationsDirectory;
import com.pnfsoftware.jeb.core.units.code.android.dex.IDexDebugInfo;
import com.pnfsoftware.jeb.core.units.code.android.dex.IDexPrototype;
import com.pnfsoftware.jeb.core.units.code.android.dex.IDexExceptionHandler;
import com.pnfsoftware.jeb.core.units.code.android.dex.IDexExceptionItem;
import com.pnfsoftware.jeb.core.units.code.android.dex.IDexType;
import com.pnfsoftware.jeb.core.units.code.android.dex.IDexValue;
import com.pnfsoftware.jeb.core.units.code.java.IJavaCall;
import com.pnfsoftware.jeb.core.units.code.java.IJavaClass;
import com.pnfsoftware.jeb.core.units.code.java.IJavaConstant;
import com.pnfsoftware.jeb.core.units.code.java.IJavaDefinition;
import com.pnfsoftware.jeb.core.units.code.java.IJavaElement;
import com.pnfsoftware.jeb.core.units.code.java.IJavaField;
import com.pnfsoftware.jeb.core.units.code.java.IJavaIdentifier;
import com.pnfsoftware.jeb.core.units.code.java.IJavaInstanceField;
import com.pnfsoftware.jeb.core.units.code.java.IJavaMethod;
import com.pnfsoftware.jeb.core.units.code.java.IJavaNew;
import com.pnfsoftware.jeb.core.units.code.java.IJavaSourceUnit;
import com.pnfsoftware.jeb.core.units.code.java.IJavaStaticField;
import com.pnfsoftware.jeb.core.units.code.java.IJavaType;
import com.pnfsoftware.jeb.core.units.code.java.JavaDocument;
import com.pnfsoftware.jeb.core.util.DecompilerHelper;
import com.pnfsoftware.jeb.util.graph.Digraph;
import dev.workbench.engine.Engine;
import dev.workbench.engine.StringClassifier;
import dev.workbench.engine.TypeNames;
import dev.workbench.engine.index.SearchIndex;
import dev.workbench.registry.OpException;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JEB 5.42 engine adapter (WORKBENCH_SPEC §3.2): one adapter instance = one target =
 * one {@link IRuntimeProject} inside the shared {@link JebCore} engines context.
 *
 * API sequences are ported from the proven old-Jeeves JebSession (5.40, unchanged in
 * 5.42): JebCoreService → IEnginesContext → createProject/loadProject → IDexUnit →
 * DalvikCallgraphBuilder (populates the reference manager for xrefs) →
 * IDexDecompilerUnit for decompile + JavaDocument for the semantic token map.
 *
 * Addressing: Dalvik addresses ("Lcom/x/Y;", "Lcom/x/Y;->m(I)V", "Lcom/x/Y;->f:I")
 * are the stable handles; resolve() also accepts dotted FQCNs and unique simple names.
 * Renames/comments go through the JEB API and persist natively in the .jdb2 on save()
 * (and automatically on close() when dirty).
 *
 * All JEB-touching ops are synchronized on this instance (per-target session guard,
 * WORKBENCH_SPEC §2.5); the analysis itself runs on a background thread.
 */
public final class JebEngine implements Engine, SearchIndex.Host {

    private final String id, name, path;
    private final File artifact;                       // null when the target IS a bare .jdb2
    private final File jdb2;
    private final IEnginesContext engines;

    private volatile boolean ready;
    private volatile String state = "idle";            // idle | loading | ready | error | closed
    private volatile String phase = "", message = "", error = null;
    private volatile long openNanos;

    /* Serializes every project-lifecycle call into the shared IEnginesContext
       (load/create/save/unload) — concurrent calls race JEB's lazy init and a
       tripped race poisons the context for the JVM's lifetime. See doOpen. */
    private static final Object OPEN_LOCK = new Object();

    private volatile IRuntimeProject project;   // close() reads it outside OPEN_LOCK (review R5)
    private volatile Map<String, Object> inputIdentity;   // the embedded input's sha256, hashed once per open (~1 s for a 100 MB apk)
    /* the JEB key of the project this engine holds — while its database loads, the key on record
       for it. target.open's pre-check reads it with no lock and no JEB call (see PROJECT KEYS). */
    private volatile String liveKey;
    private volatile String displaced;          // why this target can no longer be saved: a same-key load took its place in the JEB context
    private boolean freshAnalysis;              // doOpen analysed the artifact (no database to load); read by the open thread only
    /* history entries are being re-applied (applyDirtyReplay) and, after a fresh analysis, not yet
       saved: until it clears, close() does not save and save() refuses — a file written half-way
       would carry a time newer than every entry still to come, and "replay what is newer than
       the file" would then skip them for good (review 2026-10-05) */
    private volatile boolean replayPending;
    private volatile int replayFailed;
    private volatile Boolean primedWithJdb2;    // whether the database file existed when the Workspace computed the replay list
    private volatile boolean closing;           // set before close() contends the lock (review R3)
    /* every byte-state of the jdb2 this engine has written or loaded — the
       identity JEB's registry holds. The twin-dedupe must compare against the
       whole HISTORY (review #4: each save rewrites the file, so a copy taken
       between any two saves matches one of these, never "the" current hash). */
    private final java.util.Set<String> contentHashes = java.util.concurrent.ConcurrentHashMap.newKeySet();
    @Override public java.util.Set<String> contentHashHistory() {
        /* the LIVE set, not a per-call copy (fix-8-r2 F8): CHM-backed contains()
           is thread-safe and the Workspace dedupe reads it off-monitor (the
           pre-scan skip) and sizes it for save-detection — a copy was 2N
           allocations per open for no isolation benefit (the history only
           grows, so a mid-read add only ever ADDS a match) */
        return java.util.Collections.unmodifiableSet(contentHashes);
    }

    private static String sha1HexFile(File f) {
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
    private final List<IDexUnit> dexUnits = new ArrayList<>();
    private final Map<IDexUnit, IDexDecompilerUnit> decompilers = new LinkedHashMap<>();
    private final Map<IDexUnit, CallGraph> callGraphs = new LinkedHashMap<>();
    private volatile List<CodeString> codeStrings;
    private volatile Map<String, List<IDexClass>> subtypeIndex;
    private volatile boolean dirty;        // written under the engine monitor, read by info() with no lock (review finding 3)
    /* Decompilations are part of the JEB database: a class or method decompiled for the first
       time is unsaved work too (the user's rule — a session that only READ code still has
       something to save, and the next open is not "fresh"). Tracked per addr since the last
       save; the close dialog counts them, SAVE writes them, DISCARD drops them. */
    private final java.util.Set<String> newDecomp = new java.util.LinkedHashSet<>();   // under the engine monitor
    private volatile int newDecompCount;                                                 // info()/target.changes read it lock-free
    private volatile boolean decompDirty;
    private volatile boolean discarded;    // the user chose DISCARD on close: a LATCH, not the mutable dirty flag — close()'s save gate honors it and save()/rename()/comment() refuse once it is set, so no racing write can bake the dropped edits into the jdb2 (review c7395d2 A1)
    private volatile int classCount;       // survives a refactor that reorders the ternary (review finding 4)
    private volatile String packageName;   // cached once in doOpen — info() must not re-walk JEB per poll (review finding 2)
    private final SearchIndex searchIndex;

    public JebEngine(String id, String name, String path, File artifact, File jdb2) throws OpException {
        this.id = id;
        this.name = name;
        this.path = path;
        this.artifact = artifact;
        this.jdb2 = jdb2;
        if (jdb2 != null && dev.workbench.engine.CheckpointedFileSave.marker(jdb2.toPath()).toFile().exists())
            throw OpException.io("An interrupted database save has a recovery receipt. Check the previous process/lock and restore its verified checkpoint before opening: "
                    + dev.workbench.engine.CheckpointedFileSave.marker(jdb2.toPath()), "target=" + id);
        this.engines = JebCore.engines();
        // the persisted gz corpus lives in the artifact sidecar (Workspace.sidecarFor
        // convention: <artifact>.workbench/); jdb2-opened targets anchor on the jdb2
        File indexBase = artifact != null ? artifact : jdb2;
        File indexDir = indexBase == null ? null
                : new File(indexBase.getAbsolutePath() + ".workbench/search-index");
        this.searchIndex = new SearchIndex(this, "search-index:" + id, indexDir, JebCore.version());
    }

    // ------------------------------------------------------------ lifecycle

    /** Begin analysis on a background thread; poll info() for state/phase/message. */
    public void openAsync() {
        /* under the engine monitor, like close(): either the close already ran (nothing to
           start) or it runs after this and takes the engine out of LIVE again */
        synchronized (this) {
            if (closing || "closed".equals(state)) return;
            /* visible to target.open's pre-check from NOW, with the key on record — not from the
               moment doOpen gets OPEN_LOCK, minutes later behind another target's load: a second
               same-key open in between was accepted and died in doOpen as an error card */
            LIVE.add(this);
            liveKey = jdb2 != null && jdb2.isFile() ? recordedKey(jdb2) : null;
            state = "loading";
            phase = "analyze";
            /* "again": no database to load but a history to re-apply — a re-key (see firstSaveAfterReplay) */
            message = "Analyzing " + name + (jdb2 != null && !jdb2.isFile() && !dirtyReplay.isEmpty() ? " again, under a JEB key of its own" : "");
        }
        openNanos = System.nanoTime();
        Thread t = new Thread(() -> {
            try {
                doOpen();
                if (closing || "closed".equals(state)) return;   // closed mid-open: stay closed
                ready = true;                          // publishes the fields above (happens-before)
                int total = dirtyReplay.size();
                int n = applyDirtyReplay();
                recovered = n;
                if (replayFailed > 0)
                    throw new IllegalStateException("Recovery could not apply " + replayFailed + " history edit(s); the database was not saved. Preserve the checkpoint and history, correct the failed entries shown in the log, then reopen.");
                if (freshAnalysis && replayPending) firstSaveAfterReplay();   // the database reaches the disk only now, with the history in it
                replayPending = false;
                if (closing || "closed".equals(state)) return;   // a close landed while the replay ran: it owns the end state
                state = "ready";
                phase = "done";
                message = "Ready: " + classCount + " classes"
                        + (total == 0 ? "" : freshAnalysis ? " · re-applied " + n + " of " + total + " edit(s) from history" : " · recovered " + n + " unsaved edit(s)")
                        + (replayFailed > 0 ? " — " + replayFailed + " could not be applied, see the log" : "");
            } catch (Throwable e) {
                /* review N-1/F2: the reset must run EVEN when a close is in
                   flight — the early return below would otherwise leave the
                   ready=true corpse standing (a throw from applyDirtyReplay
                   lands here with ready ALREADY true), every later info()
                   walking totalClasses() over the just-unloaded units, and
                   close()'s save gate (ready && dirty) persisting the
                   half-replayed project this catch refuses to persist.
                   close()'s finally writes ready=false anyway, so running it
                   above the guard is free. The error must own the not-ready
                   shape: no walk, and requireReady's spoken "target failed
                   to open". */
                ready = false;
                /* review F1/finding 1: kill(), not cancel() — the dead flag
                   makes start()/ensureDrainer() refuse from here on, so a
                   racing reindex can't resurrect the builder over the unload */
                searchIndex.kill();
                if (closing || "closed".equals(state)) return;      // close() owns the end state
                /* a failed open may still have registered the project in the JEB
                   context, holding the .jdb2.lock — release it FIRST, or a retry of
                   this target poisons on our own leftover (observed live: NPE at
                   load, retry blocked until a manual close + lock removal). The
                   error state is published only AFTER the release, so the
                   openTarget fallthrough never reads "error" while the corpse still
                   holds the lock (review). Never SAVE here: a half-loaded project
                   is not ours to persist. */
                /* review finding 1: unload under the ENGINE monitor, the same
                   exclusion close()'s synchronized body gives — cancel() above
                   is advisory (the builder/drainer tests the flag only at loop
                   tops, and an in-flight decompile burst is NOT interruptible),
                   so without this hold the unload races a decompile running on
                   the very project being torn down. Lock order matches close():
                   this → OPEN_LOCK, never the reverse. Reentrancy is free:
                   doOpen ran on this same thread. */
                synchronized (this) {
                    /* finding 8 r36: close() is a synchronized method — it can
                       land between the :167 check and this hold and run to
                       completion; the re-check INSIDE makes skipping the
                       unload structural, not incidental. And the null-check
                       outside / getKey() inside shape below is safe ONLY
                       because abortIfClosing (the other project-nuller) runs
                       on this same open thread (review finding 6) — a refactor
                       moving the unload off it re-arms the NPE close()
                       documents at :2481-2484 */
                    if (closing || "closed".equals(state)) return;
                    if (project != null) {
                        synchronized (OPEN_LOCK) { unloadOrLog(project, "open failed: " + e); }
                        project = null; inputIdentity = null;
                    }
                    liveKey = null;
                    LIVE.remove(this);
                }
                state = "error";
                error = e.getClass().getSimpleName() + ": " + e.getMessage();
                message = "Failed: " + error;
                /* the ops channel records only the message — JEB's internal NPEs
                   are undebuggable without the stack */
                System.err.println("workbench: open failed for " + name + ":");
                e.printStackTrace(System.err);
            }
        }, "jeb-open-" + id);
        t.setDaemon(true);
        t.start();
    }

    /** Unload a project from the shared JEB context, under OPEN_LOCK. A failure here is a LEAK
     *  for the life of the process — the context keeps the project and every unit in it while
     *  our reference goes — and it also strands the .jdb2.lock; until 2026-09-21 every site
     *  swallowed it (a Kimi researcher cycling nine WhatsApp builds left a 16 GB heap full with
     *  one target open). So: log it with its cause, and after an OutOfMemoryError retry once
     *  when the collector has had the failed open's garbage back. Returns whether it unloaded. */
    private boolean unloadOrLog(IRuntimeProject prj, String why) {
        String key;
        try { key = prj.getKey(); } catch (Throwable t) { key = "?"; }
        /* unloadProject goes by KEY. If the key now names another project (a same-key database
           was loaded over this one), unloading "ours" would tear that other target down and leave
           ours loaded: do neither — say it, and let the handle go. */
        if (!isRegistered(prj)) {
            /* The context cannot unload it (by key it would reach another project, or nothing), and
               it still holds what an unload releases — measured 2026-10-05 on a displaced project
               (.qa/symbols/probe/KeyProbe4): its units stay, and so does <file>.jdb2.lock ("The JDB2
               database is locked" on the next open of this target). The lock is a heartbeat file
               JEB keeps touching, even after IRuntimeProject.close(). close() lets the project go
               without touching whatever the key names now, but does not remove the lock, and a
               SECOND close() throws — so: close once, then take the lock file away ourselves. It
               does not come back, and JEB loads and saves the database normally after that — with
               no lock file until this JVM ends, which the log line below says. */
            boolean closed = false;
            Throwable fail = null;
            try { if (!prj.isClosed()) prj.close(); closed = true; } catch (Throwable t) { fail = t; }
            boolean lockLeft = false;
            if (jdb2 != null) {
                File lock = new File(jdb2.getPath() + ".lock");
                if (closed && lock.isFile() && !lockInUse(prj)) lock.delete();
                lockLeft = lock.isFile();
            }
            Map<String, Object> ctx = new LinkedHashMap<>();
            ctx.put("target", id); ctx.put("name", name); ctx.put("key", key); ctx.put("why", why); ctx.put("closed", closed); ctx.put("lockLeft", lockLeft);
            String msg = key + " no longer names this target's project in the JEB context (a database with the same key was loaded over it):"
                    + " the project was closed through its own handle" + (closed ? "" : " — and that FAILED, it stays in the heap")
                    + (lockLeft ? "; its lock file is still there, the database opens again only after a restart or once " + jdb2.getName() + ".lock is removed"
                            : jdb2 == null ? "" : "; its lock file was removed — until the workbench restarts, " + jdb2.getName() + " is open WITHOUT a lock file when reopened");
            if (closed && !lockLeft) dev.workbench.server.Log.warn("jeb unloadProject", msg, ctx);
            else if (fail != null) { ctx.put("what", msg); dev.workbench.server.Log.error("jeb unloadProject", fail, ctx); }
            else dev.workbench.server.Log.error("jeb unloadProject", msg, ctx);
            return closed;
        }
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                engines.unloadProject(key);
                return true;
            } catch (Throwable ex) {
                Map<String, Object> ctx = new LinkedHashMap<>();
                ctx.put("target", id); ctx.put("name", name); ctx.put("key", key); ctx.put("why", why); ctx.put("attempt", attempt);
                dev.workbench.server.Log.error("jeb unloadProject" + (attempt == 1 && ex instanceof OutOfMemoryError ? " (retrying after gc)" : " FAILED — project leaked in the JEB context; .jdb2 lock may be stale"), ex, ctx);
                if (!(ex instanceof OutOfMemoryError) || attempt == 2) return false;
                System.gc();
                try { Thread.sleep(500); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return false; }
            }
        }
        return false;
    }

    // ------------------------------------------------------------ PROJECT KEYS
    /* What JEB does (observed through the published API, 2026-10-04; IEnginesContext says "the final
       project key may be different" of loadProject, and IRuntimeProject.getKey "uniquely identif[ies]
       a project within an engines context"):
         - createProject(name) makes a project whose key is that name; saveProject stores the key
           in the .jdb2; loadProject(path) answers a project whose key is the STORED one;
         - one context holds one project per key. Loading a second database that carries the key
           of a loaded project does not fail: the new project takes the old one's place. The old
           one still answers through its handle, but getProject / saveProject / unloadProject —
           all by key — now mean the new one: "save A" writes B's project into A's file, "close A"
           unloads B.
       Until 2026-10-04 the workbench named its projects "wb-" + target id, and target ids restart
       at t1 in every JVM — so the databases of different apks share keys (wb-t1, wb-t2 …) all over
       a store. An agent hit the mild face of it: a fresh open minted t3 while the open WhatsApp
       database had been created as wb-t3 months earlier ("The project already exists"). The other
       face is silent: two cached targets with the same key, open together.
       So: (1) a new project gets a key of its own (mintKey) — only a COPY of its database can
       carry the same one; (2) every database's key is written beside it, <file>.jdb2.key, whenever
       it is created, loaded or saved — a file cannot tell its key without being loaded, and
       loading is the damage; (3) a load is refused up front (loadClash) when its recorded key is
       held by an open target, when it has no record while an open target still has an old-style
       key it could share, or when it has no record and is opened by itself — a bare .jdb2, the
       way a copy or an older state of an open database comes in; (4) save and unload check that
       the key still names OUR project before acting by key (isRegistered), and a load that
       displaced a project anyway (a record that lied) is undone and the displaced target told.
       A displaced project is let go through its own handle (unloadOrLog).
       backend/jdb2-keys.sh records keys in a process of its own, one database at a time;
       backend/jdb2-rekey.sh sets a database aside so the next open analyses the apk again under a
       key of its own and re-applies the edit history (Workspace.dirtyReplayFor, saveReplayed). */

    private static final java.security.SecureRandom KEY_RANDOM = new java.security.SecureRandom();

    /** A key for a NEW project: the target id for the reader, sixteen random hex digits for
     *  uniqueness across every JVM and store this database will ever be loaded beside. */
    private String mintKey() {
        for (;;) {
            String k = "wb-" + id + "-" + String.format("%016x", KEY_RANDOM.nextLong());
            IRuntimeProject p = null;
            try { p = engines.getProject(k); } catch (RuntimeException ignored) {}
            if (p == null) return k;
        }
    }

    /** A key minted by mintKey: only a copy of that database can carry it too. */
    static boolean uniqueKey(String key) { return key != null && key.matches("wb-t\\d+-[0-9a-f]{16}"); }

    static File keyFile(File jdb2) { return new File(jdb2.getPath() + ".key"); }

    /** The key written beside a database, or null when there is no record. */
    public static String recordedKey(File jdb2) {
        try {
            File f = keyFile(jdb2);
            if (!f.isFile()) return null;
            String k = java.nio.file.Files.readString(f.toPath()).trim();
            return k.isEmpty() ? null : k;
        } catch (Exception e) { return null; }
    }

    /** Not a sidecar write: a record that cannot be written (a read-only folder) costs a refusal
     *  later, not user work — it must not raise the UI's "sidecar issues" alarm. */
    static void recordKey(File jdb2, String key) {
        if (jdb2 == null || key == null || key.equals(recordedKey(jdb2))) return;
        File dst = keyFile(jdb2), tmp = new File(dst.getPath() + ".tmp");
        try {
            java.nio.file.Files.writeString(tmp.toPath(), key + "\n");
            java.nio.file.Files.move(tmp.toPath(), dst.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            tmp.delete();
            Map<String, Object> ctx = new LinkedHashMap<>();
            ctx.put("file", dst.getPath()); ctx.put("key", key);
            dev.workbench.server.Log.warn("jeb key record", "could not write " + dst.getName() + " (" + e + ") — this database's key stays unknown to later opens", ctx);
        }
    }

    /** Every engine that holds (or is loading) a project — the targets a load could displace. */
    private static final java.util.Set<JebEngine> LIVE = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static JebEngine ownerOf(IRuntimeProject p) {
        for (JebEngine e : LIVE) if (e.project == p) return e;
        return null;
    }

    /** A load that would displace an open target: what kind of clash (same-key · closing · orphan ·
     *  no-record · bare-no-record), the key when it is known, the reason in words, and the targets
     *  it is about (none when no target holds the project). */
    public record Clash(String kind, String key, String message, List<String> targets) {}

    /** Why loading this database now would displace an open target — or null when it would not.
     *  With `engines` (doOpen, under OPEN_LOCK) it is authoritative: the targets' keys and every
     *  project in the context. Without (target.open's pre-check) it reads the targets' keys only —
     *  no lock, no JEB call: OPEN_LOCK is held for a whole load, analysis or save, and an open
     *  must answer at once.
     *  `bare`: the database is opened by itself, with no apk; `asked`: the file the caller named. */
    static Clash loadClash(IEnginesContext engines, File jdb2, boolean bare, String asked, JebEngine self) {
        String rec = recordedKey(jdb2);
        String what = asked == null || asked.isBlank() ? jdb2.getName() : asked;
        List<String> heldKeys = new ArrayList<>();
        List<JebEngine> owners = new ArrayList<>();
        for (JebEngine e : LIVE) {
            String k = e.liveKey;
            if (e == self || k == null) continue;
            /* under OPEN_LOCK (engines != null) only a LOADED project counts: an engine still
               waiting for the lock has announced the key it expects, and will run this same
               check against us when its turn comes — first into doOpen wins, the other is told */
            if (engines != null && e.project == null) continue;
            heldKeys.add(k); owners.add(e);
        }
        if (engines != null) {
            try {
                for (IRuntimeProject p : engines.getProjects())
                    if (ownerOf(p) == null) { heldKeys.add(p.getKey()); owners.add(null); }   // left by a failed open or close
            } catch (RuntimeException ignored) {}
        }
        if (rec != null) {
            for (int i = 0; i < heldKeys.size(); i++) {
                if (!rec.equals(heldKeys.get(i))) continue;
                JebEngine o = owners.get(i);
                if (o == null)
                    return new Clash("orphan", rec, what + " has the same internal JEB project key (" + rec + ") as a project a failed open or close left in the JEB"
                            + " context. No target holds it, so there is nothing to close: the workbench has to be restarted (operator)", List.of());
                if (o.closing)
                    return new Clash("closing", rec, what + " has the same internal JEB project key (" + rec + ") as " + o.id + " (" + o.name + "), which is still closing"
                            + " — its database is being written. Retry when `workspace` no longer lists it under `closing`", List.of());
                /* read by agents and MCP clients (a human's own open is carried out — Workspace
                   .rekeyForOpen): it must not send them to close the operator's target, nor to a
                   shell script that replaces one of his databases */
                return new Clash("same-key", rec, what + " cannot be open at the same time as " + o.id + " (" + o.name + "): their saved databases carry the same internal"
                        + " JEB project key (" + rec + ") and JEB holds one project per key, so loading this one would take " + o.id + "'s place."
                        + " If YOU opened " + o.id + ", close it and open this. Otherwise it takes the operator: "
                        + (bare ? "a .jdb2 opened by itself cannot be given a key of its own — its apk can (the operator opens the apk in the workbench)"
                                : "when they open this apk in the workbench it is analysed again under a key of its own, and from then on it opens beside " + o.id + " for every client"),
                        List.of(o.id));
            }
            return null;
        }
        List<String> old = new ArrayList<>(), oldIds = new ArrayList<>(), all = new ArrayList<>(), allIds = new ArrayList<>();
        for (int i = 0; i < heldKeys.size(); i++) {
            JebEngine o = owners.get(i);
            String label = o == null ? "a project no target holds (" + heldKeys.get(i) + ")" : o.id + " (" + o.name + ", " + heldKeys.get(i) + ")";
            all.add(o == null ? label : o.id + " (" + o.name + ")");
            if (o != null) allIds.add(o.id);
            if (uniqueKey(heldKeys.get(i))) continue;
            old.add(label);
            if (o != null) oldIds.add(o.id);
        }
        /* the exact command: the file by path (the operator's shell may point at another home, and
           the bare form would read every unrecorded database of a store to record this one) */
        String script = "backend/jdb2-keys.sh '" + jdb2.getPath().replace("'", "'\\''") + "'";
        if (!old.isEmpty())
            return new Clash("no-record", null, "the operator runs " + script + " once: the internal JEB key of " + what + "'s saved database is not on record, and "
                    + String.join(", ", old) + (old.size() == 1 ? " has an old-style key" : " have old-style keys") + " it may share. JEB cannot tell"
                    + " without loading it, and a same-key load takes the open target's place. The script reads the key in a process of its own and"
                    + " changes no database; opening " + what + " while none of those targets is open records it too", oldIds);
        if (bare && !heldKeys.isEmpty())
            return new Clash("bare-no-record", null, "the operator runs " + script + " once — or open " + what + " while no other target is open (open now: " + String.join(", ", all) + ")."
                    + " It has no key on record, and a database opened by itself can be a copy or an older state of one that is open now, with the same"
                    + " internal JEB key: JEB cannot tell without loading it, and a same-key load takes the open target's place. The script reads the"
                    + " key in a process of its own and changes no database", allIds);
        return null;
    }

    /** target.open's check before a target exists (Engines.preflightJeb): {kind, key or "", reason,
     *  the ids of the targets it is about…} when the load would displace a target, null when it
     *  may go ahead. Lock-free — see loadClash. */
    public static String[] preflight(File jdb2, Boolean bare, String asked) {
        if (jdb2 == null || !jdb2.isFile()) return null;
        Clash c = loadClash(null, jdb2, Boolean.TRUE.equals(bare), asked, null);
        if (c == null) return null;
        List<String> out = new ArrayList<>(List.of(c.kind(), c.key() == null ? "" : c.key(), c.message()));
        out.addAll(c.targets());
        return out.toArray(new String[0]);
    }

    /** Whether another engine's project persists to this engine's database file — its lock is
     *  then that project's, not the displaced one's. The Workspace opens one engine per jdb2;
     *  this is the guard for the day it does not. */
    private boolean lockInUse(IRuntimeProject mine) {
        for (JebEngine e : LIVE)
            if (e != this && e.project != null && e.project != mine && e.jdb2 != null && e.jdb2.equals(jdb2)) return true;
        return false;
    }

    /** True when the context's entry for the project's key is this very project. */
    private boolean isRegistered(IRuntimeProject prj) {
        try { return prj != null && engines.getProject(prj.getKey()) == prj; }
        catch (RuntimeException e) { return false; }
    }

    private void requireRegistered(String what) {
        if (!isRegistered(project))
            throw new IllegalStateException(what + " refused: this target's project is no longer the one JEB has under its key ("
                    + (project == null ? "?" : project.getKey()) + ") — a database with the same key was loaded over it. Nothing was written:"
                    + " saving by that key now would write the other project into " + (jdb2 == null ? "this file" : jdb2.getName())
                    + ". Close and reopen this target: its renames and comments replay from history on open, new decompilations are redone on demand");
    }

    /** Under OPEN_LOCK: a load took `other`'s place under `key`. Flag the target (info shows it,
     *  save refuses with the same words) and write it to the log. */
    private void markDisplaced(JebEngine other, String key, String extra) {
        String why = name + " (" + id + ") carries the same internal JEB key (" + key + ") and was loaded over this target."
                + " This target still reads, but it cannot be saved: close and reopen it — renames and comments replay from history";
        if (other != null) other.displaced = why;
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("target", id); ctx.put("name", name); ctx.put("key", key); ctx.put("displaced", other == null ? "a project no target holds" : other.id + " " + other.name);
        dev.workbench.server.Log.error("jeb open", "key clash found only AFTER the load: " + name + " and " + (other == null ? "another open project" : other.id + " " + other.name)
                + " carry " + key + "; the open was undone, and that target must be closed and reopened before it can be saved"
                + (extra == null ? "" : " — " + extra), ctx);
    }

    /** The first save of a database analysed AGAIN with its edit history re-applied (a re-key).
     *  It runs after the replay, never before: a file written first would be newer than every
     *  history entry, and the crash-window rule ("replay what is newer than the file") would then
     *  skip whatever a close, a stop or a crash kept from being re-applied — for good. So until
     *  this returns there is no .jdb2 at all, and any interruption leaves the marker and no file:
     *  the next open analyses and replays again. A refused or failed save fails the open. */
    private void firstSaveAfterReplay() throws Exception {
        phase = "save";
        message = "Saving " + jdb2.getName();
        synchronized (this) {
            if (closing || "closed".equals(state)) throw new IllegalStateException("target closed during open");
            boolean wrote = false;
            try {
                synchronized (OPEN_LOCK) {
                    requireRegistered("the first save");
                    for (IDexUnit dex : dexUnits) dex.notifyGenericChange();   // flush rename/comment metadata
                    wrote = persistProjectWithCheckpoint();
                    if (wrote) {
                        recordKey(jdb2, project.getKey());
                        String h = sha1HexFile(jdb2);
                        if (h != null) contentHashes.add(h);
                    }
                }
            } finally {
                if (!wrote) { jdb2.delete(); keyFile(jdb2).delete(); }   // nothing half-written may pass for the database
            }
            if (!wrote)
                throw new IllegalStateException("the first save of " + jdb2.getName() + " after re-applying its edit history was refused —"
                        + " nothing is persisted; open the target again (it is analysed and the history re-applied again)");
            dirty = false;
            clearNewDecomp();
            searchIndex.recordJdb2Mtime(jdb2.lastModified());
            dev.workbench.engine.Engines.replayMarker(jdb2).delete();   // the re-key is done
            replayPending = false;
        }
    }

    /* close() signals through `closing` BEFORE it contends OPEN_LOCK — checked
       between the open's stages: unload what we registered and die quietly, so
       a mid-analysis close never resurrects the engine ready with a held lock
       (review R3). */
    private void abortIfClosing() {
        if (!closing && !"closed".equals(state)) return;
        synchronized (OPEN_LOCK) {
            if (project != null) {
                unloadOrLog(project, "closed during open");
                project = null; inputIdentity = null;
            }
            liveKey = null;
        }
        throw new IllegalStateException("target closed during open");
    }

    /** Test/integration hook: block until analysis finishes (true) or the timeout lapses (false). */
    public boolean awaitReady(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (ready || "error".equals(state)) return ready;
            try { Thread.sleep(200); } catch (InterruptedException e) { return ready; }
        }
        return ready;
    }

    /* a peer's name for the site-A refusal — defensive: a project mid-teardown
       may throw on any accessor */
    private static String safePeerName(com.pnfsoftware.jeb.core.IRuntimeProject p) {
        try {
            String n = p.getName();
            return n != null ? n : String.valueOf(p.getKey());
        } catch (RuntimeException e) {
            return "(unreadable peer)";
        }
    }

    private void doOpen() throws Exception {
        long t0 = System.nanoTime();
        /* JEB's shared engines context is NOT safe for concurrent project
           lifecycle calls: two simultaneous loadProjects race a lazy-init list
           inside JEB (an NPE out of loadProject), and a tripped race
           POISONS the context — every later load in that JVM fails the same way
           (verified live twice; solo opens in a fresh JVM always succeed). Every
           lifecycle STAGE holds OPEN_LOCK individually, and a closing check runs
           between stages: close() signals through a volatile flag set BEFORE it
           contends the lock (review R3 — the mid-load check was unreachable when
           one hold spanned load+index+save, and a "quietly closed" target wrote
           its 90MB jdb2 anyway). */
        long atArtifact, atIndex, atSave = -1;
        synchronized (OPEN_LOCK) {
            if (closing || "closed".equals(state)) throw new IllegalStateException("target closed before open");
            /* in LIVE only past the closing check (a close that already ran has nothing to remove
               it again); out where the project is let go */
            LIVE.add(this);
            /* the replay list was computed at target.open, this runs when OPEN_LOCK is free — minutes
               later behind another target's load. A database set aside (backend/jdb2-rekey.sh) or
               put back in between makes the list the wrong one for what is about to happen */
            if (primedWithJdb2 != null && jdb2 != null && primedWithJdb2 != jdb2.isFile())
                throw new IllegalStateException(jdb2.getName() + (jdb2.isFile() ? " appeared" : " was removed") + " while this open was waiting — open the target again");
            replayPending = !dirtyReplay.isEmpty();
            if (jdb2 != null && jdb2.isFile()) {
                phase = "load";
                message = "Loading " + jdb2.getName();
                /* JEB identifies a project by the KEY it was created under, and that key is stored
                   in the database: loadProject(path) answers a project whose key is the one inside
                   the file. One context holds one project per key — a second database with the same
                   key takes the first one's place in the context (see "PROJECT KEYS" below), so the
                   clash is refused HERE, before the load, while refusing costs nothing. */
                Clash clash = loadClash(engines, jdb2, artifact == null, artifact == null ? null : artifact.getName(), this);
                if (clash != null) throw new IllegalStateException(clash.message());
                final String rec = recordedKey(jdb2);
                liveKey = rec;   // the key this load is expected to bring in — a same-key open is refused from now, not after the load
                Map<String, IRuntimeProject> before = new java.util.HashMap<>();
                for (IRuntimeProject p : engines.getProjects()) before.put(p.getKey(), p);
                IRuntimeProject loaded = null;
                Throwable loadFail = null;
                try { loaded = engines.loadProject(jdb2.getAbsolutePath(), true, null); }
                catch (Throwable t) { loadFail = t; }
                /* what the load did to the context — whether it returned or threw: a project that
                   was registered before and is not the one under its key any more was DISPLACED */
                JebEngine other = null;
                String lostKey = null;
                for (Map.Entry<String, IRuntimeProject> en : before.entrySet()) {
                    IRuntimeProject nowThere = null;
                    try { nowThere = engines.getProject(en.getKey()); } catch (RuntimeException ignored) {}
                    if (nowThere == en.getValue()) continue;
                    lostKey = en.getKey();
                    other = ownerOf(en.getValue());
                }
                if (loadFail != null) {
                    /* a load that throws can leave what it registered behind (seen live: an NPE at
                       load, then "locked" on the retry). project is still null, so the open-failure
                       path has nothing to unload: take out here whatever is new in the context. */
                    try {
                        for (IRuntimeProject p : engines.getProjects()) {
                            if (before.containsValue(p)) continue;
                            String k = p.getKey();
                            boolean gone = false;
                            try { gone = engines.getProject(k) == p && engines.unloadProject(k); } catch (Throwable ignored) {}
                            Map<String, Object> ctx = new LinkedHashMap<>();
                            ctx.put("target", id); ctx.put("name", name); ctx.put("key", k); ctx.put("unloaded", gone);
                            dev.workbench.server.Log.warn("jeb open", "the failed load of " + jdb2.getName() + " left a project in the JEB context"
                                    + (gone ? " — unloaded" : " — and it could NOT be unloaded: it stays until a restart"), ctx);
                        }
                    } catch (Throwable ignored) {}
                    liveKey = null;
                    if (lostKey != null) markDisplaced(other, lostKey, "the load itself failed (" + loadFail + ")");
                    if (loadFail instanceof Exception ex) throw ex;
                    throw (Error) loadFail;
                }
                String k = loaded.getKey();
                if (lostKey != null) {
                    /* the record beside the file was wrong and the keys DID clash: JEB has put this
                       project in the other one's place. Take ours out again — only if the key names
                       OURS, which is what the API says it does now — write the key down so the next
                       attempt is refused up front, and tell the other target: it still reads, but
                       it is no longer registered under its key and must be reopened to save. */
                    boolean undone = false;
                    try { undone = engines.getProject(k) == loaded && engines.unloadProject(k); } catch (Throwable ignored) {}
                    liveKey = null;
                    recordKey(jdb2, k);
                    markDisplaced(other, lostKey, undone ? null : "the newcomer could NOT be unloaded and stays in the JEB context until a restart");
                    throw new IllegalStateException(name + " was created under the same internal JEB key (" + k + ") as "
                            + (other == null ? "another open project" : other.id + " (" + other.name + ")") + ", and " + (rec == null ? "it had no key on record" : "the key on record for it was wrong") + ". The open was undone"
                            + (undone ? "" : " — incompletely: restart the workbench") + ". " + (other == null ? "That project" : other.id) + " still reads, but it has to be"
                            + " closed and reopened before it can be saved (renames and comments replay from history); the two cannot be open together");
                }
                project = loaded; inputIdentity = null;
                liveKey = k;
                recordKey(jdb2, k);
                { String h = sha1HexFile(jdb2); if (h != null) contentHashes.add(h); }
            } else {
                if (artifact == null || !artifact.isFile())
                    throw new IllegalStateException("no .jdb2 to load and no artifact to analyze: " + artifact);
                phase = "analyze";
                message = "Analyzing " + artifact.getName() + (replayPending ? " again, under a JEB key of its own" : "");
                freshAnalysis = true;
                final String key = mintKey();   // a key of its own: see "PROJECT KEYS"
                liveKey = key;
                try {
                    project = engines.createProject(key); inputIdentity = null;
                } catch (com.pnfsoftware.jeb.core.exceptions.JebRuntimeException ex) {
                    /* "The project already exists" for a key minted a moment ago and checked free:
                       the context still lists it as an UNLOADED entry (a record a context keeps of
                       a key after unloadProject(key)). clearUnloadedProject() drops such records
                       and concerns only projects that are NOT loaded. Retry once. A LOADED project
                       under the key would be somebody's open target — never touched: refuse. */
                    if (ex.getMessage() == null || !ex.getMessage().contains("already exists"))
                        throw ex;
                    com.pnfsoftware.jeb.core.IRuntimeProject peer = null;
                    try { peer = engines.getProject(key); }   /* guard: a throw here must not swallow the actionable error below */
                    catch (RuntimeException ignored) {}
                    if (peer != null)
                        throw new IllegalStateException("JEB project key " + key + " is already held by a loaded project ("
                                + safePeerName(peer) + ") — refusing to touch it", ex);
                    java.io.File pf = null;
                    try { pf = engines.getProjectFile(key); } catch (Exception ignored) {}
                    engines.clearUnloadedProject();
                    System.err.println("workbench: createProject hit a stale entry for " + key
                            + " (not loaded) — unloaded entries cleared; projectFile=" + pf + "; retrying once");
                    project = engines.createProject(key); inputIdentity = null;
                }
                // WORKBENCH_SEED_PERSIST_INPUT=1: persist the actual input bytes, so a database copied
                // elsewhere does not depend on its builder's absolute path. Ordinary projects keep
                // JEB's default storage policy.
                if ("1".equals(System.getenv("WORKBENCH_SEED_PERSIST_INPUT"))) {
                    if (artifact.length() > 512L * 1024 * 1024)
                        throw new IllegalStateException("Seed input exceeds the 512 MiB bound");
                    byte[] bytes = java.nio.file.Files.readAllBytes(artifact.toPath());
                    project.processArtifact(new com.pnfsoftware.jeb.core.Artifact(artifact.getName(),
                            new com.pnfsoftware.jeb.core.input.BytesInput(bytes, 0, bytes.length, artifact.getName())));
                } else project.processArtifact(artifact);
            }
            atArtifact = System.nanoTime();
        }
        abortIfClosing();
        synchronized (OPEN_LOCK) {
            /* indexUnits is under the lock too: dex.process() and the decompiler
               property manager are hierarchical JEB state, not demonstrably
               project-local (review), and a mid-indexUnits failure once left the
               registry believing a never-used project key existed */
            indexUnits();
            classCount = totalClasses();
            /* packageName cached ONCE here (review finding 2 r36): info() used
               to re-walk findUnitsByType on every poll — the last unsynchronized
               JEB walk. Single-threaded, project alive, inside the OPEN_LOCK
               hold. Throwable, not Exception (finding 8 r37: an Error here must
               not fail the whole OPEN — it used to cost only a missing key in
               info()); a no-IApkUnit artifact (plain .dex) stays null */
            try {
                List<com.pnfsoftware.jeb.core.units.code.android.IApkUnit> apks =
                        RuntimeProjectUtil.findUnitsByType(project,
                                com.pnfsoftware.jeb.core.units.code.android.IApkUnit.class, false);
                if (!apks.isEmpty()) packageName = apks.get(0).getPackageName();
            } catch (Throwable ignored) {}
            atIndex = System.nanoTime();
        }
        abortIfClosing();
        /* with a history to re-apply (a re-keyed database), the first save waits for it:
           firstSaveAfterReplay — the file exists on disk only once the replay is in it */
        if (jdb2 != null && !jdb2.isFile() && !replayPending) {
            phase = "save";
            message = "Saving " + jdb2.getName();
            boolean saved = false;
            synchronized (OPEN_LOCK) {
                /* re-checked INSIDE the lock: close() otherwise queues behind
                   saveProject for its whole length — verbatim the old symptom
                   (2.1s block + a 90MB write for a closed target, review F3) */
                if (!closing && !"closed".equals(state)) {
                    /* and the boolean is HONORED (round-13 F3): a refused initial
                       save used to report ready with no jdb2 on disk — twin
                       protection off for that engine for its whole life */
                    requireRegistered("the first save");
                    boolean wrote = persistProjectWithCheckpoint();
                    if (!wrote)
                        throw new IllegalStateException("initial saveProject refused for " + jdb2
                                + " — the analysis is not persisted; close and retry");
                    recordKey(jdb2, project.getKey());
                    dev.workbench.engine.Engines.replayMarker(jdb2).delete();   // a re-key with no history to re-apply ends here
                    { String h = sha1HexFile(jdb2); if (h != null) contentHashes.add(h); }   // the identity this engine CREATED
                    atSave = System.nanoTime();
                    saved = true;
                }
            }
            if (!saved) abortIfClosing();              // close landed before the save
            else if (closing || "closed".equals(state)) {
                /* close landed DURING the atomic save: the file is complete but
                   belongs to a dead open — delete it, then abort (review F3) */
                jdb2.delete();
                keyFile(jdb2).delete();
                abortIfClosing();
            }
        }
        prof("artifact", t0, atArtifact);
        prof("callgraph:" + classCount, t0, atIndex);
        if (atSave >= 0) prof("save", t0, atSave);
        message = "Open in " + (System.nanoTime() - t0) / 1_000_000 + " ms";
        // persisted gz corpus: warm-load when valid; re-index only classes the
        // history says were edited since the build.
        java.util.Set<String> recovery = searchIndex.recoveryCandidates();
        long builtAt = searchIndex.tryWarmLoad();
        List<String> replay = replaySince(builtAt);
        // Foreign-edit detection, exact form: the corpus records the jdb2 mtime
        // as WE last wrote it (build completion + every save). Any other writer
        // leaves mtime != witness → full rebuild; tracked edits keep the witness
        // exact and ride the history replay. A legacy corpus without a witness
        // falls back to: only a wholly unexplained mtime delta counts as
        // foreign. WORKBENCH_PARANOID=1 rebuilds on any delta regardless.
        boolean foreign = false;
        boolean onDemandIndex = "on-demand".equalsIgnoreCase(System.getenv("WORKBENCH_INDEX_MODE"));
        if (builtAt > 0 && jdb2 != null && jdb2.isFile()) {
            long recorded = searchIndex.recordedJdb2Mtime();
            if (recorded > 0) foreign = jdb2.lastModified() != recorded;
            else foreign = jdb2.lastModified() > builtAt && replay.isEmpty();
            if (System.getenv("WORKBENCH_PARANOID") != null && jdb2.lastModified() > builtAt)
                foreign = true;
        }
        if (builtAt <= 0 || foreign) {
            if (foreign) {
                searchIndex.rejectWarm("Database mtime witness changed outside this workbench");
                System.err.println("workbench: " + name
                        + " — jdb2 changed outside the workbench (mtime witness mismatch);"
                        + (onDemandIndex ? " queued on-demand cache recovery" : " rebuilding the search index"));
                phase = "index";
                message = onDemandIndex ? "Foreign jdb2 change detected — cache recovery queued" : "Foreign jdb2 change detected — rebuilding search index";
            } else if (searchIndex.warmRejectReason() != null) {
                System.err.println("workbench: " + name + " — persisted corpus rejected ("
                        + searchIndex.warmRejectReason() + (onDemandIndex ? "); cache recovery queued for search" : "); building search index"));
            }
            if (!onDemandIndex) searchIndex.start();
            else {
                recovery.addAll(indexReplay.keySet());
                java.util.Set<String> classes = new java.util.LinkedHashSet<>();
                for (String address : recovery) {
                    IDexClass cls = findClass(address);
                    if (cls != null) classes.add(cls.getAddress());
                }
                searchIndex.invalidateAll(classes);
                message += "; on-demand text index recovering " + classes.size() + " classes (partial coverage)";
            }
        } else {
            java.util.Set<String> replayClasses = new java.util.LinkedHashSet<>();
            for (String a : replay) {
                IDexClass cls = findClass(a);
                if (cls != null) replayClasses.add(cls.getAddress());
                replayClasses.addAll(referrerClasses(a));
            }
            searchIndex.invalidateAll(replayClasses);   // one pass + one pending write (fix-4 X1)
        }
    }

    /** Engine.primeIndexReplay: history addrs (any granularity) → edit ts. */
    private volatile Map<String, Long> indexReplay = Map.of();

    @Override
    public void primeIndexReplay(Map<String, Long> addrTs) {
        indexReplay = addrTs == null ? Map.of() : addrTs;
    }

    @Override
    public long searchIndexBuiltAt() {
        return searchIndex.builtAtMs();
    }

    /** Class addrs edited after the corpus was built (ts 0 = unknown → replay). */
    private List<String> replaySince(long builtAtMs) {
        if (builtAtMs <= 0) return List.of();
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Long> e : indexReplay.entrySet())
            if (e.getValue() == null || e.getValue() <= 0 || e.getValue() > builtAtMs)
                out.add(e.getKey());
        return out;
    }

    /** Crash-window recovery primed by the Workspace (history newer than the
     *  jdb2's last save). Re-applied through the normal write path AFTER ready,
     *  so index invalidation and the dirty flag ride along — the next close or
     *  ⌘S persists them into the jdb2. Conflicts with a foreign-edited jdb2 are
     *  skipped, not fatal. */
    private volatile List<Map<String, Object>> dirtyReplay = List.of();
    private volatile int recovered;

    @Override
    public void primeDirtyReplay(List<Map<String, Object>> entries) {
        dirtyReplay = entries == null ? List.of() : entries;
        primedWithJdb2 = jdb2 != null && jdb2.isFile();
    }

    /** One entry at a time under the engine monitor, with a closing check before each: a close
     *  between two entries stops the replay (close() saves nothing while replayPending). An entry
     *  that cannot be applied is counted and logged, never dropped in silence — its address may
     *  name a symbol as an UNDONE rename left it (history addresses are the ones current at edit
     *  time; undoing a class rename out of order strands the later entries of its members). */
    /** Test hook (KeysIT, -Dworkbench.test.replayPauseMs): a pause before each replayed entry, so a
     *  close can be landed in the middle of a replay. 0 everywhere else. */
    private static final long REPLAY_PAUSE_MS = Long.getLong("workbench.test.replayPauseMs", 0L);

    private int applyDirtyReplay() {
        int n = 0, failed = 0, at = 0;
        final int total = dirtyReplay.size();
        for (Map<String, Object> e : dirtyReplay) {
            String op = String.valueOf(e.get("op")), addr = String.valueOf(e.get("addr"));
            at++;
            if (!"rename".equals(op) && !"comment".equals(op)) continue;
            phase = "replay";
            message = "Re-applying edit " + at + " of " + total + " from history";
            if (REPLAY_PAUSE_MS > 0) try { Thread.sleep(REPLAY_PAUSE_MS); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            synchronized (this) {
                if (closing || "closed".equals(state)) throw new IllegalStateException("target closed during open");
                try {
                    if ("rename".equals(op)) {
                        /* a local's rename is recorded on its METHOD's addr (kind local, oldName →
                           newName); replayed as a symbol rename it renamed the method after its
                           variable — until 2026-10-05 */
                        if ("local".equals(e.get("kind"))) renameLocal(addr, String.valueOf(e.get("oldName")), String.valueOf(e.get("newName")), "recovery");
                        else if ("parameters".equals(e.get("kind"))) {
                            /* an analyst's argument mapping: its per-index changes replay through the parameter
                               binding — the entry's newName is the joined argument names, never a name for the
                               METHOD (fix-review 2026-10-08 C1: replayed as a rename it renamed the method) */
                            List<Map<String, Object>> fwd = new ArrayList<>();
                            if (e.get("parameterMutations") instanceof List<?> mutations) {
                                for (Object mutation : mutations) if (mutation instanceof Map<?,?> row) {
                                    Map<String,Object> copy = new LinkedHashMap<>();
                                    for (Map.Entry<?,?> field : row.entrySet()) copy.put(String.valueOf(field.getKey()), field.getValue());
                                    fwd.add(copy);
                                }
                            } else if (e.get("changes") instanceof List<?> cl) for (Object o : cl) if (o instanceof Map<?, ?> ch) {
                                Map<String, Object> c = new LinkedHashMap<>();
                                c.put("index", ch.get("index"));
                                if (Boolean.TRUE.equals(ch.get("cleared"))) c.put("clear", true); else c.put("name", ch.get("newName"));
                                fwd.add(c);
                            }
                            if (fwd.isEmpty()) throw new IllegalStateException("a parameters entry without changes cannot be replayed");
                            renameParameters(addr, fwd, "recovery");
                        } else {
                            /* a name the identifier rule refuses can only be the dex's own (rename.revert, or the
                               rename-back of an obfuscated "1cq"): the validated restoration, not rename() — which
                               refused it and lost the revert on every replay (fix-review 2026-10-08 H1) */
                            String nn = String.valueOf(e.get("newName"));
                            if (Boolean.TRUE.equals(e.get("revert")) || !nn.matches("[A-Za-z_$][A-Za-z0-9_$]*")) restoreOriginalName(addr, nn, "recovery");
                            else rename(addr, nn, "recovery");
                        }
                    } else {
                        comment(addr, String.valueOf(e.get("newText")), "recovery");
                    }
                    n++;
                } catch (Exception ex) {
                    failed++;
                    if (failed <= 200) {
                        Map<String, Object> ctx = new LinkedHashMap<>();
                        ctx.put("target", id); ctx.put("name", name); ctx.put("op", op); ctx.put("addr", addr);
                        if (e.get("kind") != null) ctx.put("kind", e.get("kind"));
                        ctx.put("to", "rename".equals(op) ? e.get("newName") : e.get("newText"));
                        dev.workbench.server.Log.warn("jeb replay", "a history entry could not be re-applied: " + ex.getMessage(), ctx);
                    }
                }
            }
        }
        replayFailed = failed;
        if (failed > 0) {
            Map<String, Object> ctx = new LinkedHashMap<>();
            ctx.put("target", id); ctx.put("name", name); ctx.put("applied", n); ctx.put("failed", failed);
            dev.workbench.server.Log.warn("jeb replay", name + ": " + failed + " of " + (n + failed) + " history entries could not be re-applied (each is logged above"
                    + (failed > 200 ? ", the first 200" : "") + ")", ctx);
        }
        return n;
    }

    /** WORKBENCH_PROF=1: heap marker per open phase (GMS-scale memory work). */
    private static final boolean PROF = System.getenv("WORKBENCH_PROF") != null;

    /** WORKBENCH_NOCG=1 (measurement only): skip the eager call-graph build to
     *  isolate what it costs in heap and materialized instructions. xref.from/
     *  callees degrade to empty in this mode — never ship a server run with it. */
    private static final boolean NOCG = System.getenv("WORKBENCH_NOCG") != null;

    static synchronized void prof(String phase, long t0) {
        prof(phase, t0, System.nanoTime());
    }

    /* stage timestamps captured inside OPEN_LOCK, emitted after — back-to-back
       prof() calls would otherwise report identical elapsedMs and ~0 deltas,
       and the instrumentation couldn't tell which stage is slow (review R10) */
    static synchronized void prof(String phase, long t0, long now) {
        if (!PROF) return;
        System.gc();                                     // approximate LIVE heap, not allocated
        Runtime rt = Runtime.getRuntime();
        System.err.println("PROF phase=" + phase
                + " heapLiveMb~=" + (rt.totalMemory() - rt.freeMemory()) / 1_048_576
                + " heapTotalMb=" + rt.totalMemory() / 1_048_576
                + " heapMaxMb=" + rt.maxMemory() / 1_048_576
                + " deltaMs=" + (profLast == 0 ? 0 : (now - profLast) / 1_000_000)
                + " elapsedMs=" + (now - t0) / 1_000_000);
        profLast = now;
    }

    private static long profLast;

    /** Locate DEX units, process them, build the call graph (recomputes xrefs — they are NOT
     *  persisted in the .jdb2, so this runs on both fresh analysis and jdb2 load). */
    private void indexUnits() {
        phase = "xrefs";
        message = "Building cross-references";
        dexUnits.clear();
        decompilers.clear();
        callGraphs.clear();
        codeStrings = null;
        subtypeIndex = null;
        List<IDexUnit> units = RuntimeProjectUtil.findUnitsByType(project, IDexUnit.class, false);
        for (IDexUnit dex : units) {
            if (!dex.isProcessed()) dex.process();
            if (!NOCG) {
                DalvikCallgraphBuilder cgb = new DalvikCallgraphBuilder(dex);
                Digraph cg = cgb.buildModel();
                callGraphs.put(dex, captureCallGraph(dex, cgb, cg));
            }
            dexUnits.add(dex);
            IDexDecompilerUnit dec = resolveDecompiler(dex);
            if (dec != null) {
                // monster guard: cap per-method decompilation effort (0 = unlimited;
                // the observed worst class at GMS took 82s — 120s only cuts the
                // truly pathological, never ordinary monsters)
                try {
                    dec.getPropertyManager().setInteger("MethodDecompilationTimeout", 120);
                } catch (Exception ignored) {}
                decompilers.put(dex, dec);
            }
        }
        if (dexUnits.isEmpty()) throw new IllegalStateException("project has no DEX units");
    }

    private static IDexDecompilerUnit resolveDecompiler(IDexUnit dex) {
        IDecompilerUnit d = DecompilerHelper.getDecompiler(dex);
        if (d instanceof IDexDecompilerUnit) return (IDexDecompilerUnit) d;
        for (IUnit child : dex.getChildren())
            if (child instanceof IDexDecompilerUnit) return (IDexDecompilerUnit) child;
        return null;
    }

    private int totalClasses() {
        int n = 0;
        /* walk a SNAPSHOT (review F5): close()'s finally clears dexUnits on the
           caller's thread while info() may already have read ready==true and
           entered here — a bare walk throws ConcurrentModificationException */
        for (IDexUnit dex : new ArrayList<>(dexUnits)) n += dex.getClasses().size();
        return n;
    }

    private void requireReady() throws OpException {
        if (ready) return;
        if ("error".equals(state))
            throw OpException.busy("target failed to open: " + error, "target=" + id);
        if ("closed".equals(state))
            throw OpException.notFound("target is closed", "target=" + id);
        throw OpException.busy("analysis still running (" + phase + ": " + message + ")",
                "target=" + id + " state=" + state);
    }

    // ------------------------------------------------------------ identity / caps / info

    @Override
    public Map<String, Object> caps() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("decompile", true);
        m.put("smali", true);
        m.put("xrefs", true);
        m.put("rename", true);
        m.put("comment", true);
        m.put("callgraph", true);
        m.put("strings", true);
        m.put("runs", true);
        m.put("persist", "native");                      // renames/comments land in the .jdb2 via JEB
        return m;
    }

    @Override
    public Map<String, Object> info() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        m.put("path", path);
        m.put("engine", "JEB");
        String v = JebCore.version();
        if (v != null) m.put("engineVersion", v);
        m.put("state", state);
        if (!ready || "loading".equals(state)) {   // loading with ready set: the history replay and its save are still running
            m.put("phase", phase);
            m.put("message", message);
            if (error != null) m.put("error", error);
        }
        /* classCount, NOT a walk (review finding 2): exact and immutable after
           indexUnits(), and info() is the ONLY unsynchronized reader — walking
           dexUnits here raced close()'s clear() (CME, then NPE even over a
           snapshot: clear() nulls the slots after publishing size=0) */
        m.put("classes", ready ? classCount : 0);
        Runtime rt = Runtime.getRuntime();
        m.put("heap", String.format("%.1fg", (rt.totalMemory() - rt.freeMemory()) / 1e9));
        m.put("dirty", dirty);
        m.put("unsavedDecompiles", newDecompCount);   // newly decompiled classes/methods a save would keep
        if (jdb2 != null) m.put("jdb2", jdb2.getAbsolutePath());
        /* ready-gated like classes (review finding 4 r37: two fields cached in
           the same doOpen block must publish under ONE rule — the error shape
           owns not-ready, round-34) */
        if (ready && packageName != null) m.put("package", packageName);   // cached in doOpen — no JEB walk (review finding 2)
        Map<String, Object> indexStatus = searchIndex.status();
        indexStatus.put("analysisClassesAtOpen", classCount);
        Object indexed = indexStatus.get("total");
        indexStatus.put("countMismatch", ready && "built".equals(indexStatus.get("state")) && indexed instanceof Number && ((Number) indexed).intValue() != classCount);
        indexStatus.put("countNote", "Analysis classes at open and indexed class snapshot may differ; a mismatch alone does not establish its cause");
        m.put("index", indexStatus);
        if (recovered > 0) m.put("recoveredEdits", recovered);
        if (replayFailed > 0) m.put("replayFailed", replayFailed);
        if (displaced != null) m.put("warning", displaced);
        m.put("caps", caps());
        return m;
    }

    // ------------------------------------------------------------ symbol resolution

    private enum Kind { CLASS, METHOD, FIELD }

    /** Infer the kind of item from the shape of its Dalvik address/signature. */
    private static Kind kindOf(String addr) {
        int arrow = addr.indexOf("->");
        if (arrow < 0) return Kind.CLASS;
        String member = addr.substring(arrow + 2);
        return member.indexOf('(') >= 0 ? Kind.METHOD : Kind.FIELD;
    }

    /** The class-level address for any item addr (strips the "->member" part). */
    private static String classAddrOf(String addr) {
        int arrow = addr.indexOf("->");
        return arrow < 0 ? addr : addr.substring(0, arrow);
    }

    /** JEB resolves a CURRENT or an ORIGINAL (pre-rename) handle directly. */
    /** The outline of an EXTERNAL class: the methods and fields of it the app references (each an
     *  item with xrefs of its own) — no lines, no code; external:true says so. */
    private Map<String, Object> externalOutline(String classAddr) {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> sym = new LinkedHashMap<>();
        sym.put("addr", classAddr);
        sym.put("kind", "class");
        sym.put("name", simpleNameOf(classAddr));
        sym.put("fqcn", classAddr.substring(1, classAddr.length() - 1).replace('/', '.').replace('$', '.'));
        sym.put("external", true);
        out.put("symbol", sym);
        out.put("external", true);
        List<Object> methods = new ArrayList<>(), fields = new ArrayList<>();
        String prefix = classAddr + "->";
        for (IDexUnit dex : dexUnits) {
            for (IDexMethod m : dex.getMethods()) {
                if (m.isInternal() || !m.getSignature(true).startsWith(prefix)) continue;
                Map<String, Object> mm = new LinkedHashMap<>();
                mm.put("name", m.getName(true));
                mm.put("addr", m.getAddress());
                mm.put("sig", m.getSignature(true));
                mm.put("external", true);
                methods.add(mm);
            }
            for (IDexField f : dex.getFields()) {
                if (f.isInternal() || !f.getSignature(true).startsWith(prefix)) continue;
                Map<String, Object> ff = new LinkedHashMap<>();
                ff.put("name", f.getName(true));
                ff.put("addr", f.getAddress());
                ff.put("sig", f.getSignature(true));
                ff.put("external", true);
                fields.add(ff);
            }
        }
        out.put("methods", methods);
        out.put("fields", fields);
        out.put("note", "an external class — not defined in this target; these are the members the app references");
        return out;
    }

    /** A referenced type by signature (Lpkg/C;) — internal or external — from any dex unit. */
    private com.pnfsoftware.jeb.core.units.code.android.dex.IDexType findAnyType(String sig) {
        if (sig == null || !sig.startsWith("L") || !sig.endsWith(";")) return null;
        for (IDexUnit dex : dexUnits) {
            try {
                com.pnfsoftware.jeb.core.units.code.android.dex.IDexType t = dex.getType(sig);
                if (t != null) return t;
            } catch (Exception ignored) {}
        }
        return null;
    }
    /** true when the class is referenced but not defined in this target. */
    private boolean isExternalClass(String addr) {
        return findAnyClass(addr) == null && findAnyType(addr) != null;
    }

    private IDexClass findAnyClass(String addr) {
        for (IDexUnit dex : dexUnits) {
            IDexClass c = dex.getClass(addr);
            if (c != null) return c;
        }
        return null;
    }

    /** A method reference an agent typed from what it read: "Lcls;->name(params)RET" with a
     *  return type it inferred, "Lcls;->name(params)", or just "Lcls;->name". Match by class +
     *  name (+ parameter types when given), ignoring the return type; a unique hit resolves,
     *  several refuse with the candidates listed (m2: three wasted turns on an inferred
     *  "…)Ljava/util/concurrent/Executor;" where the outline had said "…)LX/1aW;"). */
    private IDexMethod findMethodLenient(String ref) throws OpException {
        int arrow = ref.indexOf("->");
        if (arrow < 0) return null;
        String clsAddr = ref.substring(0, arrow);
        String tail = ref.substring(arrow + 2);
        int lp = tail.indexOf('('), rp = tail.indexOf(')');
        String name = lp >= 0 ? tail.substring(0, lp) : tail;
        String params = lp >= 0 && rp > lp ? tail.substring(lp + 1, rp) : null;
        if (name.isBlank()) return null;
        IDexClass c = findAnyClass(clsAddr);
        if (c == null) return null;
        List<IDexMethod> byName = new ArrayList<>(), byParams = new ArrayList<>(), byArity = new ArrayList<>();
        int arity = params == null ? -1 : countParams("(" + params + ")");     // countParams reads a full "(…)" descriptor
        for (IDexMethod mm : c.getMethods()) {
            if (!name.equals(mm.getName(true)) && !name.equals(mm.getName(false))) continue;
            byName.add(mm);
            if (params != null) {
                String a = mm.getAddress();
                int alp = a.indexOf('('), arp = a.indexOf(')');
                if (alp >= 0 && arp > alp) {
                    String ap = a.substring(alp + 1, arp);
                    if (params.equals(ap)) byParams.add(mm);
                    else if (countParams("(" + ap + ")") == arity) byArity.add(mm);   // the same shape, a parameter TYPE renamed since (a History address)
                }
            }
        }
        List<IDexMethod> pick = !byParams.isEmpty() ? byParams : !byArity.isEmpty() ? byArity : byName;
        if (pick.size() == 1) return pick.get(0);
        if (pick.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < pick.size() && i < 12; i++) sb.append(i > 0 ? " · " : "").append(pick.get(i).getAddress());
        throw OpException.notFound("ambiguous method " + ref + " — " + pick.size() + " candidates, pass one exactly: " + sb, "target=" + id);
    }

    private IDexMethod findAnyMethod(String addr) {
        for (IDexUnit dex : dexUnits) {
            IDexMethod m = dex.getMethod(addr);
            if (m != null) return m;
        }
        IDexClass owner = findAnyClass(classAddrOf(addr));   // owner class renamed → rebuild on its handle
        if (owner != null) {
            String rebuilt = owner.getAddress() + addr.substring(classAddrOf(addr).length());
            if (!rebuilt.equals(addr))
                for (IDexUnit dex : dexUnits) {
                    IDexMethod m = dex.getMethod(rebuilt);
                    if (m != null) return m;
                }
        }
        return null;
    }

    private IDexField findAnyField(String addr) {
        for (IDexUnit dex : dexUnits) {
            IDexField f = dex.getField(addr);
            if (f != null) return f;
        }
        IDexClass owner = findAnyClass(classAddrOf(addr));
        if (owner != null) {
            String rebuilt = owner.getAddress() + addr.substring(classAddrOf(addr).length());
            if (!rebuilt.equals(addr))
                for (IDexUnit dex : dexUnits) {
                    IDexField f = dex.getField(rebuilt);
                    if (f != null) return f;
                }
            /* the descriptor's TYPE was renamed since this address was written (a History entry
               `->parent:Lm76/Span;` after Span became AgentSpan): the owner and the field's name are
               enough — a class has one field of a name (undo of a comment failed on it, 2026-09-20) */
            int arrow = addr.indexOf("->"), colon = addr.indexOf(':', Math.max(0, arrow));
            if (arrow >= 0 && colon > arrow) {
                String fname = addr.substring(arrow + 2, colon);
                for (IDexField cf : owner.getFields())
                    if (fname.equals(cf.getName(true)) || fname.equals(cf.getName(false))) return cf;
            }
        }
        return null;
    }

    private IDexClass findClass(String addr) { return findAnyClass(classAddrOf(addr)); }

    /** A never-blank display name (anonymous/synthetic classes return "" from getName). */
    private static String displayName(IDexClass cls) {
        String n = cls.getName(true);
        if (n != null && !n.isBlank()) return n;
        String addr = cls.getAddress();
        if (addr == null) return "?";
        String s = addr;
        if (s.startsWith("L")) s = s.substring(1);
        if (s.endsWith(";")) s = s.substring(0, s.length() - 1);
        int slash = s.lastIndexOf('/');
        return slash >= 0 ? s.substring(slash + 1) : s;
    }

    /** A class addr as dotted FQN: "Lcom/foo/Outer$Inner;" → "com.foo.Outer.Inner". */
    private static String dottedName(IDexClass cls) {
        String a = cls.getAddress();
        if (a == null) return "";
        int beg = a.startsWith("L") ? 1 : 0;
        int end = a.endsWith(";") ? a.length() - 1 : a.length();
        return a.substring(beg, end).replace('/', '.').replace('$', '.');
    }

    /** "Lcom/foo/Bar;" -> "Lcom/foo/" (package prefix incl. trailing slash); "Lbar;" -> "L". */
    private static String packageSlash(String addr) {
        int slash = addr.lastIndexOf('/');
        return slash < 0 ? "L" : addr.substring(0, slash + 1);
    }

    /** Dotted package of an addr for display, e.g. "Lcom/foo/Bar;" -> "com.foo" (""=root). */
    private static String packageOf(String addr) {
        String s = packageSlash(addr);
        if (s.equals("L")) return "";
        s = s.substring(1, s.length() - 1);
        return s.replace('/', '.');
    }

    private static String simpleName(String name) {
        int dot = Math.max(name.lastIndexOf('.'), name.lastIndexOf('/'));
        String s = dot >= 0 ? name.substring(dot + 1) : name;
        int semi = s.indexOf(';');
        if (semi >= 0) s = s.substring(0, semi);
        return s;
    }

    /** A resolved symbol: current addr, kind, display name. */
    /** `lenient`: null when the asked text IS the address; else what the lenient match had to overlook
     *  (see lenientKind) — carried into resolve()'s answer so a convenience never passes for an exact hit. */
    private record Resolved(String addr, Kind kind, String name, String lenient) {
        Resolved(String addr, Kind kind, String name) { this(addr, kind, name, null); }
    }

    /** How a lenient method match differs from the reference as typed: "name only" (no descriptor),
     *  "return type omitted", "display name" (a renamed method asked by its current name), "owner" (an
     *  inner-class rewrite) — partial references, fine to read by; or "return type", "parameter types",
     *  "parameter arity" — a COMPLETE descriptor that contradicts the method found (the m2 inferred-return
     *  case): a reading convenience, never evidence (contradicts). */
    private static String lenientKind(String asked, String found) {
        if (asked.equals(found)) return null;
        String at = asked.substring(asked.indexOf("->") + 2), ft = found.substring(found.indexOf("->") + 2);
        int alp = at.indexOf('('), arp = at.indexOf(')');
        if (alp < 0) return "name only";
        int flp = ft.indexOf('('), frp = ft.indexOf(')');
        String ap = arp > alp ? at.substring(alp + 1, arp) : "", fp = flp >= 0 && frp > flp ? ft.substring(flp + 1, frp) : "";
        if (!ap.equals(fp)) return countParams("(" + ap + ")") == countParams("(" + fp + ")") ? "parameter types" : "parameter arity";
        String ar = arp >= 0 ? at.substring(arp + 1) : "", fr = frp >= 0 ? ft.substring(frp + 1) : "";
        if (ar.isEmpty()) return "return type omitted";
        if (!ar.equals(fr)) return "return type";
        if (!at.substring(0, alp).equals(flp >= 0 ? ft.substring(0, flp) : ft)) return "display name";
        return "owner";
    }

    /** lenientKind, knowing the method: a reference by the dex's ORIGINAL name of a renamed method is
     *  "original name" (an identity, not a guess), not a bare "display name" difference. */
    private static String lenientKind(String asked, IDexMethod m) {
        String how = lenientKind(asked, m.getAddress());
        if ("display name".equals(how)) {
            String at = asked.substring(asked.indexOf("->") + 2);
            String an = at.indexOf('(') >= 0 ? at.substring(0, at.indexOf('(')) : at;
            if (an.equals(m.getName(false))) return "original name";
        }
        return how;
    }

    /** Every descriptor component that differs between a complete reference and the method found — "parameter types",
     *  "parameter arity", "return type", "name" — so a wrong return type is never hidden behind a renamed parameter. */
    private static List<String> differences(String asked, String found) {
        List<String> out = new ArrayList<>();
        if (asked.indexOf("->") < 0 || found.indexOf("->") < 0) return out;
        String at = asked.substring(asked.indexOf("->") + 2), ft = found.substring(found.indexOf("->") + 2);
        int alp = at.indexOf('('), arp = at.indexOf(')'), flp = ft.indexOf('('), frp = ft.indexOf(')');
        if (alp < 0 || arp < alp) return out;   // not a complete descriptor: nothing to contradict
        String ap = at.substring(alp + 1, arp), fp = flp >= 0 && frp > flp ? ft.substring(flp + 1, frp) : "";
        if (!ap.equals(fp)) out.add(countParams("(" + ap + ")") == countParams("(" + fp + ")") ? "parameter types" : "parameter arity");
        String ar = at.substring(arp + 1), fr = frp >= 0 ? ft.substring(frp + 1) : "";
        if (!ar.isEmpty() && !ar.equals(fr)) out.add("return type");
        if (!at.substring(0, alp).equals(flp >= 0 ? ft.substring(0, flp) : ft)) out.add("name");
        return out;
    }

    private static boolean contradicts(String how) {
        return how != null && (how.equals("return type") || how.equals("parameter types") || how.equals("parameter arity"));
    }

    /** An inner class as the dex records it: the class, the outer's ORIGINAL signature, the inner's
     *  simple name from the InnerClass annotation (null for an anonymous class). */
    private record InnerRel(IDexClass inner, String outerSig, String innerName) {}
    private Map<String, List<InnerRel>> innerIndex;   // outer original signature → its inner classes; built once per open

    /** Outer → inner classes, from every record the dex keeps: the dalvik.annotation
     *  EnclosingClass / InnerClass system annotations when a build carries them (a WhatsApp release
     *  carries none — checked 2026-09-22); the outer's static `Companion` field, whose type IS the
     *  Kotlin companion class; an inner class's synthetic `this$0` field, whose type is its outer;
     *  and the original `Outer$Inner` naming where it survived. Original signatures and names
     *  throughout: renames never move an entry. Built once per open. */
    private Map<String, List<InnerRel>> innerIndex() {
        if (innerIndex != null) return innerIndex;
        Map<String, List<InnerRel>> idx = new java.util.HashMap<>();
        java.util.Set<String> seen = new java.util.HashSet<>();      // inner signature → recorded once
        for (IDexUnit dex : dexUnits) {
            for (IDexClass c : dex.getClasses()) {
                /* the outer's static Companion field names its companion class */
                try {
                    for (IDexField f : c.getFields()) {
                        if (!"Companion".equals(f.getName(false))) continue;
                        com.pnfsoftware.jeb.core.units.code.android.dex.IDexType ft = dex.getType(f.getFieldTypeIndex());
                        IDexClass comp = ft == null ? null : dex.getClass(ft.getSignature(false));
                        if (comp == null || comp == c) continue;
                        if (seen.add(comp.getSignature(false)))
                            idx.computeIfAbsent(c.getSignature(false), k -> new ArrayList<>()).add(new InnerRel(comp, c.getSignature(false), "Companion"));
                    }
                } catch (Throwable ignored) {}
            }
        }
        for (IDexUnit dex : dexUnits) {
            for (IDexClass c : dex.getClasses()) {
                if (seen.contains(c.getSignature(false))) continue;
                String outer = null, innerName = null;
                try {
                    com.pnfsoftware.jeb.core.units.code.android.dex.IDexAnnotationsDirectory ad = c.getAnnotationsDirectory();
                    if (ad != null && ad.getClassAnnotations() != null) {
                        for (com.pnfsoftware.jeb.core.units.code.android.dex.IDexAnnotationItem it : ad.getClassAnnotations()) {
                            com.pnfsoftware.jeb.core.units.code.android.dex.IDexAnnotation a = it.getAnnotation();
                            if (a == null) continue;
                            com.pnfsoftware.jeb.core.units.code.android.dex.IDexType at = dex.getType(a.getTypeIndex());
                            String asig = at == null ? "" : at.getSignature(false);
                            if ("Ldalvik/annotation/EnclosingClass;".equals(asig)) {
                                for (com.pnfsoftware.jeb.core.units.code.android.dex.IDexAnnotationElement el : a.getElements()) {
                                    com.pnfsoftware.jeb.core.units.code.android.dex.IDexValue v = el.getValue();
                                    if (v != null && v.getType() == com.pnfsoftware.jeb.core.units.code.android.dex.IDexValue.VALUE_TYPE) {
                                        com.pnfsoftware.jeb.core.units.code.android.dex.IDexType ot = dex.getType(v.getTypeIndex());
                                        if (ot != null) outer = ot.getSignature(false);
                                    }
                                }
                            } else if ("Ldalvik/annotation/InnerClass;".equals(asig)) {
                                for (com.pnfsoftware.jeb.core.units.code.android.dex.IDexAnnotationElement el : a.getElements()) {
                                    com.pnfsoftware.jeb.core.units.code.android.dex.IDexString en = dex.getString(el.getNameIndex());
                                    com.pnfsoftware.jeb.core.units.code.android.dex.IDexValue v = el.getValue();
                                    if (en != null && "name".equals(en.getValue(false)) && v != null
                                            && v.getType() == com.pnfsoftware.jeb.core.units.code.android.dex.IDexValue.VALUE_STRING) {
                                        com.pnfsoftware.jeb.core.units.code.android.dex.IDexString sv = dex.getString(v.getStringIndex());
                                        if (sv != null) innerName = sv.getValue(false);
                                    }
                                }
                            }
                        }
                    }
                } catch (Throwable ignored) {}
                if (outer == null) {
                    /* a non-static inner class holds its outer in a synthetic this$0 field */
                    try {
                        for (IDexField f : c.getFields()) {
                            String fn = f.getName(false);
                            if (fn == null || !fn.matches("this\\$\\d+")) continue;
                            com.pnfsoftware.jeb.core.units.code.android.dex.IDexType ft = dex.getType(f.getFieldTypeIndex());
                            if (ft != null && dex.getClass(ft.getSignature(false)) != null) { outer = ft.getSignature(false); break; }
                        }
                    } catch (Throwable ignored) {}
                }
                String orig = c.getSignature(false);
                if (outer == null && orig != null && orig.lastIndexOf('$') > 1)
                    outer = orig.substring(0, orig.lastIndexOf('$')) + ";";        // the original name still nests it
                if (outer == null) continue;
                seen.add(orig);
                if (innerName == null && orig != null && orig.lastIndexOf('$') > 1) {
                    String tail = orig.substring(orig.lastIndexOf('$') + 1, orig.length() - 1);
                    if (!tail.isEmpty() && !Character.isDigit(tail.charAt(0))) innerName = tail;
                }
                idx.computeIfAbsent(outer, k -> new ArrayList<>()).add(new InnerRel(c, outer, innerName));
            }
        }
        innerIndex = idx;
        return idx;
    }

    /** `LOuter$Inner;` → the inner class the dex ties to Outer under that simple name; else
     *  E_NOTFOUND carrying the outer's inner classes as `candidates` (name, addr, innerName). */
    private IDexClass resolveInnerClass(String clsPart) throws OpException {
        int cut = clsPart.lastIndexOf('$');
        String outerSig = clsPart.substring(0, cut) + ";";
        String want = clsPart.substring(cut + 1, clsPart.length() - 1);
        IDexClass outer = findAnyClass(outerSig);
        if (outer == null)
            throw OpException.notFound("class not found: " + clsPart + " — nor its outer class " + outerSig, "target=" + id);
        List<InnerRel> rels = innerIndex().getOrDefault(outer.getSignature(false), List.of());
        List<IDexClass> hits = new ArrayList<>();
        for (InnerRel r : rels) {
            if (want.equals(r.innerName())) hits.add(r.inner());
            else if (r.innerName() == null && r.inner().getSignature(false).endsWith("$" + want + ";")) hits.add(r.inner());
        }
        if (hits.size() == 1) return hits.get(0);
        List<Object> cands = new ArrayList<>();
        for (InnerRel r : rels) {
            if (cands.size() >= 25) break;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", displayName(r.inner()));
            m.put("kind", "class");
            m.put("addr", r.inner().getAddress());
            m.put("fqcn", dottedName(r.inner()));
            if (r.innerName() != null) m.put("innerName", r.innerName());
            if (!r.inner().getSignature(false).equals(r.inner().getSignature(true))) m.put("was", simpleNameOf(r.inner().getSignature(false)));
            cands.add(m);
        }
        OpException ex = OpException.notFound(hits.isEmpty()
                        ? "class not found: " + clsPart + " — " + displayName(outer) + " has " + rels.size() + " inner class(es)"
                                + (rels.isEmpty() ? "" : "; the candidates list them with their inner names (renamed ones no longer carry the outer's name)")
                        : "ambiguous: " + hits.size() + " inner classes of " + displayName(outer) + " are named " + want + " — pass one candidate's addr",
                "target=" + id);
        if (!cands.isEmpty()) ex.extra.put("candidates", cands);
        return throwIt(ex);
    }

    private static <T> T throwIt(OpException ex) throws OpException { throw ex; }

    /**
     * Symbol grammar (MCP_SPEC §5): a Dalvik addr ("Lcom/x/Y;" or member form) resolves
     * directly (current OR original — JEB keeps original handles valid across renames);
     * a dotted FQCN pins the exact class; "Owner.method" pins a method; a bare simple
     * name must match exactly one class or method (ambiguity returns candidates, never
     * guesses).
     */
    private Resolved lookup(String symbol) throws OpException {
        if (symbol == null || symbol.isBlank())
            throw OpException.syntax("empty symbol", "target=" + id);
        String s = symbol.trim();
        /* an xref row's `from` ("Lcls;->m()V+634h") names a method AND an instruction: the method resolves here, the
           offset is the caller's business (code.site reads it) — never a "return type" that ends in +634h */
        java.util.regex.Matcher offm = java.util.regex.Pattern.compile("^(.*->[^+]+)\\+(0x[0-9a-fA-F]+|[0-9a-fA-F]+h)$").matcher(s);
        if (offm.matches()) s = offm.group(1);

        if (s.startsWith("L") || s.contains("->")) {
            /* `LOuter$Inner;` where the inner class was renamed on its own (obfuscation keeps the
               outer's name, the Companion becomes `X.0Cs`): the dex EnclosingClass/InnerClass
               annotations still tie them — resolve through them, or answer with the outer's inner
               classes as candidates (agent 2026-09-22: "did you mean" instead of a bare E_NOTFOUND) */
            String clsPart = classAddrOf(s);
            if (clsPart != null && clsPart.startsWith("L") && clsPart.contains("$")
                    && findAnyClass(clsPart) == null && findAnyType(clsPart) == null) {
                IDexClass inner = resolveInnerClass(clsPart);
                s = inner.getAddress() + s.substring(clsPart.length());
            }
            return switch (kindOf(s)) {
                case CLASS -> {
                    IDexClass c = findAnyClass(s);
                    if (c == null) {
                        /* an EXTERNAL class (a framework or library type the app references but does not
                           define: BitmapFactory$Options, Log, String): no IDexClass, but the dex type pool
                           has it, so it resolves — xrefs and an outline of the members the app uses
                           answer for it; there is no code to decompile (user 2026-09-20) */
                        com.pnfsoftware.jeb.core.units.code.android.dex.IDexType t = findAnyType(s);
                        if (t == null) throw OpException.notFound("class not found: " + s, "target=" + id);
                        yield new Resolved(t.getSignature(), Kind.CLASS, simpleNameOf(t.getSignature()));
                    }
                    yield new Resolved(c.getAddress(), Kind.CLASS, displayName(c));
                }
                case METHOD -> {
                    IDexMethod m = findAnyMethod(s);
                    String how = null;
                    if (m == null) {                           // return type / descriptor optional; ambiguous → the candidates
                        m = findMethodLenient(s);
                        if (m != null) how = lenientKind(s, m);   // … and SAID: what the match overlooked
                    }
                    if (m == null) throw OpException.notFound("method not found: " + s, "target=" + id);
                    yield new Resolved(m.getAddress(), Kind.METHOD, m.getName(true), how);
                }
                case FIELD -> {
                    IDexField f = findAnyField(s);
                    if (f == null && !s.substring(s.indexOf("->") + 2).contains(":")) {
                        /* "Lcls;->name" without a descriptor: what an agent writes for `this.name` it saw in
                           the decompiled text (a dex field ref carries ":type" — m6 lost three turns and eight
                           writes on `LX/BitmapDecoder;->A05`). A field of that name in the class wins (current or
                           original name); else the name is a METHOD's (no descriptor either) */
                        IDexClass owner = findAnyClass(classAddrOf(s));
                        String want = s.substring(s.indexOf("->") + 2);
                        if (owner != null)
                            for (IDexField cf : owner.getFields())
                                if (want.equals(cf.getName(true)) || want.equals(cf.getName(false))) { f = cf; break; }
                        if (f == null) {
                            IDexMethod m = findMethodLenient(s);
                            if (m != null) yield new Resolved(m.getAddress(), Kind.METHOD, m.getName(true), lenientKind(s, m));   // a descriptor-less reference: "name only"
                        }
                    }
                    if (f == null) throw OpException.notFound("field not found: " + s, "target=" + id);
                    yield new Resolved(f.getAddress(), Kind.FIELD, f.getName(true));
                }
            };
        }

        // "Owner.method" form
        int lastDot = s.lastIndexOf('.');
        if (lastDot > 0 && lastDot < s.length() - 1 && !s.endsWith(";")) {
            String member = s.substring(lastDot + 1);
            if (Character.isLowerCase(member.charAt(0)) || member.equals("<init>")) {
                try {
                    Resolved owner = lookup(s.substring(0, lastDot));
                    if (owner.kind() == Kind.CLASS) {
                        IDexClass c = findAnyClass(owner.addr());
                        if (c != null) {
                            List<IDexMethod> hits = new ArrayList<>();
                            for (IDexMethod m : c.getMethods())
                                if (m.getName(true).equals(member) || m.getName(false).equals(member)) hits.add(m);
                            if (hits.size() == 1) return new Resolved(hits.get(0).getAddress(), Kind.METHOD, hits.get(0).getName(true), "name only");   // a shorthand, said
                            if (hits.size() > 1) {   // never the first overload: the candidates, by their ORIGINAL descriptors
                                StringBuilder sb = new StringBuilder();
                                for (int i = 0; i < hits.size() && i < 12; i++) sb.append(i > 0 ? " · " : "").append(hits.get(i).getSignature(false));
                                throw OpException.notFound("ambiguous method " + s + " — " + hits.size() + " candidates, pass one exactly: " + sb, "target=" + id);
                            }
                        }
                    }
                } catch (OpException ignored) { /* fall through to name search */ }
            }
        }

        // Dotted FQCN → exact class
        List<IDexClass> exact = new ArrayList<>();
        List<IDexMethod> methodHits = new ArrayList<>();
        String simple = s.substring(Math.max(s.lastIndexOf('.'), s.lastIndexOf('/')) + 1);
        boolean fqPinned = false;
        for (IDexUnit dex : dexUnits) {
            for (IDexClass cls : dex.getClasses()) {
                if (s.contains(".") && s.equals(dottedName(cls))) {
                    exact.clear();
                    exact.add(cls);
                    fqPinned = true;
                    break;
                }
                if (displayName(cls).equals(simple) || simple.equals(cls.getName(false))) exact.add(cls);
            }
            if (fqPinned) break;
        }
        if (!fqPinned && exact.isEmpty())
            for (IDexUnit dex : dexUnits)
                for (IDexMethod m : dex.getMethods()) {
                    if (!m.isInternal()) continue;
                    if (m.getName(true).equals(simple) || m.getName(false).equals(simple)) methodHits.add(m);
                }
        if (exact.size() == 1) {
            IDexClass c = exact.get(0);
            return new Resolved(c.getAddress(), Kind.CLASS, displayName(c), fqPinned ? null : "simple name");
        }
        if (exact.isEmpty() && methodHits.size() == 1) {
            IDexMethod m = methodHits.get(0);
            return new Resolved(m.getAddress(), Kind.METHOD, m.getName(true), "name only");
        }
        int matches = exact.size() + (exact.isEmpty() ? methodHits.size() : 0);
        if (matches > 1)
            throw OpException.syntax("ambiguous symbol: " + symbol,
                    "candidates=" + matches + " — use a dotted FQCN or a Dalvik addr");
        throw OpException.notFound("symbol not found: " + symbol, "target=" + id);
    }

    /** The executable as JEB's project RECORDS it: the artifact's input, hashed here once per open. Measured on
     *  the fixture (2026-10-09): JEB does NOT keep the APK's bytes inside the .jdb2 — the input is a FileInput that
     *  re-reads the artifact file at its recorded path; with that file gone the record has a name and no readable
     *  bytes (`sha256: null`, `error`). The caller says which it got; nothing here claims an embedded copy. */
    @Override
    public Map<String, Object> inputIdentity() {
        Map<String, Object> cached = inputIdentity;
        if (cached != null) return cached;
        IRuntimeProject prj = project;
        if (prj == null) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        try {
            List<com.pnfsoftware.jeb.core.ILiveArtifact> live = prj.getLiveArtifacts();
            if (live == null || live.isEmpty()) return null;
            com.pnfsoftware.jeb.core.IArtifact a = live.get(0).getArtifact();
            com.pnfsoftware.jeb.core.input.IInput in = a == null ? null : a.getInput();
            if (in == null) return null;
            String cls = in.getClass().getSimpleName();
            m.put("name", in.getName() != null ? in.getName() : a.getName());
            m.put("inputClass", cls.isEmpty() ? in.getClass().getName() : cls);   // what JEB holds: a file it re-reads (FileInput) or bytes — said, never assumed
            if (live.size() > 1) m.put("artifacts", live.size());
            try { m.put("projectKey", prj.getKey()); } catch (Throwable ignored) {}
            try {
                java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
                long n = 0;
                try (java.io.InputStream s = in.getStream()) {
                    byte[] buf = new byte[1 << 16];
                    int r;
                    while ((r = s.read(buf)) > 0) { md.update(buf, 0, r); n += r; }
                }
                StringBuilder sb = new StringBuilder();
                for (byte b : md.digest()) sb.append(String.format("%02x", b));
                if (n == 0) { m.put("sha256", null); m.put("error", "the input JEB's record points at has no readable bytes (empty stream)"); }
                else { m.put("bytes", n); m.put("sha256", sb.toString()); m.put("source", "the input JEB's artifact record points at (" + m.get("inputClass") + "), hashed now"); }
            } catch (Throwable read) {
                m.put("sha256", null);
                m.put("error", read.getClass().getSimpleName() + ": the input JEB's record points at (" + m.get("name") + ") is not readable — the database does not keep the bytes");
            }
            inputIdentity = m;
            return m;
        } catch (Throwable t) {
            dev.workbench.server.Log.warn("engine", "input identity not read from the database: " + t, null);
            return m.isEmpty() ? null : m;
        }
    }

    @Override
    public Map<String, Object> resolve(String symbol) throws OpException {
        requireReady();
        synchronized (this) {
            Resolved r = lookup(symbol);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("addr", r.addr());
            m.put("kind", r.kind().name().toLowerCase());
            m.put("name", r.name());
            /* a lenient match is never silent: `exact:false`, what it overlooked, the text as asked — and a
               COMPLETE descriptor that contradicts the method found says `contradiction:true` (the corpus
               paths — exports, findings — refuse it through Engine.resolveStrict; a reading agent sees the
               warning in every answer) */
            if (r.lenient() != null) {
                m.put("exact", false); m.put("lenient", r.lenient()); m.put("asked", symbol.trim());
                if (r.kind() == Kind.METHOD) {
                    IDexMethod mm1 = findAnyMethod(r.addr());
                    if (mm1 != null) {
                        List<String> differs = differences(symbol.trim(), mm1.getAddress());
                        if (!differs.isEmpty()) m.put("differs", differs);
                        if (contradicts(r.lenient()) || differs.stream().anyMatch(JebEngine::contradicts)) {
                            m.put("contradiction", true);
                            String orig = mm1.getSignature(false);
                            m.put("note", "the reference's " + String.join(" and ", differs.isEmpty() ? List.of(r.lenient()) : differs) + " differ from the method found — a reading convenience, not evidence: exports, findings and hooks refuse it; the exact descriptor is " + orig
                                    + (orig.equals(r.addr()) ? "" : " (displayed here as " + r.addr() + ")"));
                        }
                    }
                }
            } else if (r.kind() == Kind.CLASS) {
                m.put("exact", true);   // a dotted FQCN or the class address (a simple-name shorthand came through lenient above)
            } else if (symbol.contains("->")) {
                /* exact = the asked text IS the current address or the dex's ORIGINAL signature (JEB keeps both
                   resolvable) — each an identity, said which. Anything else the engine matched directly (JEB's own
                   getMethod accepts a name-only or descriptor-less reference) is still a LENIENT match, said as such. */
                String asked = symbol.trim();
                IDexMethod mm0 = r.kind() == Kind.METHOD ? findAnyMethod(r.addr()) : null;
                boolean byOrig = mm0 != null && asked.equals(mm0.getSignature(false));
                if (asked.equals(r.addr()) || byOrig) {
                    m.put("exact", true);
                    if (byOrig) m.put("by", "original signature");
                } else if (mm0 != null) {
                    String how = lenientKind(asked, mm0);
                    List<String> differs = differences(asked, mm0.getAddress());
                    m.put("exact", false); m.put("lenient", how == null ? "address" : how); m.put("asked", asked);
                    if (!differs.isEmpty()) m.put("differs", differs);
                    if (contradicts(how) || differs.stream().anyMatch(JebEngine::contradicts)) {
                        m.put("contradiction", true);
                        String orig = mm0.getSignature(false);
                        m.put("note", "the reference's " + String.join(" and ", differs.isEmpty() ? List.of(how) : differs) + " differ from the method found — a reading convenience, not evidence: exports, findings and hooks refuse it; the exact descriptor is " + orig
                                + (orig.equals(r.addr()) ? "" : " (displayed here as " + r.addr() + ")"));
                    }
                } else {
                    m.put("exact", asked.equals(r.addr()));
                    if (!asked.equals(r.addr())) { m.put("lenient", "name only"); m.put("asked", asked); }
                }
            }
            switch (r.kind()) {
                case CLASS -> {
                    IDexClass c = findAnyClass(r.addr());
                    if (c != null) {
                        m.put("fqcn", dottedName(c));
                        m.put("sig", c.getSignature(true));
                        m.put("origSig", c.getSignature(false));
                        if (c.isRenamed()) { m.put("renamed", true); m.put("origName", c.getName(false)); }
                    } else {
                        m.put("fqcn", r.addr().substring(1, r.addr().length() - 1).replace('/', '.').replace('$', '.'));
                        m.put("sig", r.addr());
                        m.put("external", true);          // referenced, not defined here: xrefs and an outline, no code
                    }
                }
                case METHOD -> {
                    IDexMethod mm = findAnyMethod(r.addr());
                    if (mm != null) {
                        m.put("owner", ownerDotted(r.addr()));
                        m.put("sig", mm.getSignature(true));
                        m.put("origSig", mm.getSignature(false));
                        if (mm.isRenamed()) { m.put("renamed", true); m.put("origName", mm.getName(false)); }
                    }
                }
                case FIELD -> {
                    IDexField f = findAnyField(r.addr());
                    if (f != null) {
                        m.put("owner", ownerDotted(r.addr()));
                        m.put("sig", f.getSignature(true));
                        m.put("origSig", f.getSignature(false));
                        if (f.isRenamed()) { m.put("renamed", true); m.put("origName", f.getName(false)); }
                    }
                }
            }
            m.put("target", id);
            return m;
        }
    }

    private String ownerDotted(String memberAddr) {
        IDexClass c = findClass(memberAddr);
        return c == null ? packageOf(memberAddr) : dottedName(c);
    }

    // ------------------------------------------------------------ tree / symbol search

    /**
     * Lazy package/class/method hierarchy. Three modes:
     *  - blank filter: top-level package roots (first Dalvik path segment) with class counts;
     *  - prefix filter ("Lcom/foo/" or "Lcom/foo"): the immediate sub-packages + direct classes;
     *  - any other filter: classes whose simple name contains it, with method/field children.
     */
    @Override
    public Map<String, Object> tree(String filter) throws OpException { return tree(filter, 0); }

    /** limit 0 = every row (the caller chooses the cut — no tool-side 200/500 any more, 2026-09-18);
     *  the honest truncated/total ride along either way. */
    @Override
    public Map<String, Object> tree(String filter, int limit) throws OpException {
        requireReady();
        final int treeCap = limit > 0 ? limit : Integer.MAX_VALUE;
        synchronized (this) {
            String f = filter == null ? "" : filter.trim();
            if (f.isEmpty() || f.equals("L")) return treeChildrenResult("L", treeCap);
            if (f.startsWith("L")) {
                String prefix = f.endsWith("/") || f.endsWith(";") ? f : f + "/";
                if (prefix.endsWith(";")) {                  // a class addr → its members as children
                    List<Object> one = new ArrayList<>();
                    IDexClass c = findAnyClass(f);
                    if (c != null) one.add(classNode(c, true));
                    return treeResult(one, false, -1);
                }
                return treeChildrenResult(prefix, treeCap);
            }
            // name search → matching classes with member children, capped at 200 —
            // and the cap is HONEST (app-review-opus P0-3): truncated + total ride
            // in the result, so an agent never mistakes a capped prefix for the
            // whole package. total counts matches beyond the cap (cheap: no node
            // construction past it).
            String ql = f.toLowerCase();
            List<Object> out = new ArrayList<>();
            int cap = treeCap;
            int total = 0;
            boolean truncated = false;
            for (IDexUnit dex : dexUnits) {
                for (IDexClass cls : dex.getClasses()) {
                    if (!displayName(cls).toLowerCase().contains(ql)
                            && !dottedName(cls).toLowerCase().contains(ql)) continue;
                    total++;
                    if (out.size() >= cap) { truncated = true; continue; }
                    out.add(classNode(cls, true));
                }
            }
            return treeResult(out, truncated, total);
        }
    }

    /** symbols {prefix}: the shallow listing under a package prefix (Engine.treeUnder).
     *
     *  Until 2026-10-03 the MCP tool handed its `prefix` to tree() as the FILTER, and tree() takes
     *  anything that does not start with "L" for a NAME SEARCH: every class whose dotted name
     *  contains the text, each with all its methods and fields. So `prefix: "com.whatsapp.media"`
     *  answered 157 classes in 779,265 characters, and `com.whatsapp` in 18.8 MB — the tool's own
     *  example ("m76") took that path too. A prefix is a place in the package tree, not a search. */
    @Override
    public Map<String, Object> treeUnder(String rawPrefix, int limit) throws OpException {
        requireReady();
        /* a default cut (owner's decision 2026-10-03, an exception to "the caller's cut, never the
           tool's" that query keeps): a flat obfuscated package holds 90,525 classes in a WhatsApp
           build — megabytes of rows no client can use. Without a limit the listing stops at
           UNDER_DEFAULT classes and SAYS so: truncated, total, the counts by next character
           (beginnings) and a hint. A caller who wants every row passes a limit that large. */
        final int cap = limit > 0 ? limit : UNDER_DEFAULT;
        String raw = rawPrefix == null ? "" : rawPrefix.trim();
        if (raw.length() > 512 || raw.chars().filter(ch -> ch == '.' || ch == '/').count() > 48)
            throw OpException.syntax("prefix is not a package name (" + raw.length() + " characters)", "a package such as com.example.net");
        synchronized (this) {
            if (raw.isEmpty() || raw.equals("L")) return underHint(treeChildrenResult("L", "", cap, true), "", "", limit);
            /* spellings. A dot anywhere → a dotted name. Otherwise a text that starts with "L" may be
               Dalvik (Lcom/foo/, Lcom/foo, Lcom/foo/Bar; — what this tool's own rows carry as `prefix`
               and `addr`) or a package whose first segment starts with L (Lib/foo): both readings are
               tried, Dalvik first when it has a slash or a semicolon, the package first when it is a
               bare token. A dotted name gets one more reading — its last dot as "$" — because a class
               row's fqcn writes an inner class as Outer.Inner. */
            List<String> readings = new ArrayList<>();
            if (raw.indexOf('.') >= 0) {
                String d = "L" + raw.replace('.', '/');
                readings.add(d);
                int last = d.lastIndexOf('/');
                if (last > 1 && !d.endsWith("/")) readings.add(d.substring(0, last) + "$" + d.substring(last + 1));
            } else if (raw.startsWith("L")) {
                boolean dalvikFirst = raw.indexOf('/') >= 0 || raw.endsWith(";");
                readings.add(dalvikFirst ? raw : "L" + raw);
                readings.add(dalvikFirst ? "L" + raw : raw);
            } else readings.add("L" + raw);
            for (String p : readings) {
                if (p.endsWith(";")) {                                   // a class: itself, with its members (one class is bounded)
                    IDexClass c = findAnyClass(p);
                    if (c == null) continue;
                    List<Object> one = new ArrayList<>();
                    one.add(classNode(c, true));
                    return treeResult(one, false, -1);
                }
                String pkg = p.endsWith("/") ? p : p + "/";
                Map<String, Object> whole = treeChildrenResult(pkg, "", cap, true);
                if (!((List<?>) whole.get("items")).isEmpty()) return underHint(whole, dottedPrefix(pkg), "", limit);
                if (p.endsWith("/")) continue;
                int slash = p.lastIndexOf('/');                          // the last segment as the beginning of a name
                String parent = slash < 0 ? "L" : p.substring(0, slash + 1);
                String starting = p.substring(parent.length());
                Map<String, Object> part = treeChildrenResult(parent, starting, cap, true);
                if (!((List<?>) part.get("items")).isEmpty()) {
                    part.put("under", dottedPrefix(parent));
                    part.put("startingWith", starting);
                    return underHint(part, dottedPrefix(parent), starting, limit);
                }
            }
            /* nothing there: say what IS, at the nearest package that exists — an empty list would
               read as "an empty package" and send the caller guessing. Cap 0: the level is counted,
               its class rows are not built (a typo under a flat package must not cost more than a hit). */
            String p = readings.get(0);
            String at = p.endsWith("/") ? p.substring(0, p.length() - 1) : p;
            Map<String, Object> near;
            String nearAt;
            while (true) {
                int slash = at.lastIndexOf('/');
                at = slash < 0 ? "L" : at.substring(0, slash);
                Map<String, Object> r = treeChildrenResult(at.equals("L") ? "L" : at + "/", "", 0, false);
                if (((Number) r.get("total")).intValue() > 0 || at.equals("L")) { near = r; nearAt = at.equals("L") ? "L" : at + "/"; break; }
            }
            List<Object> cands = new ArrayList<>();
            int pkgs = 0;
            for (Object o : (List<?>) near.get("items")) {
                Map<?, ?> row = (Map<?, ?>) o;
                if (!"package".equals(row.get("kind"))) continue;
                pkgs++;
                if (cands.size() < 40) cands.add(Map.of("name", String.valueOf(row.get("name")).replace('/', '.'), "kind", "package",
                        "prefix", row.get("prefix"), "count", row.get("count")));
            }
            int classes = ((Number) near.get("total")).intValue() - pkgs;
            String where = nearAt.equals("L") ? "the top level" : dottedPrefix(nearAt);
            String shown = raw.length() > 120 ? raw.substring(0, 120) + "…" : raw;
            OpException ex = OpException.notFound("nothing under " + shown + " — no package and no class name begins there",
                    where + " holds " + pkgs + " package" + (pkgs == 1 ? "" : "s") + " and " + classes + " class" + (classes == 1 ? "" : "es")
                            + (cands.isEmpty() ? "" : "; the candidates list its packages" + (pkgs > cands.size() ? " (the first " + cands.size() + ")" : "")));
            if (!cands.isEmpty()) ex.extra.put("candidates", cands);
            ex.extra.put("hint", "list " + where + " (symbols {prefix: \"" + (nearAt.equals("L") ? "" : dottedPrefix(nearAt)) + "\"}) and pass one of its rows' prefix"
                    + " — or look a class up by name with symbols {query, kind: \"class\"}");
            throw ex;
        }
    }

    /** Classes a prefix listing answers when the caller passes no limit: 500 rows are ~50 KB as an
     *  MCP client receives them (indented JSON), about 15,000 tokens — under the 25,000-token
     *  ceiling Claude Code puts on a tool result; 2,000 were ~200 KB. */
    static final int UNDER_DEFAULT = 500;

    /** A cut listing says how to get the rest: by the beginning of the name (the answer's
     *  `beginnings` count the classes by their next character), or with a larger limit. */
    private static Map<String, Object> underHint(Map<String, Object> r, String pkg, String starting, int limit) {
        if (!Boolean.TRUE.equals(r.get("truncated"))) { r.remove("beginnings"); return r; }
        int classes = 0;
        for (Object o : (List<?>) r.get("items")) if (!"package".equals(((Map<?, ?>) o).get("kind"))) classes++;
        String at = (pkg.isEmpty() ? "" : pkg + ".") + starting;
        String eg = "";
        if (r.get("beginnings") instanceof Map<?, ?> b)
            for (Map.Entry<?, ?> e : b.entrySet()) {
                if (String.valueOf(e.getKey()).isEmpty()) continue;   // "" is the class named exactly so: nothing to narrow by
                eg = " — prefix \"" + at + e.getKey() + "\" lists the " + e.getValue() + " that continue with \"" + e.getKey() + "\"";
                break;
            }
        r.put("hint", "the first " + classes + " classes in address order" + (limit > 0 ? "" : " (the default cut when no limit is passed)")
                + "; total counts every row. For the rest, narrow by the beginning of the class name: beginnings counts the classes here by"
                + " their next character" + eg + ". Or pass a larger limit");
        return r;
    }

    /** "Lcom/foo/" → "com.foo"; "L" → "". */
    private static String dottedPrefix(String dalvikPrefix) {
        String s = dalvikPrefix.startsWith("L") ? dalvikPrefix.substring(1) : dalvikPrefix;
        if (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s.replace('/', '.');
    }

    private static Map<String, Object> treeResult(List<Object> items, boolean truncated, int total) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        out.put("truncated", truncated);
        out.put("total", total >= 0 ? total : items.size());
        return out;
    }

    /** Immediate sub-packages + direct classes under a Dalvik prefix (old Jeeves children()).
        The 500-class cap is HONEST (fix-7 review F2): a flat obfuscated package used to
        report a capped prefix as the whole thing (truncated:false, total=items.size()). */
    private Map<String, Object> treeChildrenResult(String prefix, int treeCap) {
        return treeChildrenResult(prefix, "", treeCap, false);
    }

    /** `starting`: only the children of `prefix` whose own name begins with it ("" = all of them).
     *  `listing` (the MCP listing, treeUnder): class rows also carry the dotted name, come in
     *  ADDRESS order — the order a name beginning continues, where the tree pane's display-name
     *  order (case-folded, renames applied) cannot be continued — and a cut answer counts the
     *  classes by the character that follows `starting` (`beginnings`), so the caller knows which
     *  beginnings exist instead of guessing. treeCap 0 = count only: no class row is built. */
    private Map<String, Object> treeChildrenResult(String prefix, String starting, int treeCap, boolean listing) {
        Map<String, Integer> subPkgs = new java.util.TreeMap<>();
        List<IDexClass> classes = new ArrayList<>();
        List<String> addrs = listing ? new ArrayList<>() : null;
        final String match = prefix + starting;
        for (IDexUnit dex : dexUnits) {
            for (IDexClass cls : dex.getClasses()) {
                String addr = cls.getAddress();
                if (addr == null || !addr.startsWith(match)) continue;
                String rest = addr.substring(prefix.length());
                int slash = rest.indexOf('/');
                if (slash >= 0) subPkgs.merge(prefix + rest.substring(0, slash + 1), 1, Integer::sum);
                else { classes.add(cls); if (listing) addrs.add(addr); }
            }
        }
        if (listing) {                                   // by address, the keys taken once (not per comparison: 90,525 classes in one package)
            Integer[] order = new Integer[classes.size()];
            for (int k = 0; k < order.length; k++) order[k] = k;
            java.util.Arrays.sort(order, (a, b) -> addrs.get(a).compareTo(addrs.get(b)));
            List<IDexClass> sorted = new ArrayList<>(order.length);
            for (Integer k : order) sorted.add(classes.get(k));
            classes = sorted;
        } else if (treeCap > 0) classes.sort((a, b) -> displayName(a).compareToIgnoreCase(displayName(b)));   // cap 0 = a count: nothing to order
        List<Object> out = new ArrayList<>();
        for (Map.Entry<String, Integer> e : subPkgs.entrySet()) {
            Map<String, Object> n = new LinkedHashMap<>();
            n.put("name", e.getKey().substring(1, e.getKey().length() - 1));   // "Lcom/google/" → "com/google"
            n.put("kind", "package");
            n.put("prefix", e.getKey());
            n.put("count", e.getValue());
            out.add(n);
        }
        int cap = treeCap;
        int added = 0;
        for (IDexClass c : classes) {
            if (added >= cap) break;
            Map<String, Object> row = classNode(c, false);
            if (listing) row.put("fqcn", dottedName(c));
            out.add(row);
            added++;
        }
        /* total semantics (fix-7 review F5): entries the uncapped listing would
           hold — subpackages + direct classes */
        Map<String, Object> res = treeResult(out, classes.size() > cap, subPkgs.size() + classes.size());
        if (listing && classes.size() > cap && cap > 0) {
            Map<String, Integer> begin = new java.util.TreeMap<>();
            for (String a : addrs) {
                String rest = a.substring(match.length());
                String key = rest.isEmpty() || rest.charAt(0) == ';' ? "" : rest.substring(0, 1);   // "" = the class named exactly so
                begin.merge(key, 1, Integer::sum);
            }
            res.put("beginnings", begin);
        }
        return res;
    }

    private Map<String, Object> classNode(IDexClass cls, boolean withMembers) {
        Map<String, Object> n = new LinkedHashMap<>();
        n.put("name", displayName(cls));
        n.put("kind", cls.isInterface() ? "interface" : "class");
        n.put("addr", cls.getAddress());
        n.put("origSig", cls.getSignature(false));   // the row's re-open handle: resolves whatever the current name is
        if (cls.isRenamed()) n.put("origName", cls.getName(false));
        if (withMembers) {
            List<Object> kids = new ArrayList<>();
            for (IDexMethod m : cls.getMethods()) {
                Map<String, Object> mn = new LinkedHashMap<>();
                mn.put("name", m.getName(true) + m.getSignature(true).substring(
                        m.getSignature(true).indexOf('(')));
                mn.put("kind", "method");
                mn.put("addr", m.getAddress());
                if (m.isRenamed()) mn.put("origName", m.getName(false));
                kids.add(mn);
            }
            for (IDexField fld : cls.getFields()) {
                Map<String, Object> fn = new LinkedHashMap<>();
                fn.put("name", fld.getName(true));
                fn.put("kind", "field");
                fn.put("addr", fld.getAddress());
                if (fld.isRenamed()) fn.put("origName", fld.getName(false));
                kids.add(fn);
            }
            n.put("children", kids);
        }
        return n;
    }

    /** Fuzzy over classes/methods/fields (Jump Anywhere backend): name substring, classes first. */
    @Override
    public List<Object> symbolSearch(String query) throws OpException {
        @SuppressWarnings("unchecked") List<Object> rows = (List<Object>) symbolSearchPage(query, 0).get("rows");
        return rows;
    }

    /** One walk of the class list: the first `limit` matches as rows, every match counted in total. */
    @Override
    public Map<String, Object> symbolSearchPage(String query, int limit) throws OpException {
        return symbolSearchPage(query, limit, null);
    }

    /** Ranked: an exact name first (case-sensitive), then the same name in another case, then a
     *  prefix, then a substring — within a rank classes, methods, fields. `kind` (class | method |
     *  field) keeps one kind only. Obfuscated dex recycles short identifiers across classes and
     *  methods (CSL / CSl / CsL…), so a method's name used to drown under same-named classes
     *  (agent 2026-09-22); each rank keeps at most `limit` rows, every match is counted in total. */
    @Override
    public Map<String, Object> symbolSearchPage(String query, int limit, String kind) throws OpException {
        Map<String, Object> page = new LinkedHashMap<>();
        requireReady();
        String k = kind == null || kind.isBlank() ? null : kind.trim().toLowerCase();
        if (k != null && !k.equals("class") && !k.equals("method") && !k.equals("field"))
            throw OpException.syntax("kind must be class, method or field", "kind=" + kind);
        List<List<Object>> ranks = new ArrayList<>();
        for (int i = 0; i < 4; i++) ranks.add(new ArrayList<>());
        int total = 0;
        synchronized (this) {
            String q = query == null ? "" : query.trim();
            String ql = q.toLowerCase();
            int cap = limit > 0 ? limit : Integer.MAX_VALUE;
            if (!ql.isEmpty()) {
                if (k == null || k.equals("class")) {
                    for (IDexUnit dex : dexUnits) {
                        for (IDexClass cls : dex.getClasses()) {
                            String name = displayName(cls), fq = dottedName(cls);
                            int rank = rankOf(name, q, ql);
                            if (rank < 0 && fq.toLowerCase().contains(ql)) rank = fq.equals(q) ? 0 : fq.equalsIgnoreCase(q) ? 1 : 3;
                            if (rank < 0) continue;
                            total++;
                            if (ranks.get(rank).size() >= cap) continue;       // past the cut for this rank: counted, not collected
                            Map<String, Object> m = new LinkedHashMap<>();
                            m.put("name", name);
                            m.put("kind", "class");
                            m.put("addr", cls.getAddress());
                            m.put("fqcn", fq);
                            ranks.get(rank).add(m);
                        }
                    }
                }
                if (k == null || k.equals("method")) {
                    for (IDexUnit dex : dexUnits) {
                        for (IDexMethod m : dex.getMethods()) {
                            if (!m.isInternal()) continue;
                            int rank = rankOf(m.getName(true), q, ql);
                            if (rank < 0) continue;
                            total++;
                            if (ranks.get(rank).size() >= cap) continue;
                            Map<String, Object> row = new LinkedHashMap<>();
                            row.put("name", m.getName(true));
                            row.put("kind", "method");
                            row.put("addr", m.getAddress());
                            row.put("owner", ownerDotted(m.getAddress()));
                            ranks.get(rank).add(row);
                        }
                    }
                }
                if (k == null || k.equals("field")) {
                    for (IDexUnit dex : dexUnits) {
                        for (IDexField f : dex.getFields()) {
                            if (!f.isInternal()) continue;
                            int rank = rankOf(f.getName(true), q, ql);
                            if (rank < 0) continue;
                            total++;
                            if (ranks.get(rank).size() >= cap) continue;
                            Map<String, Object> row = new LinkedHashMap<>();
                            row.put("name", f.getName(true));
                            row.put("kind", "field");
                            row.put("addr", f.getAddress());
                            row.put("owner", ownerDotted(f.getAddress()));
                            ranks.get(rank).add(row);
                        }
                    }
                }
            }
        }
        List<Object> out = new ArrayList<>();
        int cap = limit > 0 ? limit : Integer.MAX_VALUE;
        for (List<Object> r : ranks) for (Object o : r) { if (out.size() >= cap) break; out.add(o); }
        page.put("rows", out);
        page.put("total", total);
        page.put("truncated", total > out.size());
        return page;
    }

    /** 0 exact, 1 exact ignoring case, 2 prefix, 3 substring, -1 no match. */
    private static int rankOf(String name, String q, String ql) {
        if (name == null) return -1;
        if (name.equals(q)) return 0;
        String nl = name.toLowerCase();
        if (nl.equals(ql)) return 1;
        if (nl.startsWith(ql)) return 2;
        if (nl.contains(ql)) return 3;
        return -1;
    }

    // ------------------------------------------------------------ decompile + semantic token map

    /**
     * Decompile the class owning {@code symbol} (class addr, or any method/field addr inside it).
     * Returns text + a semantic token map straight from JEB's AST (classTokens pattern): every
     * navigable token carries its target addr, so go-to-def/hover never reparse names. opts
     * "window" = "from,to" (1-based inclusive lines) slices the source while still returning the
     * full methods[] outline.
     */
    /** Per-method ceiling for a method-scoped decompile (JEB's DecompilationContext.maxTimePerMethod):
     *  JEB 5.45's own MCP passes a null context and one pathological method wedges its server. */
    private static final long METHOD_DECOMPILE_MS = 120_000L;

    @Override
    public Map<String, Object> decompile(String symbol, Map<String, Object> opts) throws OpException {
        requireReady();
        synchronized (this) {
            Resolved r = lookup(symbol);
            IDexClass cls = findClass(r.addr());
            if (cls == null && isExternalClass(classAddrOf(r.addr())))
                throw OpException.unsupported("external class " + simpleNameOf(classAddrOf(r.addr())) + " — not defined in this target, so there is no code;"
                        + " its outline lists the members the app references and xrefs show where", "symbol=" + symbol);
            if (cls == null) throw OpException.notFound("class not found for: " + symbol, "target=" + id);
            IDexUnit dex = owningDex(cls.getAddress());
            IDexDecompilerUnit dec = dex == null ? null : decompilers.get(dex);
            if (dec == null)
                throw OpException.unsupported("no decompiler for unit holding " + symbol, "target=" + id);

            /* scope — "method": decompile ONLY the member (JEB decompileMethod: the containing class is
               NOT decompiled; what JEB 5.45's own MCP does), "class": the whole class. Absent, a member
               symbol means method and a class symbol means class — an agent asking about a method
               pays for that method. The UI's code view asks for class explicitly (it scrolls the
               whole class to the member). tokens (the UI's semantic token map, class-sized) only on
               request. */
            String scopeOpt = opts == null || opts.get("scope") == null ? "" : String.valueOf(opts.get("scope")).trim().toLowerCase();
            if (!scopeOpt.isEmpty() && !scopeOpt.equals("method") && !scopeOpt.equals("class"))
                throw OpException.syntax("scope must be method or class", "scope=" + scopeOpt);
            boolean methodScope = scopeOpt.equals("method") || (scopeOpt.isEmpty() && r.kind() == Kind.METHOD);
            if (methodScope && r.kind() != Kind.METHOD)
                throw OpException.syntax("scope=method needs a method symbol (got a " + r.kind().name().toLowerCase() + ")", "symbol=" + symbol);
            boolean wantTokens = opts != null && Boolean.TRUE.equals(opts.get("tokens"));

            String classAddr = cls.getAddress();
            /* was it decompiled before (this session or a saved one)? getDecompiledUnit is the
               pure query ("No decompilation is performed") — getDecompiled*Text is NOT: it
               decompiles on demand, which made every decompile look pre-existing (probe on the
               fixture: a first-ever class decompile reported ms=0 and counted nothing) */
            boolean had = dec.getDecompiledUnit(methodScope ? r.addr() : classAddr) != null;
            long t0 = System.nanoTime();
            boolean ok;
            String src;
            if (methodScope) {
                var ctx = new com.pnfsoftware.jeb.core.units.code.DecompilationContext(
                        com.pnfsoftware.jeb.core.units.code.IDecompilerUnit.FLAG_NO_INNER_DECOMPILATION
                                | com.pnfsoftware.jeb.core.units.code.IDecompilerUnit.FLAG_NO_DEFERRED_DECOMPILATION,
                        Long.valueOf(METHOD_DECOMPILE_MS), null);
                ok = dec.decompileMethod(r.addr(), ctx);
                src = ok ? dec.getDecompiledMethodText(r.addr()) : null;
            } else {
                ok = dec.decompileClass(classAddr);
                src = ok ? dec.getDecompiledText(classAddr) : null;
            }
            long ms = (System.nanoTime() - t0) / 1_000_000;
            boolean fresh = !had && ok && src != null;
            /* only CLASS decompilations are unsaved work: JEB writes class units into the jdb2 on
               save and restores them on reopen (verified); a METHOD-only unit is transient — after
               save + reopen the same method is fresh again (verified on the fixture) — so counting
               it would prompt a save that keeps nothing */
            if (fresh && !methodScope && newDecomp.add(classAddr)) {
                newDecompCount = newDecomp.size();
                decompDirty = true;
            }

            int total = src == null ? 0 : countLines(src);
            Map<String, Object> out = new LinkedHashMap<>();
            Map<String, Object> sym = new LinkedHashMap<>();
            if (methodScope) {
                sym.put("addr", r.addr());
                sym.put("kind", "method");
                sym.put("name", r.name());
                Map<String, Object> owner = new LinkedHashMap<>();
                owner.put("addr", classAddr);
                owner.put("fqcn", dottedName(cls));
                sym.put("owner", owner);
            } else {
                sym.put("addr", classAddr);
                sym.put("kind", "class");
                sym.put("name", displayName(cls));
                sym.put("fqcn", dottedName(cls));
                sym.put("origSig", cls.getSignature(false));   // the ONE handle that resolves whatever the current name is: the view keys itself on it
                if (cls.isRenamed()) { sym.put("renamed", true); sym.put("origName", cls.getName(false)); }
            }
            out.put("symbol", sym);
            out.put("scope", methodScope ? "method" : "class");
            out.put("fresh", fresh);   // this call did new decompilation work (JEB had no unit for it) — unsaved until the next save
            out.put("language", "java");
            out.put("engine", "jeb");
            out.put("decompiled", ok && src != null);
            out.put("ms", ms);
            out.put("lineCount", total);

            int from = 0, to = 0;
            Object w = opts == null ? null : opts.get("window");
            if (w != null) {
                String[] ab = String.valueOf(w).split(",");
                try {
                    from = Integer.parseInt(ab[0].trim());
                    if (ab.length > 1) to = Integer.parseInt(ab[1].trim());
                } catch (NumberFormatException ignored) {}
            }
            if (src != null && (from > 0 || to > 0)) {
                int a = Math.max(1, from > 0 ? from : 1);
                int b = to > 0 ? Math.min(to, total) : total;
                out.put("text", a <= b ? sliceLines(src, a, b) : "");
                Map<String, Object> win = new LinkedHashMap<>();
                win.put("from", a);
                win.put("to", b);
                win.put("totalLines", total);
                out.put("window", win);
            } else {
                out.put("text", src == null ? "" : src);
            }
            if (methodScope) {
                Map<String, Object> one = new LinkedHashMap<>();
                one.put("addr", r.addr()); one.put("name", r.name()); one.put("from", 1); one.put("to", total);
                IDexMethod rm = cls == null ? null : cls.getDex().getMethod(r.addr());
                if (rm != null && rm.isRenamed()) { one.put("renamed", true); one.put("origName", rm.getName(false)); }   // the crumb's "(was: …)" on a renamed method
                out.put("methods", src == null ? List.of() : List.of(one));
                /* the retained parameter metadata beside the method's text — a separate block, the
                   text itself untouched (the agent and the MCP client read this field; the UI asks
                   code.params per method) */
                if (rm != null) { try { out.put("methodParameters", parameterMetadata(dex, cls, rm, false)); } catch (Throwable t) { out.put("methodParameters", Map.of("status", "unsupported", "why", String.valueOf(t))); } }   // without the current names: they are in the text, and reading them costs a class decompile
            } else {
                out.put("methods", src == null ? List.of() : methodRanges(cls, src));
                if (wantTokens) out.put("tokens", src == null ? List.of() : classTokens(dec, cls));   // class-sized; the UI asks for it
                /* which of the class's methods carry retained MethodParameters (bounded by that count):
                   the UI and an agent then ask code.params for the one they look at */
                List<Object> idx = new ArrayList<>();
                try {
                    for (IDexMethod mm : cls.getMethods()) {
                        Map<String, Object> pm = parameterMetadata(dex, cls, mm, false);
                        Map<?, ?> ann = (Map<?, ?>) pm.get("annotation");
                        if (ann == null || !("present".equals(ann.get("status")) || "malformed".equals(ann.get("status")))) continue;
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("addr", mm.getAddress()); row.put("original_method", mm.getSignature(false));
                        row.put("names", ann.get("names")); row.put("countMatchesPrototype", ann.get("countMatchesPrototype")); row.put("status", ann.get("status"));
                        idx.add(row);
                    }
                } catch (Throwable ignored) {}
                if (!idx.isEmpty()) out.put("methodParametersIndex", idx);
            }
            return out;
        }
    }

    /**
     * JEB renders methods in its own order; scan the source per method for the declaration line
     * (simple name used as a call-shaped token that opens a body, overloads disambiguated by
     * parameter count), then find the matching closing brace. Works off JEB's own class text, so
     * it stays correct under renames.
     */
    private List<Object> methodRanges(IDexClass cls, String src) {
        String[] lines = src.split("\n", -1);
        String simpleClass = simpleName(cls.getName(true));
        List<Object> out = new ArrayList<>();
        java.util.Set<Integer> claimed = new java.util.HashSet<>();
        for (IDexMethod m : cls.getMethods()) {
            String origName = m.getName(false);
            String declName = origName.equals("<clinit>") ? "static"
                    : origName.equals("<init>") ? simpleClass
                    : m.getName(true);
            int params = countParams(m.getSignature(false));
            Map<String, Object> mm = new LinkedHashMap<>();
            mm.put("addr", m.getAddress());
            mm.put("name", m.getName(true));
            mm.put("signature", m.getSignature(true));
            if (m.isRenamed()) { mm.put("renamed", true); mm.put("origName", m.getName(false)); }   // the crumb's "(was: …)" on a renamed method
            int decl = findDeclLine(lines, declName, params, origName.equals("<clinit>"), claimed);
            if (decl >= 0) {
                int end = findBlockEnd(lines, decl);
                mm.put("line", decl + 1);                     // 1-based
                mm.put("endLine", end + 1);
                claimed.add(decl);
            }
            out.add(mm);
        }
        return out;
    }

    private static int findDeclLine(String[] lines, String declName, int params, boolean clinit,
            java.util.Set<Integer> claimed) {
        int best = -1, bestIndent = Integer.MAX_VALUE;
        for (int i = 0; i < lines.length; i++) {
            if (claimed.contains(i)) continue;
            String raw = lines[i];
            String t = raw.strip();
            if (!t.endsWith("{")) continue;
            if (clinit) {
                if (t.startsWith("static {")) return i;
                continue;
            }
            int p = declName.isEmpty() ? -1 : t.indexOf(declName + "(");
            if (p < 0) continue;
            if (p > 0 && Character.isJavaIdentifierPart(t.charAt(p - 1))) continue;
            if (isControlKeyword(t)) continue;
            if (countParamsInDecl(t) != params) continue;
            int indent = raw.length() - raw.stripLeading().length();
            if (indent < bestIndent) { bestIndent = indent; best = i; }
        }
        return best;
    }

    private static boolean isControlKeyword(String t) {
        for (String kw : new String[] { "if(", "if (", "for(", "for (", "while(", "while (", "switch(",
                "switch (", "synchronized(", "synchronized (", "catch(", "catch (", "} else", "else {" })
            if (t.startsWith(kw)) return true;
        return false;
    }

    private static int countParamsInDecl(String t) {
        int open = t.indexOf('('), close = t.lastIndexOf(')');
        if (open < 0 || close < open) return -1;
        String inside = t.substring(open + 1, close).strip();
        if (inside.isEmpty()) return 0;
        int depth = 0, n = 1;
        for (int i = 0; i < inside.length(); i++) {
            char c = inside.charAt(i);
            if (c == '<' || c == '(' || c == '[') depth++;
            else if (c == '>' || c == ')' || c == ']') depth--;
            else if (c == ',' && depth == 0) n++;
        }
        return n;
    }

    /** Count params from a dalvik signature "…->m(Lp1;I[B)R" — top-level type tokens. */
    private static int countParams(String sig) {
        int open = sig.indexOf('('), close = sig.indexOf(')', open + 1);
        if (open < 0 || close < 0) return 0;
        String inside = sig.substring(open + 1, close);
        int n = 0, i = 0;
        while (i < inside.length()) {
            char c = inside.charAt(i);
            if (c == '[') { i++; continue; }
            if (c == 'L') {
                int s = inside.indexOf(';', i);
                i = (s < 0 ? inside.length() : s + 1);
                n++;
            } else { i++; n++; }
        }
        return n;
    }

    private static int findBlockEnd(String[] lines, int declLine) {
        int depth = 0;
        boolean started = false;
        for (int i = declLine; i < lines.length; i++) {
            for (int j = 0; j < lines[i].length(); j++) {
                char c = lines[i].charAt(j);
                if (c == '{') { depth++; started = true; }
                else if (c == '}') { depth--; if (started && depth == 0) return i; }
            }
        }
        return Math.min(declLine, lines.length - 1);
    }

    private static int countLines(String s) {
        int n = 1;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == '\n') n++;
        return n;
    }

    private static String sliceLines(String src, int from, int to) {
        String[] ls = src.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = from - 1; i < to && i < ls.length; i++) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(ls[i]);
        }
        return sb.toString();
    }

    // ── Semantic token map: per-class colouring + go-to-def + hover, straight from JEB's AST ─────
    // JEB's JavaDocument records an object location for every rendered token, each backed by a
    // typed IJava element. With the five render props below the document formats BYTE-IDENTICAL to
    // getDecompiledText, so these locations index the very text the client shows (verified by old
    // Jeeves across 24 classes on 5.40; same APIs in 5.42).

    private static final String[] CLEAN_RENDER_INT = {
            "DisplayMethodInternalsAsComment", "GenerateSpecialMetaComments", "SplitCallArgThreshold" };
    private static final int[] CLEAN_RENDER_VAL = { 0, 0, 1_000_000 };
    private static final String[] CLEAN_RENDER_BOOL = { "InsertBlankLinesAfterCompounds", "UseVarReservedType" };

    /** Set the clean-render props, returning previous values so the caller restores them (the
     *  property manager is shared across the unit's renders — leaving these set is a global side
     *  effect). */
    private static Map<String, Object> setCleanRender(IPropertyManager pm) {
        Map<String, Object> saved = new LinkedHashMap<>();
        if (pm == null) return saved;
        for (int i = 0; i < CLEAN_RENDER_INT.length; i++)
            try {
                saved.put(CLEAN_RENDER_INT[i], pm.getInteger(CLEAN_RENDER_INT[i], 0));
                pm.setInteger(CLEAN_RENDER_INT[i], CLEAN_RENDER_VAL[i]);
            } catch (Exception e) {}
        for (String k : CLEAN_RENDER_BOOL)
            try {
                saved.put(k, pm.getBoolean(k, false));
                pm.setBoolean(k, false);
            } catch (Exception e) {}
        // Inlined/collapsed methods still contain renameable arguments and locals.
        // Headless reads must expose them, without changing the user's saved view.
        try {
            String key = JavaDocument.propnameDisregardCollapse;
            saved.put(key, pm.getBoolean(key, false));
            pm.setBoolean(key, true);
        } catch (Exception e) {}
        return saved;
    }

    private static void restoreRender(IPropertyManager pm, Map<String, Object> saved) {
        if (pm == null) return;
        for (Map.Entry<String, Object> e : saved.entrySet())
            try {
                if (e.getValue() instanceof Integer) pm.setInteger(e.getKey(), (Integer) e.getValue());
                else if (e.getValue() instanceof Boolean) pm.setBoolean(e.getKey(), (Boolean) e.getValue());
                else if (e.getValue() == null) pm.setBoolean(e.getKey(), null);
            } catch (Exception ex) {}
    }

    /** Variable inventories need identifier items, not resolved call/field navigation targets.
     *  Keep these overrides local to the inventory render and restore even absent properties. */
    private static Map<String, Object> setVariableRender(IPropertyManager pm) {
        Map<String, Object> saved = setCleanRender(pm);
        if (pm != null) for (String key : List.of(JavaDocument.propnameResolveMethodCallTargets,
                JavaDocument.propnameResolveFieldAccessTargets)) {
            try {
                saved.put(key, pm.getValue(key, IPropertyManager.DEPTH_FULL, false, false));
                pm.setBoolean(key, false);
            } catch (Exception ignored) {}
        }
        return saved;
    }

    /** A single anchor represents the entire document (JEB's public ITextDocument contract).
     *  The line hint is not a cap: the part includes the whole anchor. Preserve the old
     *  complete-render path if a future JavaDocument implementation uses multiple anchors. */
    private static ITextDocumentPart completeDocumentPart(JavaDocument doc) {
        if (doc.getAnchorCount() == 1) return doc.getDocumentPart(doc.getFirstAnchor(), 1, 0);
        String text = doc.format();
        return doc.getDocumentPart(doc.getFirstAnchor(), countLines(text) + 5);
    }

    /** The per-class semantic token map: {line, cs, ce, kind, addr?, type?, params?} — 1-based line,
     *  0-based half-open columns over the decompiled text. kind drives colouring; addr go-to-def;
     *  type hover. */
    private List<Object> classTokens(IDexDecompilerUnit dec, IDexClass cls) {
        List<Object> tokens = new ArrayList<>();
        String ca = cls.getAddress();
        ISourceUnit su;
        try {
            su = dec.decompileToUnit(ca);
        } catch (Exception e) {
            return tokens;
        }
        if (!(su instanceof IJavaSourceUnit)) return tokens;
        IPropertyManager pm = su.getPropertyManager();
        Map<String, Object> savedProps = setCleanRender(pm);
        try {
            JavaDocument doc = new JavaDocument((IJavaSourceUnit) su);
            String text = doc.format();
            ITextDocumentPart part = doc.getDocumentPart(doc.getFirstAnchor(), countLines(text) + 5);
            Map<String, String> addrCache = new java.util.HashMap<>();
            Map<String, String> declTypeCache = new java.util.HashMap<>();
            java.util.Set<Long> declTypeSeen = new java.util.HashSet<>();
            String[] lines = text.split("\n", -1);
            if (part != null) for (IObjectLocation loc : part.getObjectLocations()) {
                Object o = loc.getObject();
                if (!(o instanceof IJavaElement)) continue;
                Map<String, Object> tk = classifyElement((IJavaElement) o);
                if (tk == null) continue;
                String kind = (String) tk.get("kind");
                Object rawAddr = tk.get("addr");           // getSignature() is ORIGINAL — map to current addr
                String addr = rawAddr == null ? null : addrCache.computeIfAbsent((String) rawAddr,
                        s -> {
                            String cur = currentAddrOf(s);
                            return cur != null ? cur : s;
                        });
                Object type = tk.get("type");
                int lb = loc.getLineBegin(), le = loc.getLineEnd();
                int cs = loc.getColumnBegin(), ce = loc.getColumnEnd();
                String lineText = lb >= 0 && lb < lines.length ? lines[lb] : "";
                boolean anchor = (ce <= cs) || (le != lb);  // 0-width / multi-line span = decl or block anchor

                if ("local".equals(kind) || "constant".equals(kind)) {
                    if (!anchor) {
                        addTok(tokens, lb + 1, cs, ce, kind, addr, type, null);
                        if ("local".equals(kind) && type != null)   // `1lG arg3`, `6ix v0 = …`: the declaration's type word
                            addDeclTypeTok(tokens, declTypeSeen, declTypeCache, lineText, lb + 1, cs, String.valueOf(type));
                    }
                    continue;
                }
                // class/type/method/field: locate the precise NAME (colour just it, not the whole span)
                String name = ("class".equals(kind) || "type".equals(kind)) ? simpleNameOf(addr)
                        : memberSimpleName(addr);
                if (name == null || name.isEmpty() || name.charAt(0) == '<') {
                    if (!anchor && addr != null) addTok(tokens, lb + 1, cs, ce, kind, addr, type, null);
                    continue;
                }
                int tokLine = lb;
                int np = findWord(lineText, name, anchor ? 0 : cs, anchor ? lineText.length() : ce);
                if (np < 0 && anchor) {
                    /* a COMMENTED declaration: the element's span begins on the
                       comment line, so the name is not on lines[lb] and the
                       declaration lost its token (F2 / ⚡ Reverse / RELATION
                       landing all fell back to the class — found by the UI
                       audit batch-1 QA, 2026-09-09). Walk down to the line that
                       carries the name, skipping comment lines. */
                    for (int l = lb + 1; l <= Math.min(le, lb + 12) && l < lines.length; l++) {
                        String t = lines[l].trim();
                        if (t.startsWith("//") || t.startsWith("/*") || t.startsWith("*")) continue;
                        int p = findWord(lines[l], name, 0, lines[l].length());
                        if (p >= 0) { np = p; tokLine = l; lineText = lines[l]; break; }
                    }
                }
                if (np < 0) {
                    if (!anchor && addr != null) addTok(tokens, lb + 1, cs, ce, kind, addr, type, null);
                    continue;
                }
                // a STATIC receiver `ClassName.member` — colour the class part as the OWNER class
                if (!anchor && addr != null && ("method".equals(kind) || "field".equals(kind))
                        && addr.indexOf("->") > 0) {
                    String owner = addr.substring(0, addr.indexOf("->")), os = simpleNameOf(owner);
                    int dot = lineText.lastIndexOf('.', np - 1);
                    if (dot > cs && os != null && !os.isEmpty()) {
                        int rStart = dot - os.length();
                        if (rStart >= cs && lineText.regionMatches(rStart, os, 0, os.length())
                                && (rStart == 0 || !isIdentChar(lineText.charAt(rStart - 1))))
                            addTok(tokens, lb + 1, rStart, dot, "class", owner, null, null);
                    }
                }
                addTok(tokens, tokLine + 1, np, np + name.length(), kind, addr, type, tk.get("params"));
                if (anchor && "method".equals(kind) && addr != null && addr.indexOf(')') > 0)   // a method DECLARATION: its return type word
                    addDeclTypeTok(tokens, declTypeSeen, declTypeCache, lineText, tokLine + 1, np, addr.substring(addr.lastIndexOf(')') + 1));
            }
        } finally {
            restoreRender(pm, savedProps);               // never leave a global render-config side effect
        }
        return tokens;
    }

    /** A declaration's TYPE word — `1lG arg3` in a parameter list, `6ix v0 = …`, the return type in
     *  `MediaDownloader create(…)` — is no element of JEB's location map, so it carried no token:
     *  double-click did nothing, a click did not highlight it and RELATION stayed empty, on every
     *  class name in a declaration (user, 2026-09-18). Emit a `type` token when the word right
     *  before the declared name is the simple name of the type's CURRENT class. Platform types
     *  (not in the dex) get none — there is nothing to navigate to. */
    private void addDeclTypeTok(List<Object> tokens, java.util.Set<Long> seen, Map<String, String> cache,
            String lineText, int line1, int nameStart, String typeSig) {
        if (typeSig == null || lineText == null || nameStart <= 0 || nameStart > lineText.length()) return;
        String sig = typeSig;
        while (sig.startsWith("[")) sig = sig.substring(1);
        if (!sig.startsWith("L") || !sig.endsWith(";")) return;
        String cur = cache.computeIfAbsent(sig, x -> { String c = currentAddrOf(x); return c == null ? "" : c; });
        if (cur.isEmpty()) return;
        String sn = simpleNameOf(cur);
        if (sn == null || sn.isEmpty()) return;
        int e = nameStart;
        while (e > 0 && lineText.charAt(e - 1) == ' ') e--;
        if (e == nameStart) return;                                   // no gap before the name: not a declaration
        while (e >= 3 && lineText.startsWith("...", e - 3)) e -= 3;   // varargs
        while (e >= 2 && lineText.charAt(e - 1) == ']' && lineText.charAt(e - 2) == '[') e -= 2;   // arrays
        int st = e - sn.length();
        if (st < 0 || !lineText.regionMatches(st, sn, 0, sn.length())) return;
        if (st > 0 && isIdentChar(lineText.charAt(st - 1))) return;
        if (!seen.add((long) line1 * 100_000L + st)) return;
        addTok(tokens, line1, st, st + sn.length(), "type", cur, null, null);
    }

    /** Classify a rendered AST element into {kind, addr?, type?, params?}. Null for elements that
     *  carry no useful symbol (blocks, statements, keywords). */
    private static Map<String, Object> classifyElement(IJavaElement e) {
        Map<String, Object> m = new LinkedHashMap<>();
        try {
            if (e instanceof IJavaCall) {
                IJavaMethod mm = ((IJavaCall) e).getMethod();
                m.put("kind", "method");
                if (mm != null) {
                    m.put("addr", mm.getSignature());
                    try {                                // callee param names → inlay hints
                        List<String> ps = new ArrayList<>();
                        for (IJavaDefinition d : mm.getParameters()) {
                            IJavaIdentifier id = d.getIdentifier();
                            ps.add(id != null ? id.getName() : null);
                        }
                        if (!ps.isEmpty()) m.put("params", ps);
                    } catch (Exception ex) {}
                }
                return m;
            }
            if (e instanceof IJavaStaticField) {
                IJavaField f = ((IJavaStaticField) e).getField();
                m.put("kind", "field");
                if (f != null) m.put("addr", f.getSignature());
                return m;
            }
            if (e instanceof IJavaInstanceField) {
                IJavaField f = ((IJavaInstanceField) e).getField();
                m.put("kind", "field");
                if (f != null) m.put("addr", f.getSignature());
                return m;
            }
            if (e instanceof IJavaNew) {
                IJavaType t = ((IJavaNew) e).getType();
                m.put("kind", "type");
                if (t != null) m.put("addr", t.getSignature());
                return m;
            }
            if (e instanceof IJavaClass) { m.put("kind", "class"); m.put("addr", ((IJavaClass) e).getSignature()); return m; }
            if (e instanceof IJavaMethod) { m.put("kind", "method"); m.put("addr", ((IJavaMethod) e).getSignature()); return m; }
            if (e instanceof IJavaField) { m.put("kind", "field"); m.put("addr", ((IJavaField) e).getSignature()); return m; }
            if (e instanceof IJavaType) { m.put("kind", "type"); m.put("addr", ((IJavaType) e).getSignature()); return m; }
            if (e instanceof IJavaIdentifier) {
                IJavaType t = ((IJavaIdentifier) e).getType();
                m.put("kind", "local");
                if (t != null) m.put("type", t.getSignature());
                return m;
            }
            if (e instanceof IJavaConstant) { m.put("kind", "constant"); return m; }
        } catch (Exception ex) { return null; }
        return null;
    }

    private static void addTok(List<Object> out, int line, int cs, int ce, String kind,
            String addr, Object type, Object params) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("line", line);
        t.put("cs", cs);
        t.put("ce", ce);
        t.put("kind", kind);
        if (addr != null) t.put("addr", addr);
        if (type != null) t.put("type", type);
        if (params != null) t.put("params", params);
        out.add(t);
    }

    /** Current addr for an ORIGINAL signature (a rename changes addrs); null when unresolvable. */
    private String currentAddrOf(String origSig) {
        try {
            return switch (kindOf(origSig)) {
                case CLASS -> {
                    IDexClass c = findAnyClass(origSig);
                    yield c != null ? c.getAddress() : null;
                }
                case METHOD -> {
                    IDexMethod m = findAnyMethod(origSig);
                    yield m != null ? m.getAddress() : null;
                }
                case FIELD -> {
                    IDexField f = findAnyField(origSig);
                    yield f != null ? f.getAddress() : null;
                }
            };
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Simple (last-segment) name of a class addr: "Lcom/foo/Bar$Baz;" → "Baz". */
    private static String simpleNameOf(String classAddr) {
        if (classAddr == null || classAddr.isEmpty()) return classAddr;
        String s = classAddr;
        if (s.startsWith("L") && s.endsWith(";")) s = s.substring(1, s.length() - 1);
        int cut = Math.max(s.lastIndexOf('/'), s.lastIndexOf('$'));
        return cut >= 0 ? s.substring(cut + 1) : s;
    }

    /** The member name of a member addr ("La;->F(III)I" → "F"); simple class name otherwise. */
    private static String memberSimpleName(String addr) {
        if (addr == null) return null;
        int arrow = addr.indexOf("->");
        if (arrow < 0) return simpleNameOf(addr);
        String m = addr.substring(arrow + 2);
        int p = m.indexOf('(');
        if (p < 0) p = m.indexOf(':');
        return p < 0 ? m : m.substring(0, p);
    }

    private static boolean isIdentChar(char c) {
        return Character.isJavaIdentifierPart(c) || c == '$';
    }

    /** First whole-word occurrence of {@code name} in {@code line} within [from,to), or -1. */
    private static int findWord(String line, String name, int from, int to) {
        if (line == null || name == null || name.isEmpty()) return -1;
        to = Math.min(to, line.length());
        int i = Math.max(0, from);
        while (i < to && (i = line.indexOf(name, i)) >= 0 && i < to) {
            int end = i + name.length();
            boolean lok = i == 0 || !isIdentChar(line.charAt(i - 1));
            boolean rok = end >= line.length() || !isIdentChar(line.charAt(end));
            if (lok && rok && end <= to) return i;
            i = end;
        }
        return -1;
    }

    private IDexUnit owningDex(String addr) {
        String classAddr = classAddrOf(addr);
        for (IDexUnit dex : dexUnits)
            if (dex.getClass(classAddr) != null) return dex;
        return null;
    }

    // ------------------------------------------------------------ outline / hover

    /** Method/field outline of a class (code.outline). */
    @Override
    public Map<String, Object> outline(String classSymbol) throws OpException { return outline(classSymbol, false); }

    /**
     * The class outline a consumer generating hooks or reflection can act on (the FMessage observer follow-up,
     * 2026-10-09): every member with its ORIGINAL name and descriptor (what the phone runs — a display rename
     * exists only here), fields with their type and static/instance kind, access flags, the declaring class;
     * `collisions` names every member name shared by a field and a method of this class, by ORIGINAL name, with
     * the hook advice (a bare name in Frida selects the method wrapper; the field needs `getDeclaredField`).
     * `inherited: true` adds the superclass chain's fields (in-dex supers, up to 6 levels; framework supers are
     * listed as unknown — their fields are not in this dex). Jeeves's DEX metadata is reported as it is; nothing is
     * renamed or hidden to work around a consumer's syntax.
     */
    @Override
    public Map<String, Object> outline(String classSymbol, boolean inherited) throws OpException {
        requireReady();
        synchronized (this) {
            Resolved r = lookup(classSymbol);
            IDexClass cls = findClass(r.addr());
            if (cls == null && isExternalClass(r.addr())) return externalOutline(r.addr());
            if (cls == null) throw OpException.notFound("class not found: " + classSymbol, "target=" + id);
            Map<String, Object> out = new LinkedHashMap<>();
            Map<String, Object> sym = new LinkedHashMap<>();
            sym.put("addr", cls.getAddress());
            sym.put("kind", "class");
            sym.put("name", displayName(cls));
            sym.put("fqcn", dottedName(cls));
            sym.put("origSig", cls.getSignature(false));
            if (cls.isRenamed()) sym.put("origName", cls.getName(false));
            out.put("symbol", sym);
            Map<String, List<String>> methodsByOrigName = new LinkedHashMap<>();
            List<Object> methods = new ArrayList<>();
            for (IDexMethod m : cls.getMethods()) {
                Map<String, Object> mm = new LinkedHashMap<>();
                mm.put("name", m.getName(true));
                mm.put("addr", m.getAddress());
                mm.put("sig", m.getSignature(true));
                mm.put("origSig", m.getSignature(false));
                if (m.isRenamed()) mm.put("origName", m.getName(false));
                IDexMethodData d = m.getData();
                int acc = d == null ? 0 : d.getAccessFlags();
                mm.put("static", (acc & 0x8) != 0);
                mm.put("access", flags(acc, false).trim());
                if (d != null && d.isNative()) mm.put("native", true);
                methods.add(mm);
                methodsByOrigName.computeIfAbsent(m.getName(false), k -> new ArrayList<>()).add(m.getSignature(false));
            }
            List<Object> fields = new ArrayList<>();
            List<Object> collisions = new ArrayList<>();
            for (IDexField f : cls.getFields()) {
                Map<String, Object> fm = fieldRow(f, cls, null);
                List<String> same = methodsByOrigName.get(f.getName(false));
                if (same != null && !same.isEmpty()) {
                    fm.put("collides", true);
                    Map<String, Object> c = new LinkedHashMap<>();
                    c.put("origName", f.getName(false));
                    if (f.isRenamed() || !f.getName(true).equals(f.getName(false))) c.put("fieldDisplayName", f.getName(true));
                    Map<String, Object> cf = new LinkedHashMap<>();
                    cf.put("origSig", f.getSignature(false)); cf.put("type", f.getFieldTypeSignature(false)); cf.put("static", fm.get("static"));
                    c.put("field", cf);
                    c.put("methods", same);
                    c.put("note", "a FIELD and " + same.size() + " METHOD" + (same.size() == 1 ? "" : "s") + " of this class share the original name " + f.getName(false)
                            + " — a bare member name selects the method wrapper in Frida (`obj." + f.getName(false) + "` is the method); read the field by kind: `obj.class.getDeclaredField(\"" + f.getName(false) + "\")` (then setAccessible/get), or Java.use(...).class.getDeclaredField; call the method by its descriptor");
                    collisions.add(c);
                }
                fields.add(fm);
            }
            for (Object mo : methods) {
                @SuppressWarnings("unchecked") Map<String, Object> mm = (Map<String, Object>) mo;
                String on = mm.get("origName") != null ? String.valueOf(mm.get("origName")) : String.valueOf(mm.get("name"));
                for (Object co : collisions) if (on.equals(((Map<?, ?>) co).get("origName"))) mm.put("collides", true);
            }
            out.put("methods", methods);
            out.put("fields", fields);
            out.put("collisions", collisions);
            if (!collisions.isEmpty()) out.put("collisionsNote", "names are ORIGINAL (what the phone runs); display renames exist only in this workbench");
            if (inherited) {
                Map<String, Object> inh = new LinkedHashMap<>();
                List<Object> ifields = new ArrayList<>();
                List<String> unknown = new ArrayList<>();
                List<String> chain = new ArrayList<>();
                IDexClass cur = cls;
                for (int level = 0; level < 6 && cur != null; level++) {
                    String sup = cur.getSupertypeSignature(false);
                    if (sup == null || sup.equals("Ljava/lang/Object;")) break;
                    IDexClass sc = findAnyClass(sup);
                    if (sc == null) { unknown.add(sup); break; }   // a framework or library class: its fields are not in this dex
                    chain.add(sc.getAddress());
                    for (IDexField f : sc.getFields()) ifields.add(fieldRow(f, sc, sc.getAddress()));
                    cur = sc;
                }
                inh.put("chain", chain); inh.put("fields", ifields); inh.put("unknown", unknown);
                inh.put("note", "the superclass chain's fields as this dex declares them (up to 6 levels); a runtime object also carries the fields of the classes listed under unknown (framework/library — not in this dex) and of any Object subclass the runtime type adds");
                out.put("inherited", inh);
            }
            return out;
        }
    }

    /** One field of the outline: original name and descriptor, type (original and display), static/instance, access. */
    private Map<String, Object> fieldRow(IDexField f, IDexClass owner, String inheritedFrom) {
        Map<String, Object> fm = new LinkedHashMap<>();
        fm.put("name", f.getName(true));
        fm.put("addr", f.getAddress());
        fm.put("sig", f.getSignature(true));
        fm.put("origSig", f.getSignature(false));
        if (f.isRenamed()) fm.put("origName", f.getName(false));
        fm.put("type", f.getFieldTypeSignature(false));
        String dispType = f.getFieldTypeSignature(true);
        if (dispType != null && !dispType.equals(fm.get("type"))) fm.put("displayType", dispType);
        com.pnfsoftware.jeb.core.units.code.android.dex.IDexFieldData fd = f.getData();
        int acc = fd == null ? 0 : fd.getAccessFlags();
        fm.put("static", (acc & 0x8) != 0);
        fm.put("final", (acc & 0x10) != 0);
        fm.put("access", flags(acc, false).trim());
        fm.put("declaredIn", owner.getSignature(false));
        if (inheritedFrom != null) fm.put("inheritedFrom", inheritedFrom);
        return fm;
    }

    /** Tooltip model (code.hover): kind, owner, sig, comment. */
    @Override
    public Map<String, Object> hover(String symbol, int offset) throws OpException {
        requireReady();
        synchronized (this) {
            Resolved r = lookup(symbol);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("kind", r.kind().name().toLowerCase());
            out.put("owner", r.kind() == Kind.CLASS ? dottedName(findClass(r.addr())) : ownerDotted(r.addr()));
            String sig = null;
            switch (r.kind()) {
                case CLASS -> { IDexClass c = findAnyClass(r.addr()); if (c != null) sig = c.getSignature(true); }
                case METHOD -> { IDexMethod m = findAnyMethod(r.addr()); if (m != null) sig = m.getSignature(true); }
                case FIELD -> { IDexField f = findAnyField(r.addr()); if (f != null) sig = f.getSignature(true); }
            }
            out.put("sig", sig == null ? r.name() : sig);
            out.put("offset", offset);
            IDexUnit dex = owningDex(r.addr());
            String cmt = dex == null ? null : dex.getCommentManager().getInlineComment(r.addr());
            out.put("comment", cmt == null ? "" : cmt);
            return out;
        }
    }

    // ------------------------------------------------------------ smali / disassembly

    /**
     * Smali listing for a class (full listing) or one method (its block only), with
     * method-relative offsets and raw opcode bytes (WORKBENCH_SPEC §5). JEB renders DEX
     * disassembly; we compose rows from IDexMethod.getInstructions() + insn.format(null),
     * resolving pool references (method@/field@/string@/type@) to live signatures and
     * branch targets to label rows.
     */
    @Override
    public Map<String, Object> smali(String symbol) throws OpException {
        requireReady();
        synchronized (this) {
            Resolved r = lookup(symbol);
            IDexClass cls = findClass(r.addr());
            if (cls == null) throw OpException.notFound("class not found: " + symbol, "target=" + id);
            IDexUnit dex = owningDex(cls.getAddress());
            List<Object> rows = new ArrayList<>();
            Map<String, Object> methodParams = null;
            if (r.kind() == Kind.METHOD) {
                IDexMethod m = findAnyMethod(r.addr());
                if (m == null) throw OpException.notFound("method not found: " + symbol, "target=" + id);
                smaliMethod(dex, m, rows);
                try { methodParams = parameterMetadata(dex, cls, m, false); } catch (Throwable ignored) {}
            } else {
                // class header
                dirRow(rows, ".class " + flags(cls.getAccessFlags(), true) + cls.getSignature(true));
                String sup = cls.getSupertypeSignature(true);
                if (sup != null) dirRow(rows, ".super " + sup);
                String[] ifs = cls.getInterfaceSignatures(true);
                if (ifs != null && ifs.length > 0) {
                    blankRow(rows);
                    for (String itf : ifs) dirRow(rows, ".implements " + itf);
                }
                List<? extends IDexField> fields = cls.getFields();
                if (!fields.isEmpty()) {
                    blankRow(rows);
                    for (IDexField f : fields) {
                        String sig = f.getSignature(true);
                        int arrow = sig.indexOf("->");
                        int fl = f.getData() == null ? 0 : f.getData().getAccessFlags();
                        dirRow(rows, ".field " + flags(fl, false) + sig.substring(arrow + 2));
                    }
                }
                blankRow(rows);
                for (IDexMethod m : cls.getMethods()) {
                    smaliMethod(dex, m, rows);
                    blankRow(rows);
                }
            }
            Map<String, Object> out = new LinkedHashMap<>();
            Map<String, Object> sym = new LinkedHashMap<>();
            sym.put("addr", r.kind() == Kind.METHOD ? r.addr() : cls.getAddress());
            sym.put("kind", r.kind().name().toLowerCase());
            sym.put("name", r.kind() == Kind.METHOD ? r.name() : displayName(cls));
            sym.put("fqcn", dottedName(cls));
            out.put("symbol", sym);
            out.put("engine", "jeb");
            out.put("count", rows.size());
            out.put("rows", rows);
            if (methodParams != null) out.put("methodParameters", methodParams);   // the annotation rows above, structured
            out.put("note", "a listing composed from the engine's instruction model (JEB) with branch destinations decoded per the DEX spec — not a qualified round-trippable smali export");
            return out;
        }
    }

    /** Dalvik opcodes whose TYPE_BRA operand names a DATA payload, not an instruction. */
    private static final int OP_FILL_ARRAY_DATA = 0x26, OP_PACKED_SWITCH = 0x2b, OP_SPARSE_SWITCH = 0x2c;

    /**
     * One method block: .method / .locals / the retained metadata (the MethodParameters annotation
     * and the debug_info parameter names, as separate rows — never applied to anything) / label+insn
     * rows / payload rows / .end method.
     *
     * Branch operands: JEB hands a TYPE_BRA value as a SIGNED DISPLACEMENT IN 16-BIT CODE UNITS from
     * the instruction (IDalvikInstruction doc) — the destination is {@code offset + 2 * value}. The
     * listing once took the value as an absolute byte offset ({@code if-nez v0, :l_22} for a branch
     * from 0x6e to 0xb2, with the label placed at 0x22 so the throw path read as a backward jump —
     * seen on a WhatsApp release, 2026-10-07). A packed/sparse-switch's operand names
     * its PAYLOAD (same units); the case targets inside the payload are displacements from the switch
     * instruction too; fill-array-data names a data payload. Payloads are not in the engine's
     * instruction list, so they are rendered as their own rows after the code. A destination that is
     * not an instruction start (or lies outside the method) is said as such in the operand instead of
     * being mapped onto another instruction. The try table comes from the code item's exception items
     * (.catch rows, :try_start_/:try_end_/:catch_ labels); the CFG's exceptional edges are the fallback.
     */
    private void smaliMethod(IDexUnit dex, IDexMethod m, List<Object> rows) {
        String sig = m.getSignature(true);
        String shortSig = sig.substring(sig.indexOf("->") + 2);
        int acc = m.getData() == null ? 0 : m.getData().getAccessFlags();
        Map<String, Object> mrow = dirRow(rows, ".method " + flags(acc, false) + shortSig);
        mrow.put("method", m.getAddress());
        mrow.put("name", m.getName(true));
        IDexMethodData md = m.getData();
        IDexCodeItem ci = md == null ? null : md.getCodeItem();
        IDexClass owner = findClass(m.getAddress());
        if (ci != null) dirRow(rows, "    .locals " + Math.max(0, ci.getRegisterCount() - ci.getInputArgumentCount()));
        /* the retained metadata, whatever the method has a body or not: shown, never applied */
        annotationRows(dex, owner, m, ci, rows);
        if (ci == null) {                                    // abstract / native — no body
            dirRow(rows, ".end method");
            return;
        }
        blankRow(rows);

        List<? extends IDalvikInstruction> insns = ci.getInstructions();
        if (insns == null) {
            dirRow(rows, ".end method");
            return;
        }
        java.util.Set<Long> starts = new java.util.HashSet<>();
        long end = 0;
        for (IDalvikInstruction insn : insns) { starts.add(insn.getOffset()); end = Math.max(end, insn.getOffset() + insn.getSize()); }
        // destinations → labels (hex of the destination byte offset), payloads → payload labels
        Map<Long, String> labels = new java.util.TreeMap<>();
        Map<Long, String> payloadLabels = new java.util.TreeMap<>();
        Map<Long, IDalvikInstruction> payloadOwner = new java.util.TreeMap<>();
        for (IDalvikInstruction insn : insns) {
            Long dest = branchDestination(insn);
            if (dest == null) continue;
            int op = insn.getOpcode();
            if (op == OP_PACKED_SWITCH || op == OP_SPARSE_SWITCH || op == OP_FILL_ARRAY_DATA) {
                payloadLabels.computeIfAbsent(dest, t -> (op == OP_PACKED_SWITCH ? ":pswitch_data_" : op == OP_SPARSE_SWITCH ? ":sswitch_data_" : ":array_") + Long.toHexString(t));
                payloadOwner.put(dest, insn);
                if (insn.getSwitchData() != null)
                    for (int[] e : insn.getSwitchData().getElements()) {
                        long caseDest = insn.getOffset() + 2L * e[1];
                        if (starts.contains(caseDest)) labels.computeIfAbsent(caseDest, t -> ":l_" + Long.toHexString(t));
                    }
            } else if (starts.contains(dest)) {
                labels.computeIfAbsent(dest, t -> ":l_" + Long.toHexString(t));
            }
        }
        /* exception handlers: the code item's try table (IDexExceptionItem / IDexExceptionHandler — addresses
           in bytes per the API) gives the try ranges and their typed / untyped handlers, rendered as smali's
           .catch rows after the code with :try_start_/:try_end_/:catch_ labels; a code item without a table
           falls back to the CFG's exceptional edges, which name the handler blocks but not the ranges or
           the caught types */
        java.util.Set<Long> handlers = new java.util.TreeSet<>();
        Map<Long, java.util.LinkedHashSet<String>> tryLabels = new java.util.TreeMap<>();
        List<String> catchRows = new ArrayList<>();
        try {
            java.util.List<? extends IDexExceptionItem> items = ci.getExceptionItems();
            if (items != null) for (IDexExceptionItem it : items) {
                long ts = it.getTryAddress(), te = it.getTryAddressEnd();
                String sl = ":try_start_" + Long.toHexString(ts), el = ":try_end_" + Long.toHexString(te);
                tryLabels.computeIfAbsent(ts, t -> new java.util.LinkedHashSet<>()).add(sl);
                tryLabels.computeIfAbsent(te, t -> new java.util.LinkedHashSet<>()).add(el);
                for (IDexExceptionHandler h : it.getHandlers()) {
                    long ha = h.getAddress();
                    String type = null;
                    if (h.getTypeIndex() >= 0) {
                        try { IDexType ty = h.resolveType(dex); type = ty == null ? "type#" + h.getTypeIndex() : ty.getSignature(false); }
                        catch (Throwable t) { type = "type#" + h.getTypeIndex(); }
                    }
                    final String tf = type;
                    String hl;
                    if (starts.contains(ha)) { handlers.add(ha); hl = labels.computeIfAbsent(ha, t -> (tf == null ? ":catchall_" : ":catch_") + Long.toHexString(t)); }
                    else hl = String.format("%04x (no instruction at that offset)", ha);
                    catchRows.add("    " + (type == null ? ".catchall" : ".catch " + type) + " {" + sl + " .. " + el + "} " + hl);
                }
            }
        } catch (Throwable ignored) {}
        boolean cfgHandlers = false;
        if (catchRows.isEmpty()) {
            try {
                com.pnfsoftware.jeb.core.units.code.android.controlflow.CFG<IDalvikInstruction> cfg = ci.getControlFlowGraph();
                if (cfg != null) for (com.pnfsoftware.jeb.core.units.code.android.controlflow.BasicBlock<IDalvikInstruction> bb : cfg.getBlocksView())
                    for (com.pnfsoftware.jeb.core.units.code.android.controlflow.BasicBlock<IDalvikInstruction> h : bb.getIrregularOutputs()) handlers.add(h.getFirstAddress());
            } catch (Throwable ignored) {}
            cfgHandlers = !handlers.isEmpty();
            for (long h : handlers) if (starts.contains(h)) labels.computeIfAbsent(h, t -> ":l_" + Long.toHexString(t));
        }

        for (IDalvikInstruction insn : insns) {
            java.util.LinkedHashSet<String> tls = tryLabels.get(insn.getOffset());
            if (tls != null) for (String tl : tls) labelRow(rows, "    " + tl);
            String label = labels.get(insn.getOffset());
            if (label != null) {
                if (cfgHandlers && handlers.contains(insn.getOffset())) commentRow(rows, "    # exception handler (an exceptional edge in the CFG — this code item carries no try table)");
                labelRow(rows, "    " + label);
            }
            String text = insn.format(null).trim();
            Long dest = branchDestination(insn);
            if (dest != null) {
                int op = insn.getOpcode();
                String operand;
                if (op == OP_PACKED_SWITCH || op == OP_SPARSE_SWITCH || op == OP_FILL_ARRAY_DATA) operand = payloadLabels.get(dest);
                else if (starts.contains(dest)) operand = labels.get(dest);
                else operand = String.format("%+d units → %04x (no instruction at that offset%s)", branchDisplacement(insn), dest, dest < 0 ? ", before the method" : dest >= end ? ", past the last instruction (a payload area or outside the method)" : "");
                // rebuild the branch operand: keep everything before the last operand
                int sp = text.indexOf(' ');
                String head = sp < 0 ? text : text.substring(0, sp);
                String ops = sp < 0 ? "" : text.substring(sp + 1);
                int lastComma = ops.lastIndexOf(',');
                String pre = lastComma < 0 ? "" : ops.substring(0, lastComma + 1);
                text = pre.isEmpty() ? head + " " + operand : head + " " + pre + " " + operand;
            }
            text = resolvePoolRefs(dex, text);
            insnRow(rows, insn.getOffset(), insn.getCode(), "    " + text);
        }
        java.util.LinkedHashSet<String> tailTls = tryLabels.get(end);   // a try block that runs to the last instruction ends here
        if (tailTls != null) for (String tl : tailTls) labelRow(rows, "    " + tl);
        for (String c : catchRows) dirRow(rows, c);
        /* the payloads, in offset order: switch tables with their case labels (destinations decoded like a
           branch's), array data with its elements as the engine decodes them */
        for (Map.Entry<Long, String> e : payloadLabels.entrySet()) {
            IDalvikInstruction sw = payloadOwner.get(e.getKey());
            blankRow(rows);
            labelRow(rows, "    " + e.getValue());
            if (sw != null && sw.getSwitchData() != null) {
                int[][] els = sw.getSwitchData().getElements();
                boolean packed = sw.getOpcode() == OP_PACKED_SWITCH;
                Map<String, Object> hdr = dirRow(rows, "    " + (packed ? ".packed-switch " + (els.length > 0 ? hexLit(els[0][0]) : "") : ".sparse-switch"));
                hdr.put("offset", String.format("%04x", e.getKey()));
                for (int[] el : els) {
                    long caseDest = sw.getOffset() + 2L * el[1];
                    String target = starts.contains(caseDest) ? labels.get(caseDest) : String.format("%+d units → %04x (no instruction at that offset)", el[1], caseDest);
                    caseRow(rows, "        " + (packed ? "" : hexLit(el[0]) + " -> ") + target);
                }
                dirRow(rows, "    " + (packed ? ".end packed-switch" : ".end sparse-switch"));
            } else {
                com.pnfsoftware.jeb.core.units.code.android.dex.IDalvikInstructionArrayData ad = null;
                byte[][] raw = null; long[] vals = null;
                try { ad = sw == null ? null : sw.getArrayData(); if (ad != null) { raw = ad.getElements(); vals = ad.asArrayOfLongs(); } } catch (Throwable ignored) {}
                int width = raw != null && raw.length > 0 && raw[0] != null ? raw[0].length : 0;
                Map<String, Object> hdr = dirRow(rows, "    .array-data" + (width > 0 ? " " + width : "") + (vals == null ? "   # the payload at " + String.format("%04x", e.getKey()) + " — the engine did not decode its elements; bytes not listed" : ""));
                hdr.put("offset", String.format("%04x", e.getKey()));
                if (vals != null) for (long v : vals) caseRow(rows, "        " + hexLit(v));
                dirRow(rows, "    .end array-data");
            }
        }
        dirRow(rows, ".end method");
    }

    /** A signed literal as smali writes it: -0x64, not 0xffffff9c. */
    private static String hexLit(long v) { return v < 0 ? "-0x" + Long.toHexString(-v) : "0x" + Long.toHexString(v); }

    /** The absolute byte destination of a branching or payload-referencing instruction, or null:
     *  the TYPE_BRA operand is a signed displacement in 16-bit code units from the instruction. */
    private static Long branchDestination(IDalvikInstruction insn) {
        for (int i = 0; i < insn.getParameterCount(); i++)
            if (insn.getParameterType(i) == IDalvikInstruction.TYPE_BRA) return insn.getOffset() + 2L * insn.getParameterValue(i);
        return null;
    }

    private static long branchDisplacement(IDalvikInstruction insn) {
        for (int i = 0; i < insn.getParameterCount(); i++)
            if (insn.getParameterType(i) == IDalvikInstruction.TYPE_BRA) return insn.getParameterValue(i);
        return 0;
    }

    /** The method's retained metadata as rows of kind "annotation": the MethodParameters system
     *  annotation verbatim (names and accessFlags in encoded order, nulls as null — never turned into
     *  .param names on registers) and the debug_info parameter names as their own `.param` rows
     *  (debug names ARE per formal parameter by the DEX format; the register is computed from the
     *  prototype). Nothing here renames anything. */
    private void annotationRows(IDexUnit dex, IDexClass owner, IDexMethod m, IDexCodeItem ci, List<Object> rows) {
        Map<String, Object> meta;
        try { meta = parameterMetadata(dex, owner, m, false); } catch (Throwable t) { commentRow(rows, "    # parameter metadata unavailable: " + t); return; }
        Map<?, ?> ann = (Map<?, ?>) meta.get("annotation");
        if (ann != null && "present".equals(ann.get("status")) || ann != null && "malformed".equals(ann.get("status"))) {
            annRow(rows, "    # retained MethodParameters — metadata the compiler kept, not the arguments' names; argument mapping unverified");
            annRow(rows, "    .annotation system Ldalvik/annotation/MethodParameters;");
            if (ann.get("accessFlags") instanceof List<?> fl) {
                StringBuilder sb = new StringBuilder("        accessFlags = { ");
                for (int i = 0; i < fl.size(); i++) sb.append(i > 0 ? ", " : "").append(fl.get(i) instanceof Number n ? "0x" + Integer.toHexString(n.intValue()) : String.valueOf(fl.get(i)));
                annRow(rows, sb.append(" }").toString());
            }
            if (ann.get("names") instanceof List<?> nl) {
                StringBuilder sb = new StringBuilder("        names = { ");
                for (int i = 0; i < nl.size(); i++) sb.append(i > 0 ? ", " : "").append(nl.get(i) == null ? "null" : "\"" + String.valueOf(nl.get(i)).replace("\\", "\\\\").replace("\"", "\\\"") + "\"");
                annRow(rows, sb.append(" }").toString());
            }
            if (!Boolean.TRUE.equals(ann.get("countMatchesPrototype")))
                annWarnRow(rows, "        # " + ann.get("mismatch"));
            if ("malformed".equals(ann.get("status"))) annWarnRow(rows, "        # malformed: " + ann.get("problems"));
            annRow(rows, "    .end annotation");
        }
        Map<?, ?> dbg = (Map<?, ?>) meta.get("debugInfo");
        if (dbg != null && "present".equals(dbg.get("status")) && dbg.get("names") instanceof List<?> dn && meta.get("parameters") instanceof List<?> ps) {
            for (int i = 0; i < dn.size(); i++) {
                if (dn.get(i) == null) continue;
                String reg = i < ps.size() && ps.get(i) instanceof Map<?, ?> pm && pm.get("register") != null ? "p" + pm.get("register") : "p?";
                annRow(rows, "    .param " + reg + ", \"" + String.valueOf(dn.get(i)).replace("\"", "\\\"") + "\"   # debug_info parameter name");
            }
        }
    }

    /** A switch payload's case line: a destination label (decoded like a branch), no bytes of its own. */
    private static void caseRow(List<Object> rows, String text) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("kind", "case");
        r.put("offset", "");
        r.put("bytes", "");
        r.put("text", text);
        rows.add(r);
    }

    private static void annRow(List<Object> rows, String text) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("kind", "annotation");
        r.put("offset", "");
        r.put("bytes", "");
        r.put("text", text);
        rows.add(r);
    }

    /** A retained-metadata row that warns (a count mismatch, a malformed annotation): its own kind, so
     *  the view gives it a warning colour instead of the faint comment ink. */
    private static void annWarnRow(List<Object> rows, String text) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("kind", "annotation-warn");
        r.put("offset", "");
        r.put("bytes", "");
        r.put("text", text);
        rows.add(r);
    }

    private static void commentRow(List<Object> rows, String text) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("kind", "comment");
        r.put("offset", "");
        r.put("bytes", "");
        r.put("text", text);
        rows.add(r);
    }

    /** Replace JEB's raw pool references (method@12, string@75, type@13, field@7) in a
     *  formatted instruction with live signatures/values. */
    private static final java.util.regex.Pattern POOL_REF =
            java.util.regex.Pattern.compile("(method|field|string|type|proto)@(\\d+)");

    private static String resolvePoolRefs(IDexUnit dex, String text) { return resolvePoolRefs(dex, text, false); }

    /** original: the dex's own names (the evidence packet — a display rename must not enter the
     *  evidence); else the analyst's current names (the smali view). */
    private static String resolvePoolRefs(IDexUnit dex, String text, boolean original) {
        boolean effective = !original;
        java.util.regex.Matcher mt = POOL_REF.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (mt.find()) {
            String kind = mt.group(1);
            int idx = Integer.parseInt(mt.group(2));
            String rep = mt.group(0);
            try {
                switch (kind) {
                    case "method" -> {
                        IDexMethod x = dex.getMethods().get(idx);
                        if (x != null) rep = x.getSignature(effective);
                    }
                    case "field" -> {
                        IDexField x = dex.getFields().get(idx);
                        if (x != null) rep = x.getSignature(effective);
                    }
                    case "string" -> {
                        IDexString x = dex.getString(idx);
                        if (x != null) rep = "\"" + String.valueOf(x.getValue(true)).replace("\"", "\\\"") + "\"";
                    }
                    case "type" -> {
                        if (dex.getType(idx) != null) rep = dex.getType(idx).getSignature(effective);
                    }
                    default -> {}
                }
            } catch (Exception ignored) {}
            mt.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(rep));
        }
        mt.appendTail(sb);
        return sb.toString();
    }

    /** Access-flags → smali modifier list ("public static final "). */
    private static String flags(int a, boolean isClass) {
        StringBuilder sb = new StringBuilder();
        if ((a & 0x0001) != 0) sb.append("public ");
        if ((a & 0x0002) != 0) sb.append("private ");
        if ((a & 0x0004) != 0) sb.append("protected ");
        if ((a & 0x0008) != 0) sb.append("static ");
        if ((a & 0x0010) != 0) sb.append("final ");
        if ((a & 0x0020) != 0) sb.append(isClass ? "super " : "synchronized ");
        if ((a & 0x0040) != 0) sb.append(isClass ? "" : "bridge/volatile ");
        if ((a & 0x0080) != 0) sb.append(isClass ? "" : "varargs/transient ");
        if ((a & 0x0100) != 0) sb.append("native ");
        if ((a & 0x0200) != 0 && isClass) sb.append("interface ");
        if ((a & 0x0400) != 0) sb.append("abstract ");
        if ((a & 0x1000) != 0) sb.append("synthetic ");
        if ((a & 0x2000) != 0 && isClass) sb.append("annotation ");
        if ((a & 0x4000) != 0 && isClass) sb.append("enum ");
        return sb.toString();
    }

    private static Map<String, Object> dirRow(List<Object> rows, String text) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("kind", "directive");
        r.put("offset", "");
        r.put("bytes", "");
        r.put("text", text);
        rows.add(r);
        return r;
    }

    private static void labelRow(List<Object> rows, String text) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("kind", "label");
        r.put("offset", "");
        r.put("bytes", "");
        r.put("text", text);
        rows.add(r);
    }

    private static void blankRow(List<Object> rows) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("kind", "blank");
        r.put("offset", "");
        r.put("bytes", "");
        r.put("text", "");
        rows.add(r);
    }

    private static void insnRow(List<Object> rows, long offset, byte[] code, String text) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("kind", "insn");
        r.put("offset", String.format("%04x", offset));
        StringBuilder hex = new StringBuilder();
        for (byte b : code) {
            if (hex.length() > 0) hex.append(' ');
            hex.append(String.format("%02x", b));
        }
        r.put("bytes", hex.toString());
        r.put("text", text);
        rows.add(r);
    }

    // ------------------------------------------------------------ evidence packet

    public static final String PACKET_SCHEMA = "evidence-packet-v1";

    /**
     * {@inheritDoc}
     *
     * Composed from what the engine already answers — the method's smali (ground truth, with
     * stable `+offset` references), its java decompile (scope method), the pool references the
     * selected instructions name, and the symbol's original signature — plus JEB's own CFG
     * ({@code IDexCodeItem.getControlFlowGraph()}: blocks with regular and irregular, i.e.
     * exceptional, edges). A branch selection is CFG reachability from the entry block, which is a
     * fact about the graph, not a claim about runtime: the packet says so, and names the caller's
     * choice of branch and the constructors of referenced classes as context it does not hold.
     * The `student` block is a fixed consumer shape (callback-cfg.json: id, lines, successors,
     * exceptional, scope_note), so a packet consumer takes a real method unchanged.
     */
    @Override
    public Map<String, Object> evidencePacket(String symbol, Map<String, Object> opts) throws OpException {
        requireReady();
        Map<String, Object> o = opts == null ? Map.of() : opts;
        String question = o.get("question") == null ? null : String.valueOf(o.get("question")).trim();
        String entry = o.get("entry") == null ? null : String.valueOf(o.get("entry")).trim();
        int maxInsns = o.get("maxInstructions") instanceof Number n && n.intValue() > 0 ? n.intValue()
                : o.get("maxInstructions") instanceof String s && s.trim().matches("[1-9]\\d{0,5}") ? Integer.parseInt(s.trim()) : 400;   // the facade's schema types it as a string too
        boolean wantJava = !(o.get("java") instanceof Boolean b) || b;
        if (o.get("java") instanceof String js) wantJava = !"false".equalsIgnoreCase(js.trim());
        boolean wantStudent = !(o.get("student") instanceof Boolean sb0) || sb0;   // the student block: every line again — an agent's call leaves it out
        if (o.get("student") instanceof String ss0) wantStudent = !"false".equalsIgnoreCase(ss0.trim());
        synchronized (this) {
            Resolved r = lookup(symbol);
            if (r.kind() != Kind.METHOD)
                throw OpException.syntax("evidence.packet takes a METHOD (a class is not a bounded question): " + symbol
                        + " is a " + r.kind().name().toLowerCase(), "symbol=" + symbol);
            IDexMethod m = findAnyMethod(r.addr());
            if (m == null) throw OpException.notFound("method not found: " + symbol, "target=" + id);
            IDexClass cls = findClass(r.addr());
            IDexUnit dex = cls == null ? null : owningDex(cls.getAddress());
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("schema", PACKET_SCHEMA);
            Map<String, Object> tgt = new LinkedHashMap<>();
            tgt.put("id", id); tgt.put("name", name); tgt.put("classes", classCount);
            out.put("target", tgt);
            if (question != null && !question.isEmpty()) out.put("question", question);
            /* identity: the display address the caller used and the ORIGINAL descriptor — the one
               the dex carries, which no rename changes; the packet is keyed on the latter */
            Map<String, Object> method = new LinkedHashMap<>();
            method.put("addr", m.getAddress());
            method.put("sig", m.getSignature(true));
            method.put("origSig", m.getSignature(false));
            method.put("name", m.getName(true));
            if (m.isRenamed()) { method.put("renamed", true); method.put("origName", m.getName(false)); }
            if (cls != null) {
                Map<String, Object> owner = new LinkedHashMap<>();
                owner.put("addr", cls.getAddress()); owner.put("fqcn", dottedName(cls)); owner.put("origSig", cls.getSignature(false));
                if (cls.isRenamed()) { owner.put("renamed", true); owner.put("origName", cls.getName(false)); }
                method.put("owner", owner);
            }
            IDexMethodData md = m.getData();
            IDexCodeItem ci = md == null ? null : md.getCodeItem();
            if (ci != null) {   // the exact DEX member: which dex unit, where its instructions start (handoff §A)
                Map<String, Object> code = new LinkedHashMap<>();
                code.put("dex", dex == null ? null : dex.getName());
                try { code.put("instructionsOffset", ci.getInstructionsOffset()); } catch (Throwable ignored) {}
                method.put("codeItem", code);
            }
            out.put("method", method);
            out.put("original_method", m.getSignature(false));

            List<? extends IDalvikInstruction> insns = ci == null ? null : ci.getInstructions();
            if (ci == null || insns == null) {
                out.put("mode", "no-code");
                out.put("note", md != null && md.isNative() ? "a native method: no Dalvik body — the evidence is in the library" : "an abstract or bodiless method");
                out.put("missing", List.of("the method has no bytecode body in this dex"));
                return out;
            }
            com.pnfsoftware.jeb.core.units.code.android.controlflow.CFG<IDalvikInstruction> cfg = null;
            try { cfg = ci.getControlFlowGraph(); } catch (Throwable ignored) {}

            /* the blocks, in address order, with stable ids by first offset */
            List<Map<String, Object>> blocks = new ArrayList<>();
            Map<Long, String> blockIdByAddr = new LinkedHashMap<>();
            Map<String, java.util.Set<String>> succ = new LinkedHashMap<>(), exc = new LinkedHashMap<>();
            Map<String, List<IDalvikInstruction>> insnsOf = new LinkedHashMap<>();
            String entryBlockId = null;
            if (cfg != null) {
                for (com.pnfsoftware.jeb.core.units.code.android.controlflow.BasicBlock<IDalvikInstruction> bb : cfg.getBlocksView())
                    blockIdByAddr.put(bb.getFirstAddress(), String.format("b%04x", bb.getFirstAddress()));
                for (com.pnfsoftware.jeb.core.units.code.android.controlflow.BasicBlock<IDalvikInstruction> bb : cfg.getBlocksView()) {
                    String bid = blockIdByAddr.get(bb.getFirstAddress());
                    java.util.Set<String> s = new java.util.LinkedHashSet<>(), x = new java.util.LinkedHashSet<>();
                    for (com.pnfsoftware.jeb.core.units.code.android.controlflow.BasicBlock<IDalvikInstruction> t : bb.getOutputs()) { String tid = blockIdByAddr.get(t.getFirstAddress()); if (tid != null) s.add(tid); }
                    for (com.pnfsoftware.jeb.core.units.code.android.controlflow.BasicBlock<IDalvikInstruction> t : bb.getIrregularOutputs()) { String tid = blockIdByAddr.get(t.getFirstAddress()); if (tid != null) x.add(tid); }
                    succ.put(bid, s); exc.put(bid, x);
                    insnsOf.put(bid, bb.getInstructions());
                }
                com.pnfsoftware.jeb.core.units.code.android.controlflow.BasicBlock<IDalvikInstruction> eb = cfg.getEntryBlock();
                if (eb != null) entryBlockId = blockIdByAddr.get(eb.getFirstAddress());
            }

            /* the selection: an entry (block id "b0040", an offset "0040" / "+40h" / 64) → the blocks the
               CFG reaches from it along regular and exceptional edges; no entry → the whole method */
            String mode;
            java.util.Set<String> selected = new java.util.LinkedHashSet<>();
            List<String> missing = new ArrayList<>();
            String selEntry = null;
            if (entry != null && !entry.isEmpty()) {
                if (cfg == null) {
                    mode = "unsupported-slice";
                    out.put("note", "JEB produced no control-flow graph for this method: a branch selection cannot be made honestly, the whole method is given instead");
                } else {
                    Long off = parseOffset(entry);
                    if (entry.matches("b[0-9a-fA-F]{4,5}") && succ.containsKey(entry.toLowerCase())) selEntry = entry.toLowerCase();
                    else if (off != null) {
                        for (Map.Entry<Long, String> e : blockIdByAddr.entrySet()) {
                            List<IDalvikInstruction> bi = insnsOf.get(e.getValue());
                            if (bi == null || bi.isEmpty()) continue;
                            long first = bi.get(0).getOffset(), last = bi.get(bi.size() - 1).getOffset();
                            if (off >= first && off <= last) { selEntry = e.getValue(); break; }
                        }
                    }
                    if (selEntry == null)
                        throw OpException.notFound("entry " + entry + " is not an instruction offset or a block of " + m.getSignature(true), "blocks=" + blockIdByAddr.values());
                    java.util.ArrayDeque<String> todo = new java.util.ArrayDeque<>(List.of(selEntry));
                    while (!todo.isEmpty()) {
                        String b = todo.poll();
                        if (!selected.add(b)) continue;
                        for (String s : succ.getOrDefault(b, java.util.Set.of())) if (!selected.contains(s)) todo.add(s);
                        for (String x : exc.getOrDefault(b, java.util.Set.of())) if (!selected.contains(x)) todo.add(x);
                    }
                    mode = "branch";
                    missing.add("the caller's choice of this branch (what made control reach " + selEntry + ") is not in this packet — selection is CFG reachability from the entry, not a claim about runtime");
                }
            } else mode = "method";
            if (!"branch".equals(mode)) selected.addAll(succ.keySet());
            out.put("mode", mode);
            if (selEntry != null) out.put("entry", selEntry);
            if (entryBlockId != null) out.put("entryBlock", entryBlockId);

            /* instructions: stable references method+offset, the formatted text with pool references
               resolved, grouped by block; the cut is by whole blocks and declared */
            List<Object> insnRows = new ArrayList<>();
            int shown = 0, cut = 0;
            java.util.Set<String> calls = new java.util.LinkedHashSet<>(), fields = new java.util.LinkedHashSet<>(), types = new java.util.LinkedHashSet<>(), strings = new java.util.LinkedHashSet<>();
            List<Object> blockRows = new ArrayList<>();
            List<Object> studentBlocks = new ArrayList<>();
            /* the evidence is written in the dex's ORIGINAL names: a callee, field or type an analyst
               renamed appears under its own descriptor here and under its display name in provenance —
               a rename must not change the evidence ("display names do not change
               evidence" check, and a blind replay need it) */
            java.util.function.Function<IDalvikInstruction, String> fmt = insn -> {
                String text = insn.format(null).trim();
                return dex == null ? text : resolvePoolRefs(dex, text, true);
            };
            if (cfg == null) {
                /* no CFG: one pseudo-block holding the method, so the shape stays the same */
                String bid = "b0000";
                selected.clear(); selected.add(bid);
                succ.put(bid, new java.util.LinkedHashSet<>()); exc.put(bid, new java.util.LinkedHashSet<>());
                insnsOf.put(bid, new ArrayList<>(insns));
            }
            for (Map.Entry<String, List<IDalvikInstruction>> e : insnsOf.entrySet()) {
                String bid = e.getKey();
                boolean sel = selected.contains(bid);
                Map<String, Object> brow = new LinkedHashMap<>();
                brow.put("id", bid);
                brow.put("selected", sel);
                brow.put("instructions", e.getValue().size());
                brow.put("successors", new ArrayList<>(succ.getOrDefault(bid, java.util.Set.of())));
                brow.put("exceptional", new ArrayList<>(exc.getOrDefault(bid, java.util.Set.of())));
                blockRows.add(brow);
                /* the student block carries EVERY block with its lines — the whole method is the consumer's
                   own selection over the whole graph (the fixture callback-cfg.json has the selector and
                   the irrelevant branch too); the packet's own listing and references are the selection */
                List<String> lines = new ArrayList<>();
                for (IDalvikInstruction insn : e.getValue()) {
                    String text = fmt.apply(insn);
                    lines.add(String.format("%04x", insn.getOffset()) + " " + text);
                    if (!sel) continue;
                    harvest(text, calls, fields, types, strings);
                    if (shown >= maxInsns) { cut++; continue; }
                    shown++;
                    Map<String, Object> row = new LinkedHashMap<>();
                    /* keyed on the ORIGINAL descriptor: a display rename moves the address, never the ref */
                    row.put("ref", m.getSignature(false) + "+" + Long.toHexString(insn.getOffset()) + "h");
                    row.put("offset", String.format("%04x", insn.getOffset()));
                    row.put("block", bid);
                    row.put("text", text);
                    insnRows.add(row);
                }
                Map<String, Object> sb = new LinkedHashMap<>();
                sb.put("id", bid); sb.put("lines", lines);
                sb.put("successors", new ArrayList<>(succ.getOrDefault(bid, java.util.Set.of())));
                sb.put("exceptional", new ArrayList<>(exc.getOrDefault(bid, java.util.Set.of())));
                studentBlocks.add(sb);
            }
            out.put("cfg", Map.of("available", cfg != null, "blocks", blockRows));
            out.put("selected", new ArrayList<>(selected));
            List<String> omitted = new ArrayList<>();
            for (String b : succ.keySet()) if (!selected.contains(b)) omitted.add(b);
            out.put("omitted", omitted);
            out.put("instructions", insnRows);
            if (cut > 0) out.put("truncated", Map.of("instructions", cut, "shown", shown, "note", "instructions past maxInstructions=" + maxInsns + " are not listed; their blocks are selected and their references counted"));
            Map<String, Object> refs = new LinkedHashMap<>();
            refs.put("calls", new ArrayList<>(calls)); refs.put("fields", new ArrayList<>(fields)); refs.put("types", new ArrayList<>(types)); refs.put("strings", new ArrayList<>(strings));
            out.put("references", refs);

            /* annotation provenance: every referenced symbol whose display name is not the dex's */
            List<Object> prov = new ArrayList<>();
            for (String sig : calls) {
                IDexMethod cm = findAnyMethod(sig);
                if (cm != null && cm.isRenamed()) prov.add(Map.of("addr", cm.getAddress(), "name", cm.getName(true), "origName", cm.getName(false), "origSig", cm.getSignature(false), "kind", "method"));
            }
            for (String sig : fields) {
                IDexField cf = findAnyField(sig);
                if (cf != null && cf.isRenamed()) prov.add(Map.of("addr", cf.getAddress(), "name", cf.getName(true), "origName", cf.getName(false), "origSig", cf.getSignature(false), "kind", "field"));
            }
            for (String sig : types) {
                IDexClass cc = findAnyClass(sig);
                if (cc != null && cc.isRenamed()) prov.add(Map.of("addr", cc.getAddress(), "name", displayName(cc), "origName", cc.getName(false), "origSig", cc.getSignature(false), "kind", "class"));
            }
            if (m.isRenamed()) prov.add(0, Map.of("addr", m.getAddress(), "name", m.getName(true), "origName", m.getName(false), "origSig", m.getSignature(false), "kind", "method"));
            out.put("provenance", prov);
            out.put("names", "original");
            out.put("provenanceNote", prov.isEmpty() ? "the evidence is written in the dex's own names and no referenced symbol carries an analyst name"
                    : "the evidence is written in the dex's own names; " + prov.size() + " referenced symbol(s) carry an analyst's display name — hypotheses, listed in provenance");

            if (wantJava) {
                try {
                    Map<String, Object> dj = decompile(m.getAddress(), Map.<String, Object>of("scope", "method"));
                    Map<String, Object> java = new LinkedHashMap<>();
                    java.put("text", dj.get("text"));
                    java.put("lines", dj.get("lines"));
                    java.put("scope", "the complete method — java text is never cut to a branch; the selection above is on the bytecode's blocks");
                    out.put("java", java);
                } catch (OpException e) {
                    out.put("java", Map.of("unavailable", e.getMessage()));
                }
            }
            missing.add("constructors and field initialisers of the referenced classes (" + types.size() + " type(s)) are not included");
            missing.add("callers of this method are not included — xrefs {direction: to} lists them");
            out.put("missing", missing);
            /* the retained parameter metadata rides along, raw and apart: the annotation's names in
               encoded order with their count check, the debug names, never a mapping onto the arguments */
            try { out.put("methodParameters", parameterMetadata(dex, cls, m, false)); } catch (Throwable t) { out.put("methodParameters", Map.of("status", "unsupported", "why", String.valueOf(t))); }

            /* the student block's shape: a consumer takes {target_id, original_method,
               display_name, entry, scope_note, blocks[{id, lines, successors, exceptional}]} */
            Map<String, Object> student = new LinkedHashMap<>();
            student.put("fixture", false);
            student.put("target_id", id + ":" + name);
            student.put("original_method", m.getSignature(false));
            student.put("display_name", m.getName(true));
            student.put("entry", selEntry != null ? selEntry : entryBlockId != null ? entryBlockId : "b0000");
            student.put("scope_note", "The caller's choice of branch is not included. Line ids are method-relative instruction offsets (hex) of " + m.getSignature(false) + ". Lines use the dex's original names. Selection is CFG reachability, not runtime.");
            student.put("blocks", studentBlocks);
            student.put("names", "original");
            student.put("provenance", prov);   // the analyst's display names for symbols in the lines, kept apart from the evidence
            if (wantStudent) out.put("student", student);
            else out.put("studentOmitted", "the student block is left out (student: false) — ask with student: true");
            return ordered(out);
        }
    }

    /**
     * §C of the path-research handoff (2026-10-09): "show me this caller" as one bounded answer. Given a method and
     * an instruction offset (an xref row's `from` = "Lcls;->m()V+634h"), answer the instruction, its DEX neighbours,
     * the containing CFG block with the branch instructions of its immediate predecessors (the obvious controlling
     * switch/guard — SYNTACTIC context from the graph, never a dominance or data-flow claim), the callee when the
     * instruction is a call, and the decompiler's statement for the offset with the lines around it (JEB's own
     * address→coordinates mapping, so the view survives decompiler formatting changes), plus the extent and the
     * exact continuation (a `window` for code.decompile, an offset range for code.smali).
     */
    @Override
    public Map<String, Object> callSite(String methodSymbol, long offset, Map<String, Object> opts) throws OpException {
        requireReady();
        Map<String, Object> o = opts == null ? Map.of() : opts;
        int ctx = o.get("context") instanceof Number n ? Math.max(1, Math.min(60, n.intValue())) : 6;
        int lines = o.get("lines") instanceof Number n ? Math.max(1, Math.min(120, n.intValue())) : 8;
        boolean wantJava = !(o.get("java") instanceof Boolean b) || b;
        synchronized (this) {
            Resolved r = lookup(methodSymbol);
            if (r.kind() != Kind.METHOD) throw OpException.syntax("a call site lives in a METHOD: " + methodSymbol + " is a " + r.kind().name().toLowerCase(), "symbol=" + methodSymbol);
            IDexMethod m = findAnyMethod(r.addr());
            if (m == null) throw OpException.notFound("method not found: " + methodSymbol, "target=" + id);
            IDexClass cls = findClass(r.addr());
            IDexUnit dex = cls == null ? null : owningDex(cls.getAddress());
            IDexMethodData md = m.getData();
            IDexCodeItem ci = md == null ? null : md.getCodeItem();
            List<? extends IDalvikInstruction> insns = ci == null ? null : ci.getInstructions();
            if (ci == null || insns == null || insns.isEmpty())
                throw OpException.notFound("no bytecode body in " + m.getSignature(true) + " (abstract or native)", "symbol=" + methodSymbol);
            int idx = -1;
            for (int i = 0; i < insns.size(); i++) {
                IDalvikInstruction in = insns.get(i);
                if (in.getOffset() == offset) { idx = i; break; }
                if (in.getOffset() < offset && offset < in.getOffset() + in.getSize()) { idx = i; break; }   // inside a wide instruction
            }
            long last = insns.get(insns.size() - 1).getOffset();
            if (idx < 0)
                throw OpException.notFound(String.format("no instruction at +%xh in %s (the body spans +0h..+%xh)", offset, m.getSignature(true), last), "symbol=" + methodSymbol);
            IDalvikInstruction at = insns.get(idx);
            java.util.function.Function<IDalvikInstruction, String> fmt = insn -> {
                String text = insn.format(null).trim();
                return dex == null ? text : resolvePoolRefs(dex, text, true);
            };
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("schema", "call-site-v1");
            Map<String, Object> caller = new LinkedHashMap<>();
            caller.put("addr", m.getAddress()); caller.put("origSig", m.getSignature(false)); caller.put("name", m.getName(true));
            if (cls != null) caller.put("owner", dottedName(cls));
            out.put("caller", caller);
            Map<String, Object> site = new LinkedHashMap<>();
            site.put("offset", String.format("%04x", at.getOffset()));
            site.put("ref", m.getSignature(false) + "+" + Long.toHexString(at.getOffset()) + "h");
            if (at.getOffset() != offset) site.put("note", String.format("+%xh falls inside this instruction (it starts at +%xh)", offset, at.getOffset()));
            String atText = fmt.apply(at);
            site.put("instruction", atText);
            site.put("size", at.getSize());
            /* JEB separates the method operand from following registers with a comma. That
               delimiter is not part of the descriptor and must not reach exact lookup. */
            java.util.regex.Matcher cm = java.util.regex.Pattern.compile("^invoke-\\S+\\s+(?:\\{[^}]*\\},\\s*)?(L[^;]+;->[^\\s,]+)").matcher(atText);
            if (cm.find()) {
                Map<String, Object> callee = new LinkedHashMap<>();
                String ref = cm.group(1);
                callee.put("ref", ref);
                IDexMethod cmm = findAnyMethod(ref);
                if (cmm != null) { callee.put("addr", cmm.getAddress()); callee.put("origSig", cmm.getSignature(false)); callee.put("name", cmm.getName(true)); callee.put("internal", cmm.isInternal()); }
                else callee.put("external", true);
                site.put("callee", callee);
                site.put("kind", "call");
            } else site.put("kind", atText.startsWith("if-") || atText.contains("-switch") ? "branch" : atText.startsWith("return") ? "return" : "other");
            out.put("site", site);
            /* the DEX neighbours */
            int a = Math.max(0, idx - ctx), b = Math.min(insns.size() - 1, idx + ctx);
            List<Object> rows = new ArrayList<>();
            for (int i = a; i <= b; i++) {
                IDalvikInstruction in = insns.get(i);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("offset", String.format("%04x", in.getOffset())); row.put("text", fmt.apply(in));
                if (i == idx) row.put("at", true);
                rows.add(row);
            }
            Map<String, Object> dexView = new LinkedHashMap<>();
            dexView.put("rows", rows); dexView.put("before", idx - a); dexView.put("after", b - idx);
            dexView.put("omitted", Map.of("before", a, "after", insns.size() - 1 - b));
            out.put("dex", dexView);
            /* the containing block and its immediate predecessors' last instructions (the controlling branch) */
            com.pnfsoftware.jeb.core.units.code.android.controlflow.CFG<IDalvikInstruction> cfg = null;
            try { cfg = ci.getControlFlowGraph(); } catch (Throwable ignored) {}
            Map<String, Object> block = new LinkedHashMap<>();
            if (cfg != null) {
                Map<Long, String> idByAddr = new LinkedHashMap<>();
                for (com.pnfsoftware.jeb.core.units.code.android.controlflow.BasicBlock<IDalvikInstruction> bb : cfg.getBlocksView()) idByAddr.put(bb.getFirstAddress(), String.format("b%04x", bb.getFirstAddress()));
                com.pnfsoftware.jeb.core.units.code.android.controlflow.BasicBlock<IDalvikInstruction> mine = null;
                for (com.pnfsoftware.jeb.core.units.code.android.controlflow.BasicBlock<IDalvikInstruction> bb : cfg.getBlocksView()) {
                    List<IDalvikInstruction> bi = bb.getInstructions();
                    if (bi.isEmpty()) continue;
                    if (at.getOffset() >= bi.get(0).getOffset() && at.getOffset() <= bi.get(bi.size() - 1).getOffset()) { mine = bb; break; }
                }
                if (mine != null) {
                    block.put("id", idByAddr.get(mine.getFirstAddress()));
                    block.put("instructions", mine.getInstructions().size());
                    block.put("range", String.format("%04x..%04x", mine.getInstructions().get(0).getOffset(), mine.getInstructions().get(mine.getInstructions().size() - 1).getOffset()));
                    List<String> s = new ArrayList<>(), x = new ArrayList<>();
                    for (var t : mine.getOutputs()) { String tid = idByAddr.get(t.getFirstAddress()); if (tid != null) s.add(tid); }
                    for (var t : mine.getIrregularOutputs()) { String tid = idByAddr.get(t.getFirstAddress()); if (tid != null) x.add(tid); }
                    block.put("successors", s); block.put("exceptional", x);
                    List<Object> preds = new ArrayList<>();
                    for (com.pnfsoftware.jeb.core.units.code.android.controlflow.BasicBlock<IDalvikInstruction> bb : cfg.getBlocksView()) {
                        boolean regular = false, irregular = false;
                        for (var t : bb.getOutputs()) if (t == mine) regular = true;
                        for (var t : bb.getIrregularOutputs()) if (t == mine) irregular = true;
                        if (!regular && !irregular) continue;
                        List<IDalvikInstruction> bi = bb.getInstructions();
                        Map<String, Object> pr = new LinkedHashMap<>();
                        pr.put("id", idByAddr.get(bb.getFirstAddress()));
                        pr.put("edge", irregular && !regular ? "exceptional" : "regular");
                        if (!bi.isEmpty()) {
                            IDalvikInstruction lastIn = bi.get(bi.size() - 1);
                            String lt = fmt.apply(lastIn);
                            pr.put("last", lt); pr.put("lastOffset", String.format("%04x", lastIn.getOffset()));
                            pr.put("controls", lt.startsWith("if-") || lt.contains("-switch") ? (lt.contains("-switch") ? "switch" : "guard") : "fallthrough");
                        }
                        preds.add(pr);
                    }
                    block.put("predecessors", preds);
                    block.put("entry", mine == cfg.getEntryBlock());
                }
                block.put("note", "the block holding the site and the branch instructions of its IMMEDIATE predecessors — syntactic context from the CFG, not a dominance, feasibility or data-flow result; a shared tail reached from several cases is listed with every predecessor");
            } else block.put("unavailable", "JEB produced no control-flow graph for this method");
            out.put("block", block);
            /* the decompiler's statement for the offset, with its surroundings — JEB's address→coordinates mapping */
            Map<String, Object> java = new LinkedHashMap<>();
            if (wantJava) {
                try {
                    MethodUnit mu = methodUnit(r.addr());
                    IPropertyManager pm = mu.ju().getPropertyManager();
                    Map<String, Object> saved = setCleanRender(pm);
                    try {
                        JavaDocument doc = new JavaDocument(mu.ju());
                        String text = doc.format();
                        String[] all = text.split("\\n", -1);
                        ITextDocumentPart part = doc.getDocumentPart(doc.getFirstAnchor(), all.length + 5);
                        String address = m.getAddress() + "+" + Long.toHexString(at.getOffset()) + "h";
                        com.pnfsoftware.jeb.core.output.text.ICoordinates co = doc.addressToCoordinates(address, com.pnfsoftware.jeb.core.output.CoordinatesConversionPrecision.BEST);
                        Integer line = null;
                        if (co != null && part != null) {
                            for (com.pnfsoftware.jeb.core.output.text.IAnchor an : part.getAnchors())
                                if (an.getIdentifier() == co.getAnchorId()) { line = an.getLineIndex() + co.getLineDelta(); break; }
                        }
                        if (line == null) {
                            java.put("unavailable", "the decompiler has no statement mapped to +" + Long.toHexString(at.getOffset()) + "h (optimised away, or a synthetic instruction)");
                        } else {
                            int from = Math.max(0, line - lines), to = Math.min(all.length - 1, line + lines);
                            StringBuilder sb = new StringBuilder();
                            for (int i = from; i <= to; i++) sb.append(i == line ? ">> " : "   ").append(all[i]).append('\n');
                            java.put("line", line + 1); java.put("from", from + 1); java.put("to", to + 1); java.put("totalLines", all.length);
                            java.put("text", sb.toString());
                            java.put("mappedBy", "JEB addressToCoordinates(BEST) on the class's decompiled text — the statement the decompiler attributes to this offset; the line numbers are the CLASS document's (code.decompile scope=class)");
                            java.put("marker", ">> marks the mapped line");
                        }
                    } finally { restoreRender(pm, saved); }
                } catch (OpException e) {
                    java.put("unavailable", e.getMessage());
                } catch (Throwable t) {
                    java.put("unavailable", "decompiler mapping failed: " + t.getClass().getSimpleName());
                }
            } else java.put("omitted", "java: false");
            out.put("java", java);
            Map<String, Object> extent = new LinkedHashMap<>();
            extent.put("instructions", insns.size()); extent.put("lastOffset", String.format("%04x", last));
            if (java.get("totalLines") != null) extent.put("classLines", java.get("totalLines"));
            out.put("extent", extent);
            Map<String, Object> more = new LinkedHashMap<>();
            more.put("dex", "code.smali {symbol: \"" + m.getAddress() + "\"} for the whole listing; this view: context (instructions each side, now " + ctx + ")");
            if (java.get("line") != null) more.put("java", "code.decompile {symbol: \"" + (cls == null ? m.getAddress() : cls.getAddress()) + "\", scope: \"class\", window: \"" + Math.max(1, (Integer) java.get("line") - 40) + "," + ((Integer) java.get("line") + 40) + "\"} for a wider slice; lines (each side, now " + lines + ")");
            more.put("branch", "code {view: packet, entry: \"" + (block.get("id") == null ? "" : block.get("id")) + "\"} for the blocks reachable from this one (the CFG selection)");
            out.put("more", more);
            out.put("note", "one call site, bounded: the instruction, its neighbours, the containing block and what branches into it, the callee, the decompiler's statement — enough to see the ORDER of checks and calls around this site without the whole class; nothing here proves reachability or a feasible path");
            return out;
        }
    }

    /** The packet with its small, essential parts first — identity, selection, omissions, references,
     *  provenance — and the long listings last: an agent reads it through the loop's result cap
     *  (6,000 chars, a cut mid-JSON), and what it must not lose is at the front. */
    private static Map<String, Object> ordered(Map<String, Object> out) {
        Map<String, Object> o = new LinkedHashMap<>();
        for (String k : List.of("schema", "target", "question", "method", "original_method", "mode", "entry", "entryBlock", "names", "selected", "omitted", "missing",
                "truncated", "references", "provenance", "provenanceNote", "methodParameters", "note", "studentOmitted", "instructions", "java", "cfg", "student"))
            if (out.containsKey(k)) o.put(k, out.get(k));
        for (Map.Entry<String, Object> e : out.entrySet()) o.putIfAbsent(e.getKey(), e.getValue());
        return o;
    }

    /** "0040", "40h", "+40h", "0x40", "1a" → an offset (always HEX — the packet's own offsets and
     *  block ids are hex; a decimal reading of "1000" would pick the wrong block silently); null when
     *  not one. */
    static Long parseOffset(String s) {
        if (s == null) return null;
        String t = s.trim().toLowerCase();
        if (t.startsWith("+")) t = t.substring(1);
        try {
            if (t.endsWith("h")) return Long.parseLong(t.substring(0, t.length() - 1), 16);
            if (t.startsWith("0x")) return Long.parseLong(t.substring(2), 16);
            if (t.matches("[0-9a-f]{1,8}")) return Long.parseLong(t, 16);
        } catch (NumberFormatException e) { return null; }
        return null;
    }

    private static final java.util.regex.Pattern HARVEST_STRING = java.util.regex.Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final java.util.regex.Pattern HARVEST_CALL = java.util.regex.Pattern.compile("L[\\w/$-]+;->[\\w<>$-]+\\([^)]*\\)[^\\s,]*");
    private static final java.util.regex.Pattern HARVEST_FIELD = java.util.regex.Pattern.compile("L[\\w/$-]+;->[\\w$-]+:[^\\s,]+");
    private static final java.util.regex.Pattern HARVEST_TYPE = java.util.regex.Pattern.compile("L[\\w/$-]+;");

    /** The symbols a formatted, pool-resolved instruction names: calls (method signatures), fields,
     *  types (class descriptors) and string literals — the literals are taken out first, so a
     *  descriptor quoted inside a string is a string, not a call; names may carry '-' (-$$Nest$m…). */
    private static void harvest(String text, java.util.Set<String> calls, java.util.Set<String> fields, java.util.Set<String> types, java.util.Set<String> strings) {
        java.util.regex.Matcher ss = HARVEST_STRING.matcher(text);
        while (ss.find()) strings.add(ss.group(1));
        String code = ss.replaceAll("\"\"");
        java.util.regex.Matcher ms = HARVEST_CALL.matcher(code);
        while (ms.find()) calls.add(ms.group());
        java.util.regex.Matcher fs = HARVEST_FIELD.matcher(code);
        while (fs.find()) fields.add(fs.group());
        java.util.regex.Matcher ts = HARVEST_TYPE.matcher(code);
        while (ts.find()) types.add(ts.group());
    }

    // ------------------------------------------------------------ relations (xref.to/from, callees/callers, impls)

    /** References *to* the class/method/field at {@code symbol}, across all DEX units
     *  (ground truth: JEB's reference manager, populated by the call-graph build at open). */
    @Override
    public List<Object> xrefsTo(String symbol) throws OpException {
        requireReady();
        synchronized (this) {
            Resolved r = lookup(symbol);
            String addr = r.addr();
            List<Object> refs = new ArrayList<>();
            boolean resolved = false;
            for (IDexUnit dex : dexUnits) {
                DexPoolType pool;
                int index;
                switch (r.kind()) {
                    case CLASS -> {
                        IDexClass c = dex.getClass(addr);
                        if (c == null) {
                            /* an external class: references to its TYPE (new-instance, check-cast, const-class,
                               field and signature types) — its methods are separate items the app calls */
                            com.pnfsoftware.jeb.core.units.code.android.dex.IDexType t = null;
                            try { t = dex.getType(addr); } catch (Exception ignored) {}
                            if (t == null) continue;
                            resolved = true;
                            pool = DexPoolType.TYPE;
                            index = t.getIndex();
                            break;
                        }
                        resolved = true;
                        pool = DexPoolType.TYPE;
                        index = c.getClassTypeIndex();
                    }
                    case METHOD -> {
                        IDexMethod m = dex.getMethod(addr);
                        if (m == null) continue;
                        resolved = true;
                        pool = DexPoolType.METHOD;
                        index = m.getIndex();
                    }
                    case FIELD -> {
                        IDexField f = dex.getField(addr);
                        if (f == null) continue;
                        resolved = true;
                        pool = DexPoolType.FIELD;
                        index = f.getIndex();
                    }
                    default -> { continue; }
                }
                IDexReferenceManager rm = dex.getReferenceManager();
                java.util.Collection<IDexAddress> got = rm.getReferences(pool, index);
                if (got == null) continue;
                for (IDexAddress a : got) refs.add(xrefRow(a.getInternalAddress(),
                        String.valueOf(a.getReferenceType())));
            }
            if (!resolved)
                throw OpException.notFound(r.kind().name().toLowerCase() + " not found: " + addr,
                        "target=" + id);
            return refs;
        }
    }

    /** One xref row: the referencing site (internal address "Lx;->m()V+0x1a") + display fields. */
    private Map<String, Object> xrefRow(String internalAddress, String type) {
        Map<String, Object> x = new LinkedHashMap<>();
        x.put("from", internalAddress);
        x.put("type", type);
        String site = internalAddress;
        int plus = site.indexOf('+');
        if (plus >= 0) site = site.substring(0, plus);
        if (site.contains("->")) {
            x.put("method", site);
            IDexClass owner = findClass(site);
            x.put("owner", owner == null ? packageOf(site) : dottedName(owner));
            x.put("name", memberSimpleName(site));
        }
        return x;
    }

    /** Referenced symbols (xref.from): forward edges of a method (or every method of a class,
     *  capped) over the call graph captured at open. */
    @Override
    public List<Object> xrefsFrom(String symbol) throws OpException {
        requireReady();
        synchronized (this) {
            Resolved r = lookup(symbol);
            if (r.kind() == Kind.FIELD)
                throw OpException.unsupported("xref.from on a field", "fields have no outgoing refs");
            List<Object> out = new ArrayList<>();
            /* no cap here: a cut that the caller did not ask for is a lie about the call graph
               (the 500 rows used to stop silently); the op applies the caller's limit and says so */
            if (r.kind() == Kind.METHOD) {
                collectCallees(r.addr(), out, Integer.MAX_VALUE);
            } else {
                IDexClass c = findAnyClass(r.addr());
                if (c == null) throw OpException.notFound("class not found: " + r.addr(), "target=" + id);
                for (IDexMethod m : c.getMethods()) collectCallees(m.getAddress(), out, Integer.MAX_VALUE);
            }
            return out;
        }
    }

    /** Append forward-edge rows for one method addr to {@code out} (internal callees only). */
    private void collectCallees(String methodAddr, List<Object> out, int cap) {
        IDexMethod m = findAnyMethod(methodAddr);
        if (m == null) return;
        for (Map.Entry<IDexUnit, CallGraph> e : callGraphs.entrySet()) {
            Integer vid = e.getValue().indexToVid.get(m.getIndex());
            if (vid == null) continue;
            int[] fw = e.getValue().callees.get(vid);
            if (fw == null) return;
            for (int c : fw) {
                if (out.size() >= cap) return;
                IDexMethod cm = e.getValue().vidToMethod.get(c);
                if (cm == null) continue;                     // external (framework) callee
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("addr", cm.getAddress());
                row.put("name", cm.getName(true));
                row.put("owner", ownerDotted(cm.getAddress()));
                row.put("kind", "call");
                out.add(row);
            }
            return;
        }
    }

    /** Forward 1-hop callees (hub-aware flags arrive with the full graph in M3). */
    @Override
    public List<Object> callees(String symbol) throws OpException {
        return xrefsFrom(symbol);
    }

    /** Backward 1-hop callers: xrefsTo refs collapsed to one row per caller method. */
    @Override
    public List<Object> callers(String symbol) throws OpException {
        requireReady();
        List<Object> refs = xrefsTo(symbol);
        Map<String, Object> byMethod = new LinkedHashMap<>();
        for (Object o : refs) {
            @SuppressWarnings("unchecked")
            Map<String, Object> x = (Map<String, Object>) o;
            Object m = x.get("method");
            /* JEB types a method reference INVOKE (ReferenceType), never CALL —
               the CALL-only filter returned an empty caller list for EVERY
               method (found 2026-09-09 by a quick reversing mission whose
               brief asks for the direct callers: "Callers list came back
               empty" against 960 xref.to rows) */
            String type = String.valueOf(x.get("type")).toUpperCase();
            if (m == null || !(type.contains("CALL") || type.contains("INVOKE"))) continue;
            if (byMethod.containsKey(m)) {
                /* several call sites in one caller: every site address, the first stays `from` */
                @SuppressWarnings("unchecked") List<Object> sites = (List<Object>) ((Map<String, Object>) byMethod.get(m)).get("sites");
                if (sites != null && x.get("from") != null) sites.add(x.get("from"));
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("addr", m);
            row.put("name", x.get("name"));
            row.put("owner", x.get("owner"));
            row.put("kind", "call");
            /* the call SITE (Lcls;->m(...)+NNh): a comment on it lands on that statement — the annotate-call
               mission read smali to find the offset when this was missing (m10, 2026-09-20) */
            if (x.get("from") != null) { row.put("from", x.get("from")); List<Object> sites = new ArrayList<>(); sites.add(x.get("from")); row.put("sites", sites); }
            row.put("lowConfidence", false);
            byMethod.put(String.valueOf(m), row);
        }
        return new ArrayList<>(byMethod.values());
    }

    /** Implementations/overrides of an interface/abstract method (subtype index + signature match). */
    @Override
    public List<Object> implementations(String symbol) throws OpException {
        requireReady();
        synchronized (this) {
            Resolved r = lookup(symbol);
            if (r.kind() != Kind.METHOD)
                throw OpException.unsupported("impls on a " + r.kind().name().toLowerCase(),
                        "methods only");
            String methodAddr = r.addr();
            int arrow = methodAddr.indexOf("->");
            IDexMethod base = findAnyMethod(methodAddr);
            IDexClass owner = findClass(methodAddr);
            if (base == null || owner == null || arrow < 0)
                throw OpException.notFound("method not found: " + symbol, "target=" + id);
            String classSig = methodAddr.substring(0, arrow);
            String sigPart = methodAddr.substring(arrow + 2);   // "name(params)ret" — overrides share it
            List<Object> impls = new ArrayList<>();
            final int cap = 500;
            for (IDexClass sub : transitiveSubtypes(classSig)) {
                for (IDexMethod im : sub.getMethods()) {
                    String iaddr = im.getAddress();
                    int a = iaddr.indexOf("->");
                    if (a < 0 || im.getData() == null) continue;   // a real, bodied override only
                    if (!iaddr.substring(a + 2).equals(sigPart)) continue;
                    Map<String, Object> x = new LinkedHashMap<>();
                    x.put("addr", iaddr);
                    x.put("name", sub.getName(true) + "." + im.getName(true));
                    x.put("owner", dottedName(sub));
                    x.put("kind", "override");
                    x.put("sig", im.getSignature(true));
                    impls.add(x);
                    if (impls.size() >= cap) return impls;
                }
            }
            impls.sort((a, b) -> {
                @SuppressWarnings("unchecked")
                Map<String, Object> ma = (Map<String, Object>) a;
                @SuppressWarnings("unchecked")
                Map<String, Object> mb = (Map<String, Object>) b;
                return String.valueOf(ma.get("owner")).compareToIgnoreCase(String.valueOf(mb.get("owner")));
            });
            return impls;
        }
    }

    private List<IDexClass> transitiveSubtypes(String typeSig) {
        Map<String, List<IDexClass>> idx = subtypeIndex();
        List<IDexClass> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        java.util.Deque<String> q = new java.util.ArrayDeque<>();
        q.add(typeSig);
        while (!q.isEmpty()) {
            List<IDexClass> subs = idx.get(q.poll());
            if (subs == null) continue;
            for (IDexClass sub : subs) {
                String sig = sub.getAddress();
                if (seen.add(sig)) {
                    out.add(sub);
                    q.add(sig);
                }
            }
        }
        return out;
    }

    /** Lazy reverse hierarchy: type signature → classes that DIRECTLY extend/implement it.
     *  Keyed under BOTH original and renamed supertype/interface signatures (addrs bake in
     *  renames, so both lookup styles must resolve). */
    private Map<String, List<IDexClass>> subtypeIndex() {
        Map<String, List<IDexClass>> idx = subtypeIndex;
        if (idx != null) return idx;
        idx = new java.util.HashMap<>();
        for (IDexUnit dex : dexUnits) {
            for (IDexClass c : dex.getClasses()) {
                java.util.Set<String> keys = new java.util.LinkedHashSet<>();
                String sup0 = c.getSupertypeSignature(false), sup1 = c.getSupertypeSignature(true);
                if (sup0 != null) keys.add(sup0);
                if (sup1 != null) keys.add(sup1);
                for (boolean renamed : new boolean[] { false, true }) {
                    String[] ifs = c.getInterfaceSignatures(renamed);
                    if (ifs != null) for (String s : ifs) if (s != null) keys.add(s);
                }
                for (String k : keys) idx.computeIfAbsent(k, kk -> new ArrayList<>()).add(c);
            }
        }
        subtypeIndex = idx;
        return idx;
    }

    // ------------------------------------------------------------ strings & search

    /** A DEX string used as a code immediate (const-string): pool index + value. */
    private record CodeString(int index, String value) {}

    /** Lazily collect every DEX string used as a code literal (list position IS the pool index). */
    private List<CodeString> codeStrings() {
        List<CodeString> cs = codeStrings;
        if (cs != null) return cs;
        List<CodeString> out = new ArrayList<>();
        for (IDexUnit dex : dexUnits) {
            List<? extends IDexString> all = dex.getStrings();
            for (int i = 0; i < all.size(); i++) {
                IDexString s = all.get(i);
                if (Boolean.TRUE.equals(s.getHintUsedAsImmediate())) out.add(new CodeString(i, s.getValue(false)));
            }
        }
        codeStrings = out;
        return out;
    }

    /**
     * Classified string table (VIEWS_SPEC §1): every DEX code-literal string with
     * two-layer types (lexical + usage), xref count, first referrer. Built once,
     * cached, invalidated on rename. The usage layer runs one instruction pass per
     * referrer method (const-string dest-register → following invoke's arg) — a
     * full-DEX scan the first time, so it stays inside the engine lock; cached
     * thereafter (Gmail-scale perf is the M10 pass).
     */
    private volatile List<Map<String, Object>> classifiedStrings;

    private List<Map<String, Object>> classifiedStrings() {
        List<Map<String, Object>> cached = classifiedStrings;
        if (cached != null) return cached;
        synchronized (this) {
            if (classifiedStrings != null) return classifiedStrings;
            // gather refs per string + the unique referrer methods
            Map<Integer, List<Map<String, Object>>> refsByIndex = new java.util.HashMap<>();
            Map<String, IDexMethod> refMethods = new java.util.LinkedHashMap<>();
            for (CodeString cs : codeStrings()) {
                List<Map<String, Object>> refs = stringRefs(cs.index());
                refsByIndex.put(cs.index(), refs);
                for (Map<String, Object> ref : refs) {
                    Object ma = ref.get("method");
                    if (ma == null) continue;
                    IDexMethod m = findAnyMethod(String.valueOf(ma));
                    if (m != null) refMethods.putIfAbsent(String.valueOf(ma), m);
                }
            }
            // usage layer: one pass per referrer method → string index → facts
            Map<Integer, List<String[]>> usage = new java.util.HashMap<>();
            for (Map.Entry<String, IDexMethod> e : refMethods.entrySet()) {
                IDexMethod m = e.getValue();
                IDexUnit dex = owningDex(m.getAddress());
                if (dex == null) continue;
                for (Map.Entry<Integer, String[]> f
                        : JebStringWalker.usageFactsForMethod(dex, m).entrySet())
                    usage.computeIfAbsent(f.getKey(), k -> new ArrayList<>()).add(f.getValue());
            }
            List<Map<String, Object>> out = new ArrayList<>();
            for (CodeString cs : codeStrings()) {
                List<Map<String, Object>> refs = refsByIndex.get(cs.index());
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", String.valueOf(cs.index()));
                row.put("value", cs.value());
                List<Map<String, Object>> types = StringClassifier.classify(cs.value(), usage.get(cs.index()));
                row.put("types", types);
                row.put("type", types.isEmpty() ? "plain" : types.get(0).get("name"));   // primary
                row.put("xrefCount", refs.size());
                String first = "(no referrer)";
                if (!refs.isEmpty()) {
                    Map<String, Object> f0 = refs.get(0);
                    first = f0.get("owner") != null
                            ? f0.get("owner") + "." + f0.get("name")
                            : String.valueOf(f0.get("from"));
                }
                row.put("firstReferrer", first);
                row.put("unreachable", refs.isEmpty());
                out.add(row);
            }
            classifiedStrings = out;
            return out;
        }
    }

    /** Paged + filtered string rows (VIEWS_SPEC §1: substring, type multi-select,
     *  unreachable-only; offset/count with truncated). */
    @Override
    public Map<String, Object> strings(String filter, List<String> types, boolean unreachableOnly,
            int offset, int count, String sort) throws OpException {
        requireReady();
        String sortMode;
        try { sortMode = dev.workbench.engine.StringSort.normalize(sort); }
        catch (IllegalArgumentException e) { throw OpException.syntax(e.getMessage(), "sort=" + sort); }
        List<Map<String, Object>> all = classifiedStrings();
        String ql = filter == null ? "" : filter.toLowerCase();
        int cap = count <= 0 ? 200 : Math.min(count, 2000);
        List<Object> rows = new ArrayList<>();
        /* filter first, sort the matches (audit decision 5: priority by default), then page */
        List<Map<String, Object>> matchedRows = new ArrayList<>();
        for (Map<String, Object> row : all) {
            if (unreachableOnly && !Boolean.TRUE.equals(row.get("unreachable"))) continue;
            if (!ql.isEmpty()) {
                Object v = row.get("value");
                if (v == null || !String.valueOf(v).toLowerCase().contains(ql)) continue;
            }
            if (types != null && !types.isEmpty()) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> ts = (List<Map<String, Object>>) row.get("types");
                boolean ok = false;
                for (Map<String, Object> t : ts)
                    if (types.contains(String.valueOf(t.get("name")))) { ok = true; break; }
                if (!ok) continue;
            }
            matchedRows.add(row);
        }
        dev.workbench.engine.StringSort.sort(matchedRows, sortMode);
        for (int i = offset; i < matchedRows.size() && rows.size() < cap; i++) rows.add(matchedRows.get(i));
        int total = matchedRows.size();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rows", rows);
        out.put("offset", offset);
        out.put("count", rows.size());
        out.put("total", total);
        out.put("truncated", offset + rows.size() < total);
        out.put("totalAll", all.size());
        out.put("sort", sortMode);
        return out;
    }

    /** Reference rows (raw maps) for one string pool index, across all dex units. */
    private List<Map<String, Object>> stringRefs(int index) {
        List<Map<String, Object>> refs = new ArrayList<>();
        for (IDexUnit dex : dexUnits) {
            IDexString s;
            try {
                s = dex.getString(index);
            } catch (RuntimeException outOfRange) {
                continue;
            }
            if (s == null) continue;
            java.util.Collection<IDexAddress> got =
                    dex.getReferenceManager().getReferences(DexPoolType.STRING, index);
            if (got == null) continue;
            for (IDexAddress a : got) {
                Map<String, Object> x = xrefRow(a.getInternalAddress(), String.valueOf(a.getReferenceType()));
                refs.add(x);
            }
        }
        return refs;
    }

    /** Usages of one string (string.xrefs) — id is the DEX string pool index. */
    @Override
    public List<Object> stringXrefs(String stringId) throws OpException {
        requireReady();
        int index;
        try {
            index = Integer.parseInt(String.valueOf(stringId).trim());
        } catch (NumberFormatException e) {
            throw OpException.syntax("string id must be a pool index: " + stringId, "target=" + id);
        }
        if (index < 0) throw OpException.syntax("negative string index: " + stringId, "target=" + id);
        synchronized (this) {
            String value = null;
            for (IDexUnit dex : dexUnits) {
                IDexString s;
                try {
                    s = dex.getString(index);
                } catch (RuntimeException outOfRange) {
                    continue;
                }
                if (s != null) { value = s.getValue(false); break; }
            }
            if (value == null)
                throw OpException.notFound("no string at index: " + stringId, "target=" + id);
            List<Object> out = new ArrayList<>();
            for (Map<String, Object> x : stringRefs(index)) {
                x.put("stringId", stringId);
                x.put("value", value);
                out.add(x);
            }
            return out;
        }
    }

    /**
     * Decompiled-text search over the background index (VIEWS_SPEC §3): grouped
     * hits with ±3-line context. Runs fully OFF the engine lock on the SearchIndex
     * snapshot (may be partial while building — index status is in target.info).
     * opts: kind (csv of code|strings|comments), regex, case.
     */
    @Override
    public List<Object> search(String query, Map<String, Object> opts) throws OpException {
        requireReady();
        if (query == null || query.isBlank()) return new ArrayList<>();
        String kindCsv = opts == null ? null : (opts.get("kind") == null ? null : String.valueOf(opts.get("kind")));
        boolean regex = opts != null && Boolean.parseBoolean(String.valueOf(opts.get("regex")));
        boolean caseSense = opts != null && Boolean.parseBoolean(String.valueOf(opts.get("case")));
        java.util.Set<String> kinds = new java.util.HashSet<>();
        if (kindCsv == null || kindCsv.isBlank()) kinds.addAll(List.of("code", "strings", "comments"));
        else for (String k : kindCsv.split(",")) if (!k.isBlank()) kinds.add(k.trim());
        java.util.regex.Pattern pat;
        try {
            pat = java.util.regex.Pattern.compile(
                    regex ? query : java.util.regex.Pattern.quote(query),
                    caseSense ? 0 : java.util.regex.Pattern.CASE_INSENSITIVE);
        } catch (Exception e) {
            throw OpException.syntax("bad regex: " + e.getMessage(), "query=" + query);
        }

        /* the caps default to 200 hits / 50 classes / 20 per class; an explicit limit (the UI's
           raise-cap control, MCP --limit) scales all three, clamped to 5000 hits (audit decision 6:
           no pagination — a search is one in-memory scan, so a bigger cap is one bigger scan) */
        int limit = 0;
        if (opts != null && opts.get("limit") != null && !String.valueOf(opts.get("limit")).isBlank()) {
            try { limit = Integer.parseInt(String.valueOf(opts.get("limit")).trim()); }
            catch (NumberFormatException e) { throw OpException.syntax("limit must be a number", "limit=" + opts.get("limit")); }
        }
        final int hitCap = limit <= 0 ? 200 : Math.min(limit, 5000);
        final int groupCap = Math.max(50, hitCap / 4), perGroup = Math.max(20, hitCap / 10);
        List<Object> groups = new ArrayList<>();
        int totalHits = 0;

        if (kinds.contains("code") || kinds.contains("comments")) {
            for (SearchIndex.Entry e : searchIndex.queryEntries()) {
                if (groups.size() >= groupCap || totalHits >= hitCap) break;
                List<Object> hits = new ArrayList<>();
                String[] lines = e.lines();
                for (int i = 0; i < lines.length; i++) {
                    String trimmed = lines[i].trim();
                    boolean isComment = trimmed.startsWith("//") || trimmed.startsWith("/*")
                            || trimmed.startsWith("*");
                    String kind = isComment ? "comments" : "code";
                    if (!kinds.contains(kind)) continue;
                    if (!pat.matcher(lines[i]).find()) continue;
                    Map<String, Object> h = new LinkedHashMap<>();
                    h.put("kind", kind);
                    h.put("line", i + 1);
                    h.put("preview", trimmed);
                    h.put("addr", e.addr());          // QA M5: open-hit needs class addr + line
                    List<Object> ctx = new ArrayList<>();
                    for (int j = Math.max(0, i - 3); j <= Math.min(lines.length - 1, i + 3); j++) {
                        Map<String, Object> cl = new LinkedHashMap<>();
                        cl.put("n", j + 1);
                        cl.put("t", lines[j]);
                        cl.put("hit", j == i);
                        ctx.add(cl);
                    }
                    h.put("context", ctx);
                    hits.add(h);
                    totalHits++;
                    if (hits.size() >= perGroup || totalHits >= hitCap) break;
                }
                if (!hits.isEmpty()) {
                    Map<String, Object> g = new LinkedHashMap<>();
                    g.put("symbol", e.fqcn());
                    g.put("addr", e.addr());
                    g.put("target", name);
                    g.put("engine", "jeb");
                    g.put("hits", hits);
                    groups.add(g);
                }
            }
        }
        if (kinds.contains("strings")) {
            List<Object> hits = new ArrayList<>();
            for (CodeString cs : codeStrings()) {
                if (totalHits >= hitCap || hits.size() >= perGroup) break;
                if (cs.value() == null || !pat.matcher(cs.value()).find()) continue;
                Map<String, Object> h = new LinkedHashMap<>();
                h.put("kind", "strings");
                h.put("line", "s" + cs.index());
                h.put("preview", "\"" + cs.value() + "\"");
                h.put("stringId", String.valueOf(cs.index()));
                hits.add(h);
                totalHits++;
            }
            if (!hits.isEmpty()) {
                Map<String, Object> g = new LinkedHashMap<>();
                g.put("symbol", "(string pool)");
                g.put("addr", "");
                g.put("target", name);
                g.put("engine", "jeb");
                g.put("hits", hits);
                groups.add(g);
            }
        }
        return groups;
    }

    // ------------------------------------------------------------ search index lifecycle

    @Override
    public Map<String, Object> indexSourceIdentity() {
        Map<String, Object> input = inputIdentity();
        Map<String, Object> identity = new LinkedHashMap<>();
        Object digest = input == null ? null : input.get("sha256");
        identity.put("measured", digest instanceof String && ((String) digest).matches("[0-9a-f]{64}"));
        identity.put("artifact_sha256", digest);
        identity.put("input_class", input == null ? "unknown" : input.get("inputClass"));
        identity.put("analysis_classes_at_open", classCount);
        return identity;
    }

    /** SearchIndex.Host: all classes as [addr, fqcn] (engine-locked snapshot). */
    @Override
    public List<String[]> classSnapshot() {
        synchronized (this) {
            List<String[]> out = new ArrayList<>();
            for (IDexUnit dex : dexUnits)
                for (IDexClass c : dex.getClasses())
                    out.add(new String[] { c.getAddress(), dottedName(c) });
            return out;
        }
    }

    /** SearchIndex.Host: decompile one class (engine-locked burst). */
    @Override
    public String decompileText(String classAddr) {
        synchronized (this) {
            IDexUnit dex = owningDex(classAddr);
            IDexDecompilerUnit dec = dex == null ? null : decompilers.get(dex);
            if (dec == null) return null;
            return dec.decompileClass(classAddr) ? dec.getDecompiledText(classAddr) : null;
        }
    }

    /** SearchIndex.Host: display name for one class (engine-locked). */
    @Override
    public String classLabel(String classAddr) {
        synchronized (this) {
            IDexClass c = findClass(classAddr);
            return c == null ? classAddr : dottedName(c);
        }
    }

    /** SearchIndex.Host: drop a class's decompiled products (AST churn control;
     *  re-decompile on demand re-reads the live — possibly renamed — model). */
    @Override
    public void evictDecompiled(String classAddr) {
        synchronized (this) {
            IDexUnit dex = owningDex(classAddr);
            IDexDecompilerUnit dec = dex == null ? null : decompilers.get(dex);
            if (dec != null) dec.removeDecompilation(classAddr);
        }
    }

    /** SearchIndex.Host: current jdb2 mtime (-1 when none) — recorded at corpus
     *  build completion and after every save as the foreign-edit witness. */
    @Override
    public long jdb2Mtime() {
        return jdb2 != null && jdb2.isFile() ? jdb2.lastModified() : -1;
    }

    @Override
    public Map<String, Object> searchIndexStatus() {
        return searchIndex.status();
    }

    @Override
    public void reindex() throws OpException {
        /* review L1: re-arming the builder on an ERRORED target walked dead
           JEB units (verified live: search.reindex on an error target returned
           ok:true) and start() deletes the persisted corpus first. Gate on
           ready. cancelTask stays ungated — a pure cancel is safe in any
           state, and gating it would block cancelling a runaway build. */
        requireReady();
        /* kill() + the commit protocol are the backstop (findings 5+7 r37:
           the synchronized gate removed here almost never fired — close()
           deregisters before it sets closing — its "retry the reindex" was
           dishonest for a target being destroyed, and it reintroduced a
           bounded stall behind monster decompiles. A close landing between
           this check and start() leaves start() a dead-refused no-op: the
           op returns ok against a target being destroyed, void either way.
           Round-39: and since the corpus wipe moved behind build()'s guard,
           the no-op doesn't even cost the corpus) */
        searchIndex.start();
    }

    @Override
    public boolean cancelTask(String ticket) {
        if (ticket != null && ticket.equals(searchIndex.ticket())) {
            searchIndex.cancel();
            return true;
        }
        return false;
    }

    /** Class metadata for the inspector bay (code.classinfo). */
    @Override
    public Map<String, Object> classinfo(String classSymbol) throws OpException {
        requireReady();
        synchronized (this) {
            Resolved r = lookup(classSymbol);
            IDexClass cls = findClass(r.addr());
            if (cls == null) throw OpException.notFound("class not found: " + classSymbol, "target=" + id);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("addr", cls.getAddress());
            out.put("name", displayName(cls));
            out.put("fqcn", dottedName(cls));
            out.put("sig", cls.getSignature(true));
            out.put("super", cls.getSupertypeSignature(true));
            List<String> ifaces = new ArrayList<>();
            String[] is = cls.getInterfaceSignatures(true);
            if (is != null) java.util.Collections.addAll(ifaces, is);
            out.put("interfaces", ifaces);
            out.put("flags", flags(cls.getAccessFlags(), true).trim());
            out.put("methods", cls.getMethods().size());
            out.put("fields", cls.getFields().size());
            out.put("xrefCount", xrefsTo(r.addr()).size());
            out.put("renamed", cls.isRenamed());
            if (cls.isRenamed()) out.put("origName", cls.getName(false));
            out.put("kind", cls.isInterface() ? "interface" : "class");
            return out;
        }
    }

    // ------------------------------------------------------------ write ops (native persist via .jdb2)

    /** Rename a class/method/field. NOTE: a rename changes the item's addr (the Dalvik
     *  signature embeds the name; a class rename also shifts every member addr) — clients
     *  must re-key from newAddr. Persisted to the .jdb2 on save()/close(). */
    /** A rename JEB did not apply (typically: the name is already taken in that scope — a class
     *  twin in the package, a member overload) must fail the op, or the workbench records a
     *  rename that never happened and a proposal shows ACCEPTED with the old name still on
     *  screen (2026-09-13: two class-rename proposals collided on one new name). */
    private void requireApplied(IDexItem item, String old, String wanted) throws OpException {
        if (wanted.equals(item.getName(true))) return;
        List<Map<String,Object>> conflicts = new ArrayList<>();
        String diagnosticError = null;
        try { conflicts = renameNameOccupants(item, wanted); }
        catch (RuntimeException | OpException error) { diagnosticError = error.toString(); }
        throw renameRefused(old, wanted, item.getSignature(false), item.getAddress(), conflicts, diagnosticError);
    }

    /** Diagnostics only, AFTER JEB refuses; never invent a uniqueness policy or auto-suffix a name. */
    private static OpException renameRefused(String old, String wanted, String original, String addr,
                                             List<Map<String,Object>> conflicts, String diagnosticError) {
        String occupied = conflicts.stream().map(row -> String.valueOf(row.get("originalSymbol"))
                + " (current: " + row.get("addr") + ", name: " + row.get("currentName") + ")")
                .collect(java.util.stream.Collectors.joining("; "));
        OpException error = OpException.busy("rename refused by JEB: '" + old + "' → '" + wanted + "' did not apply — "
                + (conflicts.isEmpty() ? "no occupying symbol could be identified; the name may be reserved or JEB may have another restriction"
                        : "the requested name is used by " + occupied), "symbol=" + addr + " name=" + wanted);
        error.extra.put("originalSymbol", original);
        error.extra.put("currentName", old);
        error.extra.put("requestedName", wanted);
        error.extra.put("conflicts", conflicts);
        error.extra.put("collisionLookup", diagnosticError != null ? "incomplete" : conflicts.isEmpty() ? "unresolved" : "matched");
        if (diagnosticError != null) error.extra.put("collisionLookupError", diagnosticError);
        error.extra.put("hint", conflicts.isEmpty()
                ? "Read the current scope and check the requested identifier; do not retry the same refused name unchanged."
                : "Inspect conflicts by originalSymbol; choose a distinct evidence-backed name for your symbol. Do not rename the occupying symbol merely to free its name.");
        return error;
    }

    private static Map<String,Object> nameOccupant(IDexItem item, String kind, String match) {
        Map<String,Object> row = new LinkedHashMap<>();
        row.put("kind", kind); row.put("originalSymbol", item.getSignature(false));
        row.put("addr", item.getAddress()); row.put("currentName", item.getName(true));
        row.put("originalName", item.getName(false)); row.put("match", match);
        if (!"class".equals(kind)) row.put("owner", classAddrOf(item.getAddress()));
        return row;
    }

    private List<Map<String,Object>> renameNameOccupants(IDexItem selected, String wanted) throws OpException {
        Map<String,Map<String,Object>> found = new LinkedHashMap<>();
        if (selected instanceof IDexClass cls) {
            String signature = cls.getSignature(true), current = cls.getName(true);
            // getName may include the outer class name; use exactly the part setName replaces.
            String destination = signature.substring(0, signature.length() - current.length() - 1) + wanted + ";";
            for (IDexUnit dex : dexUnits) {
                IDexClass other = dex.getClass(destination);
                IDexItem occupant = other != null ? other : dex.getType(destination);
                // JEB also resolves old aliases: a stale alias is not a current occupant.
                if (occupant != null && destination.equals(occupant.getSignature(true))
                        && !selected.getSignature(false).equals(occupant.getSignature(false)))
                    found.put(occupant.getSignature(false), nameOccupant(occupant, "class", "destination_class"));
            }
        } else if (selected instanceof IDexField field) {
            IDexClass owner = findClass(field.getAddress());
            if (owner != null) for (IDexField other : owner.getFields())
                if (wanted.equals(other.getName(true)) && !field.getSignature(false).equals(other.getSignature(false)))
                    found.put(other.getSignature(false), nameOccupant(other, "field", "same_class_field"));
        } else if (selected instanceof IDexMethod) {
            // A refusal may originate on an override/sibling, not the explicitly selected method.
            List<Map<String,Object>> family = renameTargets(selected.getSignature(false));
            java.util.Set<String> familyIds = new java.util.HashSet<>();
            for (Map<String,Object> member : family) familyIds.add(String.valueOf(member.get("origSig")));
            for (Map<String,Object> member : family) {
                IDexMethod method = findAnyMethod(String.valueOf(member.get("origSig")));
                if (method == null) continue;
                IDexClass owner = findClass(method.getAddress());
                if (owner == null) continue;
                String signature = method.getSignature(true);
                String descriptor = signature.substring(signature.indexOf('('));
                for (IDexMethod other : owner.getMethods()) {
                    if (!wanted.equals(other.getName(true)) || familyIds.contains(other.getSignature(false))) continue;
                    String otherSignature = other.getSignature(true);
                    if (!descriptor.equals(otherSignature.substring(otherSignature.indexOf('(')))) continue;
                    Map<String,Object> row = nameOccupant(other, "method", "same_class_method_descriptor");
                    row.put("affectedOriginalSymbol", method.getSignature(false));
                    found.put(other.getSignature(false), row);
                }
            }
        }
        return new ArrayList<>(found.values());
    }

    /** JEB setName propagates through virtual definitions/overrides, including sibling
     * implementations. Ask its published override action and traverse both directions;
     * checking only the explicitly requested method permits a related-method overwrite. */
    @Override
    public List<Map<String, Object>> renameTargets(String symbol) throws OpException {
        requireReady();
        synchronized (this) {
            Resolved selected = lookup(symbol);
            if (selected.kind() != Kind.METHOD) return List.of(resolve(symbol));
            IDexMethod first = findAnyMethod(selected.addr());
            if (first == null) throw OpException.notFound("method not found: " + symbol, "target=" + id);
            Map<String, IDexMethod> family = new LinkedHashMap<>();
            java.util.ArrayDeque<IDexMethod> pending = new java.util.ArrayDeque<>();
            family.put(first.getSignature(false), first); pending.add(first);
            while (!pending.isEmpty()) {
                IDexMethod method = pending.removeFirst();
                IDexUnit dex = method.getDex();
                ActionContext context = new ActionContext(dex, Actions.QUERY_OVERRIDES, method.getItemId(), method.getAddress());
                ActionOverridesData data = new ActionOverridesData();
                if (!dex.prepareExecution(context, data))
                    throw OpException.busy("JEB could not determine the complete method rename family; no rename was applied", "symbol=" + method.getSignature(false));
                var related = new ArrayList<com.pnfsoftware.jeb.core.units.code.ICodeItem>();
                related.addAll(data.getParents()); related.addAll(data.getChildren());
                for (var item : related) {
                    if (!(item instanceof IDexMethod relative)) continue;
                    if (family.putIfAbsent(relative.getSignature(false), relative) == null) pending.add(relative);
                }
            }
            List<Map<String,Object>> out = new ArrayList<>();
            for (IDexMethod method : family.values()) {
                Map<String,Object> row = new LinkedHashMap<>();
                row.put("origSig", method.getSignature(false)); row.put("addr", method.getAddress());
                row.put("name", method.getName(true)); row.put("kind", "method");
                out.add(row);
            }
            return out;
        }
    }

    @Override
    public Map<String, Object> rename(String symbol, String name, String actor) throws OpException {
        requireReady();
        if (name == null || !name.matches("[A-Za-z_$][A-Za-z0-9_$]*"))
            throw OpException.syntax("bad Java identifier: " + name, "symbol=" + symbol);
        synchronized (this) {
            if (discarded) throw OpException.busy("this target is being closed with its edits discarded — rename refused", "target=" + id);
            Resolved r = lookup(symbol);
            String addr = r.addr();
            if (r.kind() == Kind.METHOD && (addr.contains("-><init>(") || addr.contains("-><clinit>(")))
                throw OpException.syntax("a constructor cannot be renamed (<init> / <clinit> are the class's own) — name the class instead",
                        "symbol=" + symbol);   // JEB refused it as "taken", which sent an agent looking for another name (Kimi test, 2026-09-20)
            List<Map<String,Object>> affected = renameTargets(symbol);
            if (!affected.isEmpty() && affected.stream().allMatch(member -> name.equals(member.get("name")))) {
                Map<String,Object> out = new LinkedHashMap<>();
                out.put("addr", addr); out.put("newAddr", addr);
                out.put("kind", r.kind().name().toLowerCase());
                out.put("oldName", name); out.put("newName", name);
                out.put("applied", false); out.put("unchanged", true);
                out.put("reason", "Every affected symbol already has this name.");
                return out;
            }
            java.util.Set<String> refClasses = referrerClasses(addr);   // captured BEFORE the rename shifts addrs
            if (r.kind() == Kind.METHOD) {
                for (Map<String,Object> relative : affected) {
                    String relatedAddr = String.valueOf(relative.get("addr"));
                    refClasses.add(classAddrOf(relatedAddr));
                    refClasses.addAll(referrerClasses(relatedAddr));
                }
            }
            String old = null, newAddr = null;
            boolean done = false;
            for (IDexUnit dex : dexUnits) {
                switch (r.kind()) {
                    case CLASS -> {
                        IDexClass c = dex.getClass(addr);
                        if (c == null) continue;
                        old = c.getName(true);
                        c.setName(name);
                        requireApplied(c, old, name);   // JEB keeps the old name silently when the new one is taken
                        newAddr = c.getAddress();
                        done = true;
                    }
                    case METHOD -> {
                        IDexMethod m = dex.getMethod(addr);
                        if (m == null) continue;
                        old = m.getName(true);
                        m.setName(name);
                        requireApplied(m, old, name);   // JEB keeps the old name silently when the new one is taken
                        newAddr = m.getAddress();
                        done = true;
                    }
                    case FIELD -> {
                        IDexField f = dex.getField(addr);
                        if (f == null) continue;
                        old = f.getName(true);
                        f.setName(name);
                        requireApplied(f, old, name);   // JEB keeps the old name silently when the new one is taken
                        newAddr = f.getAddress();
                        done = true;
                    }
                }
                if (done) break;
            }
            if (!done)
                throw OpException.notFound(r.kind().name().toLowerCase() + " not found: " + addr,
                        "target=" + id);
            dirty = true;
            classifiedStrings = null;
            // keep the search index fresh: a rename changes decompiled text in the
            // owner class AND in every class that references the item (call sites,
            // type refs, field refs — proven by the M3IT freshness matrix). Drop
            // and re-queue all of them (referrers captured pre-rename, above);
            // the re-decompile runs off this thread in the index drainer.
            String freshClass = classAddrOf(newAddr == null ? addr : newAddr);
            searchIndex.invalidate(classAddrOf(addr), freshClass);
            refClasses.remove(freshClass);
            searchIndex.invalidateAll(refClasses);   // one pass + one pending write (fix-4 X1)
            namesChanged();
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("symbol", Map.of("addr", addr, "kind", r.kind().name().toLowerCase(), "name", r.name()));
            out.put("addr", addr);
            out.put("newAddr", newAddr == null ? addr : newAddr);
            out.put("kind", r.kind().name().toLowerCase());
            out.put("oldName", old);
            out.put("newName", name);
            out.put("applied", true);
            out.put("actor", actor == null ? "human" : actor);
            return out;
        }
    }

    // ------------------------------------------------------------ parameter metadata (code.params, 2026-10-08)

    public static final String PARAMS_SCHEMA = "method-parameters-v1";
    private static final String METHOD_PARAMETERS_ANNOTATION = "Ldalvik/annotation/MethodParameters;";
    /** A usable NEW analyst name: a Java identifier that is not a keyword (the rule every rename runs). */
    private static boolean validAnalystName(String s) { return Engine.usableName(s); }

    @Override
    public Map<String, Object> methodParameters(String symbol) throws OpException { return methodParameters(symbol, true); }

    @Override
    public Map<String, Object> methodParameters(String symbol, boolean withCurrent) throws OpException {
        requireReady();
        synchronized (this) {
            Resolved r = lookup(symbol);
            if (r.kind() != Kind.METHOD)
                throw OpException.syntax("code.params takes a METHOD: " + symbol + " is a " + r.kind().name().toLowerCase(), "symbol=" + symbol);
            IDexMethod m = findAnyMethod(r.addr());
            if (m == null) throw OpException.notFound("method not found: " + symbol, "target=" + id);
            IDexClass cls = findClass(r.addr());
            IDexUnit dex = cls == null ? null : owningDex(cls.getAddress());
            return parameterMetadata(dex, cls, m, withCurrent);
        }
    }

    /**
     * The retained parameter metadata of one method — a pure read (no dirty flag, no history, no
     * decompilation unless withCurrent asks for the decompiler's current argument names):
     *   annotation   the dex's method-level MethodParameters: names[] and accessFlags[] verbatim in
     *                encoded order (nulls kept, duplicates kept), each array's length against the
     *                prototype SEPARATELY, status present | absent | malformed | unsupported,
     *                argumentMapping "unverified" always — an index into the array is not a verified
     *                argument index (WhatsApp 2.23.4.6 LX/0XS;-><init> keeps intentSender,intent on
     *                a prototype whose first argument is the Intent)
     *   debugInfo    the debug_info_item parameter names — the second source, kept apart
     *   prototype    the current signature's parameter types, count, register words
     *   parameters   the formal arguments as they stand: index, type, register, the decompiler's
     *                current name and where it comes from — an analyst's setting (setParameterName),
     *                the debug info, the annotation applied BY THE ENGINE by position when the counts
     *                match (JEB 5.42 does that; it is the engine's rendering, not a verified mapping),
     *                or generic
     */
    private Map<String, Object> parameterMetadata(IDexUnit dex, IDexClass cls, IDexMethod m, boolean withCurrent) {
        /* the method object from the unit whose annotation directory is read: a method_id index belongs
           to its own dex (fix-review 2026-10-08 L3) */
        if (dex != null) { try { IDexMethod own = dex.getMethod(m.getAddress()); if (own != null) m = own; } catch (Throwable ignored) {} }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schema", PARAMS_SCHEMA);
        out.put("method", m.getAddress());
        out.put("original_method", m.getSignature(false));
        out.put("dex", dex == null ? null : dex.getName());
        IDexPrototype proto = m.getPrototype();
        List<String> ptypes = new ArrayList<>();
        if (proto != null && proto.getParameterTypes() != null) for (IDexType t : proto.getParameterTypes()) ptypes.add(t == null ? "?" : t.getSignature(false));
        IDexMethodData md = m.getData();
        int acc = md == null ? 0 : md.getAccessFlags();
        boolean isStatic = (acc & 0x0008) != 0;
        int words = 0;
        List<Integer> regs = new ArrayList<>();
        int reg = isStatic ? 0 : 1;
        for (String t : ptypes) { regs.add(reg); int w = "J".equals(t) || "D".equals(t) ? 2 : 1; reg += w; words += w; }
        Map<String, Object> prototype = new LinkedHashMap<>();
        prototype.put("parameterTypes", ptypes); prototype.put("count", ptypes.size()); prototype.put("static", isStatic);
        prototype.put("registerWords", words + (isStatic ? 0 : 1)); prototype.put("returnType", proto == null || proto.getReturnType() == null ? null : proto.getReturnType().getSignature(false));
        out.put("prototype", prototype);

        // ---- the annotation
        Map<String, Object> ann = new LinkedHashMap<>();
        ann.put("source", "dex_method_parameters_annotation");
        List<Object> names = null, flags = null;
        try {
            IDexAnnotationsDirectory ad = cls == null ? null : cls.getAnnotationsDirectory();
            List<IDexAnnotationItem> items = ad == null || dex == null ? null : ad.getMethodAnnotations(m.getIndex());
            IDexAnnotation found = null;
            int visibility = -1;
            if (items != null) for (IDexAnnotationItem it : items) {
                IDexAnnotation a = it.getAnnotation();
                if (a == null) continue;
                IDexType at = dex.getType(a.getTypeIndex());
                if (at != null && METHOD_PARAMETERS_ANNOTATION.equals(at.getSignature(false))) { found = a; visibility = it.getVisibility(); break; }
            }
            if (cls == null || dex == null) { ann.put("status", "unsupported"); ann.put("why", "the method's class or dex unit could not be located"); }
            else if (found == null) ann.put("status", "absent");
            else {
                List<String> problems = new ArrayList<>();
                for (IDexAnnotationElement el : found.getElements()) {
                    String en = el.getName(dex);
                    IDexValue v = el.getValue();
                    if (v == null) { problems.add("element " + en + " has no value"); continue; }
                    if (v.getType() != IDexValue.VALUE_ARRAY) { problems.add("element " + en + " is not an array (value type " + v.getType() + ")"); continue; }
                    List<Object> vals = new ArrayList<>();
                    for (IDexValue x : v.getArray()) {
                        if (x.getType() == IDexValue.VALUE_STRING) { IDexString s = dex.getString(x.getStringIndex()); vals.add(s == null ? null : s.getValue(false)); }
                        else if (x.getType() == IDexValue.VALUE_NULL) vals.add(null);
                        else if (x.getType() == IDexValue.VALUE_INT || x.getType() == IDexValue.VALUE_SHORT || x.getType() == IDexValue.VALUE_BYTE) vals.add(x.getInt());
                        else { vals.add(null); problems.add("element " + en + " holds a value of type " + x.getType()); }
                    }
                    if ("names".equals(en)) names = vals;
                    else if ("accessFlags".equals(en)) flags = vals;
                    else problems.add("unexpected element " + en);
                }
                if (names == null) problems.add("no names element");
                ann.put("status", problems.isEmpty() ? "present" : "malformed");
                if (!problems.isEmpty()) ann.put("problems", problems);
                ann.put("visibility", visibility);
                ann.put("names", names == null ? List.of() : names);
                ann.put("accessFlags", flags == null ? List.of() : flags);
                List<Object> decoded = new ArrayList<>();
                if (flags != null) for (Object f : flags) decoded.add(f instanceof Number n ? decodeParamFlags(n.intValue()) : null);
                ann.put("accessFlagsDecoded", decoded);
                int nc = names == null ? 0 : names.size(), fc = flags == null ? 0 : flags.size();
                ann.put("namesCount", nc); ann.put("flagsCount", fc);
                boolean namesMatch = nc == ptypes.size(), flagsMatch = flags == null || fc == ptypes.size();
                ann.put("namesCountMatchesPrototype", namesMatch); ann.put("flagsCountMatchesPrototype", flagsMatch);
                ann.put("countMatchesPrototype", namesMatch && flagsMatch);
                if (!namesMatch || !flagsMatch) ann.put("mismatch", (namesMatch ? "" : nc + " name(s) for " + ptypes.size() + " formal parameter(s)") + (!namesMatch && !flagsMatch ? "; " : "") + (flagsMatch ? "" : fc + " flag(s) for " + ptypes.size() + " formal parameter(s)") + " — the metadata describes an earlier prototype");
                int nonEmpty = 0, nulls = 0;
                java.util.Set<String> seen = new java.util.HashSet<>(); List<String> dups = new ArrayList<>(), invalid = new ArrayList<>();
                if (names != null) for (Object o : names) {
                    if (o == null) { nulls++; continue; }
                    String s = String.valueOf(o);
                    if (s.isEmpty()) { nulls++; continue; }
                    nonEmpty++;
                    if (!seen.add(s) && !dups.contains(s)) dups.add(s);
                    if (!validAnalystName(s)) invalid.add(s);
                }
                ann.put("nonEmptyNames", nonEmpty); ann.put("nullNames", nulls);
                if (!dups.isEmpty()) ann.put("duplicates", dups);
                if (!invalid.isEmpty()) ann.put("notJavaIdentifiers", invalid);
                ann.put("encodedOrder", true);
                ann.put("argumentMapping", "unverified");
            }
        } catch (Throwable t) {
            ann.put("status", "unsupported");
            ann.put("why", "the annotation directory could not be read: " + t);
        }
        out.put("annotation", ann);

        // ---- the debug info names
        Map<String, Object> dbg = new LinkedHashMap<>();
        dbg.put("source", "dex_debug_info_parameter_names");
        List<Object> debugNames = null;
        int debugNonEmpty = 0;
        IDexCodeItem ci = md == null ? null : md.getCodeItem();
        out.put("hasCode", ci != null);
        try {
            if (ci == null) dbg.put("status", md != null && (md.isNative() || md.isAbstract()) ? "no-code" : "absent");
            else {
                IDexDebugInfo di = ci.getDebugInfo();
                int[] idx = di == null ? null : di.getParameterNameIndexes();
                if (idx == null) dbg.put("status", "absent");
                else {
                    debugNames = new ArrayList<>();
                    for (int i : idx) { IDexString s = i < 0 || dex == null ? null : dex.getString(i); debugNames.add(s == null ? null : s.getValue(false)); }
                    for (Object o : debugNames) if (o != null && !String.valueOf(o).isEmpty()) debugNonEmpty++;
                    /* a table of null slots (WhatsApp keeps one on most methods) names nothing: "empty",
                       not a second source that "differs" (persona pass 2026-10-08) */
                    dbg.put("status", debugNonEmpty > 0 ? "present" : "empty");
                    dbg.put("names", debugNames);
                    dbg.put("count", debugNames.size());
                    dbg.put("nonEmpty", debugNonEmpty);
                    dbg.put("countMatchesPrototype", debugNames.size() == ptypes.size());
                }
            }
        } catch (Throwable t) { dbg.put("status", "unsupported"); dbg.put("why", "the debug info could not be read: " + t); }
        out.put("debugInfo", dbg);
        if (names != null && debugNames != null && debugNonEmpty > 0) {
            boolean same = names.size() == debugNames.size();
            if (same) for (int i = 0; i < names.size(); i++) if (!java.util.Objects.equals(names.get(i), debugNames.get(i))) { same = false; break; }
            out.put("sourcesAgree", same);
        }

        // ---- the formal arguments as they stand
        List<Object> params = new ArrayList<>();
        MethodNames mn = withCurrent ? methodNames(cls, m, ptypes.size()) : null;
        List<String> shown = mn == null ? null : mn.params();
        if (mn != null) out.put("locals", new ArrayList<>(mn.locals()));   // the body's own names: a mapping onto one of them is a collision too
        for (int i = 0; i < ptypes.size(); i++) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("index", i); p.put("type", ptypes.get(i)); p.put("register", regs.get(i));
            String set = null;
            try { set = m.getParameterName(i); } catch (Throwable ignored) {}
            String rendered = shown != null && i < shown.size() ? shown.get(i) : null;
            // JEB 5.46's abstract declaration RENAME action may store an
            // identifier's name at an index including the receiver, whereas
            // getParameterName documents formal indices. Do not project that
            // binding onto the next argument: report the actual rendered names.
            String cur = rendered != null ? rendered : set;
            if (set != null && rendered != null && !set.equals(rendered)) {
                p.put("parameterBindingName", set);
                p.put("parameterBindingMismatch", true);
            }
            if (cur != null) p.put("name", cur);
            String src;
            if (set != null && set.equals(cur)) src = "analyst";
            else if (cur == null) src = "unknown";
            else if (debugNames != null && i < debugNames.size() && cur.equals(debugNames.get(i))) src = "debug_info";
            else if (names != null && names.size() == ptypes.size() && cur.equals(names.get(i))) src = "annotation (applied by the engine by position — unverified)";
            else if (cur.matches("arg\\d+")) src = "generic";
            else src = "engine";
            p.put("nameSource", src);
            params.add(p);
        }
        out.put("parameters", params);
        Map<String, Object> decomp = new LinkedHashMap<>();
        decomp.put("engine", "jeb");
        if (!withCurrent) {
            /* this view did not read the decompiler's names: say so rather than asserting "false" —
               on the fixture JEB does apply the annotation (fix-review 2026-10-08 H2) */
            decomp.put("appliesAnnotationByPosition", null);
            decomp.put("appliesAnnotationToAny", null);
            decomp.put("annotationNamedArguments", null);
            decomp.put("note", "not computed in this view — code.params (the code tool's view params) reports the decompiler's current argument names, their sources and whether the engine applied the annotation by position");
        } else {
            /* WHICH arguments currently carry a name JEB took from the annotation by position: none, some or
               all of them — a partial analyst mapping leaves the others positional, and a single boolean
               for the whole method hid that (independent review 2026-10-08) */
            List<Integer> positional = new ArrayList<>();
            for (Object o : params) { Map<?, ?> p = (Map<?, ?>) o; if (String.valueOf(p.get("nameSource")).startsWith("annotation")) positional.add(((Number) p.get("index")).intValue()); }
            String status = positional.isEmpty() ? "none" : positional.size() == params.size() ? "all" : "some";
            Map<String, Object> an = new LinkedHashMap<>();
            an.put("status", status);
            an.put("count", positional.size());
            an.put("of", params.size());
            an.put("indices", positional);
            decomp.put("annotationNamedArguments", an);
            decomp.put("appliesAnnotationByPosition", "all".equals(status));   // compatibility: EVERY formal argument; some/none are in annotationNamedArguments
            decomp.put("appliesAnnotationToAny", !positional.isEmpty());
            decomp.put("note", switch (status) {
                case "all" -> "the decompiler names every argument after the MethodParameters annotation by position because the counts match — the engine's rendering, not a verified mapping (a same-length permutation reads wrong); set a name explicitly to override it";
                case "some" -> "the decompiler names " + positional.size() + " of " + params.size() + " arguments after the MethodParameters annotation by position (positions " + positional.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(", ")) + ") — the engine's rendering, not a verified mapping; the others carry an analyst's, the debug info's or a generic name";
                default -> "none of the decompiler's argument names is taken from the annotation: they come from an analyst's setting, the debug info or a generic argN — the annotation list beside them is separate";
            });
        }
        out.put("decompiler", decomp);
        out.put("argumentMapping", "unverified");
        out.put("note", "annotation order is the encoded order, not a verified mapping to the current arguments; the debug names are a second source; reading this changes nothing");
        return out;
    }

    private static List<String> decodeParamFlags(int f) {
        List<String> out = new ArrayList<>();
        if ((f & 0x0010) != 0) out.add("final");
        if ((f & 0x1000) != 0) out.add("synthetic");
        if ((f & 0x8000) != 0) out.add("mandated");
        int rest = f & ~(0x0010 | 0x1000 | 0x8000);
        if (rest != 0) out.add("0x" + Integer.toHexString(rest));
        return out;
    }

    /** The names a method's decompiled text SHOWS: the arguments by formal index and the body's locals.
     *  Read from the rendered text items whose object is the AST identifier (an identifier's own
     *  getName() is its generated name, arg1, while the text carries the applied one — the annotation's
     *  by position, the debug info's, an analyst's), the class decompiled if it was not. Cached per
     *  method until a name in this target changes (namesChanged): the render is one whole class, and
     *  every caret move, preview and apply asked for it (fix-review 2026-10-08 M9). */
    private record MethodNames(List<String> params, java.util.Set<String> locals) {}
    private final Map<String, MethodNames> methodNamesCache = new java.util.concurrent.ConcurrentHashMap<>();

    /** A rename, a local rename, a parameter mapping or a restoration landed: the rendered names move. */
    private void namesChanged() { methodNamesCache.clear(); }

    private List<String> currentParameterNames(IDexClass cls, IDexMethod m, int count) { return methodNames(cls, m, count).params(); }

    private MethodNames methodNames(IDexClass cls, IDexMethod m, int count) {
        String key = m.getAddress();
        MethodNames hit = methodNamesCache.get(key);
        if (hit != null && hit.params().size() == count) return hit;
        List<String> out = new ArrayList<>();
        for (int i = 0; i < count; i++) out.add(null);
        java.util.Set<String> locals = new java.util.LinkedHashSet<>();
        if (cls == null) return new MethodNames(out, locals);
        boolean resolved = false;
        try {
            MethodUnit mu = methodUnit(m.getAddress());
            IJavaSourceUnit ju = mu.ju();
            IJavaElement root = ju.getASTElement();
            if (!(root instanceof IJavaClass jc)) return new MethodNames(out, locals);
            IJavaMethod jm = findJavaMethod(jc, m);
            if (jm == null) return new MethodNames(out, locals);
            List<IJavaDefinition> ps = jm.getParameters();
            /* the AST lists the receiver first for an instance method: the formal arguments are the LAST
               count definitions; any other count means the AST's view of the prototype is not the dex's
               and the names are reported unresolved rather than shifted (fix-review 2026-10-08 L2) */
            int off = ps.size() - count;
            if (off < 0 || off > 1) return new MethodNames(out, locals);
            java.util.IdentityHashMap<IJavaIdentifier, Integer> want = new java.util.IdentityHashMap<>();
            for (int i = 0; i < count; i++) if (ps.get(off + i) != null && ps.get(off + i).getIdentifier() != null) want.put(ps.get(off + i).getIdentifier(), i);
            java.util.IdentityHashMap<IJavaIdentifier, Boolean> localIds = new java.util.IdentityHashMap<>();
            try { collectIdentifiers(jm.getBody(), want, localIds); } catch (Throwable ignored) {}
            IPropertyManager pm = ju.getPropertyManager();
            Map<String, Object> saved = setVariableRender(pm);
            JavaDocument doc = null;
            try {
                doc = new JavaDocument(ju);
                ITextDocumentPart part = completeDocumentPart(doc);
                if (part != null) {
                    java.util.List<? extends com.pnfsoftware.jeb.core.output.text.ILine> lines = part.getLines();
                    for (IObjectLocation loc : part.getObjectLocations()) {
                        if (!(loc.getObject() instanceof IJavaIdentifier ident)) continue;
                        Integer k = want.get(ident);
                        boolean local = k == null && localIds.containsKey(ident);
                        if (k == null && !local) continue;
                        if (k != null && out.get(k) != null) continue;
                        int lb = loc.getLineBegin();
                        if (lb < 0 || lb >= lines.size() || loc.getColumnEnd() <= loc.getColumnBegin()) continue;
                        String lineText = String.valueOf(lines.get(lb).getText());
                        if (loc.getColumnEnd() > lineText.length()) continue;
                        String shown = lineText.substring(loc.getColumnBegin(), loc.getColumnEnd()).trim();   // a location can span the indentation
                        if (!shown.matches("[A-Za-z_$][A-Za-z0-9_$]*")) continue;                          // not an identifier's own text: leave the slot to the AST name
                        if (k != null) out.set(k, shown); else locals.add(shown);
                    }
                }
            } finally {
                try { if (doc != null) doc.dispose(); }
                finally { restoreRender(pm, saved); }
            }
            for (Map.Entry<IJavaIdentifier, Integer> w : want.entrySet()) if (out.get(w.getValue()) == null) out.set(w.getValue(), w.getKey().getName());   // never shown in the text (unused): the AST's own name
            resolved = true;
        } catch (Throwable ignored) {}
        MethodNames res = new MethodNames(out, locals);
        if (resolved) methodNamesCache.put(key, res);
        return res;
    }

    /** The AST method of a dex method: the root class's methods, then the inner classes' (an inner or
     *  anonymous class decompiles inside its outer's source unit). */
    private static IJavaMethod findJavaMethod(IJavaClass jc, IDexMethod m) {
        for (IJavaMethod x : jc.getMethods())
            if (m.getSignature(false).equals(x.getSignature()) || m.getAddress().equals(x.getSignature())) return x;
        try {
            for (IJavaClass inner : jc.getInnerClasses()) { IJavaMethod x = findJavaMethod(inner, m); if (x != null) return x; }
        } catch (Throwable ignored) {}
        return null;
    }

    /** Every identifier object below an AST element that is not one of the parameters (the locals, by
     *  identity — the same local appears at every use). */
    private static void collectIdentifiers(IJavaElement el, java.util.IdentityHashMap<IJavaIdentifier, Integer> params, java.util.IdentityHashMap<IJavaIdentifier, Boolean> locals) {
        if (el == null) return;
        if (el instanceof IJavaClass || el instanceof IJavaMethod) return; // nested methods have their own local namespace
        if (el instanceof IJavaIdentifier id && !params.containsKey(id)) locals.put(id, Boolean.TRUE);
        List<IJavaElement> subs;
        try { subs = el.getSubElements(); } catch (Throwable t) { return; }
        if (subs != null) for (IJavaElement s : subs) collectIdentifiers(s, params, locals);
    }

    /**
     * {@inheritDoc}
     *
     * Atomic: every change is validated first (a Java identifier, not a keyword, no two changes to one
     * position, no two changes with one name, no collision with an argument that is not being changed),
     * then applied through JEB's own parameter binding ({@code IDexMethod.setParameterName}, which the
     * decompiler reads — every use in the body follows), in two phases so swaps work (all affected
     * positions to temporaries, then to their final names); a refusal mid-way rolls every position
     * back to what it was. A change whose name is null or empty is a no-op (it never erases a name);
     * `clear: true` restores the engine's own name for that position (what undo uses when the old
     * name was not an analyst's).
     */
    @Override
    public Map<String, Object> renameParameters(String methodSymbol, List<Map<String, Object>> changes, String actor) throws OpException {
        requireReady();
        synchronized (this) {
            if (discarded) throw OpException.busy("this target is being closed with its edits discarded — rename refused", "target=" + id);
            Resolved r = lookup(methodSymbol);
            if (r.kind() != Kind.METHOD) throw OpException.syntax("param.rename takes a METHOD: " + methodSymbol + " is a " + r.kind().name().toLowerCase(), "symbol=" + methodSymbol);
            IDexMethod m = findAnyMethod(r.addr());
            if (m == null) throw OpException.notFound("method not found: " + methodSymbol, "target=" + id);
            IDexClass cls = findClass(r.addr());
            IDexUnit dex = cls == null ? null : owningDex(cls.getAddress());
            int count = m.getPrototype() == null || m.getPrototype().getParameterTypes() == null ? 0 : m.getPrototype().getParameterTypes().length;
            MethodNames mn = methodNames(cls, m, count);
            List<String> current = mn.params();
            // ---- validate
            Map<Integer, String> finals = new java.util.TreeMap<>();
            java.util.Set<Integer> clears = new java.util.TreeSet<>();
            List<String> problems = new ArrayList<>();
            for (Map<String, Object> c : changes == null ? List.<Map<String, Object>>of() : changes) {
                int ix = c.get("index") instanceof Number n ? n.intValue() : c.get("index") instanceof String s && s.matches("\\d+") ? Integer.parseInt(s) : -1;
                if (ix < 0 || ix >= count) { problems.add("index " + c.get("index") + " is not a formal argument of this method (" + count + ")"); continue; }
                if (Boolean.TRUE.equals(c.get("clear"))) { clears.add(ix); continue; }
                Object nm = c.get("name");
                if (nm == null || String.valueOf(nm).isEmpty()) continue;   // a null entry is a no-op, never an erasure
                String name = String.valueOf(nm);
                if (!validAnalystName(name)) { problems.add("'" + name + "' is not a usable Java identifier" + (TypeNames.JAVA_KEYWORDS.contains(name) ? " (a keyword)" : "")); continue; }
                if (finals.containsKey(ix)) { problems.add("two changes name argument " + ix); continue; }
                if (finals.containsValue(name)) { problems.add("two arguments would be named '" + name + "'"); continue; }
                /* already the analyst's own name: not a change — not applied, not recorded (the preview said noop).
                   The SAME name on an argument the engine named (JEB's positional pick) is a change of provenance
                   the analyst asks for explicitly — adopted as theirs, recorded `adopted` (never gate the user) */
                if (name.equals(current.get(ix)) && m.getParameterName(ix) != null) continue;
                if (mn.locals().contains(name) && !current.contains(name)) { problems.add("'" + name + "' is the name of a local in this method — rename the local first, or choose another name"); continue; }
                finals.put(ix, name);
            }
            for (Map.Entry<Integer, String> e : finals.entrySet())
                for (int i = 0; i < count; i++)
                    if (i != e.getKey() && !finals.containsKey(i) && !clears.contains(i) && e.getValue().equals(current.get(i)))
                        problems.add("'" + e.getValue() + "' is already the name of argument " + i + " — swap them, or choose another name");
            if (!problems.isEmpty()) throw OpException.syntax("parameter mapping refused: " + String.join("; ", problems), "symbol=" + methodSymbol);
            if (finals.isEmpty() && clears.isEmpty()) throw OpException.syntax("nothing to change — every argument already has the proposed name", "symbol=" + methodSymbol);
            // ---- apply, three phases, rolled back on refusal: every position that gets a final name goes to a
            //      temporary, the cleared positions are cleared (an undo moves a name back onto a position whose
            //      current holder is being cleared — clearing LAST made JEB refuse it, fix-review 2026-10-08 H3),
            //      then the final names land
            Map<Integer, String> before = new java.util.TreeMap<>();
            for (int ix : finals.keySet()) before.put(ix, m.getParameterName(ix));
            for (int ix : clears) before.put(ix, m.getParameterName(ix));
            List<Object> applied = new ArrayList<>();
            try {
                for (int ix : finals.keySet())
                    if (!m.setParameterName(ix, "__wb_tmp_" + ix + "_" + System.nanoTime() % 100000, true, false)) throw new IllegalStateException("JEB refused a temporary name for argument " + ix);
                for (int ix : clears)
                    if (!m.setParameterName(ix, null, true, false)) throw new IllegalStateException("JEB refused to clear argument " + ix);
                for (Map.Entry<Integer, String> e : finals.entrySet())
                    if (!m.setParameterName(e.getKey(), e.getValue(), true, false)) throw new IllegalStateException("JEB refused '" + e.getValue() + "' for argument " + e.getKey() + " — the name is already in use in this method (a local, or an argument)");
                if (dex != null) try { dex.notifyGenericChange(); } catch (Throwable ignored) {}   // one notification for the lot
            } catch (RuntimeException ex) {
                for (Map.Entry<Integer, String> b : before.entrySet()) { try { m.setParameterName(b.getKey(), b.getValue(), false, false); } catch (Throwable ignored) {} }
                namesChanged();
                throw OpException.busy("parameter mapping not applied — nothing changed: " + ex.getMessage(), "symbol=" + methodSymbol);
            }
            for (Map.Entry<Integer, String> e : finals.entrySet()) {
                Map<String, Object> ch = new LinkedHashMap<>();
                /* the old name: the analyst's setting when there was one (what undo puts back), else the rendered one */
                ch.put("index", e.getKey()); ch.put("oldName", before.get(e.getKey()) != null ? before.get(e.getKey()) : current.get(e.getKey())); ch.put("newName", e.getValue());
                ch.put("oldSource", before.get(e.getKey()) != null ? "analyst" : "engine");
                if (before.get(e.getKey()) == null && e.getValue().equals(current.get(e.getKey()))) ch.put("adopted", true);   // the engine's pick, now the analyst's
                applied.add(ch);
            }
            for (int ix : clears) {
                Map<String, Object> ch = new LinkedHashMap<>();
                ch.put("index", ix); ch.put("oldName", before.get(ix) != null ? before.get(ix) : current.get(ix)); ch.put("cleared", true);
                applied.add(ch);
            }
            dirty = true;
            namesChanged();
            if (cls != null) searchIndex.invalidate(cls.getAddress(), cls.getAddress());
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("symbol", Map.of("addr", r.addr(), "kind", "method", "name", r.name()));
            out.put("addr", r.addr());
            out.put("newAddr", m.getAddress());
            out.put("kind", "parameters");
            out.put("changes", applied);
            out.put("applied", true);
            out.put("actor", actor == null ? "human" : actor);
            return out;
        }
    }

    /**
     * {@inheritDoc}
     *
     * JEB accepts any string as a name and resets to the original on null; the dex's own name is the
     * one thing this path restores, so the check is against the dex, not the identifier rule an
     * analyst's new name must pass ("1cq" is a fine original and a refused new name).
     */
    /** The classes referencing an item, captured BEFORE a rename shifts its addr: the reference manager
     *  resolves CURRENT or ORIGINAL handles only, so after a rename the pre-rename addr is dead (an
     *  intermediate name in a rename-back chain is neither) and this lookup would fail. A lookup that
     *  fails on an exotic handle leaves the set short: the owner refresh still happens, and stale
     *  referrer text heals on the next full reindex. */
    private java.util.Set<String> referrerClasses(String addr) {
        java.util.Set<String> refClasses = new java.util.HashSet<>();
        try {
            for (Object ref : xrefsTo(addr)) {
                if (!(ref instanceof Map<?, ?> rm) || rm.get("from") == null) continue;
                String site = String.valueOf(rm.get("from"));
                int plus = site.indexOf('+');
                if (plus >= 0) site = site.substring(0, plus);
                refClasses.add(classAddrOf(site));
            }
        } catch (OpException ignored) {}
        return refClasses;
    }

    @Override
    public Map<String, Object> restoreOriginalName(String symbol, String expectedOriginal, String actor) throws OpException {
        requireReady();
        synchronized (this) {
            if (discarded) throw OpException.busy("this target is being closed with its edits discarded — rename refused", "target=" + id);
            Resolved r = lookup(symbol);
            String addr = r.addr();
            java.util.Set<String> refClasses = referrerClasses(addr);   // the referencing classes' text carries the name too (fix-review 2026-10-08 M2)
            String old = null, orig = null, newAddr = null;
            boolean done = false, wasRenamed = false;
            for (IDexUnit dex : dexUnits) {
                com.pnfsoftware.jeb.core.units.code.android.dex.IDexItem item = switch (r.kind()) {
                    case CLASS -> dex.getClass(addr);
                    case METHOD -> dex.getMethod(addr);
                    case FIELD -> dex.getField(addr);
                };
                if (item == null) continue;
                old = item.getName(true); orig = item.getName(false); wasRenamed = item.isRenamed();
                if (expectedOriginal != null && !expectedOriginal.equals(orig))
                    throw OpException.syntax("the recorded original name '" + expectedOriginal + "' is not the dex's own for " + addr + " (that is '" + orig + "') — not restored", "symbol=" + symbol);
                if (wasRenamed) {
                    if (!item.setName(orig) || item.isRenamed()) {
                        item.setName(null);   // JEB's reset
                        if (item.isRenamed()) throw OpException.busy("JEB would not restore the original name '" + orig + "' of " + addr, "symbol=" + symbol);
                    }
                }
                newAddr = switch (r.kind()) {
                    case CLASS -> dex.getClass(item.getSignature(false)) != null ? dex.getClass(item.getSignature(false)).getAddress() : item.getSignature(true);
                    case METHOD -> dex.getMethod(item.getSignature(false)) != null ? dex.getMethod(item.getSignature(false)).getAddress() : item.getSignature(true);
                    case FIELD -> dex.getField(item.getSignature(false)) != null ? dex.getField(item.getSignature(false)).getAddress() : item.getSignature(true);
                };
                done = true;
                break;
            }
            if (!done) throw OpException.notFound(r.kind().name().toLowerCase() + " not found: " + addr, "target=" + id);
            if (wasRenamed) {
                dirty = true;
                classifiedStrings = null;
                namesChanged();
                String freshClass = classAddrOf(newAddr == null ? addr : newAddr);
                searchIndex.invalidate(classAddrOf(addr), freshClass);
                refClasses.remove(freshClass);
                searchIndex.invalidateAll(refClasses);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("symbol", Map.of("addr", addr, "kind", r.kind().name().toLowerCase(), "name", r.name()));
            out.put("addr", addr);
            out.put("newAddr", newAddr == null ? addr : newAddr);
            out.put("kind", r.kind().name().toLowerCase());
            out.put("oldName", old);
            out.put("newName", orig);
            out.put("applied", wasRenamed);
            out.put("restored", true);
            if (!wasRenamed) out.put("note", "already the original name — nothing changed");
            out.put("actor", actor == null ? "human" : actor);
            return out;
        }
    }

    /** One occurrence of a local in a method's decompiled text: the name as shown, the AST
     *  identifier's own name (its original), the declared type signature, JEB's text-item id. */
    private record LocalRef(String shown, String ident, String type, long itemId, int line, int column, Integer parameterIndex,
                            IJavaIdentifier identifier) {}

    private static final String NO_LOCAL_RENAME = "JEB generated this identifier without an actionable item; "
            + "its public API does not support persisting a rename. Keep an unresolved naming assessment when a better name is needed.";

    private static OpException unavailableLocal(LocalRef ref, String method) {
        OpException error = OpException.unsupported(NO_LOCAL_RENAME, "method=" + method + ", local=" + ref.shown());
        error.extra.put("reason", "JEB_NO_ACTIONABLE_ITEM");
        error.extra.put("local", ref.shown()); error.extra.put("method", method);
        error.extra.put("renameableLocal", false);
        return error;
    }

    /** Every identifier occurrence inside a method, in text order (declarations first by nature). */
    private List<LocalRef> localRefs(IJavaSourceUnit ju, IDexClass cls, String methodAddr) {
        List<LocalRef> out = new ArrayList<>();
        IDexMethod method = findAnyMethod(methodAddr);
        if (method == null || !(ju.getASTElement() instanceof IJavaClass root)) return out;
        IJavaMethod jm = findJavaMethod(root, method);
        if (jm == null) return out;
        int count = countParams(method.getSignature(false));
        List<IJavaDefinition> definitions = jm.getParameters();
        int offset = definitions.size() - count;
        if (offset < 0 || offset > 1) return out; // do not shift an uncertain argument mapping
        java.util.IdentityHashMap<IJavaIdentifier,Integer> parameters = new java.util.IdentityHashMap<>();
        for (int i = 0; i < count; i++) {
            IJavaDefinition definition = definitions.get(offset + i);
            if (definition != null && definition.getIdentifier() != null) parameters.put(definition.getIdentifier(), i);
        }
        java.util.IdentityHashMap<IJavaIdentifier,Boolean> identifiers = new java.util.IdentityHashMap<>();
        collectIdentifiers(jm.getBody(), parameters, identifiers);
        // Keep the receiver in the inventory (existing contract), with no formal index.
        if (offset == 1 && definitions.get(0) != null && definitions.get(0).getIdentifier() != null)
            identifiers.put(definitions.get(0).getIdentifier(), Boolean.TRUE);
        IPropertyManager pm = ju.getPropertyManager();
        Map<String, Object> savedProps = setVariableRender(pm);
        JavaDocument doc = null;
        try {
            doc = new JavaDocument(ju);
            ITextDocumentPart part = completeDocumentPart(doc);
            java.util.List<? extends com.pnfsoftware.jeb.core.output.text.ILine> lines = part == null ? List.of() : part.getLines();
            if (part != null) for (IObjectLocation loc : part.getObjectLocations()) {
                if (!(loc.getObject() instanceof IJavaIdentifier ident)) continue;
                if (!parameters.containsKey(ident) && !identifiers.containsKey(ident)) continue;
                int lb = loc.getLineBegin();
                if (lb < 0 || lb >= lines.size() || loc.getLineEnd() != lb) continue;
                if (loc.getColumnBegin() < 0 || loc.getColumnEnd() <= loc.getColumnBegin()) continue;
                String lineText = String.valueOf(lines.get(lb).getText());
                if (loc.getColumnEnd() > lineText.length()) continue;
                int start = loc.getColumnBegin(), end = loc.getColumnEnd();
                while (start < end && Character.isWhitespace(lineText.charAt(start))) start++;
                while (end > start && Character.isWhitespace(lineText.charAt(end - 1))) end--;
                String shown = lineText.substring(start, end);
                Long itemId = null;
                for (com.pnfsoftware.jeb.core.output.text.ITextItem it : lines.get(lb).getItems())
                    if (it.getOffset() == start && it.getLength() == end - start
                            && it instanceof com.pnfsoftware.jeb.core.output.IActionableItem ai) { itemId = ai.getItemId(); break; }
                if (itemId == null || shown.isEmpty()) continue;
                String type = null;
                try { type = ident.getType() == null ? null : currentTypeSig(ident.getType().getSignature()); } catch (Exception ignored) {}
                out.add(new LocalRef(shown, ident.getName(), type, itemId, lb + 1, start, parameters.get(ident), ident));
            }
        } finally {
            try { if (doc != null) doc.dispose(); }
            finally { restoreRender(pm, savedProps); }
        }
        out.sort(java.util.Comparator.comparingInt(LocalRef::line).thenComparingInt(LocalRef::column));
        return out;
    }

    /** A type signature under CURRENT names: getSignature() answers the original (Lm76/abc; for a
     *  class shown as SuffixTreeNode) — the local's name and its binding must follow the name on
     *  screen. Arrays map their element. */
    private String currentTypeSig(String sig) {
        if (sig == null) return null;
        if (sig.startsWith("[")) { String inner = currentTypeSig(sig.substring(1)); return inner == null ? null : "[" + inner; }
        if (!sig.startsWith("L")) return sig;
        String cur = currentAddrOf(sig);
        return cur != null ? cur : sig;
    }

    /** JEB's RENAME action on one identifier item; false when JEB refuses (a taken name). */
    private boolean renameIdentifier(IJavaSourceUnit ju, long itemId, String newName) throws OpException {
        ActionContext ctx = new ActionContext(ju, Actions.RENAME, itemId, null);
        ActionRenameData data = new ActionRenameData();
        if (!ju.prepareExecution(ctx, data))
            throw OpException.unsupported("JEB offers no rename for that identifier", "item=" + itemId);
        data.setNewName(newName);
        try { return ju.executeAction(ctx, data); } catch (Exception e) { throw OpException.busy("rename refused by JEB: " + e.getMessage(), "item=" + itemId); }
    }

    private OpException localRenameRefused(MethodUnit mu, LocalRef selected, String wanted) {
        Map<String,Map<String,Object>> found = new LinkedHashMap<>();
        String originalMethod = mu.r().addr(), diagnosticError = null;
        try {
            IDexMethod method = findAnyMethod(mu.r().addr());
            if (method != null) originalMethod = method.getSignature(false);
            for (LocalRef other : localRefs(mu.ju(), mu.cls(), mu.r().addr())) {
                if (other.itemId() == selected.itemId() || !wanted.equals(other.shown())) continue;
                String itemId = Long.toUnsignedString(other.itemId());
                String identity = originalMethod + "#local:" + itemId;
                Map<String,Object> row = new LinkedHashMap<>();
                row.put("kind", "local"); row.put("originalSymbol", identity);
                row.put("originalMethod", originalMethod); row.put("itemId", itemId);
                row.put("addr", mu.r().addr()); row.put("currentName", other.shown());
                row.put("originalName", other.ident()); row.put("line", other.line());
                row.put("match", "same_method_identifier");
                found.putIfAbsent(identity, row);
            }
            // A field can also reserve a source identifier. Include inherited fields, without a depth cap.
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (IDexClass owner = mu.cls(); owner != null && seen.add(owner.getSignature(false));
                    owner = findAnyClass(owner.getSupertypeSignature(false))) {
                for (IDexField field : owner.getFields()) if (wanted.equals(field.getName(true)))
                    found.put(field.getSignature(false), nameOccupant(field, "field", "same_name_field"));
                if (owner.getSupertypeSignature(false) == null) break;
            }
        } catch (RuntimeException error) { diagnosticError = error.toString(); }
        return renameRefused(selected.shown(), wanted,
                originalMethod + "#local:" + Long.toUnsignedString(selected.itemId()), mu.r().addr(),
                new ArrayList<>(found.values()), diagnosticError);
    }

    private record MethodUnit(Resolved r, IDexClass cls, IJavaSourceUnit ju) {}
    private MethodUnit methodUnit(String methodSymbol) throws OpException {
        Resolved r = lookup(methodSymbol);
        if (r.kind() != Kind.METHOD)
            throw OpException.syntax("a local lives in a METHOD: pass the method symbol, got a " + r.kind().name().toLowerCase(), "symbol=" + methodSymbol);
        IDexClass cls = findClass(r.addr());
        if (cls == null) throw OpException.notFound("class not found for: " + methodSymbol, "target=" + id);
        IDexUnit dex = owningDex(cls.getAddress());
        IDexDecompilerUnit dec = dex == null ? null : decompilers.get(dex);
        if (dec == null) throw OpException.unsupported("no decompiler for unit holding " + methodSymbol, "target=" + id);
        ISourceUnit su;
        try { su = dec.decompileToUnit(cls.getAddress()); } catch (Exception e) { throw OpException.io("decompile failed: " + e.getMessage(), "target=" + id); }
        if (!(su instanceof IJavaSourceUnit ju)) throw OpException.unsupported("no Java source unit for " + cls.getAddress(), "target=" + id);
        return new MethodUnit(r, cls, ju);
    }

    private LocalRef selectedLocal(MethodUnit mu, String local) throws OpException {
        List<LocalRef> refs = localRefs(mu.ju(), mu.cls(), mu.r().addr());
        for (LocalRef ref : refs) if (local.equals(ref.shown())) return ref;
        for (LocalRef ref : refs) if (local.equals(ref.ident())) return ref;
        throw OpException.notFound("no local named '" + local + "' in " + mu.r().addr()
                + " — use the name shown by the decompiler", "target=" + id);
    }

    @Override
    public Map<String, Object> resolveLocal(String methodSymbol, String local) throws OpException {
        requireReady();
        if (local == null || local.isBlank()) throw OpException.syntax("local names the variable to resolve", "symbol=" + methodSymbol);
        synchronized (this) {
            MethodUnit mu = methodUnit(methodSymbol);
            LocalRef ref = selectedLocal(mu, local);
            if (ref.itemId() == 0) throw unavailableLocal(ref, mu.r().addr());
            IDexMethod method = findAnyMethod(mu.r().addr());
            String originalMethod = method == null ? mu.r().addr() : method.getSignature(false);
            return Map.of("name", ref.shown(), "originalMethod", originalMethod,
                    "originalSymbol", originalMethod + "#local:" + Long.toUnsignedString(ref.itemId()));
        }
    }

    /** Rename a local variable or parameter of a method — JEB's RENAME action on the identifier's
     *  text item in the class's Java source unit (what the JEB client does from its Java view; the
     *  new name follows the identifier's definition, so every use in the method changes, and it
     *  persists with the project). The local is named by its CURRENT name inside the method. */
    @Override
    public Map<String, Object> renameLocal(String methodSymbol, String local, String newName, String actor) throws OpException {
        requireReady();
        if (newName == null || !newName.matches("[A-Za-z_$][A-Za-z0-9_$]*"))
            throw OpException.syntax("bad Java identifier: " + newName, "symbol=" + methodSymbol);
        if (local == null || local.isBlank())
            throw OpException.syntax("local names the variable to rename (its current name)", "symbol=" + methodSymbol);
        synchronized (this) {
            if (discarded) throw OpException.busy("this target is being closed with its edits discarded — rename refused", "target=" + id);
            MethodUnit mu = methodUnit(methodSymbol);
            LocalRef selected = selectedLocal(mu, local);
            long itemId = selected.itemId();
            if (itemId == 0) throw unavailableLocal(selected, mu.r().addr());
            if (newName.equals(selected.shown())) {
                Map<String,Object> out = new LinkedHashMap<>();
                out.put("addr", mu.r().addr()); out.put("newAddr", mu.r().addr());
                out.put("kind", "local"); out.put("local", local);
                out.put("oldName", selected.shown()); out.put("newName", newName);
                out.put("applied", false); out.put("unchanged", true);
                out.put("reason", "This variable already has this name.");
                return out;
            }
            boolean applied;
            try { applied = renameIdentifier(mu.ju(), itemId, newName); }
            catch (OpException refusal) {
                if (!"E_BUSY".equals(refusal.code)) throw refusal;
                OpException diagnostic = localRenameRefused(mu, selected, newName);
                diagnostic.extra.put("jebReason", refusal.getMessage());
                throw diagnostic;
            }
            if (!applied) throw localRenameRefused(mu, selected, newName);
            dirty = true;
            searchIndex.invalidate(mu.cls().getAddress(), mu.cls().getAddress());
            namesChanged();
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("symbol", Map.of("addr", mu.r().addr(), "kind", "method", "name", mu.r().name()));
            out.put("addr", mu.r().addr());
            out.put("newAddr", mu.r().addr());
            out.put("kind", "local");
            out.put("local", local);
            out.put("oldName", selected.shown());
            out.put("newName", newName);
            out.put("applied", true);
            out.put("actor", actor == null ? "human" : actor);
            return out;
        }
    }

    /** Locals as displayed; actionable IDs match resolveLocal, generated ones have inventory-only labels. */
    @Override
    public List<Object> locals(String methodSymbol) throws OpException {
        requireReady();
        synchronized (this) {
            MethodUnit mu = methodUnit(methodSymbol);
            IDexMethod method = findAnyMethod(mu.r().addr());
            String originalMethod = method == null ? mu.r().addr() : method.getSignature(false);
            Map<String, Map<String, Object>> byIdentity = new LinkedHashMap<>();
            // Zero is JEB's "no item" sentinel, not one shared variable. Keep every
            // distinct AST identifier. Ordinals are inventory labels, never native
            // rename targets; preserve the legacy first #local:0 label for checkpoints.
            java.util.IdentityHashMap<IJavaIdentifier, String> generated = new java.util.IdentityHashMap<>();
            for (LocalRef ref : localRefs(mu.ju(), mu.cls(), mu.r().addr())) {
                String itemId = Long.toUnsignedString(ref.itemId());
                String suffix = itemId;
                if (ref.itemId() == 0) {
                    suffix = generated.get(ref.identifier());
                    if (suffix == null) {
                        int ordinal = generated.size() + 1;
                        suffix = ordinal == 1 ? "0" : "0:rendered:" + ordinal;
                        generated.put(ref.identifier(), suffix);
                    }
                }
                if (byIdentity.containsKey(suffix)) continue;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("name", ref.shown());
                if (ref.type() != null) row.put("type", ref.type());
                row.put("line", ref.line());
                // String encoding preserves every bit when a JavaScript client parses JSON.
                row.put("originalMethod", originalMethod);
                row.put("originalSymbol", originalMethod + "#local:" + suffix);
                row.put("originalName", ref.ident());
                row.put("itemId", itemId);
                row.put("renameableLocal", ref.itemId() != 0);
                if (ref.itemId() == 0) {
                    row.put("identityKind", "rendered-only");
                    row.put("renameUnavailableReason", NO_LOCAL_RENAME);
                }
                if (ref.parameterIndex() != null) row.put("parameterIndex", ref.parameterIndex());
                byIdentity.put(suffix, row);
            }
            return new ArrayList<>(byIdentity.values());
        }
    }

    /** Name a method's locals after their declared types: `DownloadHelper v5` → `downloadHelper`,
     *  a second of the same type `downloadHelper_2` … Only generated names (v5, v1_5, arg2) unless
     *  all is set; primitives keep their names (an int is not its type). Returns
     *  {addr, renames:[{local, name, type}], skipped:[{local, why}]}. Each applied rename is one
     *  JEB RENAME action; the caller records History and the type→local bindings. */
    @Override
    public Map<String, Object> autoNameLocals(String methodSymbol, boolean all, String actor) throws OpException {
        requireReady();
        synchronized (this) {
            if (discarded) throw OpException.busy("this target is being closed with its edits discarded — rename refused", "target=" + id);
            MethodUnit mu = methodUnit(methodSymbol);
            List<Object> renames = new ArrayList<>(), skipped = new ArrayList<>();
            java.util.Set<String> used = new java.util.HashSet<>();
            // the variables, first occurrence each, and every name in use
            Map<String, LocalRef> vars = new LinkedHashMap<>();
            for (LocalRef ref : localRefs(mu.ju(), mu.cls(), mu.r().addr())) {
                used.add(ref.shown());
                vars.putIfAbsent(ref.shown(), ref);
            }
            for (LocalRef v : vars.values()) {
                if ("this".equals(v.shown())) continue;
                if (v.itemId() == 0) { skipped.add(Map.of("local", v.shown(), "why", NO_LOCAL_RENAME)); continue; }
                if (!all && !GENERATED_LOCAL.matcher(v.shown()).matches()) continue;   // a name somebody chose stays
                String base = TypeNames.nameForType(v.type());
                if (base == null) { skipped.add(Map.of("local", v.shown(), "why", v.type() == null ? "no type" : "primitive " + v.type())); continue; }
                if (base.equals(v.shown())) continue;
                String pick = null;
                for (int n = 1; n <= 20 && pick == null; n++) {
                    String cand = n == 1 ? base : base + "_" + n;
                    if (used.contains(cand)) continue;
                    // the item id is re-read for every rename: the document re-renders after each action
                    long itemId = -1;
                    for (LocalRef ref : localRefs(mu.ju(), mu.cls(), mu.r().addr()))
                        if (v.shown().equals(ref.shown())) { itemId = ref.itemId(); break; }
                    if (itemId < 0) break;
                    if (renameIdentifier(mu.ju(), itemId, cand)) pick = cand;
                    else used.add(cand);                                       // JEB knows a conflict we do not (a field, a keyword): next suffix
                }
                if (pick == null) { skipped.add(Map.of("local", v.shown(), "why", "no free name for " + base)); continue; }
                used.add(pick);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("local", v.shown());
                row.put("name", pick);
                row.put("type", v.type());
                renames.add(row);
            }
            if (!renames.isEmpty()) {
                dirty = true;
                searchIndex.invalidate(mu.cls().getAddress(), mu.cls().getAddress());
            namesChanged();
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("addr", mu.r().addr());
            out.put("symbol", Map.of("addr", mu.r().addr(), "kind", "method", "name", mu.r().name()));
            out.put("renames", renames);
            out.put("skipped", skipped);
            out.put("actor", actor == null ? "human" : actor);
            return out;
        }
    }

    private static final java.util.regex.Pattern GENERATED_LOCAL =
            java.util.regex.Pattern.compile("(v|arg|p|var|local)\\d+(_\\d+)?");

    /** Inline comment on a class/method/field (JEB comment manager → persisted in .jdb2). */
    @Override
    public Map<String, Object> comment(String symbol, String text, String actor) throws OpException {
        requireReady();
        synchronized (this) {
            if (discarded) throw OpException.busy("this target is being closed with its edits discarded — comment refused", "target=" + id);
            /* a CALL SITE: "Lcls;->m(desc)ret+66h" (the `from` of an xref row) — the comment goes on
               that instruction and the decompiler prints it above the statement (the "annotate call"
               mission, user 2026-09-20); a plain member gets its inline comment as before */
            String site = null;
            java.util.regex.Matcher sm = java.util.regex.Pattern.compile("^(.*->[^+]+)\\+([0-9A-Fa-f]+h?)$").matcher(symbol.trim());
            if (sm.matches()) { symbol = sm.group(1); site = sm.group(2); }
            Resolved r = lookup(symbol);
            String at = site == null ? r.addr() : r.addr() + "+" + site;
            IDexUnit dex = owningDex(r.addr());
            if (dex == null) throw OpException.notFound("nothing found at: " + symbol, "target=" + id);
            if (site != null && text != null && !text.isBlank()) at = placeSiteComment(dex, r.addr(), site, text);
            String oldText = dex.getCommentManager().getInlineComment(at);
            boolean ok = dex.getCommentManager().setInlineComment(at, text == null ? "" : text);
            if (ok) {
                dirty = true;
                // comments live in the decompiled text → same per-class refresh as rename
                searchIndex.invalidate(classAddrOf(r.addr()), classAddrOf(r.addr()));
                /* JEB refreshes the decompiled text of a class on a RENAME event but not when a
                   comment lands on the dex comment manager — the Explorer kept the pre-comment text
                   (user, 2026-09-12). A generic change notification on the dex unit makes the Java
                   unit re-render from the AST it already has: no re-decompilation. */
                dex.notifyGenericChange();
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("symbol", Map.of("addr", r.addr(), "kind", r.kind().name().toLowerCase(), "name", r.name()));
            out.put("addr", at);
            if (site != null) out.put("site", true);
            out.put("text", text == null ? "" : text);
            out.put("oldText", oldText == null ? "" : oldText);
            out.put("applied", ok);
            out.put("actor", actor == null ? "human" : actor);
            return out;
        }
    }

    /** Where a call-site comment renders. JEB prints an instruction comment on the statement the
     *  instruction belongs to, keyed by the statement's LAST instruction (probe sweep on the fixture,
     *  2026-09-20: `x = (T) a.get(k)` takes its comment at the check-cast, `foo();` at the invoke,
     *  `return` at the return; an `if` condition takes none). An invoke whose result is assigned or
     *  cast therefore shows nothing at its own address — and a walk BACK from it lands on the
     *  previous statement (WhatsApp: the runDownloadLoop call's comment sat three lines up, on
     *  `new LongBox()`). So: try the site, then forward through the statement's tail, then back;
     *  accept the first address whose rendered line also names the callee (a forward hit without it
     *  is the next statement — stop there), else the first that renders at all. Returns the address
     *  to comment. */
    private String placeSiteComment(IDexUnit dex, String methodAddr, String site, String text) {
        List<String> offsets = new ArrayList<>();
        List<String> insns = new ArrayList<>();
        try {
            Map<String, Object> sm = smali(methodAddr);
            for (Object o : (List<?>) sm.get("rows")) {
                if (!(o instanceof Map<?, ?> row)) continue;
                Object off = row.get("offset");
                if (off != null && !String.valueOf(off).isBlank()) {
                    offsets.add(String.valueOf(off).toLowerCase());
                    insns.add(String.valueOf(row.get("text")).trim());
                }
            }
        } catch (Exception e) { return methodAddr + "+" + site; }
        String want = site.toLowerCase().replaceAll("h$", "");
        while (want.length() < 4) want = "0" + want;
        int at = offsets.indexOf(want);
        if (at < 0) return methodAddr + "+" + site;
        String callee = calleeNameOf(insns.get(at));
        String probe = "JEEVES_SITE_PROBE_" + Integer.toHexString(text.hashCode());   // a marker no source line carries
        List<Integer> order = new ArrayList<>();
        order.add(at);
        for (int i = at + 1; i < offsets.size() && i - at <= 6; i++) order.add(i);
        for (int i = at - 1; i >= 0 && at - i <= 12; i--) order.add(i);
        String fallback = null;
        for (int i : order) {
            String addr = methodAddr + "+" + offsets.get(i) + "h";
            try {
                String prev = dex.getCommentManager().getInlineComment(addr);
                if (prev != null && !prev.isEmpty() && i != at) continue;   // never displace another comment while walking
                dex.getCommentManager().setInlineComment(addr, probe);
                Map<String, Object> d = decompile(methodAddr, Map.of("scope", "method"));
                String line = lineWith(String.valueOf(d.get("text")), probe);
                dex.getCommentManager().setInlineComment(addr, prev == null ? "" : prev);
                if (line == null) continue;
                boolean named = callee == null || line.contains(callee);
                if (named) return addr;
                if (i > at) break;                 // rendered past the statement: the tail is exhausted, look back
                if (fallback == null) fallback = addr;
            } catch (Exception ignored) {}
        }
        return fallback != null ? fallback : methodAddr + "+" + site;
    }

    /** The name a rendered call line shows for an instruction: `foo` for `invoke-* Lc;->foo(…)`, the
     *  class's simple name for a constructor, a field's name for a field access, null otherwise. */
    static String calleeNameOf(String insn) {
        if (insn == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^invoke-\\S+\\s+(?:\\{[^}]*\\},\\s*)?(L[^;]+;)->([^(\\s]+)\\(").matcher(insn);
        if (m.find()) {
            String name = m.group(2);
            if (!"<init>".equals(name)) return name;
            String cls = m.group(1);
            int cut = Math.max(cls.lastIndexOf('/'), cls.lastIndexOf('$'));
            return cls.substring(cut + 1, cls.length() - 1);
        }
        m = java.util.regex.Pattern.compile("^[is](?:get|put)\\S*\\s+.*?L[^;]+;->([^:\\s]+):").matcher(insn);
        if (m.find()) return m.group(1);
        return null;
    }

    private static String lineWith(String text, String marker) {
        for (String l : text.split("\\n")) if (l.contains(marker)) return l;
        return null;
    }

    // ------------------------------------------------------------ persistence / close

    /** Preserve JEB's native persistence path and lock. Saving to a temporary filename changes
     * the SDK's lock ownership, so a verified checkpoint protects its in-place writer instead. */
    private boolean persistProjectWithCheckpoint() throws Exception {
        return dev.workbench.engine.CheckpointedFileSave.write(jdb2.toPath(), destination ->
                engines.saveProject(project.getKey(), destination.toString(), null, null));
    }

    /** Persist the project (renames + comments) to the .jdb2 — native persistence. */
    /* JEB rewrites its native path. A verified fsynced checkpoint protects the previous save;
       progress still reads the current file while this engine's monitor is held. */
    private volatile long saveStartedAt;
    private volatile long saveExpectBytes;
    /* the outcome of the most recent save ({ok, at, ms, bytes, error?}) — an asynchronous save
       (target.save {async}: the MCP path, whose clients time out at ~2 min while a 2 GB jdb2 takes
       longer) is polled through saveStatus, and "not saving any more" alone does not say whether it
       wrote (Kimi researcher, 2026-09-21: "success and failure indistinguishable") */
    private volatile Map<String, Object> lastSave;

    @Override
    public Map<String, Object> saveStatus() {
        long started = saveStartedAt;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("saving", started > 0);
        if (started > 0) {
            out.put("startedAt", started);
            out.put("elapsedMs", System.currentTimeMillis() - started);
            out.put("bytesNow", jdb2 != null && jdb2.isFile() ? jdb2.length() : 0L);
            out.put("bytesExpected", saveExpectBytes);
            out.put("jdb2", jdb2 == null ? null : jdb2.getName());
        }
        if (lastSave != null) out.put("last", lastSave);
        return out;
    }

    private void recordSave(boolean ok, long startedAt, String error) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", ok);
        m.put("at", System.currentTimeMillis());
        m.put("ms", startedAt > 0 ? System.currentTimeMillis() - startedAt : 0);
        m.put("bytes", jdb2 != null && jdb2.isFile() ? jdb2.length() : 0L);
        if (error != null) m.put("error", error);
        lastSave = m;
    }

    @Override
    public synchronized Map<String, Object> save() throws OpException {
        long t0 = System.currentTimeMillis();
        try {
            requireReady();
            if (discarded)   // a save racing a discard-close must not write the edits the user just dropped (review c7395d2 A1)
                throw OpException.busy("this target is being closed with its edits discarded — save refused", "target=" + id);
            if (jdb2 == null)
                throw OpException.unsupported("no .jdb2 path configured; nothing to save to", "target=" + id);
            if (replayPending)
                throw OpException.busy("this target's edit history is still being re-applied — it is saved when that is done", "target=" + id);
            saveExpectBytes = jdb2.isFile() ? jdb2.length() : 0L;
            saveStartedAt = t0;
            /* same context-mutation serialization as doOpen/close: a target.save
               must never run saveProject concurrently with another target's
               load/create (review — the commit promised ALL lifecycle calls) */
            synchronized (OPEN_LOCK) {
                try { requireRegistered("save"); }
                catch (IllegalStateException e) { throw OpException.busy(e.getMessage(), "target=" + id); }
                for (IDexUnit dex : dexUnits) dex.notifyGenericChange();   // flush rename/comment metadata
                boolean ok = persistProjectWithCheckpoint();
                /* saved:false with ok:true made a torn registry indistinguishable
                   from a successful save — edits silently lost forever while the
                   GUI flashed success (review F1, observed live: mtime unchanged
                   across repeated 'saves'). A failed save is an ERROR, loudly. */
                if (!ok)
                    throw OpException.busy("saveProject refused the write — the jdb2 was NOT updated"
                            + " (the registry entry is torn; retries refuse for the life of this"
                            + " engine — verified live). Close and reopen the target: your edits"
                            + " replay from history on open", "target=" + id);
                dirty = false;
                clearNewDecomp();
                recordKey(jdb2, project.getKey());
                String h = sha1HexFile(jdb2);
                if (h != null) contentHashes.add(h);                     // the save-chain (review #4/F4)
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("saved", true);
                out.put("jdb2", jdb2.getAbsolutePath());
                out.put("bytes", jdb2.isFile() ? jdb2.length() : 0);
                /* the witness result rides the response — stderr is invisible to
                   API callers (round-14 F7) */
                out.put("witnessOk", searchIndex.recordJdb2Mtime(jdb2.lastModified()));
                recordSave(true, t0, null);
                return out;
            }
        } catch (OpException e) {
            recordSave(false, t0, e.getMessage());
            throw e;
        } catch (Exception e) {
            recordSave(false, t0, String.valueOf(e));
            throw OpException.busy("save failed: " + e, "target=" + id);
        } finally {
            saveStartedAt = 0;
        }
    }

    public boolean isDirty() { return dirty; }

    /** The user chose DISCARD in the close dialog: drop the dirty flag so close() skips
     *  the save. The Workspace marks the unsaved history entries discarded so they do
     *  not replay on the next open — together, "no" means no. */
    @Override
    public synchronized void discardUnsaved() { discarded = true; dirty = false; clearNewDecomp(); }
    private void clearNewDecomp() { newDecomp.clear(); newDecompCount = 0; decompDirty = false; }
    @Override
    public int unsavedDecompilations() { return newDecompCount; }

    @Override
    public File persistenceFile() {
        return jdb2;
    }

    /** Save (when dirty) and unload the project, releasing the .jdb2 lock. */
    @Override
    public synchronized void close() {
        if ("closed".equals(state) || closing) return;
        /* visible to doOpen's stage checks BEFORE we contend OPEN_LOCK — a
           mid-analysis close must not queue behind the whole load and then
           watch the "closed" target finish writing its jdb2 (review R3) */
        closing = true;
        /* kill(), not cancel() (finding 1 r36): the dead flag defeats a racing
           search.reindex — start() refuses instead of resetting cancel=false
           over this call and building against the engine being torn down */
        searchIndex.kill();
        RuntimeException closeFailure = null;
        try {
            if (ready && (dirty || decompDirty) && !discarded && !replayPending && jdb2 != null) {   // !replayPending: a half-replayed project is never written (see the field); !discarded: a DISCARD close never writes, even if a racing edit re-set dirty in the gap (review c7395d2 A1)
                long started = System.currentTimeMillis();
                try {
                    boolean saved;
                    synchronized (OPEN_LOCK) {   // same context-mutation serialization as doOpen
                        for (IDexUnit dex : dexUnits) dex.notifyGenericChange();
                        /* saveProject goes by KEY: when the key names another project (isRegistered),
                           "saving this target" would write that other project into this file */
                        saved = isRegistered(project) && persistProjectWithCheckpoint();
                        if (saved) {
                            recordKey(jdb2, project.getKey());
                            String h = sha1HexFile(jdb2);
                            if (h != null) contentHashes.add(h);         // the save-chain (review #4/F4)
                        }
                    }
                    if (saved) {
                        dirty = false;
                        clearNewDecomp();
                        searchIndex.recordJdb2Mtime(jdb2.lastModified());  // foreign-edit witness
                        recordSave(true, started, null);
                    } else {
                        closeFailure = new IllegalStateException("close-save refused for " + name + "; database unchanged; accepted edits remain in history");
                        recordSave(false, started, closeFailure.getMessage());
                    }
                } catch (Exception failure) {
                    closeFailure = new IllegalStateException("close-save failed for " + name + "; accepted edits remain in history", failure);
                    recordSave(false, started, failure.toString());
                }
            }
            /* snapshot the reference and unload under ONE lock hold — between a
               null-check outside and a getKey() inside, abortIfClosing can null
               it: the NPE became the false 'lock may be stale' alarm on every
               routine mid-analysis close (review #8) */
            synchronized (OPEN_LOCK) {
                IRuntimeProject prj = project;
                project = null; inputIdentity = null;
                if (prj != null) unloadOrLog(prj, "close");
            }
        } finally {
            ready = false;
            state = "closed";
            project = null; inputIdentity = null;
            liveKey = null;
            LIVE.remove(this);
            dexUnits.clear();
            decompilers.clear();
            callGraphs.clear();
            codeStrings = null;
            subtypeIndex = null;
        }
        if (closeFailure != null) throw closeFailure;
    }

    // ------------------------------------------------------------ forward call graph (lean, rename-safe)

    /** Lean, rename-safe forward call graph: vertexId→callee vertexIds + index↔vid maps.
     *  Captured once at open while addresses are original; method-pool index is stable
     *  across renames, and IDexMethod objects reflect renames live. */
    private static final class CallGraph {
        final Map<Integer, int[]> callees;          // vertexId -> callee vertexIds (forward edges)
        final Map<Integer, Integer> indexToVid;     // method-pool index -> vertexId
        final Map<Integer, IDexMethod> vidToMethod; // vertexId -> the live method
        CallGraph(Map<Integer, int[]> c, Map<Integer, Integer> i, Map<Integer, IDexMethod> v) {
            callees = c;
            indexToVid = i;
            vidToMethod = v;
        }
    }

    private static CallGraph captureCallGraph(IDexUnit dex, DalvikCallgraphBuilder b, Digraph g) {
        Map<Integer, List<Integer>> tmp = new java.util.HashMap<>();
        for (Digraph.E e : g.getEdges()) tmp.computeIfAbsent(e.getSrcId(), k -> new ArrayList<>()).add(e.getDstId());
        Map<Integer, int[]> callees = new java.util.HashMap<>(tmp.size() * 2);
        for (Map.Entry<Integer, List<Integer>> en : tmp.entrySet()) {
            List<Integer> v = en.getValue();
            int[] arr = new int[v.size()];
            for (int i = 0; i < v.size(); i++) arr[i] = v.get(i);
            callees.put(en.getKey(), arr);
        }
        Map<Integer, Integer> indexToVid = new java.util.HashMap<>();
        Map<Integer, IDexMethod> vidToMethod = new java.util.HashMap<>();
        for (IDexMethod m : dex.getMethods()) {
            Integer vid = b.getVertexIdForAddress(m.getAddress());
            if (vid != null) {
                indexToVid.put(m.getIndex(), vid);
                vidToMethod.put(vid, m);
            }
        }
        return new CallGraph(callees, indexToVid, vidToMethod);
    }
}
