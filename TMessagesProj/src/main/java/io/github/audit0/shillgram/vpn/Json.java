/*
 * SHILLGRAM: SHILLVPN built into the app.
 */
package io.github.audit0.shillgram.vpn;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small JSON reader and writer with no Android dependencies, so the Xray
 * config builder runs (and is checked) on a plain JVM too. Objects are
 * {@link LinkedHashMap} (order kept), arrays {@link List}, numbers
 * {@link Long} or {@link Double}.
 */
public final class Json {

    private Json() {
    }

    // ---- Building.

    public static Map<String, Object> object(Object... keyValues) {
        final Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            result.put((String) keyValues[i], keyValues[i + 1]);
        }
        return result;
    }

    public static List<Object> array(Object... values) {
        final List<Object> result = new ArrayList<>(values.length);
        Collections.addAll(result, values);
        return result;
    }

    // ---- Reading values the way the desktop client does (QJsonValue):
    // a wrong type gives the empty value.

    public static boolean bool(Map<String, Object> object, String key) {
        final Object value = object != null ? object.get(key) : null;
        return value instanceof Boolean && (Boolean) value;
    }

    public static long number(Map<String, Object> object, String key) {
        final Object value = object != null ? object.get(key) : null;
        return value instanceof Number ? ((Number) value).longValue() : 0;
    }

    public static String string(Map<String, Object> object, String key) {
        final Object value = object != null ? object.get(key) : null;
        return value instanceof String ? (String) value : "";
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> child(Map<String, Object> object, String key) {
        final Object value = object != null ? object.get(key) : null;
        return value instanceof Map ? (Map<String, Object>) value : new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    public static List<Object> list(Map<String, Object> object, String key) {
        final Object value = object != null ? object.get(key) : null;
        return value instanceof List ? (List<Object>) value : new ArrayList<>();
    }

    // ---- Writing.

    public static String write(Object value) {
        final StringBuilder out = new StringBuilder();
        write(out, value);
        return out.toString();
    }

    private static void write(StringBuilder out, Object value) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof String) {
            writeString(out, (String) value);
        } else if (value instanceof Boolean) {
            out.append(((Boolean) value) ? "true" : "false");
        } else if (value instanceof Double || value instanceof Float) {
            final double d = ((Number) value).doubleValue();
            if (d == Math.rint(d) && Math.abs(d) < 1e15) {
                out.append((long) d);
            } else if (Double.isNaN(d) || Double.isInfinite(d)) {
                out.append("null");
            } else {
                out.append(d);
            }
        } else if (value instanceof Number) {
            out.append(((Number) value).longValue());
        } else if (value instanceof Map) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                writeString(out, String.valueOf(entry.getKey()));
                out.append(':');
                write(out, entry.getValue());
            }
            out.append('}');
        } else if (value instanceof List) {
            out.append('[');
            boolean first = true;
            for (Object item : (List<?>) value) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                write(out, item);
            }
            out.append(']');
        } else {
            writeString(out, value.toString());
        }
    }

    private static void writeString(StringBuilder out, String value) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            final char c = value.charAt(i);
            switch (c) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                case '\b': out.append("\\b"); break;
                case '\f': out.append("\\f"); break;
                default:
                    if (c < 0x20 || c == 0x2028 || c == 0x2029) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
            }
        }
        out.append('"');
    }

    // ---- Parsing.

    /** The parsed value, or null when the text is not valid JSON. */
    public static Object parse(String text) {
        if (text == null) {
            return null;
        }
        try {
            final Parser parser = new Parser(text);
            parser.skipSpace();
            final Object result = parser.value(0);
            parser.skipSpace();
            return parser.atEnd() ? result : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The parsed object, or null when the text is not a JSON object. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        final Object result = parse(text);
        return result instanceof Map ? (Map<String, Object>) result : null;
    }

    private static final class Parser {
        private static final int MAX_DEPTH = 64;

        private final String text;
        private int position;

        Parser(String text) {
            this.text = text;
        }

        boolean atEnd() {
            return position >= text.length();
        }

        void skipSpace() {
            while (position < text.length()) {
                final char c = text.charAt(position);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    position++;
                } else {
                    break;
                }
            }
        }

        private char next() {
            if (position >= text.length()) {
                throw new IllegalArgumentException();
            }
            return text.charAt(position++);
        }

        private void expect(String word) {
            if (!text.startsWith(word, position)) {
                throw new IllegalArgumentException();
            }
            position += word.length();
        }

        Object value(int depth) {
            if (depth > MAX_DEPTH || atEnd()) {
                throw new IllegalArgumentException();
            }
            final char c = text.charAt(position);
            switch (c) {
                case '{': return object(depth);
                case '[': return array(depth);
                case '"': return string();
                case 't': expect("true"); return Boolean.TRUE;
                case 'f': expect("false"); return Boolean.FALSE;
                case 'n': expect("null"); return null;
                default: return number();
            }
        }

        private Map<String, Object> object(int depth) {
            final Map<String, Object> result = new LinkedHashMap<>();
            position++;
            skipSpace();
            if (!atEnd() && text.charAt(position) == '}') {
                position++;
                return result;
            }
            while (true) {
                skipSpace();
                if (atEnd() || text.charAt(position) != '"') {
                    throw new IllegalArgumentException();
                }
                final String key = string();
                skipSpace();
                if (next() != ':') {
                    throw new IllegalArgumentException();
                }
                skipSpace();
                result.put(key, value(depth + 1));
                skipSpace();
                final char c = next();
                if (c == '}') {
                    return result;
                } else if (c != ',') {
                    throw new IllegalArgumentException();
                }
            }
        }

        private List<Object> array(int depth) {
            final List<Object> result = new ArrayList<>();
            position++;
            skipSpace();
            if (!atEnd() && text.charAt(position) == ']') {
                position++;
                return result;
            }
            while (true) {
                skipSpace();
                result.add(value(depth + 1));
                skipSpace();
                final char c = next();
                if (c == ']') {
                    return result;
                } else if (c != ',') {
                    throw new IllegalArgumentException();
                }
            }
        }

        private String string() {
            position++; // "
            final StringBuilder out = new StringBuilder();
            while (true) {
                final char c = next();
                if (c == '"') {
                    return out.toString();
                } else if (c == '\\') {
                    final char e = next();
                    switch (e) {
                        case '"': out.append('"'); break;
                        case '\\': out.append('\\'); break;
                        case '/': out.append('/'); break;
                        case 'b': out.append('\b'); break;
                        case 'f': out.append('\f'); break;
                        case 'n': out.append('\n'); break;
                        case 'r': out.append('\r'); break;
                        case 't': out.append('\t'); break;
                        case 'u':
                            if (position + 4 > text.length()) {
                                throw new IllegalArgumentException();
                            }
                            out.append((char) Integer.parseInt(text.substring(position, position + 4), 16));
                            position += 4;
                            break;
                        default:
                            throw new IllegalArgumentException();
                    }
                } else if (c < 0x20) {
                    throw new IllegalArgumentException();
                } else {
                    out.append(c);
                }
            }
        }

        private Number number() {
            final int start = position;
            if (!atEnd() && text.charAt(position) == '-') {
                position++;
            }
            boolean fraction = false;
            while (!atEnd()) {
                final char c = text.charAt(position);
                if (c >= '0' && c <= '9') {
                    position++;
                } else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                    fraction = true;
                    position++;
                } else {
                    break;
                }
            }
            final String token = text.substring(start, position);
            if (token.isEmpty() || token.equals("-")) {
                throw new IllegalArgumentException();
            }
            if (!fraction) {
                try {
                    return Long.parseLong(token);
                } catch (NumberFormatException ignored) {
                }
            }
            return Double.parseDouble(token);
        }
    }
}
