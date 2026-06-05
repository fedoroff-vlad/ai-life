package dev.fedorov.ailife.memory.domain;

/**
 * pgvector's wire format on the JDBC side is the text literal
 * {@code [v1,v2,v3,...]}. The driver accepts it as a String parameter bound
 * with the explicit {@code ::vector} cast in the SQL. We don't pull in a
 * dedicated pgvector-jdbc library yet — one helper here is enough.
 */
public final class Vectors {

    private Vectors() {}

    /** Format a float[] as {@code [v1,v2,...]} using {@link Float#toString}. */
    public static String toLiteral(float[] v) {
        if (v == null || v.length == 0) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder(v.length * 8 + 2).append('[');
        for (int i = 0; i < v.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(Float.toString(v[i]));
        }
        return sb.append(']').toString();
    }
}
