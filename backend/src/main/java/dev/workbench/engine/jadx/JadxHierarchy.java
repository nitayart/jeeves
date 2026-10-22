package dev.workbench.engine.jadx;

import dev.workbench.registry.OpException;
import java.util.*;
import java.util.stream.Collectors;
import jadx.core.codegen.TypeGen;
import jadx.core.dex.attributes.AType;
import jadx.core.dex.attributes.nodes.MethodOverrideAttr;
import jadx.core.dex.instructions.args.ArgType;
import jadx.core.dex.nodes.*;

/**
 * Package-private collaborator for JADX class/package navigation, outlines,
 * virtual-method override families, rename target expansion, and collision checks.
 */
final class JadxHierarchy {
    private JadxHierarchy() {}

    static Map<String, Object> treeUnder(JadxSymbols symbols, String prefix, int limit) throws OpException {
        String p = prefix == null ? "" : prefix.trim();
        if (p.endsWith(";")) {
            JadxSymbols.Symbol s = symbols.lookup(p);
            Map<String, Object> row = s.row();
            row.put("children", symbols.membersOf(s.owner()).stream()
                    .map(JadxSymbols.Symbol::row).toList());
            return Map.of("items", List.of(row), "total", 1, "truncated", false);
        }
        if (!p.startsWith("L")) p = "L" + p.replace('.', '/');
        if (!p.equals("L") && !p.endsWith("/")) p += "/";
        Map<String, Integer> packages = new TreeMap<>();
        List<Object> classes = new ArrayList<>();
        for (ClassNode cls : symbols.classes) {
            String origAddr = JadxSymbols.address(cls);
            String curAddr = JadxSymbols.currentAddress(cls);
            String matchAddr = curAddr.startsWith(p) ? curAddr : origAddr.startsWith(p) ? origAddr : null;
            if (matchAddr == null) continue;
            String rest = matchAddr.substring(p.length());
            int slash = rest.indexOf('/');
            if (slash >= 0) {
                packages.merge(p + rest.substring(0, slash + 1), 1, Integer::sum);
            } else {
                JadxSymbols.Symbol clsSym = symbols.byAddress.get(origAddr);
                if (clsSym != null) classes.add(clsSym.row());
            }
        }
        List<Object> rows = new ArrayList<>();
        packages.forEach((pkg, count) -> rows.add(Map.of(
                "kind", "package",
                "name", pkg.substring(1, pkg.length() - 1),
                "prefix", pkg,
                "count", count)));
        int cap = limit > 0 ? limit : 500;
        rows.addAll(classes.subList(0, Math.min(cap, classes.size())));
        return Map.of("items", rows, "total", packages.size() + classes.size(), "truncated", classes.size() > cap);
    }

    static Map<String, Object> outline(JadxSymbols symbols, String symbol) throws OpException {
        ClassNode c = symbols.lookup(symbol).owner();
        String addr = JadxSymbols.address(c);
        List<JadxSymbols.Symbol> members = symbols.membersOf(c);
        return Map.of(
                "symbol", symbols.byAddress.get(addr).row(),
                "methods", members.stream().filter(s -> s.kind().equals("method")).map(JadxSymbols.Symbol::row).toList(),
                "fields", members.stream().filter(s -> s.kind().equals("field")).map(JadxSymbols.Symbol::row).toList());
    }

    static Map<String, Object> outline(JadxSymbols symbols, String symbol, boolean inherited) throws OpException {
        Map<String, Object> result = new LinkedHashMap<>(outline(symbols, symbol));
        if (!inherited) return result;
        ClassNode current = symbols.lookup(symbol).owner();
        List<Object> fields = new ArrayList<>();
        List<String> chain = new ArrayList<>(), unknown = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        while (current.getSuperClass() != null) {
            String parent = TypeGen.signature(current.getSuperClass());
            if (!seen.add(parent) || parent.equals("Ljava/lang/Object;")) break;
            JadxSymbols.Symbol found = symbols.byAddress.get(parent);
            if (found == null) { unknown.add(parent); break; }
            current = found.owner();
            chain.add(parent);
            for (FieldNode f : current.getFields()) {
                JadxSymbols.Symbol fieldSym = symbols.byAddress.get(JadxSymbols.fieldAddress(f));
                if (fieldSym != null) {
                    Map<String, Object> row = fieldSym.row();
                    row.put("inheritedFrom", parent);
                    fields.add(row);
                }
            }
        }
        result.put("inherited", Map.of("chain", chain, "fields", fields, "unknown", unknown));
        return result;
    }

    static Map<String, Object> classinfo(JadxSymbols symbols, String symbol) throws OpException {
        ClassNode c = symbols.lookup(symbol).owner();
        Map<String, Object> result = symbols.byAddress.get(JadxSymbols.address(c)).row();
        result.put("super", c.getSuperClass() == null ? null : symbols.currentTypeSig(c.getSuperClass()));
        result.put("interfaces", c.getInterfaces().stream().map(symbols::currentTypeSig).toList());
        result.put("flags", c.getAccessFlags().makeString(false).trim());
        result.put("methods", c.getMethods().size());
        result.put("fields", c.getFields().size());
        result.put("innerClasses", c.getInnerClasses().stream().map(JadxSymbols::currentAddress).toList());
        result.put("renamed", !c.getName().equals(c.getAlias()));
        return result;
    }

    static List<Object> implementations(JadxSymbols symbols, String symbol) throws OpException {
        JadxSymbols.Symbol selected = symbols.lookup(symbol);
        if (!(selected.node() instanceof MethodNode method)) {
            throw OpException.syntax("implementations needs a method", "jadx");
        }
        if (method.getAccessFlags().isStatic() || method.getAccessFlags().isPrivate() || method.getName().startsWith("<")) {
            return List.of();
        }
        Set<String> descendants = descendantRawNames(symbols, selected.owner().getClassInfo().getRawName());
        String shortId = method.getMethodInfo().getShortId();
        List<Object> result = new ArrayList<>();
        for (ClassNode c : symbols.classes) {
            if (!descendants.contains(c.getClassInfo().getRawName())) continue;
            for (JadxSymbols.Symbol s : symbols.membersOf(c)) {
                if (!(s.node() instanceof MethodNode candidate) || candidate == method
                        || candidate.getAccessFlags().isAbstract()
                        || candidate.getAccessFlags().isStatic()
                        || candidate.getAccessFlags().isPrivate()) continue;
                if (!candidate.getMethodInfo().getShortId().equals(shortId)) continue;
                Map<String, Object> row = s.row();
                row.put("kind", "override");
                result.add(row);
            }
        }
        return result;
    }

    private static Set<String> descendantRawNames(JadxSymbols symbols, String rootRawName) {
        Set<String> descendants = new HashSet<>();
        descendants.add(rootRawName);
        boolean changed;
        do {
            changed = false;
            for (ClassNode c : symbols.classes) {
                boolean child = c.getSuperClass() != null && descendants.contains(c.getSuperClass().getObject());
                if (!child) {
                    for (var t : c.getInterfaces()) {
                        if (descendants.contains(t.getObject())) { child = true; break; }
                    }
                }
                if (child) changed |= descendants.add(c.getClassInfo().getRawName());
            }
        } while (changed);
        return descendants;
    }

    static List<MethodNode> overrideFamily(JadxSymbols symbols, MethodNode method) {
        if (method.getAccessFlags().isStatic() || method.getAccessFlags().isPrivate()
                || method.isConstructor() || method.getName().startsWith("<")) {
            return List.of(method);
        }
        Set<MethodNode> result = new LinkedHashSet<>();
        result.add(method);
        MethodOverrideAttr attr = method.get(AType.METHOD_OVERRIDE);
        if (attr != null && attr.getRelatedMthNodes() != null) {
            result.addAll(attr.getRelatedMthNodes());
        }
        // Walk connected hierarchy in symbols.classes in case MethodOverrideAttr was not populated on an unloaded class
        String shortId = method.getMethodInfo().getShortId();
        Set<ClassNode> visited = new LinkedHashSet<>();
        Deque<ClassNode> queue = new ArrayDeque<>();
        for (MethodNode m : result) {
            if (visited.add(m.getParentClass())) queue.add(m.getParentClass());
        }
        while (!queue.isEmpty()) {
            ClassNode cur = queue.removeFirst();
            if (cur.getSuperClass() != null) {
                ClassNode sup = symbols.classByRawName.get(cur.getSuperClass().getObject());
                if (sup != null && visited.add(sup)) queue.add(sup);
            }
            for (var ifaceType : cur.getInterfaces()) {
                ClassNode iface = symbols.classByRawName.get(ifaceType.getObject());
                if (iface != null && visited.add(iface)) queue.add(iface);
            }
            String curRaw = cur.getClassInfo().getRawName();
            for (ClassNode sub : symbols.classes) {
                boolean extendsCur = sub.getSuperClass() != null && curRaw.equals(sub.getSuperClass().getObject());
                if (!extendsCur) {
                    for (var it : sub.getInterfaces()) {
                        if (curRaw.equals(it.getObject())) { extendsCur = true; break; }
                    }
                }
                if (extendsCur && visited.add(sub)) queue.add(sub);
            }
        }
        for (ClassNode c : visited) {
            for (MethodNode cand : c.getMethods()) {
                if (cand.getAccessFlags().isStatic() || cand.getAccessFlags().isPrivate()
                        || cand.isConstructor() || cand.getName().startsWith("<")) continue;
                if (cand.getMethodInfo().getShortId().equals(shortId)) result.add(cand);
            }
        }
        return List.copyOf(result);
    }

    static Set<ClassNode> hierarchyClasses(JadxSymbols symbols, ClassNode root) {
        Set<ClassNode> out = new LinkedHashSet<>();
        out.add(root);
        Deque<ClassNode> up = new ArrayDeque<>();
        up.add(root);
        while (!up.isEmpty()) {
            ClassNode cur = up.removeFirst();
            if (cur.getSuperClass() != null) {
                ClassNode sup = symbols.classByRawName.get(cur.getSuperClass().getObject());
                if (sup != null && out.add(sup)) up.add(sup);
            }
            for (var it : cur.getInterfaces()) {
                ClassNode iface = symbols.classByRawName.get(it.getObject());
                if (iface != null && out.add(iface)) up.add(iface);
            }
        }
        for (String raw : descendantRawNames(symbols, root.getClassInfo().getRawName())) {
            ClassNode desc = symbols.classByRawName.get(raw);
            if (desc != null) out.add(desc);
        }
        return out;
    }

    static List<Map<String, Object>> renameTargets(JadxSymbols symbols, Map<String, Object> resolvedFallback, String symbol) throws OpException {
        JadxSymbols.Symbol sym = symbols.lookup(symbol);
        if (sym.node() instanceof MethodNode m) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (MethodNode fm : overrideFamily(symbols, m)) {
                JadxSymbols.Symbol fs = symbols.byAddress.get(JadxSymbols.methodAddress(fm));
                if (fs != null) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("origSig", fs.addr());
                    row.put("addr", fs.currentAddr());
                    row.put("name", fs.name());
                    row.put("oldName", fs.name());
                    row.put("kind", "method");
                    out.add(row);
                }
            }
            if (!out.isEmpty()) return out;
        }
        Map<String, Object> row = new LinkedHashMap<>(resolvedFallback);
        row.putIfAbsent("oldName", sym.name());
        return List.of(row);
    }

    static List<Map<String, Object>> collectRenameConflicts(JadxSymbols symbols, JadxSymbols.Symbol sym,
                                                            String newName, List<JadxSymbols.Symbol> changedSymbols) {
        String origSig = sym.addr();
        List<Map<String, Object>> conflicts = new ArrayList<>();
        if (sym.node() instanceof FieldNode f) {
            for (FieldNode other : sym.owner().getFields()) {
                if (other != f && newName.equals(other.getAlias())) {
                    JadxSymbols.Symbol otherSym = symbols.byAddress.get(JadxSymbols.fieldAddress(other));
                    if (otherSym != null) conflicts.add(nameOccupant(otherSym, "field", "same_class_field", null));
                }
            }
            changedSymbols.add(sym);
        } else if (sym.node() instanceof MethodNode m) {
            List<MethodNode> family = overrideFamily(symbols, m);
            Set<MethodNode> familySet = new HashSet<>(family);
            Map<String, Map<String, Object>> foundConflicts = new LinkedHashMap<>();
            for (MethodNode fm : family) {
                String fmOrig = JadxSymbols.methodAddress(fm);
                for (MethodNode other : fm.getParentClass().getMethods()) {
                    if (familySet.contains(other)) continue;
                    if (newName.equals(other.getAlias()) && sameParamTypes(fm, other)) {
                        JadxSymbols.Symbol otherSym = symbols.byAddress.get(JadxSymbols.methodAddress(other));
                        if (otherSym != null) {
                            foundConflicts.put(otherSym.addr(), nameOccupant(otherSym, "method", "same_class_method_descriptor", fmOrig));
                        }
                    }
                }
            }
            if (foundConflicts.isEmpty() && !m.getAccessFlags().isStatic() && !m.getAccessFlags().isPrivate()) {
                Set<ClassNode> scopeClasses = new LinkedHashSet<>();
                for (MethodNode fm : family) scopeClasses.addAll(hierarchyClasses(symbols, fm.getParentClass()));
                for (ClassNode hc : scopeClasses) {
                    for (MethodNode other : hc.getMethods()) {
                        if (familySet.contains(other) || other.getAccessFlags().isStatic()
                                || other.getAccessFlags().isPrivate() || other.isConstructor()
                                || other.getName().startsWith("<")) continue;
                        if (newName.equals(other.getAlias()) && sameParamTypes(m, other)) {
                            JadxSymbols.Symbol otherSym = symbols.byAddress.get(JadxSymbols.methodAddress(other));
                            if (otherSym != null) {
                                foundConflicts.put(otherSym.addr(), nameOccupant(otherSym, "method", "override_hierarchy_method_descriptor", origSig));
                            }
                        }
                    }
                }
            }
            conflicts.addAll(foundConflicts.values());
            for (MethodNode fm : family) {
                JadxSymbols.Symbol fs = symbols.byAddress.get(JadxSymbols.methodAddress(fm));
                if (fs != null) changedSymbols.add(fs);
            }
        } else if (sym.node() instanceof ClassNode c) {
            String targetDesc;
            if (c.isTopClass()) {
                String pkg = c.getClassInfo().getAliasPkg();
                String targetRaw = pkg.isEmpty() ? newName : pkg + "." + newName;
                targetDesc = "L" + targetRaw.replace('.', '/') + ";";
            } else {
                String parentRaw = c.getParentClass().getClassInfo().makeAliasRawFullName();
                targetDesc = "L" + parentRaw.replace('.', '/') + "$" + newName + ";";
            }
            for (ClassNode other : symbols.classes) {
                if (other != c && targetDesc.equals(JadxSymbols.currentAddress(other))) {
                    JadxSymbols.Symbol otherSym = symbols.byAddress.get(JadxSymbols.address(other));
                    if (otherSym != null) conflicts.add(nameOccupant(otherSym, "class", "destination_class", null));
                }
            }
            changedSymbols.add(sym);
        }
        return conflicts;
    }

    static Set<ClassNode> affectedTopClasses(JadxSymbols symbols, JadxRelations rels, Collection<JadxSymbols.Symbol> changed) {
        Set<ClassNode> tops = new LinkedHashSet<>();
        for (JadxSymbols.Symbol s : changed) {
            ClassNode top = s.owner().getTopParentClass();
            tops.add(top);
            tops.addAll(rels.referrerTopClasses(s.addr()));
            if (s.node() instanceof ClassNode renamedClass) {
                String rawName = renamedClass.getClassInfo().getRawName();
                collectClassTreeReferrers(top, symbols, rels, tops);
                for (ClassNode candidate : symbols.classes) {
                    if (classSignatureReferences(candidate, rawName)) {
                        tops.add(candidate.getTopParentClass());
                    }
                }
            }
        }
        return tops;
    }

    private static void collectClassTreeReferrers(ClassNode cls, JadxSymbols symbols, JadxRelations rels, Set<ClassNode> tops) {
        tops.addAll(rels.referrerTopClasses(JadxSymbols.address(cls)));
        for (JadxSymbols.Symbol member : symbols.membersOf(cls)) {
            tops.addAll(rels.referrerTopClasses(member.addr()));
        }
        for (ClassNode inner : cls.getInnerClasses()) {
            collectClassTreeReferrers(inner, symbols, rels, tops);
        }
    }

    static OpException renameRefused(String old, String wanted, String original, String addr,
                                     List<Map<String, Object>> conflicts) {
        String occupied = conflicts.stream().map(row -> String.valueOf(row.get("originalSymbol"))
                + " (current: " + row.get("addr") + ", name: " + row.get("currentName") + ")")
                .collect(Collectors.joining("; "));
        OpException error = OpException.busy("rename refused by JADX: '" + old + "' → '" + wanted + "' did not apply — "
                + (conflicts.isEmpty() ? "no occupying symbol could be identified"
                        : "the requested name is used by " + occupied), "symbol=" + addr + " name=" + wanted);
        error.extra.put("originalSymbol", original);
        error.extra.put("currentName", old);
        error.extra.put("requestedName", wanted);
        error.extra.put("conflicts", conflicts);
        error.extra.put("collisionLookup", conflicts.isEmpty() ? "unresolved" : "matched");
        error.extra.put("hint", "Inspect conflicts by originalSymbol; choose a distinct evidence-backed name for your symbol. Do not rename the occupying symbol merely to free its name.");
        return error;
    }

    private static boolean sameParamTypes(MethodNode a, MethodNode b) {
        return Objects.equals(a.getMethodInfo().getArgumentsTypes(), b.getMethodInfo().getArgumentsTypes());
    }

    private static Map<String, Object> nameOccupant(JadxSymbols.Symbol otherSym, String kind, String match, String affectedOriginalSymbol) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("kind", kind);
        row.put("originalSymbol", otherSym.addr());
        row.put("addr", otherSym.currentAddr());
        row.put("currentName", otherSym.name());
        row.put("originalName", otherSym.originalName());
        row.put("match", match);
        if (!"class".equals(kind)) row.put("owner", JadxSymbols.currentAddress(otherSym.owner()));
        if (affectedOriginalSymbol != null) row.put("affectedOriginalSymbol", affectedOriginalSymbol);
        return row;
    }

    private static boolean referencesClassType(ArgType type, String rawClassName) {
        if (type == null) return false;
        if (type.isArray()) return referencesClassType(type.getArrayElement(), rawClassName);
        if (type.isObject()) {
            String obj = type.getObject();
            if (rawClassName.equals(obj) || (obj != null && obj.startsWith(rawClassName + "$"))) return true;
            if (type.getGenericTypes() != null) {
                for (var gt : type.getGenericTypes()) {
                    if (referencesClassType(gt, rawClassName)) return true;
                }
            }
        }
        return false;
    }

    private static boolean classSignatureReferences(ClassNode candidate, String rawClassName) {
        if (referencesClassType(candidate.getSuperClass(), rawClassName)) return true;
        for (var iface : candidate.getInterfaces()) {
            if (referencesClassType(iface, rawClassName)) return true;
        }
        for (FieldNode f : candidate.getFields()) {
            if (referencesClassType(f.getType(), rawClassName)) return true;
        }
        for (MethodNode m : candidate.getMethods()) {
            if (referencesClassType(m.getReturnType(), rawClassName)) return true;
            for (var arg : m.getArgTypes()) {
                if (referencesClassType(arg, rawClassName)) return true;
            }
        }
        return false;
    }
}
