package util;

import java.util.Map;

/**
 * ValidationUtil - checks values that arrive from the browser.
 * NEVER trust the browser: every field is checked here on the server for type, range and allowed values.
 * Every problem is reported as a ValidationException with a clear message (HTTP 400).
 */
public final class ValidationUtil {

    private ValidationUtil() { }

    /** The request body must be a JSON object { ... }. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> requireObject(Object parsed) {
        if (!(parsed instanceof Map)) {
            throw new ValidationException("The request body must be a JSON object such as {\"sourceId\": 1}");
        }
        return (Map<String, Object>) parsed;
    }

    /** A required whole number in the range min..max. Rejects strings, decimals, booleans, null and missing fields. */
    public static int requireInt(Map<String, Object> body, String key, int min, int max) {
        if (!body.containsKey(key) || body.get(key) == null) {
            throw new ValidationException("Field '" + key + "' is required");
        }
        Object v = body.get(key);
        long number;
        if (v instanceof Long) {
            number = (Long) v;
        } else if (v instanceof Double && ((Double) v) == Math.rint((Double) v) && Math.abs((Double) v) < 1e15) {
            number = (long) (double) (Double) v;          // 8.0 is accepted as 8
        } else {
            throw new ValidationException("Field '" + key + "' must be a whole number");
        }
        if (number < min || number > max) {
            throw new ValidationException("Field '" + key + "' must be between " + min + " and " + max);
        }
        return (int) number;
    }

    /** An optional true/false value. Anything that is not exactly true or false is rejected. */
    public static boolean optionalBoolean(Map<String, Object> body, String key, boolean defaultValue) {
        if (!body.containsKey(key) || body.get(key) == null) {
            return defaultValue;
        }
        Object v = body.get(key);
        if (!(v instanceof Boolean)) {
            throw new ValidationException("Field '" + key + "' must be true or false");
        }
        return (Boolean) v;
    }

    /** An optional number in the range min..max. */
    public static double optionalDouble(Map<String, Object> body, String key, double defaultValue, double min, double max) {
        if (!body.containsKey(key) || body.get(key) == null) {
            return defaultValue;
        }
        Object v = body.get(key);
        if (!(v instanceof Number) || v instanceof Boolean) {
            throw new ValidationException("Field '" + key + "' must be a number");
        }
        double d = ((Number) v).doubleValue();
        if (Double.isNaN(d) || d < min || d > max) {
            throw new ValidationException("Field '" + key + "' must be between " + min + " and " + max);
        }
        return d;
    }

    /** An optional text value that must be one of the names of an enum (upper/lower case does not matter). */
    public static <E extends Enum<E>> E optionalEnum(Map<String, Object> body, String key, Class<E> type, E defaultValue) {
        if (!body.containsKey(key) || body.get(key) == null) {
            return defaultValue;
        }
        Object v = body.get(key);
        if (!(v instanceof String)) {
            throw new ValidationException("Field '" + key + "' must be text");
        }
        return parseEnum((String) v, type, key);
    }

    /** Converts text to an enum value, or throws a ValidationException listing the allowed values. */
    public static <E extends Enum<E>> E parseEnum(String text, Class<E> type, String fieldName) {
        for (E constant : type.getEnumConstants()) {
            if (constant.name().equalsIgnoreCase(text.trim())) {
                return constant;
            }
        }
        StringBuilder allowed = new StringBuilder();
        for (E constant : type.getEnumConstants()) {
            if (allowed.length() > 0) allowed.append(", ");
            allowed.append(constant.name());
        }
        throw new ValidationException("Field '" + fieldName + "' must be one of: " + allowed);
    }
}
