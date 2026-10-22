package dev.workbench.engine.jadx;

import dev.workbench.engine.Engine;
import dev.workbench.engine.TypeNames;
import dev.workbench.registry.OpException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import jadx.api.ICodeInfo;
import jadx.api.data.CodeRefType;
import jadx.api.data.ICodeRename;
import jadx.api.data.impl.JadxCodeRef;
import jadx.api.data.impl.JadxCodeRename;
import jadx.api.data.impl.JadxNodeRef;
import jadx.api.metadata.ICodeAnnotation;
import jadx.api.metadata.annotations.NodeDeclareRef;
import jadx.api.metadata.annotations.VarNode;
import jadx.api.plugins.input.data.IDebugInfo;
import jadx.api.plugins.input.data.ILocalVar;
import jadx.api.plugins.input.data.attributes.JadxAttrType;
import jadx.api.plugins.input.data.attributes.types.MethodParametersAttr;
import jadx.api.utils.CodeUtils;
import jadx.core.dex.nodes.ClassNode;
import jadx.core.dex.nodes.MethodNode;

/**
 * Package-private collaborator for JADX method local variables and formal parameters
 * inspection, naming, and metadata extraction.
 */
final class JadxLocals {
    private final String targetId;
    private final JadxSession session;
    private final Map<String, String> originalLocalNames = new ConcurrentHashMap<>();
    private final Set<String> seededMethods = ConcurrentHashMap.newKeySet();

    JadxLocals(String targetId, JadxSession session) {
        this.targetId = targetId;
        this.session = session;
    }

    private record LocalInfo(VarNode node, String name, String type, int line, Integer parameterIndex) {
        JadxCodeRef codeRef() {
            return parameterIndex == null
                    ? JadxCodeRef.forVar(node.getReg(), node.getSsa())
                    : JadxCodeRef.forMthArg(parameterIndex);
        }
    }

    private MethodNode methodNode(String symbol) throws OpException {
        JadxSymbols.Symbol resolved = session.symbols().lookup(symbol);
        if (!(resolved.node() instanceof MethodNode method)) {
            throw OpException.syntax("this operation takes a METHOD: " + symbol + " is a " + resolved.kind(), "symbol=" + symbol);
        }
        return method;
    }

    private static String localOriginalMethod(MethodNode method) {
        return JadxSymbols.methodAddress(method);
    }

    private static String localStableId(MethodNode method, LocalInfo local) {
        return localOriginalMethod(method) + "#local:r" + local.node().getReg() + ":s" + local.node().getSsa();
    }

    private void ensureOriginalLocalNames(MethodNode method) throws OpException {
        String methodKey = localOriginalMethod(method);
        if (!seededMethods.add(methodKey)) return;
        JadxNodeRef methodRef = JadxSession.nodeRefFor(method);
        List<ICodeRename> allRenames = new ArrayList<>(session.codeData().getRenames());
        boolean hasMethodLocalRenames = allRenames.stream()
                .anyMatch(r -> Objects.equals(r.getNodeRef(), methodRef) && r.getCodeRef() != null);
        if (!hasMethodLocalRenames) {
            for (LocalInfo local : localInfos(method)) {
                originalLocalNames.putIfAbsent(localStableId(method, local), local.name());
            }
            return;
        }
        // Temporarily strip this method's local/arg renames to read the unaliased JADX variable names
        List<ICodeRename> withoutMethodLocals = allRenames.stream()
                .filter(r -> !(Objects.equals(r.getNodeRef(), methodRef) && r.getCodeRef() != null))
                .collect(Collectors.toCollection(ArrayList::new));
        ClassNode top = method.getTopParentClass();
        session.codeData().setRenames(withoutMethodLocals);
        session.reloadCodeData();
        JadxSession.evictClass(top);
        try {
            for (LocalInfo local : localInfos(method)) {
                originalLocalNames.putIfAbsent(localStableId(method, local), local.name());
            }
        } finally {
            session.codeData().setRenames(allRenames);
            session.reloadCodeData();
            JadxSession.evictClass(top);
        }
    }

    /** Process the owning class and read the variable declarations emitted into JADX's code metadata. */
    private List<LocalInfo> localInfos(MethodNode method) throws OpException {
        JadxSymbols symbols = session.symbols();
        ICodeInfo code = method.getTopParentClass().getCode();
        Map<String, Integer> parameterIndices = new HashMap<>();
        List<VarNode> args = method.collectArgNodes();
        for (int i = 0; i < args.size(); i++) {
            parameterIndices.put(args.get(i).getReg() + ":" + args.get(i).getSsa(), i);
        }
        Map<String, LocalInfo> vars = new LinkedHashMap<>();
        for (Map.Entry<Integer, ICodeAnnotation> entry : code.getCodeMetadata().getAsMap().entrySet()) {
            VarNode var = null;
            ICodeAnnotation ann = entry.getValue();
            if (ann instanceof NodeDeclareRef declaration && declaration.getNode() instanceof VarNode node) var = node;
            else if (ann instanceof VarNode node) var = node;
            if (var == null || var.getMth() != method) continue;
            String key = var.getReg() + ":" + var.getSsa();
            Integer parameterIndex = parameterIndices.get(key);
            String type = symbols.currentTypeSig(var.getType());
            int line = CodeUtils.getLineNumForPos(code.getCodeStr(), entry.getKey(), System.lineSeparator());
            vars.putIfAbsent(key, new LocalInfo(var, var.getName(), type, line, parameterIndex));
        }
        // An unused formal argument can have no declaration occurrence in the body metadata.
        for (int i = 0; i < args.size(); i++) {
            VarNode var = args.get(i);
            String key = var.getReg() + ":" + var.getSsa();
            vars.putIfAbsent(key, new LocalInfo(var, var.getName(), symbols.currentTypeSig(var.getType()), 0, i));
        }
        return new ArrayList<>(vars.values());
    }

    private LocalInfo selectedLocal(MethodNode method, String local) throws OpException {
        List<LocalInfo> matches = localInfos(method).stream().filter(v -> Objects.equals(v.name(), local)).toList();
        if (matches.isEmpty()) {
            throw OpException.notFound("no local named '" + local + "' in " + localOriginalMethod(method), "target=" + targetId);
        }
        if (matches.size() > 1) {
            throw OpException.syntax("local name '" + local + "' is ambiguous in " + localOriginalMethod(method)
                    + " — rename one through its stable identity first", "symbol=" + localOriginalMethod(method));
        }
        return matches.get(0);
    }

    List<Object> locals(String methodSymbol) throws OpException {
        MethodNode method = methodNode(methodSymbol);
        ensureOriginalLocalNames(method);
        List<Object> out = new ArrayList<>();
        for (LocalInfo local : localInfos(method)) {
            String stable = localStableId(method, local);
            String original = originalLocalNames.computeIfAbsent(stable, ignored -> local.name());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", local.name());
            row.put("type", local.type());
            row.put("line", local.line());
            row.put("originalMethod", localOriginalMethod(method));
            row.put("originalSymbol", stable);
            row.put("originalName", original);
            row.put("register", local.node().getReg());
            row.put("ssa", local.node().getSsa());
            row.put("renameableLocal", true);
            if (local.parameterIndex() != null) row.put("parameterIndex", local.parameterIndex());
            out.add(row);
        }
        return out;
    }

    Map<String, Object> resolveLocal(String methodSymbol, String local) throws OpException {
        if (local == null || local.isBlank()) {
            throw OpException.syntax("local names the variable to resolve", "symbol=" + methodSymbol);
        }
        MethodNode method = methodNode(methodSymbol);
        ensureOriginalLocalNames(method);
        LocalInfo selected = selectedLocal(method, local);
        return Map.of(
                "name", selected.name(),
                "originalMethod", localOriginalMethod(method),
                "originalSymbol", localStableId(method, selected));
    }

    Map<String, Object> renameLocal(String methodSymbol, String local, String newName, String actor,
                                    Consumer<ClassNode> onClassMutated) throws OpException {
        if (!Engine.usableName(newName)) {
            throw OpException.syntax("bad Java identifier: " + newName, "symbol=" + methodSymbol);
        }
        if (local == null || local.isBlank()) {
            throw OpException.syntax("local names the variable to rename (its current name)", "symbol=" + methodSymbol);
        }
        MethodNode method = methodNode(methodSymbol);
        ensureOriginalLocalNames(method);
        LocalInfo selected = selectedLocal(method, local);
        String origMethodAddr = localOriginalMethod(method);
        String addr = session.symbols().byAddress.get(origMethodAddr).currentAddr();
        if (newName.equals(selected.name())) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("addr", addr);
            out.put("newAddr", addr);
            out.put("kind", "local");
            out.put("local", local);
            out.put("oldName", local);
            out.put("newName", newName);
            out.put("applied", false);
            out.put("unchanged", true);
            out.put("reason", "This variable already has this name.");
            return out;
        }
        List<LocalInfo> vars = localInfos(method);
        List<Map<String, Object>> localConflicts = new ArrayList<>();
        for (LocalInfo v : vars) {
            if (v == selected || !newName.equals(v.name())) continue;
            String otherStable = localStableId(method, v);
            String otherOrig = originalLocalNames.computeIfAbsent(otherStable, ignored -> v.name());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("kind", "local");
            row.put("originalSymbol", otherStable);
            row.put("originalMethod", origMethodAddr);
            row.put("addr", addr);
            row.put("currentName", v.name());
            row.put("originalName", otherOrig);
            row.put("line", v.line());
            row.put("match", "same_method_identifier");
            localConflicts.add(row);
        }
        String stable = localStableId(method, selected);
        if (!localConflicts.isEmpty()) {
            throw JadxHierarchy.renameRefused(selected.name(), newName, stable, addr, localConflicts);
        }
        String origName = originalLocalNames.computeIfAbsent(stable, ignored -> selected.name());
        JadxNodeRef ref = JadxSession.nodeRefFor(method);
        List<ICodeRename> previousRenames = new ArrayList<>(session.codeData().getRenames());
        ClassNode top = method.getTopParentClass();
        try {
            if (newName.equals(origName) && selected.parameterIndex() == null) {
                session.upsertRename(ref, selected.codeRef(), null);
            } else {
                session.upsertRename(ref, selected.codeRef(), newName);
            }
            JadxSession.evictClass(top);
            boolean updated = localInfos(method).stream()
                    .anyMatch(v -> v.node().getReg() == selected.node().getReg()
                            && v.node().getSsa() == selected.node().getSsa()
                            && newName.equals(v.name()));
            if (!updated) {
                throw OpException.busy("JADX did not apply local rename '" + selected.name() + "' → '" + newName + "'",
                        "symbol=" + addr + " local=" + local);
            }
        } catch (OpException | RuntimeException failure) {
            session.codeData().setRenames(previousRenames);
            try { session.reloadCodeData(); } catch (RuntimeException ignored) {}
            JadxSession.evictClass(top);
            throw failure;
        }
        onClassMutated.accept(top);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("symbol", Map.of("addr", addr, "kind", "method", "name", method.getAlias()));
        out.put("addr", addr);
        out.put("newAddr", addr);
        out.put("kind", "local");
        out.put("local", local);
        out.put("oldName", selected.name());
        out.put("newName", newName);
        out.put("applied", true);
        out.put("actor", actor == null ? "human" : actor);
        return out;
    }

    Map<String, Object> autoNameLocals(String methodSymbol, boolean all, String actor,
                                       Consumer<ClassNode> onClassMutated) throws OpException {
        MethodNode method = methodNode(methodSymbol);
        ensureOriginalLocalNames(method);
        List<Object> renamed = new ArrayList<>(), skipped = new ArrayList<>();
        Set<String> used = new HashSet<>();
        for (LocalInfo v : localInfos(method)) used.add(v.name());
        // Apply one at a time because each reload constructs new VarNode identities.
        List<String> candidates = localInfos(method).stream()
                .filter(v -> v.parameterIndex() == null)
                .map(LocalInfo::name)
                .distinct()
                .toList();
        for (String old : candidates) {
            LocalInfo v;
            try { v = selectedLocal(method, old); } catch (OpException missing) { continue; }
            if (!all && !v.name().matches("(v|arg|p|var|local)\\d+(_\\d+)?")) continue;
            String base = TypeNames.nameForType(v.type());
            if (base == null) {
                skipped.add(Map.of("local", old, "why", v.type() == null ? "no type" : "primitive " + v.type()));
                continue;
            }
            String pick = null;
            for (int suffix = 1; suffix <= 20; suffix++) {
                String proposed = suffix == 1 ? base : base + "_" + suffix;
                if (!used.contains(proposed)) { pick = proposed; break; }
            }
            if (pick == null) {
                skipped.add(Map.of("local", old, "why", "no free name for " + base));
                continue;
            }
            renameLocal(methodSymbol, v.name(), pick, actor, onClassMutated);
            used.remove(old);
            used.add(pick);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("local", old);
            row.put("name", pick);
            row.put("type", v.type());
            renamed.add(row);
        }
        String addr = session.symbols().byAddress.get(localOriginalMethod(method)).currentAddr();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("addr", addr);
        out.put("renames", renamed);
        out.put("skipped", skipped);
        out.put("actor", actor == null ? "human" : actor);
        return out;
    }

    private static List<String> decodeParameterFlags(int flags) {
        List<String> out = new ArrayList<>();
        if ((flags & 0x0010) != 0) out.add("final");
        if ((flags & 0x1000) != 0) out.add("synthetic");
        if ((flags & 0x8000) != 0) out.add("mandated");
        int unknown = flags & ~(0x0010 | 0x1000 | 0x8000);
        if (unknown != 0) out.add("0x" + Integer.toHexString(unknown));
        return out;
    }

    private static List<String> parameterNames(MethodNode method) {
        method.getTopParentClass().getCode();
        List<VarNode> args = method.collectArgNodes();
        List<String> names = new ArrayList<>();
        for (int i = 0; i < method.getArgTypes().size(); i++) {
            names.add(i < args.size() ? args.get(i).getName() : "arg" + i);
        }
        return names;
    }

    Map<String, Object> methodParameters(String methodSymbol, boolean withCurrent) throws OpException {
        JadxSymbols symbols = session.symbols();
        MethodNode method = methodNode(methodSymbol);
        List<String> types = method.getArgTypes().stream().map(symbols::currentTypeSig).toList();
        Map<String, Object> prototype = new LinkedHashMap<>();
        prototype.put("parameterTypes", types);
        prototype.put("count", types.size());
        prototype.put("static", method.getAccessFlags().isStatic());
        prototype.put("registerWords", method.getArgTypes().stream().mapToInt(t -> t.getRegCount()).sum()
                + (method.getAccessFlags().isStatic() ? 0 : 1));
        prototype.put("returnType", symbols.currentTypeSig(method.getReturnType()));

        MethodParametersAttr attr = method.get(JadxAttrType.METHOD_PARAMETERS);
        Map<String, Object> annotation = new LinkedHashMap<>();
        annotation.put("source", "dex_method_parameters_annotation");
        List<Object> annotationNames = new ArrayList<>(), flags = new ArrayList<>(), decoded = new ArrayList<>();
        if (attr == null) {
            annotation.put("status", "absent");
        } else {
            for (MethodParametersAttr.Info info : attr.getList()) {
                annotationNames.add(info.getName());
                flags.add(info.getAccFlags());
                decoded.add(decodeParameterFlags(info.getAccFlags()));
            }
            boolean match = annotationNames.size() == types.size();
            annotation.put("status", match ? "present" : "malformed");
            annotation.put("names", annotationNames);
            annotation.put("accessFlags", flags);
            annotation.put("accessFlagsDecoded", decoded);
            annotation.put("namesCount", annotationNames.size());
            annotation.put("flagsCount", flags.size());
            annotation.put("namesCountMatchesPrototype", match);
            annotation.put("flagsCountMatchesPrototype", match);
            annotation.put("countMatchesPrototype", match);
            annotation.put("encodedOrder", true);
            annotation.put("argumentMapping", "unverified");
            if (!match) {
                annotation.put("mismatch", annotationNames.size() + " name(s) for " + types.size()
                        + " formal parameter(s) — the metadata describes an earlier prototype");
            }
        }

        List<Object> debugNames = new ArrayList<>();
        IDebugInfo debug = method.getDebugInfo();
        if (debug != null && debug.getLocalVars() != null) {
            List<ILocalVar> params = debug.getLocalVars().stream()
                    .filter(ILocalVar::isMarkedAsParameter)
                    .sorted(Comparator.comparingInt(ILocalVar::getRegNum))
                    .toList();
            if (!params.isEmpty()) {
                int totalRegs = method.getCodeReader() != null ? method.getCodeReader().getRegistersCount() : 0;
                int argWords = method.getArgTypes().stream().mapToInt(t -> t.getRegCount()).sum()
                        + (method.getAccessFlags().isStatic() ? 0 : 1);
                int firstArgReg = Math.max(0, totalRegs - argWords) + (method.getAccessFlags().isStatic() ? 0 : 1);
                Map<Integer, String> byReg = new HashMap<>();
                for (ILocalVar param : params) {
                    if (param.getName() != null) byReg.put(param.getRegNum(), param.getName());
                }
                int reg = firstArgReg;
                for (int i = 0; i < types.size(); i++) {
                    debugNames.add(byReg.get(reg));
                    reg += method.getArgTypes().get(i).getRegCount();
                }
            }
        }
        Map<String, Object> debugInfo = new LinkedHashMap<>();
        debugInfo.put("source", "dex_debug_info_parameter_names");
        debugInfo.put("status", debugNames.isEmpty() ? (method.isNoCode() ? "no-code" : "absent") : "present");
        if (!debugNames.isEmpty()) {
            debugInfo.put("names", debugNames);
            debugInfo.put("count", debugNames.size());
            debugInfo.put("nonEmpty", debugNames.stream().filter(Objects::nonNull).count());
            debugInfo.put("countMatchesPrototype", debugNames.size() == types.size());
        }

        String origMethod = localOriginalMethod(method);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schema", "method-parameters-v1");
        out.put("method", symbols.byAddress.get(origMethod).currentAddr());
        out.put("original_method", origMethod);
        out.put("dex", method.getInputFileName());
        out.put("prototype", prototype);
        out.put("annotation", annotation);
        out.put("debugInfo", debugInfo);
        out.put("hasCode", !method.isNoCode());
        out.put("argumentMapping", "unverified");
        if (withCurrent) {
            List<String> current = parameterNames(method);
            List<Object> parameters = new ArrayList<>();
            List<VarNode> argNodes = method.collectArgNodes();
            int reg = method.getArgsStartReg();
            for (int i = 0; i < types.size(); i++) {
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("index", i);
                p.put("type", types.get(i));
                p.put("register", i < argNodes.size() ? argNodes.get(i).getReg() : reg);
                String currentName = current.get(i);
                p.put("name", currentName);
                JadxCodeRef codeRef = JadxCodeRef.forMthArg(i);
                String analyst = session.getRename(JadxSession.nodeRefFor(method), codeRef);
                String source = analyst != null ? "analyst"
                        : i < debugNames.size() && Objects.equals(currentName, debugNames.get(i)) ? "debug_info"
                        : i < annotationNames.size() && Objects.equals(currentName, annotationNames.get(i)) ? "annotation (applied by the engine by position — unverified)"
                        : currentName.matches("arg\\d+") ? "generic" : "engine";
                p.put("nameSource", source);
                parameters.add(p);
                reg += method.getArgTypes().get(i).getRegCount();
            }
            out.put("parameters", parameters);
            Set<String> parameterSet = new HashSet<>(current);
            out.put("locals", localInfos(method).stream()
                    .filter(v -> v.parameterIndex() == null)
                    .map(LocalInfo::name)
                    .filter(n -> !parameterSet.contains(n))
                    .distinct()
                    .toList());
            long positional = parameters.stream().map(x -> (Map<?, ?>) x)
                    .filter(x -> String.valueOf(x.get("nameSource")).startsWith("annotation")).count();
            Map<String, Object> named = new LinkedHashMap<>();
            named.put("status", positional == 0 ? "none" : positional == types.size() ? "all" : "some");
            named.put("count", positional);
            named.put("of", types.size());
            out.put("decompiler", Map.of(
                    "engine", "jadx",
                    "annotationNamedArguments", named,
                    "appliesAnnotationByPosition", positional == types.size(),
                    "appliesAnnotationToAny", positional > 0));
        } else {
            out.put("decompiler", Map.of(
                    "engine", "jadx",
                    "appliesAnnotationByPosition", false,
                    "appliesAnnotationToAny", false,
                    "note", "not computed in this raw metadata view"));
        }
        out.put("note", "annotation order is encoded order, not a verified mapping to current arguments; reading this changes nothing");
        return out;
    }

    Map<String, Object> renameParameters(String methodSymbol, List<Map<String, Object>> changes, String actor,
                                         Consumer<ClassNode> onClassMutated) throws OpException {
        MethodNode method = methodNode(methodSymbol);
        if (method.isNoCode() || method.getCodeReader() == null) {
            throw OpException.unsupported("JADX cannot persist parameter renames for a method without code", "symbol=" + methodSymbol);
        }
        ensureOriginalLocalNames(method);
        int count = method.getArgTypes().size();
        List<String> current = parameterNames(method);
        Map<Integer, String> names = new TreeMap<>();
        Set<Integer> clears = new TreeSet<>();
        List<String> problems = new ArrayList<>();
        for (Map<String, Object> change : changes == null ? List.<Map<String, Object>>of() : changes) {
            Object rawIndex = change.get("index");
            int index = rawIndex instanceof Number n ? n.intValue()
                    : rawIndex instanceof String s && s.matches("\\d+") ? Integer.parseInt(s) : -1;
            if (index < 0 || index >= count) {
                problems.add("index " + rawIndex + " is not a formal argument of this method (" + count + ")");
                continue;
            }
            if (names.containsKey(index) || clears.contains(index)) {
                problems.add("two changes name argument " + index);
                continue;
            }
            if (Boolean.TRUE.equals(change.get("clear"))) {
                clears.add(index);
                continue;
            }
            Object value = change.get("name");
            if (value == null || String.valueOf(value).isEmpty()) continue;
            String newName = String.valueOf(value);
            if (!Engine.usableName(newName)) {
                problems.add("'" + newName + "' is not a usable Java identifier");
                continue;
            }
            if (names.containsValue(newName)) {
                problems.add("two arguments would be named '" + newName + "'");
                continue;
            }
            if (newName.equals(current.get(index)) && session.getRename(JadxSession.nodeRefFor(method), JadxCodeRef.forMthArg(index)) != null) {
                continue;
            }
            names.put(index, newName);
        }
        Set<String> locals = localInfos(method).stream()
                .filter(v -> v.parameterIndex() == null)
                .map(LocalInfo::name)
                .collect(Collectors.toSet());
        for (Map.Entry<Integer, String> change : names.entrySet()) {
            if (locals.contains(change.getValue())) {
                problems.add("'" + change.getValue() + "' is the name of a local in this method — rename the local first, or choose another name");
            }
            for (int i = 0; i < count; i++) {
                if (i != change.getKey() && !names.containsKey(i) && !clears.contains(i) && change.getValue().equals(current.get(i))) {
                    problems.add("'" + change.getValue() + "' is already the name of argument " + i + " — swap them, or choose another name");
                }
            }
        }
        if (!problems.isEmpty()) {
            throw OpException.syntax("parameter mapping refused: " + String.join("; ", problems), "symbol=" + methodSymbol);
        }
        if (names.isEmpty() && clears.isEmpty()) {
            throw OpException.syntax("nothing to change — every argument already has the proposed name", "symbol=" + methodSymbol);
        }

        JadxNodeRef ref = JadxSession.nodeRefFor(method);
        List<ICodeRename> previousRenames = new ArrayList<>(session.codeData().getRenames());
        List<ICodeRename> updated = new ArrayList<>(previousRenames);
        Set<Integer> touched = new TreeSet<>();
        touched.addAll(names.keySet());
        touched.addAll(clears);
        updated.removeIf(rename -> Objects.equals(rename.getNodeRef(), ref)
                && rename.getCodeRef() != null
                && rename.getCodeRef().getAttachType() == CodeRefType.MTH_ARG
                && touched.contains(rename.getCodeRef().getIndex()));
        for (Map.Entry<Integer, String> change : names.entrySet()) {
            updated.add(new JadxCodeRename(ref, JadxCodeRef.forMthArg(change.getKey()), change.getValue()));
        }
        List<Object> applied = new ArrayList<>();
        for (int index : touched) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("index", index);
            row.put("oldName", current.get(index));
            String oldBinding = session.getRename(ref, JadxCodeRef.forMthArg(index));
            row.put("oldSource", oldBinding == null ? "engine" : "analyst");
            if (clears.contains(index)) row.put("cleared", true);
            else row.put("newName", names.get(index));
            applied.add(row);
        }
        ClassNode top = method.getTopParentClass();
        try {
            session.codeData().setRenames(updated);
            session.reloadCodeData();
            JadxSession.evictClass(top);
            List<String> after = parameterNames(method);
            for (Map.Entry<Integer, String> change : names.entrySet()) {
                int idx = change.getKey();
                if (idx >= after.size() || !change.getValue().equals(after.get(idx))) {
                    throw OpException.busy("JADX did not apply parameter rename at index " + idx + " to '" + change.getValue() + "'",
                            "symbol=" + methodSymbol);
                }
            }
        } catch (OpException | RuntimeException failure) {
            session.codeData().setRenames(previousRenames);
            try { session.reloadCodeData(); } catch (RuntimeException ignored) {}
            JadxSession.evictClass(top);
            throw failure;
        }
        onClassMutated.accept(top);
        String addr = session.symbols().byAddress.get(localOriginalMethod(method)).currentAddr();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("addr", addr);
        out.put("newAddr", addr);
        out.put("kind", "parameters");
        out.put("changes", applied);
        out.put("applied", true);
        out.put("actor", actor == null ? "human" : actor);
        return out;
    }
}
