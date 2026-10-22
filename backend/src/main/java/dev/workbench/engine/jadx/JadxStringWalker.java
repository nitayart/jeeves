package dev.workbench.engine.jadx;

import dev.workbench.engine.StringClassifier;
import jadx.api.plugins.input.data.ICodeReader;
import jadx.api.plugins.input.data.IMethodRef;
import jadx.api.plugins.input.insns.InsnData;
import jadx.api.plugins.input.insns.InsnIndexType;
import jadx.core.dex.nodes.MethodNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JADX-specific DEX instruction walker for two-layer string classification
 * ({@link StringClassifier}) and exact string/method/field/type xref site discovery.
 *
 * Uses {@link MethodNode#getCodeReader()} directly so scanning never triggers
 * full class decompilation.
 */
final class JadxStringWalker {

    private JadxStringWalker() {}

    /**
     * One pass over a method's raw DEX instructions: tracks const-string loads
     * (dest register → string value) and invokes within {@link StringClassifier#WINDOW}
     * using a tracked register. Emits facts {stringValue → List of {type, detail}}.
     */
    static Map<String, List<String[]>> usageFactsForMethod(MethodNode mth) {
        Map<String, List<String[]>> facts = new LinkedHashMap<>();
        ICodeReader reader = mth.getCodeReader();
        if (reader == null) return facts;
        // reg -> {stringIndexInLocalTable, instructionPos}
        List<String> loadedStrings = new ArrayList<>();
        Map<Integer, int[]> pending = new LinkedHashMap<>();
        int[] posBox = { 0 };
        reader.copy().visitInstructions(insn -> {
            int pos = posBox[0]++;
            int rawOp = insn.getRawOpcodeUnit() & 0xFF;
            if (rawOp == 0x1a || rawOp == 0x1b) { // const-string, const-string/jumbo
                insn.decode();
                if (insn.getIndexType() == InsnIndexType.STRING_REF && insn.getRegsCount() > 0) {
                    String s = insn.getIndexAsString();
                    if (s != null) {
                        int sIdx = loadedStrings.size();
                        loadedStrings.add(s);
                        pending.put(insn.getReg(0), new int[] { sIdx, pos });
                    }
                }
            } else if ((rawOp >= 0x6e && rawOp <= 0x72) || (rawOp >= 0x74 && rawOp <= 0x78)) {
                // invoke-virtual/super/direct/static/interface and /range variants
                if (pending.isEmpty()) return;
                insn.decode();
                if (insn.getIndexType() != InsnIndexType.METHOD_REF) return;
                IMethodRef mref = insn.getIndexAsMethod();
                if (mref == null) return;
                String target = methodDescriptor(mref);
                boolean instance = rawOp != 0x71 && rawOp != 0x77; // 0x71=invoke-static, 0x77=invoke-static/range
                int rc = insn.getRegsCount();
                Map<Integer, Integer> formalByWord = new LinkedHashMap<>();
                int word = instance ? 1 : 0;
                List<String> formalTypes = mref.getArgTypes();
                for (int arg = 0; arg < formalTypes.size(); arg++) {
                    formalByWord.put(word, arg);
                    String type = formalTypes.get(arg);
                    word += type.equals("J") || type.equals("D") ? 2 : 1;
                }
                for (String[] rule : StringClassifier.USAGE_RULES) {
                    if (!target.startsWith(rule[0]) && !target.contains(rule[0])) continue;
                    for (int ri = 0; ri < rc; ri++) {
                        int reg = insn.getReg(ri);
                        int[] fact = pending.get(reg);
                        if (fact == null || pos - fact[1] > StringClassifier.WINDOW) continue;
                        Integer argPos = formalByWord.get(ri);
                        if (argPos == null) continue;
                        String val = loadedStrings.get(fact[0]);
                        facts.computeIfAbsent(val, k -> new ArrayList<>())
                                .add(new String[] { rule[1], "arg " + argPos + " of " + rule[2] });
                    }
                }
            } else {
                // Conservative straight-line evidence only. Unknown writes, moves and
                // control flow end the window rather than attributing a stale register.
                pending.clear();
            }
        });
        return facts;
    }

    /** Format an IMethodRef into a canonical Dalvik descriptor: Lpkg/Cls;->name(Args)Ret */
    static String methodDescriptor(IMethodRef mref) {
        mref.load();
        StringBuilder sb = new StringBuilder();
        sb.append(mref.getParentClassType()).append("->").append(mref.getName()).append('(');
        List<String> args = mref.getArgTypes();
        if (args != null) for (String a : args) sb.append(a);
        sb.append(')').append(mref.getReturnType());
        return sb.toString();
    }
}
