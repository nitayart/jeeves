package dev.workbench.engine.index;

/**
 * Case-insensitive-filesystem-safe encoding of class addrs to corpus filenames
 * (search-index persistence). Fixed-arity escapes so an escape can never merge
 * with payload: '_' → "_1"; uppercase X → "_" + lowercase(X); anything outside
 * the safe set → "_0" + 4-hex. Every '_' in the output starts an escape of
 * known width, so the map is injective — including under Unicode case folding
 * (the Kelvin sign L\u212A; folds to LK; under naive lowercasing; here it
 * escapes to _0212A and stays distinct). Public so the ITs assert on the SAME
 * implementation the corpus uses (no test-copy drift).
 *
 * WIRE FORMAT: any change here must bump SearchIndex.CORPUS_FORMAT.
 */
public final class CorpusEncoding {

    private CorpusEncoding() {}

    public static String encodeAddr(String addr) {
        StringBuilder sb = new StringBuilder(addr.length() + 8);
        for (int i = 0; i < addr.length(); i++) {
            char c = addr.charAt(i);
            if (c == '_') sb.append("_1");
            else if (c >= 'A' && c <= 'Z') sb.append('_').append((char) (c + 32));
            else if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || "/$;()-[]".indexOf(c) >= 0)
                sb.append(c);
            else sb.append("_0").append(String.format(java.util.Locale.ROOT, "%04X", (int) c));
        }
        return sb.toString();
    }
}
