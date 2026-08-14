package io.github.cdevarenne.gctx.app;

import java.util.List;
import java.util.Map;

/**
 * Minimal JSON output for the {@code --json} envelope.
 *
 * <p>Hand-rolled rather than pulled from a mapper: the envelope is already a plain
 * {@code Map}/{@code List}/scalar tree by the time it reaches here, and this keeps the wire
 * format under the same review as the contract it serializes.
 */
final class JsonWriter {

    private JsonWriter() {
    }

    static String write(Object value) {
        StringBuilder out = new StringBuilder();
        write(value, out, 0);
        return out.toString();
    }

    private static void write(Object value, StringBuilder out, int depth) {
        switch (value) {
            case null -> out.append("null");
            case Map<?, ?> map -> writeMap(map, out, depth);
            case List<?> list -> writeList(list, out, depth);
            case String text -> writeString(text, out);
            case Boolean flag -> out.append(flag);
            case Number number -> out.append(number);
            default -> writeString(String.valueOf(value), out);
        }
    }

    private static void writeMap(Map<?, ?> map, StringBuilder out, int depth) {
        if (map.isEmpty()) {
            out.append("{}");
            return;
        }
        out.append("{\n");
        int remaining = map.size();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            indent(out, depth + 1);
            writeString(String.valueOf(entry.getKey()), out);
            out.append(": ");
            write(entry.getValue(), out, depth + 1);
            out.append(--remaining > 0 ? ",\n" : "\n");
        }
        indent(out, depth);
        out.append('}');
    }

    private static void writeList(List<?> list, StringBuilder out, int depth) {
        if (list.isEmpty()) {
            out.append("[]");
            return;
        }
        out.append("[\n");
        for (int i = 0; i < list.size(); i++) {
            indent(out, depth + 1);
            write(list.get(i), out, depth + 1);
            out.append(i < list.size() - 1 ? ",\n" : "\n");
        }
        indent(out, depth);
        out.append(']');
    }

    private static void writeString(String text, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    private static void indent(StringBuilder out, int depth) {
        out.append("  ".repeat(depth));
    }
}
