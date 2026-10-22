package dev.workbench.engine.jadx;

import dev.workbench.engine.CheckpointedFileSave;
import dev.workbench.engine.Engine;
import dev.workbench.engine.Engines;
import dev.workbench.engine.StringSort;
import dev.workbench.engine.index.SearchIndex;
import dev.workbench.registry.OpException;
import dev.workbench.server.Log;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import jadx.api.JavaClass;
import jadx.api.JavaField;
import jadx.api.JavaMethod;
import jadx.api.JavaVariable;
import jadx.api.data.impl.JadxCodeRef;
import jadx.api.data.impl.JadxNodeRef;
import jadx.core.dex.nodes.*;

/** Stock JADX 1.5.6 engine adapter with write and sidecar persistence support. */
public final class JadxEngine implements Engine, SearchIndex.Host {
    private final String id, name, path;
    private final File sidecarFile;
    private final JadxSession session;
    private final JadxLocals locals;
    private JadxRelations relations;
    private final SearchIndex index;
    private boolean indexInitialized;
    private volatile boolean dirty;
    private volatile boolean discarded;
    private volatile boolean replayPending;
    private volatile int replayFailed;
    private final AtomicLong mutationEpoch = new AtomicLong();
    private final AtomicLong savedEpoch = new AtomicLong();
    private volatile long saveStartedAt;
    private volatile long saveExpectBytes;
    private volatile Map<String, Object> lastSave;
    private volatile List<Map<String, Object>> pendingDirtyReplay = List.of();
    private volatile Map<String, Long> indexReplay = Map.of();
    private volatile boolean primedWithFile;
    private volatile int recoveredEdits;
    private final Set<String> contentHashes = ConcurrentHashMap.newKeySet();

    public JadxEngine(String id, String name, String path, File artifact, File sidecar) throws OpException {
        this.id = id;
        this.name = name;
        this.path = path;
        this.sidecarFile = sidecar == null ? null : new File(sidecar, "jadx/code-data.json");
        if (sidecarFile != null && CheckpointedFileSave.marker(sidecarFile.toPath()).toFile().exists()) {
            throw OpException.io("An interrupted sidecar save has a recovery receipt. Check the previous process/lock and restore its verified checkpoint before opening: "
                    + CheckpointedFileSave.marker(sidecarFile.toPath()), "target=" + id);
        }
        this.session = new JadxSession(artifact, sidecarFile, this::onSessionReady);
        this.locals = new JadxLocals(id, session);
        File indexDir = sidecar == null ? null : new File(sidecar, "jadx/search-index");
        this.index = new SearchIndex(this, "search-index:" + id, indexDir, Engines.jadxVersion());
    }

    private List<String> replaySince(long builtAtMs) {
        if (builtAtMs <= 0) return List.of();
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Long> entry : indexReplay.entrySet()) {
            if (entry.getValue() == null || entry.getValue() <= 0 || entry.getValue() > builtAtMs) {
                out.add(entry.getKey());
            }
        }
        return out;
    }

    private Set<String> topClassAddrsForReplay(Collection<String> addresses) throws OpException {
        JadxSymbols symbols = session.symbols();
        JadxRelations rels = relations();
        Set<String> out = new LinkedHashSet<>();
        for (String addr : addresses) {
            String base = JadxSymbols.stripSiteOffset(addr);
            int hash = base.indexOf('#');
            if (hash > 0) base = base.substring(0, hash);
            try {
                JadxSymbols.Symbol sym = symbols.lookup(base);
                out.add(JadxSymbols.currentAddress(sym.owner().getTopParentClass()));
                for (ClassNode refTop : rels.referrerTopClasses(sym.addr())) {
                    out.add(JadxSymbols.currentAddress(refTop));
                }
            } catch (OpException ignored) {}
        }
        return out;
    }

    private void onSessionReady() {
        synchronized (session) {
            if (session.isClosing()) return;
            if (sidecarFile != null && sidecarFile.isFile()) {
                recordSavedContentHash(sidecarFile);
            }
            Set<String> recovery = index.recoveryCandidates();
            long builtAt = index.tryWarmLoad();
            List<String> replay = replaySince(builtAt);
            boolean foreign = false;
            boolean onDemandIndex = "on-demand".equalsIgnoreCase(System.getenv("WORKBENCH_INDEX_MODE"));
            if (builtAt > 0 && sidecarFile != null && sidecarFile.isFile()) {
                long recorded = index.recordedPersistenceMtime();
                if (recorded > 0) foreign = sidecarFile.lastModified() != recorded;
                else foreign = sidecarFile.lastModified() > builtAt && replay.isEmpty();
                if (System.getenv("WORKBENCH_PARANOID") != null && sidecarFile.lastModified() > builtAt) {
                    foreign = true;
                }
            }
            if (!pendingDirtyReplay.isEmpty()) {
                replayPending = true;
                try {
                    recoveredEdits = applyDirtyReplay();
                } finally {
                    replayPending = false;
                }
                if (replayFailed > 0) {
                    index.kill();
                    throw new IllegalStateException("Recovery could not apply " + replayFailed
                            + " history edit(s); the sidecar was not saved. Preserve the checkpoint and history, correct the failed entries shown in the log, then reopen.");
                }
            }
            if (sidecarFile != null && !sidecarFile.isFile() && !session.isClosing() && !discarded) {
                try { writePersistenceFile(); } catch (OpException ignored) {}
            } else if (recoveredEdits > 0 && primedWithFile) {
                dirty = true;
            }
            if (indexInitialized || session.isClosing()) return;
            indexInitialized = true;
            if (builtAt <= 0 || foreign) {
                if (foreign) {
                    index.rejectWarm("Sidecar mtime witness changed outside this workbench");
                }
                if (!onDemandIndex) {
                    index.start();
                } else {
                    recovery.addAll(indexReplay.keySet());
                    try { index.invalidateAll(topClassAddrsForReplay(recovery)); } catch (OpException ignored) {}
                }
            } else if (!replay.isEmpty()) {
                try { index.invalidateAll(topClassAddrsForReplay(replay)); } catch (OpException ignored) {}
            }
        }
    }

    public void openAsync() { session.openAsync(); }

    @Override public void primeIndexReplay(Map<String, Long> addrTs) {
        indexReplay = addrTs == null ? Map.of() : Map.copyOf(addrTs);
    }

    @Override public long searchIndexBuiltAt() { return index.builtAtMs(); }

    @Override public long persistenceMtime() {
        return sidecarFile != null && sidecarFile.isFile() ? sidecarFile.lastModified() : -1;
    }

    @Override public Map<String, Object> caps() {
        Map<String, Object> caps = new LinkedHashMap<>();
        for (String key : List.of("decompile", "smali", "xrefs", "callgraph", "strings", "rename", "comment")) caps.put(key, true);
        caps.put("runs", false);
        caps.put("persist", "sidecar");
        return caps;
    }

    @Override public Map<String, Object> info() {
        Map<String, Object> info = session.status();
        info.put("id", id); info.put("name", name); info.put("path", path); info.put("engine", "JADX");
        info.put("caps", caps()); info.put("dirty", dirty); info.put("index", index.status());
        info.put("engineVersion", Engines.jadxVersion());
        if (recoveredEdits > 0) info.put("recoveredEdits", recoveredEdits);
        if (replayFailed > 0) info.put("replayFailed", replayFailed);
        return info;
    }

    private void markDirty() {
        dirty = true;
        mutationEpoch.incrementAndGet();
    }

    private void requireWritable(String verb) throws OpException {
        if (discarded) {
            throw OpException.busy("this target is being closed with its edits discarded — " + verb + " refused", "target=" + id);
        }
    }

    private void invalidateTopClass(ClassNode top) {
        ClassNode root = top.getTopParentClass();
        String addr = JadxSymbols.currentAddress(root);
        JadxSession.evictClass(root);
        index.invalidate(addr, addr);
        markDirty();
    }

    @Override public Map<String, Object> inputIdentity() { return session.identity(); }

    @Override public Map<String, Object> resolve(String symbol) throws OpException {
        synchronized (session) {
            Map<String, Object> r;
            try {
                r = session.symbols().resolve(symbol);
            } catch (OpException missing) {
                JadxSymbols.SiteRef site = JadxSymbols.splitSite(symbol);
                String clean = JadxSymbols.stripSiteOffset(symbol);
                if (!"E_NOTFOUND".equals(missing.code) || clean.isEmpty() || !relations().references(clean)) {
                    throw missing;
                }
                int arrow = clean.indexOf("->");
                String kind = arrow < 0 ? "class" : clean.indexOf('(', arrow) >= 0 ? "method" : "field";
                if (site != null && !"method".equals(kind)) {
                    throw OpException.syntax("instruction offset requires a METHOD symbol: " + symbol, "symbol=" + symbol);
                }
                String resolvedAddr = site == null ? clean : clean + site.canonicalSuffix();
                r = new LinkedHashMap<>();
                r.put("addr", resolvedAddr);
                r.put("origSig", resolvedAddr);
                r.put("sig", resolvedAddr);
                r.put("kind", kind);
                if (site != null) r.put("site", true);
                r.put("external", true);
                r.put("exact", true);
                r.put("name", arrow < 0 ? clean : clean.substring(arrow + 2).split("[(:]", 2)[0]);
            }
            r.put("target", id);
            return r;
        }
    }

    @Override public List<Object> symbolSearch(String query) throws OpException {
        synchronized (session) {
            @SuppressWarnings("unchecked")
            List<Object> rows = (List<Object>) session.symbols().search(query, 0, null).get("rows");
            return rows;
        }
    }

    @Override public Map<String, Object> symbolSearchPage(String q, int limit, String kind) throws OpException {
        synchronized (session) { return session.symbols().search(q, limit, kind); }
    }

    @Override public Map<String, Object> decompile(String symbol, Map<String, Object> opts) throws OpException {
        synchronized (session) {
            JadxSymbols.Symbol resolved = session.symbols().lookup(symbol);
            String comment = session.getComment(JadxSession.nodeRefFor(resolved.node()), null);
            Map<String, Object> out = JadxSource.render(session.symbols(), resolved, opts, comment);
            if ("method".equals(out.get("scope"))) {
                try { out.put("methodParameters", locals.methodParameters(resolved.addr(), false)); }
                catch (Throwable t) { out.put("methodParameters", Map.of("status", "unsupported", "why", String.valueOf(t))); }
            }
            return out;
        }
    }

    @Override public Map<String, Object> smali(String symbol) throws OpException {
        synchronized (session) {
            JadxSymbols.Symbol resolved = session.symbols().lookup(symbol);
            return JadxSmali.render(session.symbols(), locals, resolved);
        }
    }

    @Override public Map<String, Object> tree(String filter) throws OpException { return tree(filter, 0); }

    @Override public Map<String, Object> tree(String filter, int limit) throws OpException {
        synchronized (session) {
            if (filter == null || filter.isBlank() || filter.startsWith("L")) return treeUnder(filter, limit);
            Map<String, Object> page = symbolSearchPage(filter, limit, "class");
            return Map.of("items", page.get("rows"), "total", page.get("total"), "truncated", page.get("truncated"));
        }
    }

    @Override public Map<String, Object> treeUnder(String prefix, int limit) throws OpException {
        synchronized (session) {
            return JadxHierarchy.treeUnder(session.symbols(), prefix, limit);
        }
    }

    @Override public Map<String, Object> outline(String symbol) throws OpException {
        synchronized (session) {
            return JadxHierarchy.outline(session.symbols(), symbol);
        }
    }

    @Override public Map<String, Object> outline(String symbol, boolean inherited) throws OpException {
        synchronized (session) {
            return JadxHierarchy.outline(session.symbols(), symbol, inherited);
        }
    }

    @Override public Map<String, Object> hover(String symbol, int offset) throws OpException {
        synchronized (session) {
            String raw = symbol == null ? "" : symbol.trim();
            JadxSymbols.SiteRef site = JadxSymbols.splitSite(raw);
            String baseSym = site == null ? raw : site.baseSymbol();
            Map<String, Object> row = new LinkedHashMap<>(resolve(baseSym));
            String comment = "";
            if (offset > 0) {
                var selected = session.symbols().lookup(baseSym);
                var code = selected.owner().getTopParentClass().getCode();
                String text = code.getCodeStr();
                if (offset >= text.length()) throw OpException.syntax("hover offset outside source", "jadx");
                int start = offset;
                while (start > 0 && Character.isJavaIdentifierPart(text.charAt(start - 1))) start--;
                var node = session.decompiler().getJavaNodeAtPosition(code, start);
                if (node != null) {
                    row = new LinkedHashMap<>();
                    row.put("name", node.getName());
                    row.put("sig", node.getFullName());
                    row.put("kind", node instanceof JavaMethod ? "method"
                            : node instanceof JavaField ? "field"
                            : node instanceof JavaVariable ? "local" : "class");
                    if (node instanceof JavaClass jc)
                        comment = session.getComment(JadxSession.nodeRefFor(jc.getClassNode()), null);
                    else if (node instanceof JavaMethod jm)
                        comment = session.getComment(JadxSession.nodeRefFor(jm.getMethodNode()), null);
                    else if (node instanceof JavaField jf)
                        comment = session.getComment(JadxSession.nodeRefFor(jf.getFieldNode()), null);
                } else {
                    throw OpException.notFound("no semantic symbol at source offset", "jadx");
                }
            } else if (!Boolean.TRUE.equals(row.get("external"))) {
                JadxSymbols.Symbol selected = session.symbols().lookup(baseSym);
                JadxNodeRef nodeRef = JadxSession.nodeRefFor(selected.node());
                JadxCodeRef codeRef = site != null ? JadxCodeRef.forInsn(site.offset()) : null;
                comment = session.getComment(nodeRef, codeRef);
            }
            row.put("offset", offset);
            row.put("comment", comment == null ? "" : comment);
            return row;
        }
    }

    private JadxRelations relations() throws OpException {
        session.requireReady();
        if (relations == null) relations = new JadxRelations(session.symbols());
        return relations;
    }

    @Override public List<Object> xrefsTo(String symbol) throws OpException {
        synchronized (session) { return relations().to(JadxSymbols.stripSiteOffset(String.valueOf(resolve(symbol).get("origSig")))); }
    }

    @Override public List<Object> xrefsFrom(String symbol) throws OpException {
        synchronized (session) { return relations().from(session.symbols().lookup(symbol).addr(), false); }
    }

    @Override public List<Object> callees(String symbol) throws OpException {
        synchronized (session) { return relations().from(session.symbols().lookup(symbol).addr(), true); }
    }

    @Override public List<Object> callers(String symbol) throws OpException {
        synchronized (session) { return relations().callers(JadxSymbols.stripSiteOffset(String.valueOf(resolve(symbol).get("origSig")))); }
    }

    @Override public List<Object> implementations(String symbol) throws OpException {
        synchronized (session) { return JadxHierarchy.implementations(session.symbols(), symbol); }
    }

    @Override public Map<String, Object> classinfo(String symbol) throws OpException {
        synchronized (session) { return JadxHierarchy.classinfo(session.symbols(), symbol); }
    }

    @Override public Map<String, Object> strings(String filter, List<String> types, boolean unreachable, int offset, int count, String sort) throws OpException {
        synchronized (session) {
            if (offset < 0) throw OpException.syntax("negative offset", "jadx");
            List<Map<String, Object>> all = relations().stringRows();
            List<Map<String, Object>> rows = new ArrayList<>(all.stream()
                    .filter(r -> !unreachable || Boolean.TRUE.equals(r.get("unreachable")))
                    .filter(r -> filter == null || filter.isEmpty() || String.valueOf(r.get("value")).toLowerCase(Locale.ROOT).contains(filter.toLowerCase(Locale.ROOT)))
                    .filter(r -> {
                        if (types == null || types.isEmpty()) return true;
                        if (r.get("types") instanceof List<?> ts) {
                            for (Object o : ts) {
                                if (o instanceof Map<?, ?> tm && types.contains(String.valueOf(tm.get("name")))) return true;
                            }
                        }
                        return types.contains(String.valueOf(r.get("type")));
                    }).toList());
            String mode;
            try { mode = StringSort.normalize(sort); }
            catch (IllegalArgumentException e) { throw OpException.syntax(e.getMessage(), "jadx"); }
            StringSort.sort(rows, mode);
            int from = Math.min(offset, rows.size()), end = from + Math.min(count <= 0 ? 200 : count, rows.size() - from);
            return Map.of("rows", new ArrayList<>(rows.subList(from, end)), "offset", offset, "count", end - from,
                    "total", rows.size(), "totalAll", all.size(), "truncated", end < rows.size(), "sort", mode);
        }
    }

    @Override public List<Object> stringXrefs(String id) throws OpException {
        synchronized (session) { return relations().stringRefs(id); }
    }

    @Override public List<Object> search(String query, Map<String, Object> opts) throws OpException {
        session.requireReady();
        synchronized (session) {
            if (!indexInitialized) {
                indexInitialized = true;
                if (index.tryWarmLoad() < 0) index.start();
            }
        }
        if (query == null || query.isBlank()) return List.of();
        Map<String, Object> options = opts == null ? Map.of() : opts;
        Pattern pattern;
        try {
            pattern = Pattern.compile(Boolean.parseBoolean(String.valueOf(options.get("regex")))
                    ? query : Pattern.quote(query),
                    Boolean.parseBoolean(String.valueOf(options.get("case"))) ? 0 : Pattern.CASE_INSENSITIVE);
        } catch (PatternSyntaxException e) {
            throw OpException.syntax("bad regex: " + e.getMessage(), "jadx");
        }
        Set<String> kinds = new HashSet<>(Arrays.asList(String.valueOf(options.getOrDefault("kind", "code,strings,comments")).split(",")));
        int limit = 200;
        if (options.get("limit") != null) {
            try {
                int requested = Integer.parseInt(String.valueOf(options.get("limit")));
                if (requested > 0) limit = Math.min(requested, 5000);
            } catch (NumberFormatException e) {
                throw OpException.syntax("limit must be an integer", "jadx");
            }
        }
        int hitCount = 0;
        List<Object> groups = new ArrayList<>();
        if (kinds.contains("code") || kinds.contains("comments")) {
            for (var entry : index.queryEntries()) {
                List<Object> hits = new ArrayList<>();
                for (int i = 0; i < entry.lines().length && hitCount < limit; i++) {
                    String text = entry.lines()[i], trimmed = text.trim();
                    String kind = trimmed.startsWith("//") || trimmed.startsWith("/*") || trimmed.startsWith("*") ? "comments" : "code";
                    if (!kinds.contains(kind) || !pattern.matcher(text).find()) continue;
                    List<Object> context = new ArrayList<>();
                    for (int j = Math.max(0, i - 3); j <= Math.min(entry.lines().length - 1, i + 3); j++) {
                        context.add(Map.of("n", j + 1, "t", entry.lines()[j], "hit", i == j));
                    }
                    hits.add(Map.of("kind", kind, "line", i + 1, "preview", trimmed, "addr", entry.addr(), "context", context));
                    hitCount++;
                }
                if (!hits.isEmpty()) {
                    groups.add(Map.of("symbol", entry.fqcn(), "addr", entry.addr(), "engine", "jadx", "target", name, "hits", hits));
                }
                if (hitCount >= limit) break;
            }
        }
        if (kinds.contains("strings") && hitCount < limit) {
            synchronized (session) {
                List<Object> hits = new ArrayList<>();
                for (Map<String, Object> row : relations().stringRows()) {
                    if (!pattern.matcher(String.valueOf(row.get("value"))).find()) continue;
                    String strId = String.valueOf(row.get("id"));
                    Map<String, Object> hit = new LinkedHashMap<>();
                    hit.put("kind", "strings");
                    hit.put("line", "s" + strId);
                    hit.put("preview", "\"" + row.get("value") + "\"");
                    hit.put("stringId", strId);
                    List<Object> refs = relations().stringRefs(strId);
                    if (!refs.isEmpty() && refs.getFirst() instanceof Map<?, ?> ref0) {
                        Object refSym = ref0.get("method") != null ? ref0.get("method") : ref0.get("from");
                        if (refSym != null) hit.put("addr", String.valueOf(refSym).split("\\+", 2)[0]);
                        List<Object> ctx = new ArrayList<>();
                        for (int ri = 0; ri < Math.min(refs.size(), 10); ri++) {
                            if (refs.get(ri) instanceof Map<?, ?> r) {
                                String label = r.get("owner") != null && r.get("name") != null
                                        ? r.get("owner") + "." + r.get("name")
                                        : String.valueOf(r.get("from"));
                                ctx.add(Map.of("n", ri + 1, "t", label + " (" + r.get("from") + ")", "hit", ri == 0));
                            }
                        }
                        if (!ctx.isEmpty()) hit.put("context", ctx);
                    }
                    hits.add(hit);
                    if (++hitCount >= limit) break;
                }
                if (!hits.isEmpty()) {
                    groups.add(Map.of("symbol", "(string literals)", "addr", "", "engine", "jadx", "target", name, "hits", hits));
                }
            }
        }
        return groups;
    }

    @Override public Map<String, Object> searchIndexStatus() { return index.status(); }

    @Override public void reindex() throws OpException {
        synchronized (session) { session.requireReady(); indexInitialized = true; }
        index.start();
    }

    @Override public boolean cancelTask(String ticket) {
        if (!index.ticket().equals(ticket)) return false;
        index.cancel();
        return true;
    }

    @Override public List<String[]> classSnapshot() {
        synchronized (session) {
            try {
                return session.symbols().classes.stream()
                        .filter(ClassNode::isTopClass)
                        .map(c -> new String[] { JadxSymbols.currentAddress(c), c.getFullName() })
                        .toList();
            } catch (OpException e) { throw new IllegalStateException(e); }
        }
    }

    @Override public String decompileText(String addr) {
        synchronized (session) {
            try { return session.symbols().lookup(addr).owner().getTopParentClass().getCode().getCodeStr(); }
            catch (OpException e) { throw new IllegalStateException(e); }
        }
    }

    @Override public String classLabel(String addr) {
        synchronized (session) {
            try { return session.symbols().lookup(addr).owner().getFullName(); }
            catch (OpException e) { throw new IllegalStateException(e); }
        }
    }

    @Override public void evictDecompiled(String addr) {
        synchronized (session) {
            try { session.symbols().lookup(addr).owner().getTopParentClass().unloadFromCache(); }
            catch (OpException e) { /* close has already released the model */ }
        }
    }

    @Override public Map<String, Object> indexSourceIdentity() {
        Map<String, Object> identity = inputIdentity();
        if (identity == null) return Map.of("measured", false);
        return Map.of("measured", true, "artifact_sha256", identity.get("sha256"),
                "input_class", "File", "analysis_classes_at_open", classSnapshot().size());
    }

    @Override public List<Map<String, Object>> renameTargets(String symbol) throws OpException {
        synchronized (session) {
            return JadxHierarchy.renameTargets(session.symbols(), resolve(symbol), symbol);
        }
    }

    @Override public Map<String, Object> rename(String symbol, String newName, String actor) throws OpException {
        session.requireReady();
        if (!Engine.usableName(newName)) {
            throw OpException.syntax("bad Java identifier: " + newName, "symbol=" + symbol);
        }
        synchronized (session) {
            requireWritable("rename");
            JadxSymbols symbols = session.symbols();
            JadxSymbols.Symbol sym = symbols.lookup(symbol);
            String oldAddr = sym.currentAddr();
            String origSig = sym.addr();
            String oldName = sym.name();
            if (sym.kind().equals("method") && (origSig.contains("-><init>(") || origSig.contains("-><clinit>("))) {
                throw OpException.syntax("a constructor cannot be renamed (<init> / <clinit> are the class's own) — name the class instead",
                        "symbol=" + symbol);
            }

            List<Map<String, Object>> affected = renameTargets(symbol);
            if (!affected.isEmpty() && affected.stream().allMatch(member -> newName.equals(member.get("name")))) {
                Map<String, Object> noop = new LinkedHashMap<>();
                noop.put("addr", oldAddr);
                noop.put("origSig", origSig);
                noop.put("newAddr", oldAddr);
                noop.put("kind", sym.kind());
                noop.put("oldName", newName);
                noop.put("newName", newName);
                noop.put("applied", false);
                noop.put("unchanged", true);
                noop.put("reason", "Every affected symbol already has this name.");
                return noop;
            }

            List<JadxSymbols.Symbol> changedSymbols = new ArrayList<>();
            List<Map<String, Object>> conflicts = JadxHierarchy.collectRenameConflicts(symbols, sym, newName, changedSymbols);
            if (!conflicts.isEmpty()) {
                throw JadxHierarchy.renameRefused(oldName, newName, origSig, oldAddr, conflicts);
            }

            applySymbolRenames(symbols, changedSymbols, newName);

            String newAddr = sym.currentAddr();
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("symbol", Map.of("addr", oldAddr, "kind", sym.kind(), "name", oldName));
            out.put("addr", oldAddr);
            out.put("origSig", origSig);
            out.put("newAddr", newAddr);
            out.put("kind", sym.kind());
            out.put("oldName", oldName);
            out.put("newName", newName);
            out.put("applied", true);
            out.put("actor", actor == null ? "human" : actor);
            return out;
        }
    }

    @Override public Map<String, Object> restoreOriginalName(String symbol, String expectedOriginal, String actor) throws OpException {
        session.requireReady();
        synchronized (session) {
            requireWritable("rename");
            JadxSymbols symbols = session.symbols();
            JadxSymbols.Symbol sym = symbols.lookup(symbol);
            String oldAddr = sym.currentAddr();
            String origSig = sym.addr();
            String oldName = sym.name();
            String origName = sym.originalName();
            if (expectedOriginal != null && !expectedOriginal.equals(origName)) {
                throw OpException.syntax("the recorded original name '" + expectedOriginal + "' is not the dex's own for "
                        + oldAddr + " (that is '" + origName + "') — not restored", "symbol=" + symbol);
            }

            List<JadxSymbols.Symbol> changedSymbols = new ArrayList<>();
            List<Map<String, Object>> conflicts = JadxHierarchy.collectRenameConflicts(symbols, sym, origName, changedSymbols);

            boolean wasRenamed = changedSymbols.stream().anyMatch(JadxSymbols.Symbol::isRenamed);
            if (wasRenamed) {
                if (!conflicts.isEmpty()) {
                    throw JadxHierarchy.renameRefused(oldName, origName, origSig, oldAddr, conflicts);
                }
                applySymbolRenames(symbols, changedSymbols, null);
            }

            String newAddr = sym.currentAddr();
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("symbol", Map.of("addr", oldAddr, "kind", sym.kind(), "name", oldName));
            out.put("addr", oldAddr);
            out.put("origSig", origSig);
            out.put("newAddr", newAddr);
            out.put("kind", sym.kind());
            out.put("oldName", oldName);
            out.put("newName", origName);
            out.put("applied", wasRenamed);
            out.put("restored", true);
            if (!wasRenamed) out.put("note", "already the original name — nothing changed");
            out.put("actor", actor == null ? "human" : actor);
            return out;
        }
    }

    private void applySymbolRenames(JadxSymbols symbols, List<JadxSymbols.Symbol> changedSymbols, String newName) throws OpException {
        Set<ClassNode> affectedTops = JadxHierarchy.affectedTopClasses(symbols, relations(), changedSymbols);
        Map<ClassNode, String> oldTopAddrs = new LinkedHashMap<>();
        for (ClassNode top : affectedTops) oldTopAddrs.put(top, JadxSymbols.currentAddress(top));
        List<jadx.api.data.ICodeRename> previousRenames = new ArrayList<>(session.codeData().getRenames());
        Map<JadxSymbols.Symbol, String> previousAliases = new LinkedHashMap<>();
        for (JadxSymbols.Symbol cs : changedSymbols) {
            previousAliases.put(cs, cs.isRenamed() ? cs.name() : null);
        }

        try {
            for (JadxSymbols.Symbol cs : changedSymbols) {
                JadxNodeRef ref = JadxSession.nodeRefFor(cs.node());
                if (newName == null || newName.equals(cs.originalName())) {
                    session.setRenameEntry(ref, null, null);
                    JadxSession.clearNodeAlias(cs.node());
                } else {
                    session.setRenameEntry(ref, null, newName);
                    JadxSession.setNodeAlias(cs.node(), newName);
                }
            }
            session.reloadCodeData();
            for (var entry : oldTopAddrs.entrySet()) {
                JadxSession.evictClass(entry.getKey());
            }
            for (JadxSymbols.Symbol cs : changedSymbols) {
                String expected = newName == null ? cs.originalName() : newName;
                if (!expected.equals(cs.name())) {
                    throw OpException.busy("JADX did not apply rename for " + cs.addr() + " to '" + expected + "'", "symbol=" + cs.addr());
                }
            }
        } catch (OpException | RuntimeException failure) {
            session.codeData().setRenames(previousRenames);
            for (var entry : previousAliases.entrySet()) {
                Object node = entry.getKey().node();
                if (entry.getValue() == null) JadxSession.clearNodeAlias(node);
                else JadxSession.setNodeAlias(node, entry.getValue());
            }
            try { session.reloadCodeData(); } catch (RuntimeException ignored) {}
            for (ClassNode top : oldTopAddrs.keySet()) JadxSession.evictClass(top);
            throw failure;
        }
        for (var entry : oldTopAddrs.entrySet()) {
            index.invalidate(entry.getValue(), JadxSymbols.currentAddress(entry.getKey()));
        }
        markDirty();
    }

    @Override public List<Object> locals(String methodSymbol) throws OpException {
        session.requireReady();
        synchronized (session) { return locals.locals(methodSymbol); }
    }

    @Override public Map<String, Object> resolveLocal(String methodSymbol, String local) throws OpException {
        session.requireReady();
        synchronized (session) { return locals.resolveLocal(methodSymbol, local); }
    }

    @Override public Map<String, Object> renameLocal(String methodSymbol, String local, String newName, String actor) throws OpException {
        session.requireReady();
        synchronized (session) {
            requireWritable("rename");
            return locals.renameLocal(methodSymbol, local, newName, actor, this::invalidateTopClass);
        }
    }

    @Override public Map<String, Object> autoNameLocals(String methodSymbol, boolean all, String actor) throws OpException {
        session.requireReady();
        synchronized (session) {
            requireWritable("rename");
            return locals.autoNameLocals(methodSymbol, all, actor, this::invalidateTopClass);
        }
    }

    @Override public Map<String, Object> methodParameters(String methodSymbol) throws OpException {
        return methodParameters(methodSymbol, true);
    }

    @Override public Map<String, Object> methodParameters(String methodSymbol, boolean withCurrent) throws OpException {
        session.requireReady();
        synchronized (session) { return locals.methodParameters(methodSymbol, withCurrent); }
    }

    @Override public Map<String, Object> renameParameters(String methodSymbol, List<Map<String, Object>> changes, String actor) throws OpException {
        session.requireReady();
        synchronized (session) {
            requireWritable("rename");
            return locals.renameParameters(methodSymbol, changes, actor, this::invalidateTopClass);
        }
    }

    private void recordSavedContentHash(File file) {
        if (file == null || !file.isFile()) return;
        try (var input = Files.newInputStream(file.toPath())) {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] buffer = new byte[64 * 1024];
            for (int read; (read = input.read(buffer)) >= 0;) if (read > 0) digest.update(buffer, 0, read);
            contentHashes.add(HexFormat.of().formatHex(digest.digest()));
        } catch (Exception ignored) {}
    }

    @Override public Set<String> contentHashHistory() { return Collections.unmodifiableSet(contentHashes); }
    @Override public File persistenceFile() { return sidecarFile; }
    public boolean isDirty() { return dirty; }

    private void recordSave(boolean ok, long started, String error) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("ok", ok);
        row.put("at", System.currentTimeMillis());
        row.put("ms", Math.max(0, System.currentTimeMillis() - started));
        row.put("bytes", sidecarFile != null && sidecarFile.isFile() ? sidecarFile.length() : 0L);
        if (error != null) row.put("error", error);
        lastSave = row;
    }

    private Map<String, Object> writePersistenceFile() throws OpException {
        if (sidecarFile == null) throw OpException.unsupported("no JADX sidecar path configured; nothing to save to", "target=" + id);
        long started = System.currentTimeMillis();
        long epoch = mutationEpoch.get();
        saveStartedAt = started;
        saveExpectBytes = sidecarFile.isFile() ? sidecarFile.length() : 0L;
        try {
            String json = session.serializeCodeData();
            boolean wrote = CheckpointedFileSave.write(sidecarFile.toPath(), destination -> {
                Files.writeString(destination, json, StandardCharsets.UTF_8);
                return true;
            });
            if (!wrote) throw OpException.busy("sidecar writer refused the write — code-data.json was not updated", "target=" + id);
            savedEpoch.set(epoch);
            if (mutationEpoch.get() == epoch) dirty = false;
            boolean witnessOk = index.recordPersistenceMtime(sidecarFile.lastModified());
            recordSavedContentHash(sidecarFile);
            recordSave(true, started, null);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("saved", true);
            out.put("sidecar", sidecarFile.getAbsolutePath());
            out.put("bytes", sidecarFile.length());
            out.put("witnessOk", witnessOk);
            return out;
        } catch (OpException error) {
            recordSave(false, started, error.getMessage());
            throw error;
        } catch (Exception error) {
            recordSave(false, started, String.valueOf(error));
            throw OpException.busy("save failed: " + error, "target=" + id);
        } finally {
            saveStartedAt = 0;
        }
    }

    @Override public Map<String, Object> save() throws OpException {
        session.requireReady();
        synchronized (session) {
            requireWritable("save");
            if (replayPending) throw OpException.busy("this target's edit history is still being re-applied", "target=" + id);
            return writePersistenceFile();
        }
    }

    @Override public Map<String, Object> saveStatus() {
        long started = saveStartedAt;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("saving", started > 0);
        if (started > 0) {
            out.put("startedAt", started);
            out.put("elapsedMs", System.currentTimeMillis() - started);
            out.put("bytesNow", sidecarFile != null && sidecarFile.isFile() ? sidecarFile.length() : 0L);
            out.put("bytesExpected", saveExpectBytes);
            out.put("sidecar", sidecarFile == null ? null : sidecarFile.getName());
        }
        if (lastSave != null) out.put("last", lastSave);
        return out;
    }

    @Override public void discardUnsaved() { synchronized (session) { discarded = true; dirty = false; } }

    @Override public void primeDirtyReplay(List<Map<String, Object>> entries) {
        pendingDirtyReplay = entries == null ? List.of() : List.copyOf(entries);
        primedWithFile = sidecarFile != null && sidecarFile.isFile();
    }

    private int applyDirtyReplay() {
        int applied = 0, failed = 0;
        for (Map<String, Object> entry : pendingDirtyReplay) {
            String operation = String.valueOf(entry.get("op"));
            String addr = String.valueOf(entry.get("addr"));
            if (!"rename".equals(operation) && !"comment".equals(operation)) continue;
            try {
                if ("comment".equals(operation)) {
                    comment(addr, entry.get("newText") == null ? "" : String.valueOf(entry.get("newText")), "recovery");
                } else if ("local".equals(entry.get("kind"))) {
                    renameLocal(addr, String.valueOf(entry.get("oldName")), String.valueOf(entry.get("newName")), "recovery");
                } else if ("parameters".equals(entry.get("kind"))) {
                    List<Map<String, Object>> changes = extractParameterReplayChanges(entry);
                    if (changes.isEmpty()) throw new IllegalStateException("a parameters entry without changes cannot be replayed");
                    renameParameters(addr, changes, "recovery");
                } else {
                    String newName = String.valueOf(entry.get("newName"));
                    if (Boolean.TRUE.equals(entry.get("revert")) || !Engine.usableName(newName)) {
                        restoreOriginalName(addr, newName, "recovery");
                    } else {
                        rename(addr, newName, "recovery");
                    }
                }
                applied++;
            } catch (Exception failure) {
                failed++;
                Map<String, Object> context = new LinkedHashMap<>();
                context.put("target", id);
                context.put("op", operation);
                context.put("addr", addr);
                context.put("error", String.valueOf(failure));
                Log.warn("jadx replay", "history edit could not be re-applied", context);
            }
        }
        replayFailed = failed;
        pendingDirtyReplay = List.of();
        return applied;
    }

    private static List<Map<String, Object>> extractParameterReplayChanges(Map<String, Object> entry) {
        List<Map<String, Object>> changes = new ArrayList<>();
        if (entry.get("parameterMutations") instanceof List<?> mutations) {
            for (Object item : mutations) {
                if (item instanceof Map<?, ?> source) {
                    Map<String, Object> change = new LinkedHashMap<>();
                    for (Map.Entry<?, ?> field : source.entrySet()) change.put(String.valueOf(field.getKey()), field.getValue());
                    changes.add(change);
                }
            }
        } else if (entry.get("changes") instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> source) {
                    Map<String, Object> change = new LinkedHashMap<>();
                    change.put("index", source.get("index"));
                    if (Boolean.TRUE.equals(source.get("cleared"))) change.put("clear", true);
                    else change.put("name", source.get("newName") != null ? source.get("newName") : source.get("name"));
                    changes.add(change);
                }
            }
        }
        return changes;
    }

    @Override public Map<String, Object> comment(String symbol, String text, String actor) throws OpException {
        session.requireReady();
        if (symbol == null || symbol.isBlank()) throw OpException.syntax("comment requires a symbol", "jadx");
        synchronized (session) {
            requireWritable("comment");
            String requested = symbol.trim();
            JadxSymbols.SiteRef site = JadxSymbols.splitSite(requested);
            String base = site == null ? requested : site.baseSymbol();
            JadxSymbols.Symbol resolved = session.symbols().lookup(base);
            if (site != null && !(resolved.node() instanceof MethodNode)) {
                throw OpException.syntax("an instruction comment requires a METHOD+offset", "symbol=" + requested);
            }
            JadxNodeRef nodeRef = JadxSession.nodeRefFor(resolved.node());
            JadxCodeRef codeRef = site == null ? null : JadxCodeRef.forInsn(site.offset());
            String value = text == null ? "" : text;
            String old = session.getComment(nodeRef, codeRef);
            boolean changed = !Objects.equals(old, value);
            if (changed) {
                List<jadx.api.data.ICodeComment> previousComments = new ArrayList<>(session.codeData().getComments());
                try {
                    session.upsertComment(nodeRef, codeRef, value);
                    invalidateTopClass(resolved.owner());
                } catch (RuntimeException failure) {
                    session.codeData().setComments(previousComments);
                    try { session.reloadCodeData(); } catch (RuntimeException ignored) {}
                    JadxSession.evictClass(resolved.owner());
                    throw failure;
                }
            }
            String canonicalAddr = site == null ? resolved.currentAddr() : resolved.currentAddr() + site.canonicalSuffix();
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("symbol", Map.of("addr", resolved.currentAddr(), "kind", resolved.kind(), "name", resolved.name()));
            out.put("addr", canonicalAddr);
            out.put("origSig", site == null ? resolved.addr() : resolved.addr() + site.canonicalSuffix());
            if (site != null) out.put("site", true);
            out.put("text", value);
            out.put("oldText", old);
            out.put("applied", changed);
            if (!changed) {
                out.put("unchanged", true);
                out.put("reason", "This comment already has that text.");
            }
            out.put("actor", actor == null ? "human" : actor);
            return out;
        }
    }

    @Override public void close() {
        index.kill();
        RuntimeException saveFailure = null;
        synchronized (session) {
            if (session.isReady() && dirty && !discarded && !replayPending && sidecarFile != null) {
                try { writePersistenceFile(); }
                catch (OpException error) {
                    saveFailure = new IllegalStateException("close-save failed for " + name + "; accepted edits remain in history", error);
                }
            }
            session.close();
            relations = null;
        }
        if (saveFailure != null) throw saveFailure;
    }
}
