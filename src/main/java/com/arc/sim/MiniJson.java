package com.arc.sim;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal dependency-free JSON parser for decoding weather API responses (Engine 6) without
 * introducing an external JSON library dependency, consistent with this project's preference
 * for lightweight hand-rolled readers over third-party dependencies (see MiniParquet). Limited
 * to deserialization; does not support streaming, serialization, or schema validation. Parses a
 * complete document into nested Map/List/String/Double/Boolean/null structures navigable via
 * get().
 */
public class MiniJson {
    private final String s;
    private int i;

    private MiniJson(String s) {
        this.s = s;
        this.i = 0;
    }

    /** Parses a complete JSON document into nested Map&lt;String,Object&gt;/List&lt;Object&gt;/String/Double/Boolean/null. */
    public static Object parse(String json) {
        MiniJson p = new MiniJson(json);
        Object v = p.parseValue();
        return v;
    }

    /** Resolves a chain of object keys (e.g. get(root, "current", "wind_kph")); returns null on any missing or non-object step. */
    @SuppressWarnings("unchecked")
    public static Object get(Object node, String... path) {
        Object cur = node;
        for (String key : path) {
            if (!(cur instanceof Map)) return null;
            cur = ((Map<String, Object>) cur).get(key);
        }
        return cur;
    }

    public static double asDouble(Object v, double defaultValue) {
        return (v instanceof Number) ? ((Number) v).doubleValue() : defaultValue;
    }

    public static String asString(Object v, String defaultValue) {
        return (v instanceof String) ? (String) v : defaultValue;
    }

    private Object parseValue() {
        skipWs();
        char c = s.charAt(i);
        if (c == '{') return parseObject();
        if (c == '[') return parseArray();
        if (c == '"') return parseString();
        if (c == 't' || c == 'f') return parseBoolean();
        if (c == 'n') {
            i += 4; // "null"
            return null;
        }
        return parseNumber();
    }

    private Map<String, Object> parseObject() {
        Map<String, Object> map = new LinkedHashMap<>();
        i++; // consume '{'
        skipWs();
        if (peek() == '}') {
            i++;
            return map;
        }
        while (true) {
            skipWs();
            String key = parseString();
            skipWs();
            i++; // consume ':'
            Object val = parseValue();
            map.put(key, val);
            skipWs();
            char c = s.charAt(i);
            if (c == ',') {
                i++;
                continue;
            }
            if (c == '}') {
                i++;
                break;
            }
            throw new IllegalArgumentException("Malformed JSON object near index " + i);
        }
        return map;
    }

    private List<Object> parseArray() {
        List<Object> list = new ArrayList<>();
        i++; // consume '['
        skipWs();
        if (peek() == ']') {
            i++;
            return list;
        }
        while (true) {
            list.add(parseValue());
            skipWs();
            char c = s.charAt(i);
            if (c == ',') {
                i++;
                continue;
            }
            if (c == ']') {
                i++;
                break;
            }
            throw new IllegalArgumentException("Malformed JSON array near index " + i);
        }
        return list;
    }

    private String parseString() {
        skipWs();
        i++; // consume opening quote
        StringBuilder sb = new StringBuilder();
        while (true) {
            char c = s.charAt(i++);
            if (c == '"') break;
            if (c == '\\') {
                char esc = s.charAt(i++);
                switch (esc) {
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case '/': sb.append('/'); break;
                    case 'n': sb.append('\n'); break;
                    case 't': sb.append('\t'); break;
                    case 'r': sb.append('\r'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'u':
                        String hex = s.substring(i, i + 4);
                        sb.append((char) Integer.parseInt(hex, 16));
                        i += 4;
                        break;
                    default:
                        sb.append(esc);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private Double parseNumber() {
        int start = i;
        while (i < s.length() && "-+.eE0123456789".indexOf(s.charAt(i)) >= 0) i++;
        return Double.parseDouble(s.substring(start, i));
    }

    private Boolean parseBoolean() {
        if (s.startsWith("true", i)) {
            i += 4;
            return Boolean.TRUE;
        }
        i += 5; // "false"
        return Boolean.FALSE;
    }

    private char peek() {
        return s.charAt(i);
    }

    private void skipWs() {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
    }
}
