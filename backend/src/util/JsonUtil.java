package util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JsonUtil - a small JSON writer and reader built only with standard Java.
 *
 * WHAT IS JSON?  A text format for data. Example:  {"id": 1, "name": "Main Gate", "tags": ["a","b"]}
 *
 * WRITING  toJson(object)   Java value  -> JSON text
 *     Map -> {...}   List/Collection -> [...]   String   Number   Boolean   null   Enum (written as its name)
 *     NaN and Infinity are not valid JSON, so they are written as null.
 *
 * READING  parse(text)      JSON text   -> Java value
 *     {...} -> LinkedHashMap<String,Object>     [...] -> ArrayList<Object>
 *     "text" -> String      whole number -> Long      other number -> Double
 *     true / false -> Boolean       null -> null
 *
 * The reader is STRICT, because the text comes from outside and must not be trusted:
 *     - it follows the JSON grammar exactly (no comments, no trailing commas, no leading zeros)
 *     - text after the value is an error
 *     - duplicate keys in one object are an error (stops "parameter pollution" tricks)
 *     - nesting deeper than 32 levels is an error (stops crashes from deeply nested input)
 *     - numbers must be finite
 * Errors are reported as JsonParseException with the position of the problem
 * (the message never repeats the client's text).
 */
public final class JsonUtil {

    private static final int MAX_DEPTH = 32;

    private JsonUtil() { }

    /** Thrown when text is not valid JSON. */
    public static class JsonParseException extends RuntimeException {
        public JsonParseException(String message) {
            super(message);
        }
    }

    // =====================================================================
    // WRITER
    // =====================================================================

    public static String toJson(Object value) {
        StringBuilder sb = new StringBuilder();
        write(sb, value);
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String) {
            writeString(sb, (String) value);
        } else if (value instanceof Boolean) {
            sb.append(((Boolean) value) ? "true" : "false");
        } else if (value instanceof Double || value instanceof Float) {
            double d = ((Number) value).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                sb.append("null");                       // not representable in JSON
            } else {
                sb.append(d);                            // e.g. 202.7  (always a valid JSON number)
            }
        } else if (value instanceof Number) {
            sb.append(value.toString());                 // Integer, Long ...
        } else if (value instanceof Enum) {
            writeString(sb, ((Enum<?>) value).name());
        } else if (value instanceof Map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                if (!first) sb.append(',');
                first = false;
                writeString(sb, String.valueOf(entry.getKey()));
                sb.append(':');
                write(sb, entry.getValue());
            }
            sb.append('}');
        } else if (value instanceof Collection) {
            sb.append('[');
            boolean first = true;
            for (Object item : (Collection<?>) value) {
                if (!first) sb.append(',');
                first = false;
                write(sb, item);
            }
            sb.append(']');
        } else {
            // A programming mistake: we only ever hand this class plain data.
            throw new IllegalArgumentException("Cannot write value of type " + value.getClass().getName() + " as JSON");
        }
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\b': sb.append("\\b");  break;
                case '\f': sb.append("\\f");  break;
                case '\n': sb.append("\\n");  break;
                case '\r': sb.append("\\r");  break;
                case '\t': sb.append("\\t");  break;
                default:
                    // control characters and the two line-separator characters are written as \\uXXXX
                    if (c < 0x20 || c == '\u2028' || c == '\u2029') {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }

    // =====================================================================
    // READER (recursive-descent parser)
    // =====================================================================

    public static Object parse(String text) {
        if (text == null) {
            throw new JsonParseException("Empty JSON text");
        }
        Parser p = new Parser(text);
        p.skipWhitespace();
        if (p.atEnd()) {
            throw new JsonParseException("Empty JSON text");
        }
        Object result = p.readValue(0);
        p.skipWhitespace();
        if (!p.atEnd()) {
            throw p.error("Unexpected text after the JSON value");
        }
        return result;
    }

    private static final class Parser {
        private final String s;
        private int pos = 0;

        Parser(String s) { this.s = s; }

        boolean atEnd() { return pos >= s.length(); }

        JsonParseException error(String what) {
            return new JsonParseException("Invalid JSON at position " + pos + ": " + what);
        }

        void skipWhitespace() {
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') pos++; else break;
            }
        }

        Object readValue(int depth) {
            if (depth > MAX_DEPTH) {
                throw error("Nesting is too deep");
            }
            if (atEnd()) {
                throw error("Unexpected end");
            }
            char c = s.charAt(pos);
            switch (c) {
                case '{': return readObject(depth);
                case '[': return readArray(depth);
                case '"': return readString();
                case 't': expectWord("true");  return Boolean.TRUE;
                case 'f': expectWord("false"); return Boolean.FALSE;
                case 'n': expectWord("null");  return null;
                default:
                    if (c == '-' || (c >= '0' && c <= '9')) return readNumber();
                    throw error("Unexpected character");
            }
        }

        void expectWord(String word) {
            if (!s.startsWith(word, pos)) {
                throw error("Unexpected character");
            }
            pos += word.length();
        }

        Map<String, Object> readObject(int depth) {
            Map<String, Object> map = new LinkedHashMap<>();
            pos++;                                    // skip {
            skipWhitespace();
            if (!atEnd() && s.charAt(pos) == '}') { pos++; return map; }
            while (true) {
                skipWhitespace();
                if (atEnd() || s.charAt(pos) != '"') throw error("Expected a quoted key");
                String key = readString();
                if (map.containsKey(key)) throw error("Duplicate key in object");
                skipWhitespace();
                if (atEnd() || s.charAt(pos) != ':') throw error("Expected ':' after key");
                pos++;
                skipWhitespace();
                map.put(key, readValue(depth + 1));
                skipWhitespace();
                if (atEnd()) throw error("Unexpected end");
                char c = s.charAt(pos++);
                if (c == '}') return map;
                if (c != ',') { pos--; throw error("Expected ',' or '}'"); }
            }
        }

        List<Object> readArray(int depth) {
            List<Object> list = new ArrayList<>();
            pos++;                                    // skip [
            skipWhitespace();
            if (!atEnd() && s.charAt(pos) == ']') { pos++; return list; }
            while (true) {
                skipWhitespace();
                list.add(readValue(depth + 1));
                skipWhitespace();
                if (atEnd()) throw error("Unexpected end");
                char c = s.charAt(pos++);
                if (c == ']') return list;
                if (c != ',') { pos--; throw error("Expected ',' or ']'"); }
            }
        }

        String readString() {
            pos++;                                    // skip opening quote
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (atEnd()) throw error("Unterminated string");
                char c = s.charAt(pos++);
                if (c == '"') return sb.toString();
                if (c < 0x20) { pos--; throw error("Control character inside string"); }
                if (c != '\\') { sb.append(c); continue; }
                if (atEnd()) throw error("Unterminated string");
                char e = s.charAt(pos++);
                switch (e) {
                    case '"':  sb.append('"');  break;
                    case '\\': sb.append('\\'); break;
                    case '/':  sb.append('/');  break;
                    case 'b':  sb.append('\b'); break;
                    case 'f':  sb.append('\f'); break;
                    case 'n':  sb.append('\n'); break;
                    case 'r':  sb.append('\r'); break;
                    case 't':  sb.append('\t'); break;
                    case 'u':
                        if (pos + 4 > s.length()) throw error("Bad \\u escape");
                        int code = 0;
                        for (int i = 0; i < 4; i++) {
                            int digit = Character.digit(s.charAt(pos + i), 16);
                            if (digit < 0) throw error("Bad \\u escape");
                            code = code * 16 + digit;
                        }
                        pos += 4;
                        sb.append((char) code);
                        break;
                    default:
                        pos--;
                        throw error("Bad escape sequence");
                }
            }
        }

        /** JSON number grammar:  -? (0 | [1-9][0-9]*) (. [0-9]+)? ([eE] [+-]? [0-9]+)? */
        Object readNumber() {
            int start = pos;
            if (s.charAt(pos) == '-') pos++;
            if (atEnd()) throw error("Bad number");
            if (s.charAt(pos) == '0') {
                pos++;
            } else if (s.charAt(pos) >= '1' && s.charAt(pos) <= '9') {
                while (!atEnd() && Character.isDigit(s.charAt(pos)) && s.charAt(pos) < 128) pos++;
            } else {
                throw error("Bad number");
            }
            boolean isWhole = true;
            if (!atEnd() && s.charAt(pos) == '.') {
                isWhole = false;
                pos++;
                int digits = 0;
                while (!atEnd() && s.charAt(pos) >= '0' && s.charAt(pos) <= '9') { pos++; digits++; }
                if (digits == 0) throw error("Bad number");
            }
            if (!atEnd() && (s.charAt(pos) == 'e' || s.charAt(pos) == 'E')) {
                isWhole = false;
                pos++;
                if (!atEnd() && (s.charAt(pos) == '+' || s.charAt(pos) == '-')) pos++;
                int digits = 0;
                while (!atEnd() && s.charAt(pos) >= '0' && s.charAt(pos) <= '9') { pos++; digits++; }
                if (digits == 0) throw error("Bad number");
            }
            String text = s.substring(start, pos);
            if (isWhole) {
                try {
                    return Long.valueOf(text);
                } catch (NumberFormatException e) {
                    // too large for a long: fall through and treat as a double
                }
            }
            double d = Double.parseDouble(text);
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                throw error("Number is out of range");
            }
            return d;
        }
    }
}
