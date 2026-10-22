package dev.workbench.engine.jeb;

import com.pnfsoftware.jeb.core.units.code.android.IDexUnit;
import com.pnfsoftware.jeb.core.units.code.android.dex.IDalvikInstruction;
import com.pnfsoftware.jeb.core.units.code.android.dex.IDexCodeItem;
import com.pnfsoftware.jeb.core.units.code.android.dex.IDexMethod;
import com.pnfsoftware.jeb.core.units.code.android.dex.IDexMethodData;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JEB-specific instruction walker for two-layer string classification
 * ({@link dev.workbench.engine.StringClassifier}).
 */
final class JebStringWalker {

    private JebStringWalker() {}

    /**
     * One pass over a method's instructions: const-string loads (dest register → pool
     * index), then invokes within {@link dev.workbench.engine.StringClassifier#WINDOW}
     * using a tracked register. Emits facts {stringIndex → {type, detail}}. Runs on the
     * engine lock (reads DEX).
     */
    static Map<Integer, String[]> usageFactsForMethod(IDexUnit dex, IDexMethod m) {
        Map<Integer, String[]> facts = new LinkedHashMap<>();
        IDexMethodData md = m.getData();
        IDexCodeItem ci = md == null ? null : md.getCodeItem();
        if (ci == null) return facts;
        List<? extends IDalvikInstruction> insns = ci.getInstructions();
        if (insns == null) return facts;
        // reg → {stringIndex, instructionsAgo}
        Map<Long, int[]> pending = new LinkedHashMap<>();
        int pos = 0;
        for (IDalvikInstruction insn : insns) {
            int op = insn.getOpcode();
            if (op == 0x1a || op == 0x1b) {                       // const-string(/jumbo)
                Long dest = null;
                int strIdx = -1;
                for (int i = 0; i < insn.getParameterCount(); i++) {
                    int t = insn.getParameterType(i);
                    if (t == IDalvikInstruction.TYPE_REG && dest == null) dest = insn.getParameterValue(i);
                    if (t == IDalvikInstruction.TYPE_IDX) strIdx = (int) insn.getParameterValue(i);
                }
                if (dest != null && strIdx >= 0) pending.put(dest, new int[] { strIdx, pos });
            } else if (op >= 0x6e && op <= 0x72) {                // invoke-* family
                // collect registers used + the method pool index
                int methodIdx = -1;
                List<Long> regs = new ArrayList<>();
                for (int i = 0; i < insn.getParameterCount(); i++) {
                    int t = insn.getParameterType(i);
                    if (t == IDalvikInstruction.TYPE_IDX) methodIdx = (int) insn.getParameterValue(i);
                    else if (t == IDalvikInstruction.TYPE_REG) regs.add(insn.getParameterValue(i));
                    else if (t == IDalvikInstruction.TYPE_RGR) {
                        long v = insn.getParameterValue(i);       // packed: count<<16 | firstReg? treat low/high
                        int first = (int) (v & 0xFFFF), count = (int) ((v >>> 16) & 0xFFFF);
                        for (long rr = first; rr < first + Math.max(1, count); rr++) regs.add(rr);
                    }
                }
                if (methodIdx >= 0) {
                    String target = null;
                    try {
                        IDexMethod callee = dex.getMethods().get(methodIdx);
                        if (callee != null) target = callee.getSignature(false);
                    } catch (Exception ignored) {}
                    if (target != null) {
                        for (String[] rule : dev.workbench.engine.StringClassifier.USAGE_RULES) {
                            if (!target.startsWith(rule[0]) && !target.contains(rule[0])) continue;
                            // arg position of the tracked register (static: as listed;
                            // instance: reg 0 is `this`, so positions shift by 1)
                            boolean instance = op != 0x71;         // 0x71 = invoke-static
                            for (int ri = 0; ri < regs.size(); ri++) {
                                int[] fact = pending.get(regs.get(ri));
                                if (fact == null || pos - fact[1] > dev.workbench.engine.StringClassifier.WINDOW) continue;
                                int argPos = instance ? ri - 1 : ri;
                                if (argPos < 0) continue;          // the string is `this` — not an arg
                                facts.putIfAbsent(fact[0],
                                        new String[] { rule[1], "arg " + argPos + " of " + rule[2] });
                            }
                        }
                    }
                }
            }
            pos++;
        }
        return facts;
    }
}
