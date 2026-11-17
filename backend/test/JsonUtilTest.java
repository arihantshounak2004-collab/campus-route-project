import util.JsonUtil;
import util.ValidationException;
import util.ValidationUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JsonUtilTest  (Phase 7)
 * -----------------------
 * Tests the JSON writer/reader and the input validation. NO database needed.
 * Run from the PROJECT ROOT:   java -cp "out;lib\*" JsonUtilTest
 */
public class JsonUtilTest {

    private static int passed = 0;
    private static int failed = 0;

    private static void check(String name, boolean ok, String detail) {
        if (ok) { passed++; } else { failed++; }
        System.out.println("  " + (ok ? "PASS" : "FAIL") + "  " + name + (detail.isEmpty() ? "" : "  -> " + detail));
    }

    /** True if parsing this text is rejected with a JsonParseException (and nothing else, e.g. no crash). */
    private static boolean rejected(String text) {
        try {
            JsonUtil.parse(text);
            return false;
        } catch (JsonUtil.JsonParseException e) {
            return true;
        } catch (Throwable t) {
            System.out.println("        (unexpected " + t.getClass().getName() + " for input of length " + text.length() + ")");
            return false;
        }
    }

    private static boolean rejectedValidation(Runnable r) {
        try {
            r.run();
            return false;
        } catch (ValidationException e) {
            return true;
        }
    }

    public static void main(String[] args) {
        System.out.println("=== Phase 7: JSON and validation tests ===");
        writer();
        readerValid();
        readerInvalid();
        validation();
        System.out.println("\n=== Result: " + passed + " passed, " + failed + " failed ===");
        System.exit(failed == 0 ? 0 : 1);
    }

    // ------------------------------------------------------------------
    private static void writer() {
        System.out.println("\n[1] Writing JSON");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", 1);
        m.put("name", "Main Gate");
        m.put("distance", 202.7);
        m.put("active", true);
        m.put("note", null);
        m.put("tags", Arrays.asList("a", "b"));
        check("Map with numbers, text, boolean, null and list",
                JsonUtil.toJson(m).equals("{\"id\":1,\"name\":\"Main Gate\",\"distance\":202.7,\"active\":true,\"note\":null,\"tags\":[\"a\",\"b\"]}"),
                JsonUtil.toJson(m));
        check("Quotes, backslash and new line are escaped",
                JsonUtil.toJson("say \"hi\"\\\n").equals("\"say \\\"hi\\\"\\\\\\n\""), JsonUtil.toJson("say \"hi\"\\\n"));
        check("Control character (char 1) is written as \\u0001", JsonUtil.toJson("a\u0001b").equals("\"a\\u0001b\""), JsonUtil.toJson("a\u0001b"));
        check("NaN and Infinity are written as null (not valid JSON numbers)",
                JsonUtil.toJson(Double.NaN).equals("null") && JsonUtil.toJson(Double.POSITIVE_INFINITY).equals("null"), "");
        check("Enum is written as its name", JsonUtil.toJson(model.CrowdLevel.HIGH).equals("\"HIGH\""), "");
        check("Empty list and empty map", JsonUtil.toJson(new ArrayList<Object>()).equals("[]")
                && JsonUtil.toJson(new LinkedHashMap<String, Object>()).equals("{}"), "");
        check("Unicode text (Hindi and emoji) is kept as it is", JsonUtil.toJson("\u0915\u0948\u0902\u092a\u0938 \uD83D\uDE80").contains("\u0915\u0948\u0902\u092a\u0938 \uD83D\uDE80"), "");
        boolean unsupported = false;
        try {
            JsonUtil.toJson(new Object());
        } catch (IllegalArgumentException e) {
            unsupported = true;
        }
        check("An unsupported Java object is refused (no silent garbage)", unsupported, "");
        check("A key that looks like an attack is escaped safely",
                JsonUtil.toJson(java.util.Collections.singletonMap("a\"b", 1)).equals("{\"a\\\"b\":1}"), "");
    }

    // ------------------------------------------------------------------
    @SuppressWarnings("unchecked")
    private static void readerValid() {
        System.out.println("\n[2] Reading valid JSON");
        Object o = JsonUtil.parse("  {\"sourceId\": 1, \"metric\": \"TIME\", \"w\": 2.5, \"ok\": true, \"x\": null, \"list\": [1, 2, 3]}  ");
        Map<String, Object> m = (Map<String, Object>) o;
        check("Object keys and types: whole number -> Long, decimal -> Double, true -> Boolean, null -> null",
                m.get("sourceId") instanceof Long && (Long) m.get("sourceId") == 1L
                        && m.get("w") instanceof Double && (Double) m.get("w") == 2.5
                        && Boolean.TRUE.equals(m.get("ok")) && m.containsKey("x") && m.get("x") == null
                        && "TIME".equals(m.get("metric")), m.toString());
        check("Array of numbers", ((List<Object>) m.get("list")).equals(Arrays.<Object>asList(1L, 2L, 3L)), "");
        check("Key order is kept", new ArrayList<>(m.keySet()).equals(Arrays.asList("sourceId", "metric", "w", "ok", "x", "list")), "");
        check("Escapes: \\n \\t \\\" \\\\ \\/ and \\u0041 are decoded",
                JsonUtil.parse("\"a\\n\\t\\\"\\\\\\/\\u0041\"").equals("a\n\t\"\\/A"), "");
        check("Surrogate pair \\ud83d\\ude80 becomes one emoji", JsonUtil.parse("\"\\ud83d\\ude80\"").equals("\uD83D\uDE80"), "");
        check("Numbers: 0, -5, 1e3, 1.5E-2, -0.5", ((Long) JsonUtil.parse("0")) == 0L && ((Long) JsonUtil.parse("-5")) == -5L
                && ((Double) JsonUtil.parse("1e3")) == 1000.0 && Math.abs((Double) JsonUtil.parse("1.5E-2") - 0.015) < 1e-12
                && ((Double) JsonUtil.parse("-0.5")) == -0.5, "");
        check("Very large whole number falls back to Double instead of crashing", JsonUtil.parse("123456789012345678901234567890") instanceof Double, "");
        check("true / false / null on their own", JsonUtil.parse("true").equals(true) && JsonUtil.parse("false").equals(false) && JsonUtil.parse("null") == null, "");
        check("Empty object and empty array", ((Map<?, ?>) JsonUtil.parse("{}")).isEmpty() && ((List<?>) JsonUtil.parse("[ ]")).isEmpty(), "");
        // Round trip: write then read gives the same text again
        String original = "{\"a\":[1,2,{\"b\":\"c\\nd\"}],\"e\":null,\"f\":1.25,\"g\":false}";
        check("Round trip: parse then write gives the same text", JsonUtil.toJson(JsonUtil.parse(original)).equals(original), JsonUtil.toJson(JsonUtil.parse(original)));
        StringBuilder deep = new StringBuilder();
        for (int i = 0; i < 30; i++) deep.append('[');
        for (int i = 0; i < 30; i++) deep.append(']');
        check("Nesting of 30 levels is accepted (limit is 32)", !rejected(deep.toString()), "");
    }

    // ------------------------------------------------------------------
    private static void readerInvalid() {
        System.out.println("\n[3] Rejecting invalid or hostile JSON (must be an error, never a crash)");
        String[] bad = {
                "", "   ", "{", "}", "[", "{\"a\":1", "{\"a\":1,}", "[1,2,]", "{\"a\" 1}", "{a:1}", "{'a':1}",
                "{\"a\":1}{\"b\":2}", "{\"a\":1} extra", "nul", "tru", "True", "NaN", "Infinity", "-Infinity",
                "01", "1.", ".5", "-", "+1", "1e", "1e+", "0x10", "--1",
                "\"unterminated", "\"bad \\q escape\"", "\"bad \\u12G4\"", "\"bad \\u12\"", "\"tab\there\"",
                "{\"a\":1,\"a\":2}", "[1 2]", "{\"a\":}", "{,}", "[,]", "// comment\n{}", "{\"a\":1} // c",
                "\uFEFF{}"
        };
        int notRejected = 0;
        StringBuilder which = new StringBuilder();
        for (String b : bad) {
            if (!rejected(b)) { notRejected++; which.append(" [").append(b.replace("\n", "\\n")).append("]"); }
        }
        check("All " + bad.length + " malformed inputs are rejected", notRejected == 0, notRejected == 0 ? "" : which.toString());
        check("null text is rejected", rejected(null), "");

        StringBuilder deep = new StringBuilder();
        for (int i = 0; i < 20000; i++) deep.append('[');
        check("20,000 nested brackets are rejected without a StackOverflowError", rejected(deep.toString()), "");
        StringBuilder deepObj = new StringBuilder();
        for (int i = 0; i < 20000; i++) deepObj.append("{\"a\":");
        check("20,000 nested objects are rejected without a StackOverflowError", rejected(deepObj.toString()), "");
        check("33 levels of nesting are rejected (limit is 32)", rejected(repeat("[", 34) + repeat("]", 34)), "");

        try {
            JsonUtil.parse("{\"secret-password-123\": }");
            check("Error message does not repeat the client's text", false, "no error");
        } catch (JsonUtil.JsonParseException e) {
            check("Error message gives a position and does not repeat the client's text",
                    e.getMessage().contains("position") && !e.getMessage().contains("secret-password-123"), e.getMessage());
        }
    }

    private static String repeat(String s, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(s);
        return sb.toString();
    }

    // ------------------------------------------------------------------
    @SuppressWarnings("unchecked")
    private static void validation() {
        System.out.println("\n[4] ValidationUtil");
        final Map<String, Object> body = (Map<String, Object>) JsonUtil.parse(
                "{\"id\":5,\"idDecimal\":5.5,\"idWhole\":8.0,\"idText\":\"5\",\"big\":99999999999,\"neg\":-1,\"flag\":true,\"flagText\":\"true\","
              + "\"w\":2.5,\"wText\":\"2\",\"wBig\":11,\"metric\":\"time\",\"metricBad\":\"FAST\",\"metricNum\":3,\"nothing\":null}");

        check("requireInt accepts 5", ValidationUtil.requireInt(body, "id", 1, 100) == 5, "");
        check("requireInt accepts 8.0 as 8", ValidationUtil.requireInt(body, "idWhole", 1, 100) == 8, "");
        check("requireInt rejects 5.5", rejectedValidation(() -> ValidationUtil.requireInt(body, "idDecimal", 1, 100)), "");
        check("requireInt rejects text \"5\"", rejectedValidation(() -> ValidationUtil.requireInt(body, "idText", 1, 100)), "");
        check("requireInt rejects a number above the maximum", rejectedValidation(() -> ValidationUtil.requireInt(body, "big", 1, 1000000)), "");
        check("requireInt rejects a number below the minimum", rejectedValidation(() -> ValidationUtil.requireInt(body, "neg", 1, 100)), "");
        check("requireInt rejects a missing field", rejectedValidation(() -> ValidationUtil.requireInt(body, "absent", 1, 100)), "");
        check("requireInt rejects null", rejectedValidation(() -> ValidationUtil.requireInt(body, "nothing", 1, 100)), "");
        check("requireInt rejects true", rejectedValidation(() -> ValidationUtil.requireInt(body, "flag", 1, 100)), "");

        check("optionalBoolean: true, default when absent, default when null",
                ValidationUtil.optionalBoolean(body, "flag", false) && !ValidationUtil.optionalBoolean(body, "absent", false)
                        && ValidationUtil.optionalBoolean(body, "nothing", true), "");
        check("optionalBoolean rejects the text \"true\"", rejectedValidation(() -> ValidationUtil.optionalBoolean(body, "flagText", false)), "");

        check("optionalDouble accepts 2.5 and uses the default when absent",
                ValidationUtil.optionalDouble(body, "w", 1, 0, 10) == 2.5 && ValidationUtil.optionalDouble(body, "absent", 1, 0, 10) == 1.0, "");
        check("optionalDouble rejects text", rejectedValidation(() -> ValidationUtil.optionalDouble(body, "wText", 1, 0, 10)), "");
        check("optionalDouble rejects a value above the maximum (11 > 10)", rejectedValidation(() -> ValidationUtil.optionalDouble(body, "wBig", 1, 0, 10)), "");
        check("optionalDouble rejects a boolean", rejectedValidation(() -> ValidationUtil.optionalDouble(body, "flag", 1, 0, 10)), "");

        check("optionalEnum accepts 'time' (any case) and uses the default when absent",
                ValidationUtil.optionalEnum(body, "metric", model.CostMetric.class, model.CostMetric.DISTANCE) == model.CostMetric.TIME
                        && ValidationUtil.optionalEnum(body, "absent", model.CostMetric.class, model.CostMetric.DISTANCE) == model.CostMetric.DISTANCE, "");
        check("optionalEnum rejects an unknown value and lists the allowed ones", rejectedValidation(() -> ValidationUtil.optionalEnum(body, "metricBad", model.CostMetric.class, model.CostMetric.DISTANCE)), "");
        try {
            ValidationUtil.optionalEnum(body, "metricBad", model.CostMetric.class, model.CostMetric.DISTANCE);
        } catch (ValidationException e) {
            check("The message lists the allowed values", e.getMessage().contains("DISTANCE") && e.getMessage().contains("TIME"), e.getMessage());
        }
        check("optionalEnum rejects a number", rejectedValidation(() -> ValidationUtil.optionalEnum(body, "metricNum", model.CostMetric.class, model.CostMetric.DISTANCE)), "");
        check("requireObject rejects an array and a number", rejectedValidation(() -> ValidationUtil.requireObject(JsonUtil.parse("[1]")))
                && rejectedValidation(() -> ValidationUtil.requireObject(JsonUtil.parse("5"))) && rejectedValidation(() -> ValidationUtil.requireObject(null)), "");
    }
}
