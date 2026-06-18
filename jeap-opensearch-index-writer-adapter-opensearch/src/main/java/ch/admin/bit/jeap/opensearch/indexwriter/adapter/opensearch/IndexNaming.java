package ch.admin.bit.jeap.opensearch.indexwriter.adapter.opensearch;

class IndexNaming {

    private static final String WRITE_SUFFIX = "_write";
    private static final String INDEX_PATTERN_SUFFIX = "-*";
    private static final String INITIAL_INDEX_SUFFIX = "-000001";

    private IndexNaming() {
    }

    static String logicalName(String indexWriteAlias) {
        String base = indexWriteAlias.toLowerCase();
        if (base.endsWith(WRITE_SUFFIX)) {
            base = base.substring(0, base.length() - WRITE_SUFFIX.length());
        }
        return base;
    }

    static String indexPattern(String indexWriteAlias) {
        return logicalName(indexWriteAlias) + INDEX_PATTERN_SUFFIX;
    }

    static String initialPhysicalIndexName(String indexWriteAlias) {
        return logicalName(indexWriteAlias) + INITIAL_INDEX_SUFFIX;
    }
}
