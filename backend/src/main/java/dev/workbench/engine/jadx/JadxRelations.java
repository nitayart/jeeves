package dev.workbench.engine.jadx;

import java.util.*;
import jadx.api.plugins.input.data.IFieldRef;
import jadx.core.dex.nodes.*;

/** Raw DEX reference snapshot: offsets are 16-bit code units, never transformed IR offsets. */
final class JadxRelations {
    record Site(String from, String target, String type, int offset) {
        Map<String, Object> row(JadxSymbols symbols) {
            JadxSymbols.Symbol fromSym = symbols.byAddress.get(from);
            String curFrom = fromSym != null ? fromSym.currentAddr() : from;
            JadxSymbols.Symbol targetSym = symbols.byAddress.get(target);
            String curTarget = targetSym != null ? targetSym.currentAddr() : target;
            Map<String, Object> row = new LinkedHashMap<>();
            if (offset < 0) {
                row.put("from", curFrom); row.put("addr", curFrom); row.put("type", type);
                row.put("kind", "field");
                if (fromSym != null) {
                    row.put("name", fromSym.name());
                    row.put("owner", fromSym.owner().getFullName());
                } else {
                    int arrow = from.indexOf("->"), colon = from.indexOf(':', arrow);
                    if (arrow > 1 && colon > arrow) {
                        row.put("name", from.substring(arrow + 2, colon));
                        row.put("owner", from.substring(1, arrow - 1).replace('/', '.').replace('$', '.'));
                    }
                }
                return row;
            }
            row.put("from", curFrom + "+" + Integer.toHexString(offset) + "h");
            row.put("method", curFrom); row.put("addr", curTarget); row.put("type", type);
            row.put("offset", offset); row.put("offsetUnit", "dex-code-unit");
            if (fromSym != null) {
                row.put("name", fromSym.name());
                row.put("owner", fromSym.owner().getFullName());
            } else {
                int arrow = from.indexOf("->"), paren = from.indexOf('(', arrow);
                if (arrow > 1 && paren > arrow) {
                    row.put("name", from.substring(arrow + 2, paren));
                    row.put("owner", from.substring(1, arrow - 1).replace('/', '.').replace('$', '.'));
                }
            }
            return row;
        }
        Map<String, Object> outgoingRow(JadxSymbols symbols) {
            JadxSymbols.Symbol fromSym = symbols.byAddress.get(from);
            String curFrom = fromSym != null ? fromSym.currentAddr() : from;
            JadxSymbols.Symbol targetSym = symbols.byAddress.get(target);
            String curTarget = targetSym != null ? targetSym.currentAddr() : target;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("from", offset < 0 ? curFrom : curFrom + "+" + Integer.toHexString(offset) + "h");
            row.put("addr", curTarget); row.put("origSig", target); row.put("type", type);
            row.put("kind", "INVOKE".equals(type) ? "call" : type.toLowerCase(Locale.ROOT));
            if (offset >= 0) {
                row.put("offset", offset); row.put("offsetUnit", "dex-code-unit");
            }
            if (targetSym != null) {
                row.put("name", targetSym.name());
                row.put("owner", targetSym.owner().getFullName());
            } else {
                int arrow = target.indexOf("->");
                if (arrow > 1) {
                    int split = target.indexOf('(', arrow);
                    if (split < 0) split = target.indexOf(':', arrow);
                    row.put("name", split > arrow ? target.substring(arrow + 2, split) : target.substring(arrow + 2));
                    row.put("owner", target.substring(1, arrow - 1).replace('/', '.').replace('$', '.'));
                } else if (target.startsWith("L") && target.endsWith(";")) {
                    String dotted = target.substring(1, target.length() - 1).replace('/', '.').replace('$', '.');
                    int dot = dotted.lastIndexOf('.');
                    row.put("name", dot >= 0 ? dotted.substring(dot + 1) : dotted);
                    row.put("owner", dotted);
                } else {
                    row.put("name", target);
                }
            }
            return row;
        }
    }
    private final JadxSymbols symbols;
    private final Map<String, List<Site>> incoming = new LinkedHashMap<>();
    private final Map<String, List<Site>> outgoing = new LinkedHashMap<>();
    private final Map<String, List<Site>> strings = new TreeMap<>();
    private final Map<String, String> stringValues = new LinkedHashMap<>();
    private final Map<String, List<String[]>> usage = new LinkedHashMap<>();

    JadxRelations(JadxSymbols symbols) {
        this.symbols = symbols;
        for (JadxSymbols.Symbol symbol : symbols.byAddress.values()) {
            if (symbol.node() instanceof FieldNode field) {
                var constant = field.get(jadx.api.plugins.input.data.attributes.JadxAttrType.CONSTANT_VALUE);
                if (constant != null && constant.getValue() instanceof String value)
                    strings.computeIfAbsent(value, k -> new ArrayList<>()).add(new Site(symbol.addr(), "", "STATIC_VALUE", -1));
            }
            if (!(symbol.node() instanceof MethodNode m) || m.getCodeReader() == null) continue;
            m.getCodeReader().copy().visitInstructions(insn -> {
                insn.decode();
                String target, type;
                switch (insn.getIndexType()) {
                    case METHOD_REF -> { target = JadxStringWalker.methodDescriptor(insn.getIndexAsMethod()); type = "INVOKE"; }
                    case FIELD_REF -> {
                        IFieldRef f = insn.getIndexAsField();
                        target = f.getParentClassType() + "->" + f.getName() + ":" + f.getType(); type = "FIELD";
                    }
                    case TYPE_REF -> { target = insn.getIndexAsType(); type = "TYPE"; }
                    case STRING_REF -> {
                        String value = insn.getIndexAsString();
                        if (value != null) {
                            strings.computeIfAbsent(value, k -> new ArrayList<>())
                                    .add(new Site(symbol.addr(), "", "STRING", insn.getOffset()));
                        }
                        return;
                    }
                    default -> { return; }
                }
                Site site = new Site(symbol.addr(), target, type, insn.getOffset());
                incoming.computeIfAbsent(target, k -> new ArrayList<>()).add(site);
                outgoing.computeIfAbsent(symbol.addr(), k -> new ArrayList<>()).add(site);
            });
            JadxStringWalker.usageFactsForMethod(m).forEach((value, facts) ->
                    usage.computeIfAbsent(value, k -> new ArrayList<>()).addAll(facts));
        }
        // Sorted values give deterministic IDs, with no cross-DEX pool-index collisions.
        int id = 0;
        for (String value : strings.keySet()) stringValues.put(Integer.toString(id++), value);
    }

    boolean references(String address) { return incoming.containsKey(address); }

    Set<ClassNode> referrerTopClasses(String origAddr) {
        Set<ClassNode> out = new LinkedHashSet<>();
        for (Site site : incoming.getOrDefault(origAddr, List.of())) {
            JadxSymbols.Symbol fromSym = symbols.byAddress.get(site.from());
            if (fromSym != null) out.add(fromSym.owner().getTopParentClass());
        }
        return out;
    }

    List<Object> to(String address) {
        return new ArrayList<>(incoming.getOrDefault(address, List.of()).stream().map(site -> {
            Map<String, Object> row = site.row(symbols);
            row.remove("addr"); // incoming xrefs navigate to the referencing method/field (`method`/`from`), not the target itself
            return (Object) row;
        }).toList());
    }
    List<Object> from(String address, boolean callsOnly) {
        List<Object> result = new ArrayList<>();
        outgoing.forEach((method, sites) -> {
            if (!method.equals(address) && !method.startsWith(address + "->")) return;
            for (Site site : sites) if (!callsOnly || site.type().equals("INVOKE")) result.add(site.outgoingRow(symbols));
        });
        return result;
    }
    List<Object> callers(String address) {
        Map<String, Map<String, Object>> callers = new LinkedHashMap<>();
        for (Site site : incoming.getOrDefault(address, List.of())) {
            if (!site.type().equals("INVOKE")) continue;
            JadxSymbols.Symbol fromSym = symbols.byAddress.get(site.from());
            String curFrom = fromSym != null ? fromSym.currentAddr() : site.from();
            Map<String, Object> row = callers.computeIfAbsent(curFrom, key -> {
                Map<String, Object> r = site.row(symbols); r.put("addr", key); r.put("origSig", site.from());
                r.put("kind", "call"); r.put("sites", new ArrayList<String>()); return r;
            });
            @SuppressWarnings("unchecked") List<String> sites = (List<String>) row.get("sites");
            sites.add(curFrom + "+" + Integer.toHexString(site.offset()) + "h");
        }
        return new ArrayList<>(callers.values());
    }
    List<Map<String, Object>> stringRows() {
        List<Map<String, Object>> result = new ArrayList<>();
        stringValues.forEach((id, value) -> {
            List<Site> refs = strings.get(value);
            List<Map<String, Object>> types = dev.workbench.engine.StringClassifier.classify(value, usage.get(value));
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", id); row.put("value", value); row.put("types", types);
            row.put("type", types.isEmpty() ? "plain" : types.getFirst().get("name"));
            row.put("xrefCount", refs.size());
            String first = "(no referrer)";
            if (!refs.isEmpty()) {
                Map<String, Object> f0 = refs.getFirst().row(symbols);
                first = f0.get("owner") != null && f0.get("name") != null
                        ? f0.get("owner") + "." + f0.get("name")
                        : String.valueOf(f0.get("from"));
            }
            row.put("firstReferrer", first);
            row.put("unreachable", refs.isEmpty()); result.add(row);
        });
        return result;
    }
    List<Object> stringRefs(String id) throws dev.workbench.registry.OpException {
        String value = stringValues.get(id);
        if (value == null) throw dev.workbench.registry.OpException.notFound("unknown string id: " + id, "jadx");
        List<Object> result = new ArrayList<>();
        for (Site site : strings.get(value)) {
            Map<String, Object> row = site.row(symbols);
            row.remove("addr"); // string xrefs resolve to the referencing method/field, not ""
            row.put("stringId", id); row.put("value", value); result.add(row);
        }
        return result;
    }
}
