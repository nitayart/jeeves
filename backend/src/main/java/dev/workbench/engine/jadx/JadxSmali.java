package dev.workbench.engine.jadx;

import dev.workbench.registry.OpException;
import java.util.*;
import jadx.api.plugins.input.data.*;
import jadx.api.plugins.input.data.annotations.EncodedValue;
import jadx.api.plugins.input.insns.InsnData;
import jadx.api.plugins.input.insns.custom.IArrayPayload;
import jadx.api.plugins.input.insns.custom.ICustomPayload;
import jadx.api.plugins.input.insns.custom.ISwitchPayload;
import jadx.core.dex.nodes.ClassNode;
import jadx.core.dex.nodes.FieldNode;
import jadx.core.dex.nodes.MethodNode;

/**
 * Renders structured Smali disassembly rows from JADX's raw DEX {@link ICodeReader}
 * without triggering full class decompilation, while resolving constant-pool references
 * against current analyst renames in {@link JadxSymbols}.
 */
final class JadxSmali {
    private static final int OP_FILL_ARRAY_DATA = 0x26;
    private static final int OP_PACKED_SWITCH = 0x2b;
    private static final int OP_SPARSE_SWITCH = 0x2c;
    private static final int OP_PACKED_SWITCH_PAYLOAD = 0x0100;
    private static final int OP_SPARSE_SWITCH_PAYLOAD = 0x0200;
    private static final int OP_FILL_ARRAY_DATA_PAYLOAD = 0x0300;

    private JadxSmali() {}

    private record DecodedInsn(
            int offset,
            int length,
            int opcodeUnit,
            int opcode,
            String mnemonic,
            byte[] bytes,
            int[] regs,
            long literal,
            int target,
            int index,
            String stringRef,
            String typeRef,
            IFieldRef fieldRef,
            IMethodRef methodRef,
            ICallSite callSite,
            IMethodProto protoRef,
            IMethodHandle methodHandle,
            ICustomPayload payload
    ) {
        static DecodedInsn from(InsnData insn) {
            insn.decode();
            int rawUnit = insn.getRawOpcodeUnit();
            int op = rawUnit & 0xFF;
            int rc = Math.max(0, insn.getRegsCount());
            int[] regs = new int[rc];
            for (int i = 0; i < rc; i++) regs[i] = insn.getReg(i);

            byte[] code = insn.getByteCode();
            int len = code != null && code.length > 0 ? code.length / 2 : 1;

            String str = null, type = null;
            IFieldRef field = null;
            IMethodRef method = null;
            ICallSite callSite = null;
            IMethodProto proto = null;
            IMethodHandle handle = null;

            switch (insn.getIndexType()) {
                case STRING_REF -> str = insn.getIndexAsString();
                case TYPE_REF -> type = insn.getIndexAsType();
                case FIELD_REF -> field = insn.getIndexAsField();
                case METHOD_REF -> {
                    method = insn.getIndexAsMethod();
                    if (method != null) method.load();
                    if (op == 0xfa || op == 0xfb) { // invoke-polymorphic(/range): proto index in target
                        proto = insn.getIndexAsProto(insn.getTarget());
                    }
                }
                case CALL_SITE -> {
                    callSite = insn.getIndexAsCallSite();
                    if (callSite != null) callSite.load();
                }
                default -> {
                    if (op == 0xfe) { // const-method-handle
                        handle = insn.getIndexAsMethodHandle();
                        if (handle != null) handle.load();
                    } else if (op == 0xff) { // const-method-type
                        proto = insn.getIndexAsProto(insn.getIndex());
                    }
                }
            }
            return new DecodedInsn(
                    insn.getOffset(),
                    len,
                    rawUnit,
                    op,
                    insn.getOpcodeMnemonic(),
                    code == null ? new byte[0] : code,
                    regs,
                    insn.getLiteral(),
                    insn.getTarget(),
                    insn.getIndex(),
                    str,
                    type,
                    field,
                    method,
                    callSite,
                    proto,
                    handle,
                    insn.getPayload());
        }

        boolean isPayloadPseudoInsn() {
            return opcode == 0 && (opcodeUnit == OP_PACKED_SWITCH_PAYLOAD
                    || opcodeUnit == OP_SPARSE_SWITCH_PAYLOAD
                    || opcodeUnit == OP_FILL_ARRAY_DATA_PAYLOAD);
        }

        boolean isBranchOrPayloadRef() {
            return (opcode >= 0x28 && opcode <= 0x2a) // goto, goto/16, goto/32
                    || (opcode >= 0x32 && opcode <= 0x3d) // if-* and if-*z
                    || opcode == OP_FILL_ARRAY_DATA
                    || opcode == OP_PACKED_SWITCH
                    || opcode == OP_SPARSE_SWITCH;
        }
    }

    static Map<String, Object> render(JadxSymbols symbols, JadxLocals locals, JadxSymbols.Symbol resolved) throws OpException {
        List<Object> rows = new ArrayList<>();
        Map<String, Object> methodParams = null;
        ClassNode cls = resolved.owner();
        JadxSymbols.Symbol clsSym = symbols.byAddress.get(JadxSymbols.address(cls));

        if ("method".equals(resolved.kind()) && resolved.node() instanceof MethodNode m) {
            renderMethod(symbols, locals, m, rows);
            try { methodParams = locals.methodParameters(resolved.addr(), false); } catch (Throwable ignored) {}
        } else {
            renderClass(symbols, locals, cls, rows);
        }

        Map<String, Object> sym = new LinkedHashMap<>();
        sym.put("addr", "method".equals(resolved.kind())
                ? resolved.currentAddr()
                : (clsSym != null ? clsSym.currentAddr() : JadxSymbols.currentAddress(cls)));
        sym.put("kind", "method".equals(resolved.kind()) ? "method" : "class");
        sym.put("name", "method".equals(resolved.kind()) ? resolved.name() : cls.getAlias());
        sym.put("fqcn", cls.getFullName());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("symbol", sym);
        out.put("engine", "jadx");
        out.put("count", rows.size());
        out.put("rows", rows);
        if (methodParams != null) out.put("methodParameters", methodParams);
        out.put("note", "a listing composed from the engine's raw DEX instruction reader (JADX) with branch destinations decoded per the DEX spec — not a qualified round-trippable smali export");
        return out;
    }

    private static void renderClass(JadxSymbols symbols, JadxLocals locals, ClassNode cls, List<Object> rows) {
        dirRow(rows, ".class " + flags(cls.getAccessFlags().rawValue(), true) + JadxSymbols.currentAddress(cls));
        if (cls.getSuperClass() != null) {
            dirRow(rows, ".super " + symbols.currentTypeSig(cls.getSuperClass()));
        }
        var interfaces = cls.getInterfaces();
        if (interfaces != null && !interfaces.isEmpty()) {
            blankRow(rows);
            for (var itf : interfaces) {
                dirRow(rows, ".implements " + symbols.currentTypeSig(itf));
            }
        }
        List<FieldNode> fields = cls.getFields();
        if (fields != null && !fields.isEmpty()) {
            blankRow(rows);
            for (FieldNode f : fields) {
                JadxSymbols.Symbol fSym = symbols.byAddress.get(JadxSymbols.fieldAddress(f));
                String curSig = fSym != null ? fSym.currentAddr()
                        : JadxSymbols.currentAddress(cls) + "->" + f.getAlias() + ":" + symbols.currentTypeSig(f.getFieldInfo().getType());
                int arrow = curSig.indexOf("->");
                dirRow(rows, ".field " + flags(f.getAccessFlags().rawValue(), false) + curSig.substring(arrow + 2));
            }
        }
        blankRow(rows);
        for (MethodNode m : cls.getMethods()) {
            renderMethod(symbols, locals, m, rows);
            blankRow(rows);
        }
    }

    private static void renderMethod(JadxSymbols symbols, JadxLocals locals, MethodNode m, List<Object> rows) {
        String origAddr = JadxSymbols.methodAddress(m);
        JadxSymbols.Symbol mSym = symbols.byAddress.get(origAddr);
        String curAddr = mSym != null ? mSym.currentAddr() : origAddr;
        String shortSig = curAddr.substring(curAddr.indexOf("->") + 2);
        int rawAcc = m.getAccessFlags().rawValue();

        Map<String, Object> mrow = dirRow(rows, ".method " + flags(rawAcc, false) + shortSig);
        mrow.put("method", curAddr);
        mrow.put("name", m.getAlias());

        ICodeReader reader = m.getCodeReader();
        int argRegWords = m.getMethodInfo().getArgumentsTypes().stream().mapToInt(t -> t.getRegCount()).sum()
                + (m.getAccessFlags().isStatic() ? 0 : 1);
        int totalRegs = reader != null ? reader.getRegistersCount() : 0;
        int localsCount = Math.max(0, totalRegs - argRegWords);
        int argsStartReg = reader != null && reader.getArgsStartReg() >= 0
                ? reader.getArgsStartReg()
                : localsCount;

        if (reader != null) {
            dirRow(rows, "    .locals " + localsCount);
        }
        annotationRows(locals, origAddr, argsStartReg, m, rows);
        if (reader == null || m.isNoCode()) {
            dirRow(rows, ".end method");
            return;
        }
        blankRow(rows);

        List<DecodedInsn> allInsns = new ArrayList<>();
        Map<Integer, DecodedInsn> payloadsByOffset = new TreeMap<>();
        Set<Integer> starts = new HashSet<>();
        int[] endBox = { 0 };

        reader.copy().visitInstructions(raw -> {
            DecodedInsn insn = DecodedInsn.from(raw);
            if (insn.isPayloadPseudoInsn()) {
                payloadsByOffset.put(insn.offset(), insn);
            } else {
                allInsns.add(insn);
                starts.add(insn.offset());
                endBox[0] = Math.max(endBox[0], insn.offset() + insn.length());
            }
        });

        int methodEnd = endBox[0];
        Map<Integer, String> labels = new TreeMap<>();
        Map<Integer, String> payloadLabels = new TreeMap<>();
        Map<Integer, DecodedInsn> payloadOwner = new TreeMap<>();

        for (DecodedInsn insn : allInsns) {
            if (!insn.isBranchOrPayloadRef()) continue;
            int dest = insn.target();
            int op = insn.opcode();
            if (op == OP_PACKED_SWITCH || op == OP_SPARSE_SWITCH || op == OP_FILL_ARRAY_DATA) {
                payloadLabels.computeIfAbsent(dest, t -> (op == OP_PACKED_SWITCH ? ":pswitch_data_"
                        : op == OP_SPARSE_SWITCH ? ":sswitch_data_" : ":array_") + Integer.toHexString(t));
                payloadOwner.put(dest, insn);
                DecodedInsn payloadInsn = payloadsByOffset.get(dest);
                if (payloadInsn != null && payloadInsn.payload() instanceof ISwitchPayload sw) {
                    for (int relTarget : sw.getTargets()) {
                        int caseDest = insn.offset() + relTarget;
                        if (starts.contains(caseDest)) {
                            labels.computeIfAbsent(caseDest, t -> ":l_" + Integer.toHexString(t));
                        }
                    }
                }
            } else if (starts.contains(dest)) {
                labels.computeIfAbsent(dest, t -> ":l_" + Integer.toHexString(t));
            }
        }

        Map<Integer, LinkedHashSet<String>> tryLabels = new TreeMap<>();
        List<String> catchRows = new ArrayList<>();
        try {
            List<ITry> tries = reader.copy().getTries();
            if (tries != null) {
                for (ITry tr : tries) {
                    int ts = tr.getStartOffset();
                    int te = tr.getEndOffset() + 1; // ITry.getEndOffset() is startAddr + insnsCount - 1
                    String sl = ":try_start_" + Integer.toHexString(ts);
                    String el = ":try_end_" + Integer.toHexString(te);
                    tryLabels.computeIfAbsent(ts, t -> new LinkedHashSet<>()).add(sl);
                    tryLabels.computeIfAbsent(te, t -> new LinkedHashSet<>()).add(el);
                    ICatch catchData = tr.getCatch();
                    if (catchData == null) continue;
                    String[] types = catchData.getTypes();
                    int[] handlers = catchData.getHandlers();
                    if (types != null && handlers != null) {
                        int n = Math.min(types.length, handlers.length);
                        for (int i = 0; i < n; i++) {
                            int ha = handlers[i];
                            String typeDesc = resolveTypeDescriptor(symbols, types[i]);
                            String hl = starts.contains(ha)
                                    ? labels.computeIfAbsent(ha, t -> ":catch_" + Integer.toHexString(t))
                                    : String.format("%04x (no instruction at that offset)", ha);
                            catchRows.add("    .catch " + typeDesc + " {" + sl + " .. " + el + "} " + hl);
                        }
                    }
                    int catchAll = catchData.getCatchAllHandler();
                    if (catchAll >= 0) {
                        String hl = starts.contains(catchAll)
                                ? labels.computeIfAbsent(catchAll, t -> ":catchall_" + Integer.toHexString(t))
                                : String.format("%04x (no instruction at that offset)", catchAll);
                        catchRows.add("    .catchall {" + sl + " .. " + el + "} " + hl);
                    }
                }
            }
        } catch (Throwable ignored) {}

        for (DecodedInsn insn : allInsns) {
            LinkedHashSet<String> tls = tryLabels.get(insn.offset());
            if (tls != null) for (String tl : tls) labelRow(rows, "    " + tl);
            String label = labels.get(insn.offset());
            if (label != null) labelRow(rows, "    " + label);

            String text = formatInsn(symbols, insn, argsStartReg, starts, methodEnd, labels, payloadLabels);
            insnRow(rows, insn.offset(), insn.bytes(), "    " + text);
        }

        LinkedHashSet<String> tailTls = tryLabels.get(methodEnd);
        if (tailTls != null) for (String tl : tailTls) labelRow(rows, "    " + tl);
        for (String c : catchRows) dirRow(rows, c);

        for (Map.Entry<Integer, String> entry : payloadLabels.entrySet()) {
            int payloadOff = entry.getKey();
            DecodedInsn owner = payloadOwner.get(payloadOff);
            DecodedInsn payloadInsn = payloadsByOffset.get(payloadOff);
            blankRow(rows);
            labelRow(rows, "    " + entry.getValue());
            if (owner != null && (owner.opcode() == OP_PACKED_SWITCH || owner.opcode() == OP_SPARSE_SWITCH)) {
                boolean packed = owner.opcode() == OP_PACKED_SWITCH;
                ISwitchPayload sw = payloadInsn != null && payloadInsn.payload() instanceof ISwitchPayload s ? s : null;
                int[] keys = sw != null ? sw.getKeys() : new int[0];
                int[] targets = sw != null ? sw.getTargets() : new int[0];
                Map<String, Object> hdr = dirRow(rows, "    " + (packed
                        ? ".packed-switch " + (keys.length > 0 ? hexLit(keys[0]) : "")
                        : ".sparse-switch"));
                hdr.put("offset", String.format("%04x", payloadOff));
                int len = Math.min(keys.length, targets.length);
                for (int i = 0; i < len; i++) {
                    int caseDest = owner.offset() + targets[i];
                    String target = starts.contains(caseDest)
                            ? labels.get(caseDest)
                            : String.format("%+d units → %04x (no instruction at that offset)", targets[i], caseDest);
                    caseRow(rows, "        " + (packed ? "" : hexLit(keys[i]) + " -> ") + target);
                }
                dirRow(rows, "    " + (packed ? ".end packed-switch" : ".end sparse-switch"));
            } else {
                IArrayPayload arr = payloadInsn != null && payloadInsn.payload() instanceof IArrayPayload a ? a : null;
                int elemWidth = arr != null ? arr.getElementSize() : 0;
                Map<String, Object> hdr = dirRow(rows, "    .array-data" + (elemWidth > 0 ? " " + elemWidth : "")
                        + (arr == null ? "   # the payload at " + String.format("%04x", payloadOff) + " — the engine did not decode its elements; bytes not listed" : ""));
                hdr.put("offset", String.format("%04x", payloadOff));
                if (arr != null) {
                    for (long val : extractArrayValues(arr.getData())) {
                        caseRow(rows, "        " + hexLit(val));
                    }
                }
                dirRow(rows, "    .end array-data");
            }
        }
        dirRow(rows, ".end method");
    }

    private static void annotationRows(JadxLocals locals, String origMethodAddr, int argsStartReg, MethodNode m, List<Object> rows) {
        Map<String, Object> meta;
        try {
            meta = locals.methodParameters(origMethodAddr, false);
        } catch (Throwable t) {
            commentRow(rows, "    # parameter metadata unavailable: " + t);
            return;
        }
        Map<?, ?> ann = (Map<?, ?>) meta.get("annotation");
        if (ann != null && ("present".equals(ann.get("status")) || "malformed".equals(ann.get("status")))) {
            annRow(rows, "    # retained MethodParameters — metadata the compiler kept, not the arguments' names; argument mapping unverified");
            annRow(rows, "    .annotation system Ldalvik/annotation/MethodParameters;");
            if (ann.get("accessFlags") instanceof List<?> fl) {
                StringBuilder sb = new StringBuilder("        accessFlags = { ");
                for (int i = 0; i < fl.size(); i++) {
                    sb.append(i > 0 ? ", " : "").append(fl.get(i) instanceof Number n ? "0x" + Integer.toHexString(n.intValue()) : String.valueOf(fl.get(i)));
                }
                annRow(rows, sb.append(" }").toString());
            }
            if (ann.get("names") instanceof List<?> nl) {
                StringBuilder sb = new StringBuilder("        names = { ");
                for (int i = 0; i < nl.size(); i++) {
                    sb.append(i > 0 ? ", " : "").append(nl.get(i) == null ? "null" : "\"" + escapeString(String.valueOf(nl.get(i))) + "\"");
                }
                annRow(rows, sb.append(" }").toString());
            }
            if (!Boolean.TRUE.equals(ann.get("countMatchesPrototype"))) {
                annWarnRow(rows, "        # " + ann.get("mismatch"));
            }
            if ("malformed".equals(ann.get("status")) && ann.get("problems") != null) {
                annWarnRow(rows, "        # malformed: " + ann.get("problems"));
            }
            annRow(rows, "    .end annotation");
        }
        Map<?, ?> dbg = (Map<?, ?>) meta.get("debugInfo");
        if (dbg != null && "present".equals(dbg.get("status")) && dbg.get("names") instanceof List<?> dn) {
            List<jadx.core.dex.instructions.args.ArgType> argTypes = m.getMethodInfo().getArgumentsTypes();
            int pReg = m.getAccessFlags().isStatic() ? 0 : 1; // p0 is `this` for instance methods
            for (int i = 0; i < dn.size(); i++) {
                Object nameObj = dn.get(i);
                if (nameObj != null) {
                    String regLabel = i < argTypes.size() ? "p" + pReg : "p?";
                    annRow(rows, "    .param " + regLabel + ", \"" + escapeString(String.valueOf(nameObj)) + "\"   # debug_info parameter name");
                }
                if (i < argTypes.size()) {
                    pReg += argTypes.get(i).getRegCount();
                }
            }
        }
    }

    private static String formatInsn(
            JadxSymbols symbols,
            DecodedInsn insn,
            int argsStartReg,
            Set<Integer> starts,
            int methodEnd,
            Map<Integer, String> labels,
            Map<Integer, String> payloadLabels) {
        int op = insn.opcode();
        String mnem = insn.mnemonic() != null ? insn.mnemonic() : String.format("op_%02x", op);

        if (op == 0x00 || op == 0x0e) { // nop, return-void
            return mnem;
        }
        if ((op >= 0x28 && op <= 0x2a) || (op >= 0x32 && op <= 0x3d)
                || op == OP_FILL_ARRAY_DATA || op == OP_PACKED_SWITCH || op == OP_SPARSE_SWITCH) {
            String targetOp = formatBranchOperand(insn, starts, methodEnd, labels, payloadLabels);
            if (op >= 0x28 && op <= 0x2a) { // goto*
                return mnem + " " + targetOp;
            }
            if (op >= 0x38 && op <= 0x3d || op == OP_FILL_ARRAY_DATA || op == OP_PACKED_SWITCH || op == OP_SPARSE_SWITCH) {
                return mnem + " " + reg(insn, 0, argsStartReg) + ", " + targetOp;
            }
            return mnem + " " + reg(insn, 0, argsStartReg) + ", " + reg(insn, 1, argsStartReg) + ", " + targetOp;
        }
        if (op >= 0x12 && op <= 0x19) { // const*, const-wide*
            return mnem + " " + reg(insn, 0, argsStartReg) + ", " + hexLit(insn.literal());
        }
        if (op == 0x1a || op == 0x1b) { // const-string(/jumbo)
            String s = insn.stringRef() != null ? "\"" + escapeString(insn.stringRef()) + "\"" : "string@" + insn.index();
            return mnem + " " + reg(insn, 0, argsStartReg) + ", " + s;
        }
        if (op == 0x1c || op == 0x1f || op == 0x22) { // const-class, check-cast, new-instance
            return mnem + " " + reg(insn, 0, argsStartReg) + ", " + resolveTypeDescriptor(symbols, insn.typeRef());
        }
        if (op == 0x20 || op == 0x23) { // instance-of, new-array
            return mnem + " " + reg(insn, 0, argsStartReg) + ", " + reg(insn, 1, argsStartReg) + ", "
                    + resolveTypeDescriptor(symbols, insn.typeRef());
        }
        if (op == 0x24 || op == 0x25) { // filled-new-array(/range)
            return mnem + " " + regList(insn, argsStartReg, op == 0x25) + ", "
                    + resolveTypeDescriptor(symbols, insn.typeRef());
        }
        if (op >= 0x52 && op <= 0x5f) { // iget*, iput*
            return mnem + " " + reg(insn, 0, argsStartReg) + ", " + reg(insn, 1, argsStartReg) + ", "
                    + resolveFieldDescriptor(symbols, insn.fieldRef());
        }
        if (op >= 0x60 && op <= 0x6d) { // sget*, sput*
            return mnem + " " + reg(insn, 0, argsStartReg) + ", "
                    + resolveFieldDescriptor(symbols, insn.fieldRef());
        }
        if ((op >= 0x6e && op <= 0x72) || (op >= 0x74 && op <= 0x78)) { // invoke-*(/range)
            boolean isRange = op >= 0x74;
            return mnem + " " + regList(insn, argsStartReg, isRange) + ", "
                    + resolveMethodDescriptor(symbols, insn.methodRef());
        }
        if (op >= 0xd0 && op <= 0xe2) { // *-int/lit16, *-int/lit8
            return mnem + " " + reg(insn, 0, argsStartReg) + ", " + reg(insn, 1, argsStartReg) + ", "
                    + hexLit(insn.literal());
        }
        if (op == 0xfa || op == 0xfb) { // invoke-polymorphic(/range)
            boolean isRange = op == 0xfb;
            return mnem + " " + regList(insn, argsStartReg, isRange) + ", "
                    + resolveMethodDescriptor(symbols, insn.methodRef()) + ", "
                    + formatProto(symbols, insn.protoRef());
        }
        if (op == 0xfc || op == 0xfd) { // invoke-custom(/range)
            boolean isRange = op == 0xfd;
            return mnem + " " + regList(insn, argsStartReg, isRange) + ", "
                    + formatCallSite(symbols, insn.callSite(), insn.index());
        }
        if (op == 0xfe) { // const-method-handle
            return mnem + " " + reg(insn, 0, argsStartReg) + ", "
                    + formatMethodHandle(symbols, insn.methodHandle(), insn.index());
        }
        if (op == 0xff) { // const-method-type
            return mnem + " " + reg(insn, 0, argsStartReg) + ", "
                    + formatProto(symbols, insn.protoRef());
        }

        // Standard register-only instructions (11x, 12x, 22x, 32x, 23x: move*, return*, throw, monitor-*, array-length, aget*, aput*, unop, binop, binop/2addr)
        int rc = insn.regs().length;
        if (rc == 0) return mnem;
        StringBuilder sb = new StringBuilder(mnem).append(' ');
        for (int i = 0; i < rc; i++) {
            if (i > 0) sb.append(", ");
            sb.append(reg(insn, i, argsStartReg));
        }
        return sb.toString();
    }

    private static String formatBranchOperand(
            DecodedInsn insn,
            Set<Integer> starts,
            int methodEnd,
            Map<Integer, String> labels,
            Map<Integer, String> payloadLabels) {
        int dest = insn.target();
        int op = insn.opcode();
        if (op == OP_PACKED_SWITCH || op == OP_SPARSE_SWITCH || op == OP_FILL_ARRAY_DATA) {
            return payloadLabels.get(dest);
        }
        if (starts.contains(dest)) {
            return labels.get(dest);
        }
        int disp = dest - insn.offset();
        return String.format("%+d units → %04x (no instruction at that offset%s)",
                disp,
                dest,
                dest < 0 ? ", before the method" : dest >= methodEnd ? ", past the last instruction (a payload area or outside the method)" : "");
    }

    private static String reg(DecodedInsn insn, int argIdx, int argsStartReg) {
        if (argIdx < 0 || argIdx >= insn.regs().length) return "v?";
        return formatRegNum(insn.regs()[argIdx], argsStartReg);
    }

    private static String formatRegNum(int r, int argsStartReg) {
        return r >= argsStartReg ? "p" + (r - argsStartReg) : "v" + r;
    }

    private static String regList(DecodedInsn insn, int argsStartReg, boolean range) {
        int[] regs = insn.regs();
        if (regs.length == 0) return "{}";
        if (range && regs.length > 1) {
            return "{" + formatRegNum(regs[0], argsStartReg) + " .. " + formatRegNum(regs[regs.length - 1], argsStartReg) + "}";
        }
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < regs.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(formatRegNum(regs[i], argsStartReg));
        }
        return sb.append('}').toString();
    }

    private static String resolveTypeDescriptor(JadxSymbols symbols, String rawType) {
        if (rawType == null || rawType.isEmpty()) return "?";
        int dims = 0;
        while (dims < rawType.length() && rawType.charAt(dims) == '[') dims++;
        String elem = rawType.substring(dims);
        if (elem.startsWith("L") && elem.endsWith(";")) {
            JadxSymbols.Symbol sym = symbols.byAddress.get(elem);
            if (sym != null) elem = sym.currentAddr();
        }
        return dims == 0 ? elem : "[".repeat(dims) + elem;
    }

    private static String resolveFieldDescriptor(JadxSymbols symbols, IFieldRef ref) {
        if (ref == null) return "field@?";
        String orig = ref.getParentClassType() + "->" + ref.getName() + ":" + ref.getType();
        JadxSymbols.Symbol sym = symbols.byAddress.get(orig);
        if (sym != null) return sym.currentAddr();
        return resolveTypeDescriptor(symbols, ref.getParentClassType())
                + "->" + ref.getName() + ":" + resolveTypeDescriptor(symbols, ref.getType());
    }

    private static String resolveMethodDescriptor(JadxSymbols symbols, IMethodRef ref) {
        if (ref == null) return "method@?";
        String orig = JadxStringWalker.methodDescriptor(ref);
        JadxSymbols.Symbol sym = symbols.byAddress.get(orig);
        if (sym != null) return sym.currentAddr();
        StringBuilder sb = new StringBuilder();
        sb.append(resolveTypeDescriptor(symbols, ref.getParentClassType()))
                .append("->").append(ref.getName()).append('(');
        List<String> args = ref.getArgTypes();
        if (args != null) {
            for (String a : args) sb.append(resolveTypeDescriptor(symbols, a));
        }
        return sb.append(')').append(resolveTypeDescriptor(symbols, ref.getReturnType())).toString();
    }

    private static String formatProto(JadxSymbols symbols, IMethodProto proto) {
        if (proto == null) return "()V";
        StringBuilder sb = new StringBuilder("(");
        List<String> args = proto.getArgTypes();
        if (args != null) {
            for (String a : args) sb.append(resolveTypeDescriptor(symbols, a));
        }
        return sb.append(')').append(resolveTypeDescriptor(symbols, proto.getReturnType())).toString();
    }

    private static String formatMethodHandle(JadxSymbols symbols, IMethodHandle handle, int idx) {
        if (handle == null) return "method-handle@" + idx;
        String type = handle.getType() != null ? handle.getType().name().toLowerCase(Locale.ROOT).replace('_', '-') : "handle";
        if (handle.getFieldRef() != null) {
            return type + "@" + resolveFieldDescriptor(symbols, handle.getFieldRef());
        }
        if (handle.getMethodRef() != null) {
            return type + "@" + resolveMethodDescriptor(symbols, handle.getMethodRef());
        }
        return type + "@" + idx;
    }

    private static String formatCallSite(JadxSymbols symbols, ICallSite cs, int idx) {
        if (cs == null || cs.getValues() == null || cs.getValues().isEmpty()) {
            return "call_site_" + idx;
        }
        List<EncodedValue> vals = cs.getValues();
        StringBuilder sb = new StringBuilder("call_site_").append(idx).append('(');
        for (int i = 0; i < vals.size(); i++) {
            if (i > 0) sb.append(", ");
            EncodedValue ev = vals.get(i);
            if (ev == null) {
                sb.append("null");
                continue;
            }
            Object v = ev.getValue();
            switch (ev.getType()) {
                case ENCODED_STRING -> sb.append('"').append(escapeString(String.valueOf(v))).append('"');
                case ENCODED_TYPE -> sb.append(resolveTypeDescriptor(symbols, String.valueOf(v)));
                case ENCODED_METHOD_TYPE -> sb.append(v instanceof IMethodProto p ? formatProto(symbols, p) : String.valueOf(v));
                case ENCODED_METHOD_HANDLE -> sb.append(v instanceof IMethodHandle mh ? formatMethodHandle(symbols, mh, idx) : String.valueOf(v));
                case ENCODED_METHOD -> sb.append(v instanceof IMethodRef mr ? resolveMethodDescriptor(symbols, mr) : String.valueOf(v));
                case ENCODED_FIELD -> sb.append(v instanceof IFieldRef fr ? resolveFieldDescriptor(symbols, fr) : String.valueOf(v));
                default -> sb.append(v);
            }
        }
        return sb.append(')').toString();
    }

    private static List<Long> extractArrayValues(Object data) {
        if (data instanceof byte[] b) {
            List<Long> out = new ArrayList<>(b.length);
            for (byte x : b) out.add((long) x);
            return out;
        }
        if (data instanceof short[] s) {
            List<Long> out = new ArrayList<>(s.length);
            for (short x : s) out.add((long) x);
            return out;
        }
        if (data instanceof int[] arr) {
            List<Long> out = new ArrayList<>(arr.length);
            for (int x : arr) out.add((long) x);
            return out;
        }
        if (data instanceof long[] l) {
            List<Long> out = new ArrayList<>(l.length);
            for (long x : l) out.add(x);
            return out;
        }
        return List.of();
    }

    private static String escapeString(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20 || c > 0x7e) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.toString();
    }

    private static String hexLit(long v) {
        return v < 0 ? "-0x" + Long.toHexString(-v) : "0x" + Long.toHexString(v);
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

    private static void insnRow(List<Object> rows, int offset, byte[] code, String text) {
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
}
