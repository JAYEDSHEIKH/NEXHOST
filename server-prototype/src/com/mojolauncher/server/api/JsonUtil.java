package com.mojolauncher.server.api;

/**
 * Minimal hand-rolled JSON helpers — no external dependencies needed.
 */
public final class JsonUtil {

    private JsonUtil() {}

    /** Encode a Java string as a JSON string literal (with quotes and escaping). */
    public static String str(String value) {
        if (value == null) return "null";
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n");  break;
                case '\r': sb.append("\\r");  break;
                case '\t': sb.append("\\t");  break;
                default:
                    if (c < 0x20) { sb.append(String.format("\\u%04x", (int) c)); }
                    else          { sb.append(c); }
            }
        }
        sb.append('"');
        return sb.toString();
    }

    /**
     * Naive JSON string field extractor.
     * Looks for {@code "key":"value"} patterns; not a full parser — use only for
     * small, flat request bodies produced by well-behaved clients.
     */
    public static String parseString(String json, String key) {
        if (json == null) return null;
        String searchKey = "\"" + key + "\"";
        int idx = json.indexOf(searchKey);
        if (idx < 0) return null;
        idx += searchKey.length();
        while (idx < json.length() && (json.charAt(idx) == ' ' || json.charAt(idx) == ':')) idx++;
        if (idx >= json.length() || json.charAt(idx) != '"') return null;
        idx++; // skip opening quote
        StringBuilder sb = new StringBuilder();
        while (idx < json.length()) {
            char c = json.charAt(idx);
            if (c == '"') break;
            if (c == '\\' && idx + 1 < json.length()) {
                idx++;
                char esc = json.charAt(idx);
                switch (esc) {
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    default:  sb.append(esc); break;
                }
            } else {
                sb.append(c);
            }
            idx++;
        }
        return sb.toString();
    }

    /** Extract a boolean field from a flat JSON object. */
    public static boolean parseBoolean(String json, String key, boolean defaultValue) {
        if (json == null) return defaultValue;
        String searchKey = "\"" + key + "\"";
        int idx = json.indexOf(searchKey);
        if (idx < 0) return defaultValue;
        idx += searchKey.length();
        while (idx < json.length() && (json.charAt(idx) == ' ' || json.charAt(idx) == ':')) idx++;
        if (json.startsWith("true",  idx)) return true;
        if (json.startsWith("false", idx)) return false;
        return defaultValue;
    }
}
