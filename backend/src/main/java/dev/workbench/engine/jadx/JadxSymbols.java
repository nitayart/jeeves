package dev.workbench.engine.jadx;

import dev.workbench.registry.OpException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import jadx.core.dex.nodes.*;

/** Original DEX identities indexed without calling JavaClass.load(). */
final class JadxSymbols {
    static final Pattern INSN_SITE_PATTERN = Pattern.compile("^(.*->[^+]+)\\+(0x[0-9A-Fa-f]+|[0-9A-Fa-f]+h?)$");

    record SiteRef(String baseSymbol, String suffix, int offset) {
        String canonicalSuffix() { return "+" + Integer.toHexString(offset) + "h"; }
    }

    static SiteRef splitSite(String raw) throws OpException {
        if (raw == null) return null;
        Matcher matcher = INSN_SITE_PATTERN.matcher(raw.trim());
        if (!matcher.matches()) return null;
        String suffix = matcher.group(2);
        return new SiteRef(matcher.group(1), suffix, parseHexOffset(suffix));
    }

    static String stripSiteOffset(String raw) {
        if (raw == null) return "";
        String trimmed = raw.trim();
        Matcher matcher = INSN_SITE_PATTERN.matcher(trimmed);
        return matcher.matches() ? matcher.group(1) : trimmed;
    }

    static int parseHexOffset(String value) throws OpException {
        String text = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        try {
            if (text.startsWith("0x")) return Integer.parseUnsignedInt(text.substring(2), 16);
            if (text.endsWith("h")) return Integer.parseUnsignedInt(text.substring(0, text.length() - 1), 16);
            // Instruction suffixes are conventionally hexadecimal even without 0x.
            return Integer.parseUnsignedInt(text, 16);
        } catch (NumberFormatException error) {
            throw OpException.syntax("bad instruction offset: " + value, "jadx");
        }
    }

    final class Symbol {
        private final String addr, kind, originalName;
        private final ClassNode owner;
        private final Object node;

        Symbol(String addr, String kind, String originalName, ClassNode owner, Object node) {
            this.addr = addr; this.kind = kind; this.originalName = originalName;
            this.owner = owner; this.node = node;
        }
        String addr() { return addr; }
        String kind() { return kind; }
        String originalName() { return originalName; }
        ClassNode owner() { return owner; }
        Object node() { return node; }

        String name() {
            if (node instanceof ClassNode c) return c.getAlias();
            if (node instanceof MethodNode m) return m.getAlias();
            return ((FieldNode) node).getAlias();
        }
        boolean isRenamed() { return !originalName.equals(name()); }

        String currentAddr() {
            if (node instanceof ClassNode c) return currentAddress(c);
            if (node instanceof MethodNode m) {
                StringBuilder sb = new StringBuilder(currentAddress(owner)).append("->").append(m.getAlias()).append('(');
                for (var arg : m.getMethodInfo().getArgumentsTypes()) sb.append(currentTypeSig(arg));
                return sb.append(')').append(currentTypeSig(m.getMethodInfo().getReturnType())).toString();
            }
            FieldNode f = (FieldNode) node;
            return currentAddress(owner) + "->" + f.getAlias() + ":" + currentTypeSig(f.getFieldInfo().getType());
        }

        Map<String, Object> row() {
            Map<String, Object> row = new LinkedHashMap<>();
            String cur = currentAddr();
            row.put("addr", cur); row.put("origSig", addr); row.put("sig", cur);
            row.put("kind", kind); row.put("name", name());
            row.put("owner", owner.getFullName());
            if (node instanceof ClassNode) row.put("fqcn", owner.getFullName());
            if (node instanceof MethodNode m) {
                row.put("static", m.getAccessFlags().isStatic());
                row.put("native", m.getAccessFlags().isNative());
                row.put("access", m.getAccessFlags().makeString(false).trim());
            }
            if (node instanceof FieldNode f) {
                row.put("static", f.getAccessFlags().isStatic());
                row.put("final", f.getAccessFlags().isFinal());
                row.put("access", f.getAccessFlags().makeString(false).trim());
                row.put("type", currentTypeSig(f.getFieldInfo().getType()));
                row.put("declaredIn", currentAddress(owner));
            }
            if (isRenamed()) {
                row.put("renamed", true);
                row.put("origName", originalName);
            }
            return row;
        }
    }

    record ResolvedLookup(Symbol symbol, boolean exact, String by, String lenient,
                          List<String> differs, boolean contradiction, String asked) {}

    final Map<String, Symbol> byAddress = new LinkedHashMap<>();
    final Map<String, ClassNode> classByRawName = new LinkedHashMap<>();
    private final Map<ClassNode, List<Symbol>> membersByOwner = new IdentityHashMap<>();
    final List<ClassNode> classes;

    JadxSymbols(RootNode root) {
        classes = List.copyOf(root.getClasses(true));
        for (ClassNode c : classes) {
            classByRawName.putIfAbsent(c.getClassInfo().getRawName(), c);
            String clsAddr = address(c);
            put(new Symbol(clsAddr, "class", c.getName(), c, c));
            List<Symbol> members = new ArrayList<>(c.getMethods().size() + c.getFields().size());
            for (MethodNode m : c.getMethods()) {
                Symbol sym = new Symbol(methodAddress(m), "method", m.getName(), c, m);
                if (put(sym)) members.add(sym);
            }
            for (FieldNode f : c.getFields()) {
                Symbol sym = new Symbol(fieldAddress(f), "field", f.getName(), c, f);
                if (put(sym)) members.add(sym);
            }
            membersByOwner.put(c, Collections.unmodifiableList(members));
        }
    }

    private boolean put(Symbol symbol) {
        return byAddress.putIfAbsent(symbol.addr(), symbol) == null;
    }

    List<Symbol> membersOf(ClassNode owner) {
        return membersByOwner.getOrDefault(owner, List.of());
    }

    static String address(ClassNode cls) {
        return "L" + cls.getClassInfo().getRawName().replace('.', '/') + ";";
    }

    static String currentAddress(ClassNode cls) {
        return "L" + cls.getClassInfo().makeAliasRawFullName().replace('.', '/') + ";";
    }

    static String methodAddress(MethodNode m) {
        return address(m.getParentClass()) + "->" + m.getMethodInfo().getShortId();
    }

    static String fieldAddress(FieldNode f) {
        return address(f.getParentClass()) + "->" + f.getFieldInfo().getShortId();
    }

    String currentTypeSig(jadx.core.dex.instructions.args.ArgType type) {
        if (type == null) return "";
        if (type.isArray()) return "[" + currentTypeSig(type.getArrayElement());
        if (type.isObject()) {
            Symbol cls = byAddress.get("L" + type.getObject().replace('.', '/') + ";");
            if (cls != null) return currentAddress(cls.owner());
        }
        return jadx.core.codegen.TypeGen.signature(type);
    }

    Symbol lookup(String query) throws OpException {
        return lookupWithMode(query).symbol();
    }

    private List<ClassNode> matchingOwners(String ownerDesc) {
        List<ClassNode> out = new ArrayList<>(2);
        Symbol origCls = byAddress.get(ownerDesc);
        if (origCls != null && "class".equals(origCls.kind())) {
            out.add(origCls.owner());
        }
        for (ClassNode c : classes) {
            if (c != (origCls != null ? origCls.owner() : null) && ownerDesc.equals(currentAddress(c))) {
                out.add(c);
            }
        }
        return out;
    }

    ResolvedLookup lookupWithMode(String query) throws OpException {
        if (query == null || query.isBlank()) throw OpException.syntax("empty symbol", "jadx");
        String q = stripSiteOffset(query);
        if (q.isEmpty()) throw OpException.syntax("empty symbol", "jadx");

        // Fast path when query is an unrenamed original DEX descriptor
        Symbol exactOrig = byAddress.get(q);
        if (exactOrig != null && exactOrig.currentAddr().equals(q)) {
            return new ResolvedLookup(exactOrig, true, null, null, List.of(), false, q);
        }

        // 1. Exact current address match (scoped by owner class when L-prefixed)
        if (q.startsWith("L")) {
            int arrow = q.indexOf("->");
            if (arrow < 0) {
                for (ClassNode c : classes) {
                    if (currentAddress(c).equals(q)) {
                        Symbol s = byAddress.get(address(c));
                        if (s != null) return new ResolvedLookup(s, true, null, null, List.of(), false, q);
                    }
                }
            } else {
                String ownerDesc = q.substring(0, arrow);
                for (ClassNode c : matchingOwners(ownerDesc)) {
                    for (Symbol s : membersOf(c)) {
                        if (s.currentAddr().equals(q)) {
                            return new ResolvedLookup(s, true, null, null, List.of(), false, q);
                        }
                    }
                }
            }
        } else {
            for (Symbol s : byAddress.values()) {
                if (s.currentAddr().equals(q)) return new ResolvedLookup(s, true, null, null, List.of(), false, q);
            }
        }

        // 2. Exact original DEX signature match (when symbol or its types were renamed)
        if (exactOrig != null) {
            return new ResolvedLookup(exactOrig, true, "original signature", null, List.of(), false, q);
        }

        // 3. Exact dotted FQCN class match
        if (!q.contains("->") && !q.startsWith("L")) {
            List<Symbol> clsMatches = new ArrayList<>();
            for (ClassNode c : classes) {
                if (q.equals(c.getFullName())
                        || q.equals(c.getClassInfo().getRawName())
                        || q.equals(c.getClassInfo().makeAliasRawFullName())) {
                    Symbol s = byAddress.get(address(c));
                    if (s != null) clsMatches.add(s);
                }
            }
            if (clsMatches.size() == 1) return new ResolvedLookup(clsMatches.getFirst(), true, null, null, List.of(), false, q);
        }

        // 4. Member reference with '->'
        if (q.contains("->")) {
            int arrow = q.indexOf("->");
            String owner = q.substring(0, arrow), member = q.substring(arrow + 2);
            List<Symbol> inOwner = new ArrayList<>();
            for (ClassNode c : matchingOwners(owner)) {
                inOwner.addAll(membersOf(c));
            }
            List<Symbol> exactDesc = new ArrayList<>();
            List<Symbol> prefixMatches = new ArrayList<>();
            List<Symbol> fieldNameMatches = new ArrayList<>();
            List<Symbol> methodNameMatches = new ArrayList<>();
            for (Symbol s : inOwner) {
                String origTail = s.addr().substring(s.addr().indexOf("->") + 2);
                String curTail = s.currentAddr().substring(s.currentAddr().indexOf("->") + 2);
                int origSplit = s.kind().equals("method") ? origTail.indexOf('(') : origTail.indexOf(':');
                int curSplit = s.kind().equals("method") ? curTail.indexOf('(') : curTail.indexOf(':');
                String aliasOrigTail = s.name() + origTail.substring(origSplit);
                String origCurTail = s.originalName() + curTail.substring(curSplit);
                if (member.equals(origTail) || member.equals(curTail) || member.equals(aliasOrigTail) || member.equals(origCurTail)) {
                    exactDesc.add(s);
                } else if (s.kind().equals("method") && member.endsWith(")")
                        && (origTail.startsWith(member) || curTail.startsWith(member)
                            || aliasOrigTail.startsWith(member) || origCurTail.startsWith(member))) {
                    prefixMatches.add(s);
                } else if (!member.contains("(") && !member.contains(":")
                        && (member.equals(s.name()) || member.equals(s.originalName()))) {
                    if (s.kind().equals("field")) fieldNameMatches.add(s);
                    else methodNameMatches.add(s);
                }
            }
            if (exactDesc.size() == 1)
                return new ResolvedLookup(exactDesc.getFirst(), false, null, "address", List.of(), false, q);
            if (exactDesc.size() > 1) throw ambiguous(q, exactDesc);
            if (prefixMatches.size() == 1)
                return new ResolvedLookup(prefixMatches.getFirst(), false, null, "return type", List.of(), false, q);
            if (prefixMatches.size() > 1) throw ambiguous(q, prefixMatches);
            if (fieldNameMatches.size() == 1)
                return new ResolvedLookup(fieldNameMatches.getFirst(), false, null, "name only", List.of(), false, q);
            if (fieldNameMatches.size() > 1) throw ambiguous(q, fieldNameMatches);
            if (methodNameMatches.size() == 1)
                return new ResolvedLookup(methodNameMatches.getFirst(), false, null, "name only", List.of(), false, q);
            if (methodNameMatches.size() > 1) throw ambiguous(q, methodNameMatches);

            // Check lenient method match where parameter or return types differ (contradiction / rename history)
            int lp = member.indexOf('('), rp = member.indexOf(')', Math.max(0, lp));
            if (lp > 0 && rp > lp) {
                String askedName = member.substring(0, lp);
                List<Symbol> sameNameMethods = new ArrayList<>();
                for (Symbol s : inOwner) {
                    if (s.kind().equals("method") && (askedName.equals(s.name()) || askedName.equals(s.originalName())))
                        sameNameMethods.add(s);
                }
                if (sameNameMethods.size() == 1) {
                    Symbol s = sameNameMethods.getFirst();
                    String curTail = s.currentAddr().substring(s.currentAddr().indexOf("->") + 2);
                    String origTail = s.addr().substring(s.addr().indexOf("->") + 2);
                    int clp = curTail.indexOf('('), crp = curTail.indexOf(')', clp);
                    int olp = origTail.indexOf('('), orp = origTail.indexOf(')', olp);
                    String askedParams = member.substring(lp, rp + 1);
                    String askedRet = rp + 1 < member.length() ? member.substring(rp + 1) : "";
                    List<String> differs = new ArrayList<>();
                    if (!askedParams.equals(curTail.substring(clp, crp + 1)) && !askedParams.equals(origTail.substring(olp, orp + 1)))
                        differs.add("parameter types");
                    if (!askedRet.isEmpty() && !askedRet.equals(curTail.substring(crp + 1)) && !askedRet.equals(origTail.substring(orp + 1)))
                        differs.add("return type");
                    if (differs.isEmpty()) differs.add("parameter types");
                    return new ResolvedLookup(s, false, null, differs.getFirst(), differs, true, q);
                }
                if (sameNameMethods.size() > 1) throw ambiguous(q, sameNameMethods);
            }
            throw OpException.notFound("symbol not found: " + q, "jadx");
        }

        // 5. Dotted Owner.member or simple name
        List<Symbol> matches = new ArrayList<>();
        for (Symbol s : byAddress.values()) {
            boolean match = false;
            if (q.contains(".")) {
                if (!s.kind().equals("class")) {
                    match = q.equals(s.owner().getFullName() + "." + s.name())
                            || q.equals(s.owner().getClassInfo().getRawName() + "." + s.originalName())
                            || q.equals(s.owner().getClassInfo().makeAliasRawFullName() + "." + s.name());
                }
            } else if (!q.startsWith("L")) {
                match = q.equals(s.name()) || q.equals(s.originalName());
            }
            if (match) matches.add(s);
        }
        if (matches.size() == 1) {
            Symbol s = matches.getFirst();
            return new ResolvedLookup(s, false, null, s.kind().equals("class") ? "simple name" : "name only", List.of(), false, q);
        }
        if (matches.isEmpty()) throw OpException.notFound("symbol not found: " + q, "jadx");
        throw ambiguous(q, matches);
    }

    private static OpException ambiguous(String q, List<Symbol> matches) {
        OpException failure = OpException.notFound("ambiguous symbol: " + q + " — use an exact DEX descriptor", "jadx");
        failure.extra.put("candidates", matches.stream().limit(25).map(Symbol::row).toList());
        return failure;
    }

    Map<String, Object> resolve(String query) throws OpException {
        SiteRef site = splitSite(query);
        ResolvedLookup rl = lookupWithMode(query);
        Symbol s = rl.symbol();
        if (site != null && !"method".equals(s.kind())) {
            throw OpException.syntax("instruction offset requires a METHOD symbol: " + query, "symbol=" + query);
        }
        Map<String, Object> row = s.row();
        if (site != null) {
            String suffix = site.canonicalSuffix();
            row.put("addr", s.currentAddr() + suffix);
            row.put("origSig", s.addr() + suffix);
            row.put("sig", s.currentAddr() + suffix);
            row.put("site", true);
        }
        row.put("exact", rl.exact());
        if (rl.by() != null) row.put("by", rl.by());
        if (!rl.exact()) {
            row.put("asked", rl.asked());
            row.put("lenient", rl.lenient());
            if (!rl.differs().isEmpty()) row.put("differs", rl.differs());
            if (rl.contradiction()) {
                row.put("contradiction", true);
                String orig = s.addr(), cur = s.currentAddr();
                row.put("note", "the reference's " + String.join(" and ", rl.differs())
                        + " differ from the method found — a reading convenience, not evidence: exports, findings and hooks refuse it; the exact descriptor is "
                        + orig + (orig.equals(cur) ? "" : " (displayed here as " + cur + ")"));
            }
        }
        return row;
    }

    Map<String, Object> search(String query, int limit, String kind) throws OpException {
        if (kind != null && !Set.of("class", "method", "field").contains(kind))
            throw OpException.syntax("kind must be class, method or field", "jadx");
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        List<Symbol> matches = byAddress.values().stream()
                .filter(s -> kind == null || kind.equals(s.kind()))
                .filter(s -> !q.isEmpty() && (s.name().toLowerCase(Locale.ROOT).contains(q)
                        || s.originalName().toLowerCase(Locale.ROOT).contains(q)
                        || s.currentAddr().toLowerCase(Locale.ROOT).contains(q)
                        || s.addr().toLowerCase(Locale.ROOT).contains(q)))
                .sorted(Comparator.comparingInt((Symbol s) -> s.name().equals(query) ? 0
                        : s.name().equalsIgnoreCase(query) ? 1
                        : s.name().toLowerCase(Locale.ROOT).startsWith(q) ? 2 : 3)
                        .thenComparing(Symbol::kind).thenComparing(Symbol::addr)).toList();
        int count = limit > 0 ? Math.min(limit, matches.size()) : matches.size();
        return Map.of("rows", matches.subList(0, count).stream().map(Symbol::row).toList(),
                "total", matches.size(), "truncated", count < matches.size());
    }
}
