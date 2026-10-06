package com.prankcraft.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A very small JSON reader/writer.
 *
 * <p><b>Why hand-rolled instead of Gson.</b> Gson is already on a Forge server's classpath
 * (Minecraft uses it), so pulling it in would be tempting. Two reasons not to: a mod that
 * reaches into another mod's libraries breaks the moment that library changes shape, and the
 * two files this class serves - the consent store and the audit log - must stay readable by
 * whoever is investigating a complaint, with nothing hidden behind a serialiser's opinions.
 * The output here is pretty-printed, stable and hand-editable on purpose.
 *
 * <p>The reader is strict about what it accepts and returns {@code null} on anything it does
 * not understand. A corrupt consent file must never silently become "everyone consented".
 */
public final class Json {

    private Json() {
    }

    // ------------------------------------------------------------------ writing

    /** Renders a value (map/list/String/Number/Boolean/null) as pretty-printed JSON. */
    public static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        writeValue(sb, value, 0);
        sb.append('\n');
        return sb.toString();
    }

    private static void writeValue(StringBuilder sb, Object value, int indent) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String) {
            writeString(sb, (String) value);
        } else if (value instanceof Map) {
            writeObject(sb, asMap(value), indent);
        } else if (value instanceof Iterable) {
            writeArray(sb, (Iterable<?>) value, indent);
        } else if (value instanceof Boolean || value instanceof Number) {
            sb.append(value.toString());
        } else {
            // Anything unexpected is written as text. Losing the type is better than
            // losing the record entirely.
            writeString(sb, value.toString());
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
            out.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return out;
    }

    private static void writeObject(StringBuilder sb, Map<String, Object> map, int indent) {
        if (map.isEmpty()) {
            sb.append("{}");
            return;
        }
        sb.append("{\n");
        int i = 0;
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            pad(sb, indent + 1);
            writeString(sb, entry.getKey());
            sb.append(": ");
            writeValue(sb, entry.getValue(), indent + 1);
            if (++i < map.size()) {
                sb.append(',');
            }
            sb.append('\n');
        }
        pad(sb, indent);
        sb.append('}');
    }

    private static void writeArray(StringBuilder sb, Iterable<?> values, int indent) {
        List<Object> list = new ArrayList<>();
        for (Object value : values) {
            list.add(value);
        }
        if (list.isEmpty()) {
            sb.append("[]");
            return;
        }
        // Arrays of scalars stay on one line: the consent file is meant to be readable.
        boolean scalarsOnly = true;
        for (Object value : list) {
            if (value instanceof Map || value instanceof Iterable) {
                scalarsOnly = false;
                break;
            }
        }
        if (scalarsOnly) {
            sb.append('[');
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                writeValue(sb, list.get(i), indent);
            }
            sb.append(']');
            return;
        }
        sb.append("[\n");
        for (int i = 0; i < list.size(); i++) {
            pad(sb, indent + 1);
            writeValue(sb, list.get(i), indent + 1);
            if (i + 1 < list.size()) {
                sb.append(',');
            }
            sb.append('\n');
        }
        pad(sb, indent);
        sb.append(']');
    }

    private static void writeString(StringBuilder sb, String value) {
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }

    private static void pad(StringBuilder sb, int indent) {
        for (int i = 0; i < indent; i++) {
            sb.append("  ");
        }
    }

    // ------------------------------------------------------------------ reading

    /** Parses a JSON document, or returns {@code null} if it is not valid JSON. */
    public static Object read(String text) {
        if (text == null) {
            return null;
        }
        try {
            Parser parser = new Parser(text);
            parser.skipWhitespace();
            Object value = parser.readValue();
            parser.skipWhitespace();
            if (!parser.atEnd()) {
                return null; // trailing junk means a truncated or hand-mangled file
            }
            return value;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static final class Parser {
        private final String text;
        private int pos;

        Parser(String text) {
            this.text = text;
        }

        boolean atEnd() {
            return pos >= text.length();
        }

        void skipWhitespace() {
            while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
                pos++;
            }
        }

        Object readValue() {
            skipWhitespace();
            if (atEnd()) {
                throw new IllegalStateException("unexpected end of input");
            }
            char c = text.charAt(pos);
            switch (c) {
                case '{':
                    return readObject();
                case '[':
                    return readArray();
                case '"':
                    return readString();
                case 't':
                    expect("true");
                    return Boolean.TRUE;
                case 'f':
                    expect("false");
                    return Boolean.FALSE;
                case 'n':
                    expect("null");
                    return null;
                default:
                    return readNumber();
            }
        }

        Map<String, Object> readObject() {
            Map<String, Object> map = new LinkedHashMap<>();
            pos++; // '{'
            skipWhitespace();
            if (!atEnd() && text.charAt(pos) == '}') {
                pos++;
                return map;
            }
            while (true) {
                skipWhitespace();
                String key = readString();
                skipWhitespace();
                if (atEnd() || text.charAt(pos) != ':') {
                    throw new IllegalStateException("expected ':'");
                }
                pos++;
                map.put(key, readValue());
                skipWhitespace();
                if (atEnd()) {
                    throw new IllegalStateException("unterminated object");
                }
                char c = text.charAt(pos++);
                if (c == '}') {
                    return map;
                }
                if (c != ',') {
                    throw new IllegalStateException("expected ',' or '}'");
                }
            }
        }

        List<Object> readArray() {
            List<Object> list = new ArrayList<>();
            pos++; // '['
            skipWhitespace();
            if (!atEnd() && text.charAt(pos) == ']') {
                pos++;
                return list;
            }
            while (true) {
                list.add(readValue());
                skipWhitespace();
                if (atEnd()) {
                    throw new IllegalStateException("unterminated array");
                }
                char c = text.charAt(pos++);
                if (c == ']') {
                    return list;
                }
                if (c != ',') {
                    throw new IllegalStateException("expected ',' or ']'");
                }
            }
        }

        String readString() {
            if (atEnd() || text.charAt(pos) != '"') {
                throw new IllegalStateException("expected a string");
            }
            pos++;
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (atEnd()) {
                    throw new IllegalStateException("unterminated string");
                }
                char c = text.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                if (atEnd()) {
                    throw new IllegalStateException("unterminated escape");
                }
                char esc = text.charAt(pos++);
                switch (esc) {
                    case '"':
                        sb.append('"');
                        break;
                    case '\\':
                        sb.append('\\');
                        break;
                    case '/':
                        sb.append('/');
                        break;
                    case 'b':
                        sb.append('\b');
                        break;
                    case 'f':
                        sb.append('\f');
                        break;
                    case 'n':
                        sb.append('\n');
                        break;
                    case 'r':
                        sb.append('\r');
                        break;
                    case 't':
                        sb.append('\t');
                        break;
                    case 'u':
                        if (pos + 4 > text.length()) {
                            throw new IllegalStateException("truncated \\u escape");
                        }
                        sb.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
                        pos += 4;
                        break;
                    default:
                        throw new IllegalStateException("bad escape \\" + esc);
                }
            }
        }

        Object readNumber() {
            int start = pos;
            while (pos < text.length()) {
                char c = text.charAt(pos);
                if (c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E' || (c >= '0' && c <= '9')) {
                    pos++;
                } else {
                    break;
                }
            }
            if (start == pos) {
                throw new IllegalStateException("expected a value");
            }
            String raw = text.substring(start, pos);
            if (raw.indexOf('.') < 0 && raw.indexOf('e') < 0 && raw.indexOf('E') < 0) {
                try {
                    return Long.valueOf(raw);
                } catch (NumberFormatException ignored) {
                    // falls through to double, e.g. a number too large for a long
                }
            }
            return Double.valueOf(raw);
        }

        void expect(String literal) {
            if (!text.startsWith(literal, pos)) {
                throw new IllegalStateException("expected " + literal);
            }
            pos += literal.length();
        }
    }

    // ------------------------------------------------------------------ helpers

    /** Convenience accessor: a nested value, or {@code fallback} when absent/wrong type. */
    @SuppressWarnings("unchecked")
    public static <T> T get(Object root, String fallback, Class<T> type, T orElse) {
        if (!(root instanceof Map)) {
            return orElse;
        }
        Object value = ((Map<String, Object>) root).get(fallback);
        return type.isInstance(value) ? (T) value : orElse;
    }

    /** Convenience accessor for a nested object. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> object(Object root, String key) {
        if (root instanceof Map) {
            Object value = ((Map<String, Object>) root).get(key);
            if (value instanceof Map) {
                return (Map<String, Object>) value;
            }
        }
        return new LinkedHashMap<>();
    }

    /** Convenience accessor for a list of strings, ignoring non-string members. */
    public static List<String> stringList(Object root) {
        List<String> out = new ArrayList<>();
        if (root instanceof Iterable) {
            for (Object value : (Iterable<?>) root) {
                if (value instanceof String) {
                    out.add((String) value);
                }
            }
        }
        return out;
    }
}
