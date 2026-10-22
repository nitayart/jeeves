package dev.workbench.engine;

import dev.workbench.registry.OpException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Engine adapter interface (WORKBENCH_SPEC §3.1). Every method is optional in
 * principle; adapters declare what's real via caps() and throw
 * OpException(E_UNSUPPORTED) for the rest. All return values are JSON-shaped
 * (Map/List/String/Number/Boolean) — the wire format is `op + params → JSON`
 * and engine names never appear on the wire beyond the `engine` badge field.
 */
public interface Engine {

    /** Capability flags: { decompile, smali, xrefs, rename, comment, callgraph, strings, runs, persist }. */
    Map<String, Object> caps();

    /** Target stats: classes, engine, heap, dirty, caps (target.info). */
    Map<String, Object> info();

    /** Cleanup capability/limitations, not proof that close or memory reclamation completed. */
    default Map<String, Object> closeCapabilities() { return Map.of("reported", false); }

    /** Package/class/method hierarchy, optionally filtered (symbol.tree).
        Returns {items: [...], truncated: bool, total: int} — name search caps at
        200 and browse caps direct classes at 500; both MUST say so. A bare list
        let agents mistake a capped prefix for the whole package (app-review-opus
        P0-3). total = entries the UNCAPPED listing would hold (browse: subpackages
        + direct classes; search: matching classes — fix-7 review F5). */
    Map<String, Object> tree(String filter) throws OpException;
    /** limit: the caller's cut (0 = every row — the caller, not the tool, chooses; 2026-09-18). */
    default Map<String, Object> tree(String filter, int limit) throws OpException { return tree(filter); }

    /** What is UNDER a package prefix, one level, SHALLOW (symbol.tree {prefix}; the MCP `symbols`
     *  tool): every immediate sub-package with its class count, then the direct classes in address
     *  order, one row each — {name, kind, addr, fqcn}, without their methods and fields (a class
     *  address as the prefix answers that one class WITH them). The prefix is a package in any
     *  spelling (com.foo.bar, com/foo/bar, Lcom/foo/bar/); a last segment that is not a whole
     *  package name is the beginning of a class or package name (com.foo.b → what starts with "b"
     *  under com.foo). Nothing there → E_NOTFOUND naming what the nearest existing package holds.
     *  limit cuts the classes only; 0 = the engine's default cut (JEB: the first 500), announced
     *  by truncated, total, beginnings (classes counted by their next character) and a hint.
     *  The default serves engines without a package model: their tree. */
    default Map<String, Object> treeUnder(String prefix, int limit) throws OpException { return tree(prefix, limit); }

    /** string → engine symbol, cross-target grammar addr|origSig|fuzzy (symbol.resolve). A lenient method
     *  match answers `exact:false, lenient:<what it overlooked>, asked`, and `contradiction:true` when a
     *  COMPLETE descriptor disagrees with the method found (a wrong return or parameter type). */
    Map<String, Object> resolve(String symbol) throws OpException;

    /** resolve() with the corpus rule: a complete method descriptor must match the dex exactly — a reference
     *  whose return or parameter types contradict the method found is refused with that method named, never
     *  silently re-aimed (what reads fine for an agent is wrong evidence in an export or a finding). Partial
     *  references (no descriptor, no return type) keep resolving. */
    default Map<String, Object> resolveStrict(String symbol) throws OpException {
        Map<String, Object> r = resolve(symbol);
        if (Boolean.TRUE.equals(r.get("contradiction")))
            throw OpException.notFound("no method with the exact descriptor " + r.get("asked") + " — the class has " + r.get("addr") + " (the " + r.get("lenient") + " differ); pass that descriptor to export or cite it",
                    "symbol=contradiction");
        return r;
    }

    /** The class outline with the superclass chain's fields too (in-dex supers; framework supers named as unknown),
     *  for a consumer that reflects on a runtime object — the observer follow-up of 2026-10-09. */
    default Map<String, Object> outline(String classSymbol, boolean inherited) throws OpException { return outline(classSymbol); }

    /** The bounded context around ONE instruction of a method (an xref's call site): the instruction and its
     *  neighbours, the containing block with its immediate predecessors' branch instructions (syntactic context,
     *  not dominance), the callee when it is a call, the decompiler's lines around the mapped statement, the
     *  extent and how to ask for more. opts: context (instructions each side), lines (Java lines each side), java. */
    default Map<String, Object> callSite(String methodSymbol, long offset, Map<String, Object> opts) throws OpException {
        throw OpException.unsupported("call-site context is not available on this engine", "engine");
    }

    /** The identity of the executable this engine analysed, as the ENGINE holds it: {name, bytes, sha256,
     *  source} — JEB hashes the input embedded in its database (what a reopened .jdb2 still carries when the
     *  APK is gone), once per open; null when the engine has no such record (the mock). */
    default Map<String, Object> inputIdentity() { return null; }

    /** Fuzzy over classes/methods/fields (symbol.search). */
    List<Object> symbolSearch(String query) throws OpException;
    /** A page of the search: {rows, total, truncated}; limit 0 = every row. */
    default Map<String, Object> symbolSearchPage(String query, int limit) throws OpException {
        List<Object> all = symbolSearch(query);
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("rows", limit > 0 && all.size() > limit ? new java.util.ArrayList<>(all.subList(0, limit)) : all);
        out.put("total", all.size());
        out.put("truncated", limit > 0 && all.size() > limit);
        return out;
    }

    /** With a kind filter (class | method | field; null = every kind); engines without ranking
     *  filter the plain page. */
    default Map<String, Object> symbolSearchPage(String query, int limit, String kind) throws OpException {
        if (kind == null || kind.isBlank()) return symbolSearchPage(query, limit);
        List<Object> all = new java.util.ArrayList<>();
        for (Object o : symbolSearch(query))
            if (o instanceof Map<?, ?> m && kind.equals(m.get("kind"))) all.add(o);
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("rows", limit > 0 && all.size() > limit ? new java.util.ArrayList<>(all.subList(0, limit)) : all);
        out.put("total", all.size());
        out.put("truncated", limit > 0 && all.size() > limit);
        return out;
    }

    /** Source + semantic token map (code.decompile). */
    Map<String, Object> decompile(String symbol, Map<String, Object> opts) throws OpException;

    /** Smali/disassembly listing with method-relative offsets + raw opcode bytes
     *  (code.smali; cap `smali`; native engines: disassembly). */
    default Map<String, Object> smali(String symbol) throws OpException {
        throw OpException.unsupported("code.smali not supported by this engine", "cap=smali");
    }

    /** Method/field outline of a class (code.outline). */
    Map<String, Object> outline(String classSymbol) throws OpException;

    /** Tooltip model: kind, owner, sig, comment (code.hover). */
    Map<String, Object> hover(String symbol, int offset) throws OpException;

    /** Callers (xref.to). */
    List<Object> xrefsTo(String symbol) throws OpException;

    /** Referenced symbols (xref.from). */
    List<Object> xrefsFrom(String symbol) throws OpException;

    /** Forward tree, may be capped / hub-aware (callees). */
    List<Object> callees(String symbol) throws OpException;

    /** Backward tree, may be capped / hub-aware (callers). */
    List<Object> callers(String symbol) throws OpException;

    /** Implementations/overrides (impls). */
    List<Object> implementations(String symbol) throws OpException;

    /** String table page: {rows:[{id,value,types:[{name,confidence,why}],xrefCount,
     *  firstReferrer,unreachable}], total, truncated} — two-layer classification
     *  (VIEWS_SPEC §1, WORKBENCH_SPEC §6.4). types=null → all; count<=0 → default page. */
    Map<String, Object> strings(String filter, List<String> types, boolean unreachableOnly,
            int offset, int count, String sort) throws OpException;   // sort: StringSort (priority|xrefs|order), applied before paging

    /** Usages of one string (string.xrefs). */
    List<Object> stringXrefs(String stringId) throws OpException;

    /** Decompiled-text search over the background index, grouped hits with context
     *  (VIEWS_SPEC §3). opts: scope, kind (csv of code|strings|comments), regex, case, limit. */
    List<Object> search(String query, Map<String, Object> opts) throws OpException;

    /** Class metadata for the inspector bay (code.classinfo; VIEWS_SPEC §shared). */
    default Map<String, Object> classinfo(String classSymbol) throws OpException {
        throw OpException.unsupported("code.classinfo not supported by this engine", "");
    }

    /** A bounded, read-only evidence packet for one method (evidence.packet; the MCP `code` tool's
     *  view "packet"): the method's original identity beside its display
     *  names, its complete code, stable instruction references (method + offset), the calls, fields,
     *  types and strings it touches, annotation provenance, and explicit omissions. With a CFG and
     *  an `entry` (an instruction offset or a block id) the selection is the blocks reachable from
     *  that entry along regular and exceptional edges — never a text cut between labels — else the
     *  whole method, said as such; without a CFG and with an entry: an unsupported-slice status.
     *  opts: question, entry, maxInstructions (default 400), java (default true). */
    default Map<String, Object> evidencePacket(String symbol, Map<String, Object> opts) throws OpException {
        throw OpException.unsupported("evidence.packet not supported by this engine", "symbol=" + symbol);
    }

    /** Background search-index status: {state, built, total, ms, ageMs} (search.index). */
    default Map<String, Object> searchIndexStatus() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("state", "none");
        return m;
    }

    /** (Re)build the background search index (search.reindex). */
    default void reindex() throws OpException {}

    /** Prime the search-index freshness replay (persisted gz corpus, ROADMAP
     *  major item): history addrs → edit ts (epoch ms; 0 = unknown), so a
     *  warm-loaded corpus re-indexes exactly the classes edited since it was
     *  built. Workspace primes from history.json before analysis starts. */
    default void primeIndexReplay(Map<String, Long> addrTs) {}

    /** Corpus build time (epoch ms) of a warm-loaded persisted search index,
     *  -1 when there is no valid persisted corpus. */
    default long searchIndexBuiltAt() { return -1; }

    /** Unsaved (crash-window) edits to re-apply after open: history entries
     *  newer than the jdb2's last save — the jdb2 never saw them. List of
     *  {op: rename|comment, addr, newName|newText}. Applied through the normal
     *  write path, so index invalidation and the dirty flag ride along. */
    default void primeDirtyReplay(List<Map<String, Object>> entries) {}

    /** Cancel a background task by ticket (op.cancel). Returns true if something was cancelled. */
    default boolean cancelTask(String ticket) { return false; }

    /** Symbols whose names change together. The selected symbol comes first; each row has
     * stable origSig, current addr/name, and kind. Engines with virtual-method propagation
     * must return the complete family so callers can check ownership before any mutation. */
    default List<Map<String, Object>> renameTargets(String symbol) throws OpException {
        return List.of(resolve(symbol));
    }

    /** Write op — optional (cap `rename`). */
    Map<String, Object> rename(String symbol, String name, String actor) throws OpException;

    /** Resolve a local's current display name and stable identity before a guarded write.
     * Returns {name, originalSymbol, originalMethod}; identity must survive a display rename. */
    default Map<String, Object> resolveLocal(String methodSymbol, String local) throws OpException {
        throw OpException.unsupported("this engine cannot identify locals for a guarded rename", "symbol=" + methodSymbol);
    }

    /** Rename a local variable or parameter (named by its current name) inside a method. Returns
     *  {addr (the method), kind: local, oldName, newName, applied}. Default: unsupported. */
    default Map<String, Object> renameLocal(String methodSymbol, String local, String newName, String actor) throws OpException {
        throw OpException.unsupported("this engine cannot rename locals", "symbol=" + methodSymbol);
    }

    /** The locals of a method as the decompiled text shows them: [{name, type?, line}]. */
    default List<Object> locals(String methodSymbol) throws OpException {
        throw OpException.unsupported("this engine cannot list locals", "symbol=" + methodSymbol);
    }

    /** Name a method's locals after their declared types (generated names only unless all):
     *  {addr, renames:[{local, name, type}], skipped:[{local, why}]}. */
    default Map<String, Object> autoNameLocals(String methodSymbol, boolean all, String actor) throws OpException {
        throw OpException.unsupported("this engine cannot name locals", "symbol=" + methodSymbol);
    }

    /** A usable NEW analyst name: a Java identifier that is not a keyword — the rule every rename of
     *  a class, method, field, local or parameter runs. An ORIGINAL dex name is a different contract
     *  (restoreOriginalName). */
    static boolean usableName(String s) {
        return s != null && s.matches("[A-Za-z_$][A-Za-z0-9_$]*") && !JAVA_KEYWORDS.contains(s);
    }
    java.util.Set<String> JAVA_KEYWORDS = java.util.Set.of("abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const", "continue",
            "default", "do", "double", "else", "enum", "extends", "final", "finally", "float", "for", "goto", "if", "implements", "import", "instanceof", "int", "interface", "long",
            "native", "new", "package", "private", "protected", "public", "return", "short", "static", "strictfp", "super", "switch", "synchronized", "this", "throw", "throws",
            "transient", "try", "void", "volatile", "while", "true", "false", "null", "var", "record", "yield", "sealed", "permits");

    /** Retained parameter metadata of a METHOD (code.params, 2026-10-08): the dex's method-level
     *  {@code Ldalvik/annotation/MethodParameters;} — names and accessFlags arrays verbatim, in encoded
     *  order, nulls and duplicates kept — the debug_info parameter names as a SECOND source, the
     *  current prototype and the decompiler's current argument names. Nothing is paired: an
     *  annotation index is not a verified argument index. Absent metadata, unsupported extraction
     *  and malformed arrays are told apart. Reading it changes nothing (no dirty, no history). */
    default Map<String, Object> methodParameters(String methodSymbol) throws OpException {
        throw OpException.unsupported("code.params not supported by this engine", "symbol=" + methodSymbol);
    }

    /** The same metadata with or without the decompiler's current argument names: withCurrent=false is
     *  the raw read (the annotation and the debug info only — no class render, cheap on a hot path). */
    default Map<String, Object> methodParameters(String methodSymbol, boolean withCurrent) throws OpException { return methodParameters(methodSymbol); }

    /** Rename formal arguments of a method by their position (0-based, `this` excluded) through the
     *  engine's own parameter binding — every bound use follows, the prototype's types and order do
     *  not change. changes: [{index, name} | {index, clear: true}]; validated whole, applied whole
     *  (swaps included) or not at all. Returns {addr, kind: parameters, changes:[{index, oldName,
     *  newName | cleared, oldSource}], applied}. */
    default Map<String, Object> renameParameters(String methodSymbol, List<Map<String, Object>> changes, String actor) throws OpException {
        throw OpException.unsupported("this engine cannot rename parameters", "symbol=" + methodSymbol);
    }

    /** Restore a class's, method's or field's ORIGINAL dex name — what rename.revert and the undo of
     *  a rename back to a name the Java-identifier rule refuses (1cq) go through. Validated against
     *  the dex: when expectedOriginal is given it must be the item's original name. Returns the
     *  rename shape {addr, newAddr, kind, oldName, newName, applied, restored: true}. */
    default Map<String, Object> restoreOriginalName(String symbol, String expectedOriginal, String actor) throws OpException {
        throw OpException.unsupported("this engine cannot restore original names", "symbol=" + symbol);
    }

    /** Write op — optional (cap `comment`). */
    Map<String, Object> comment(String symbol, String text, String actor) throws OpException;

    /** Persist the session (WORKBENCH_SPEC §3.1). Default: no persistence story. */
    default Map<String, Object> save() throws OpException {
        throw OpException.unsupported("save not supported by this engine", "persist=none");
    }

    /** Progress of a save in flight (the GUI's saving box): {saving} plus, while saving,
     *  elapsedMs, bytesNow (the database file as it grows), bytesExpected (its size before
     *  the write) and jdb2. Never blocks on the engine's monitor. Default: never saving. */
    default Map<String, Object> saveStatus() { return Map.of("saving", false); }

    /** Forget unsaved edits so a following close() does NOT write them — the user chose
     *  DISCARD in the close dialog. Default: a stateless engine has nothing to forget. */
    default void discardUnsaved() {}
    /** Classes/methods decompiled for the first time since the last save — unsaved work the
     *  close dialog counts (a JEB database stores decompilations). Stateless engines: 0. */
    default int unsavedDecompilations() { return 0; }

    /** The file this engine persists edits to (e.g. .jdb2 for JEB, or code-data.json for JADX),
     *  or null when stateless. */
    default java.io.File persistenceFile() { return null; }

    /** Timestamp of the last persisted save of this target, or {@link java.time.Instant#MIN}
     *  when no persisted state exists yet. */
    default java.time.Instant lastSavedAt() {
        java.io.File f = persistenceFile();
        if (f == null || !f.isFile()) return java.time.Instant.MIN;
        try { return java.nio.file.Files.getLastModifiedTime(f.toPath()).toInstant(); }
        catch (java.io.IOException ex) { return java.time.Instant.ofEpochMilli(f.lastModified()); }
    }

    /** Close the session, releasing engine resources (WORKBENCH_SPEC §3.1). Default: stateless. */
    default void close() {}

    /** Every byte-state of the jdb2 this engine has loaded or written — the
        identity a native registry would hold, for twin-dedupe (each save adds
        one entry). Default empty: no native project identity. */
    default java.util.Set<String> contentHashHistory() { return java.util.Set.of(); }
}
