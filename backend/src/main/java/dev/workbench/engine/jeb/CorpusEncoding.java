package dev.workbench.engine.jeb;

/**
 * Compatibility delegate for {@link dev.workbench.engine.index.CorpusEncoding}.
 *
 * WIRE FORMAT: any change there must bump SearchIndex.CORPUS_FORMAT.
 */
public final class CorpusEncoding {

    private CorpusEncoding() {}

    public static String encodeAddr(String addr) {
        return dev.workbench.engine.index.CorpusEncoding.encodeAddr(addr);
    }
}
