package com.nosqldriver.aerospike.sql.query;

import com.aerospike.client.Key;
import com.nosqldriver.util.SneakyThrower;

import java.lang.reflect.Array;
import java.sql.SQLException;
import java.util.stream.IntStream;

import static java.lang.String.format;

public class KeyFactory {
    public static Key createKey(String schema, String table, Object value) {
        Key key;
        if (value instanceof Byte) {
            key = new Key(schema, table, ((Byte) value).intValue());
        } else if (value instanceof Short) {
            key = new Key(schema, table, ((Short) value).intValue());
        } else if (value instanceof Integer) {
            key = new Key(schema, table, (Integer) value);
        } else if (value instanceof Long) {
            key = new Key(schema, table, (Long) value);
        } else if (value instanceof Number) {
            key = new Key(schema, table, ((Number) value).longValue());
        } else if (value instanceof String) {
            key = new Key(schema, table, (String) value);
        } else if (value instanceof byte[]) {
            key = new Key(schema, table, (byte[]) value);
        } else {
            return SneakyThrower.sneakyThrow(new SQLException(format("Filter by %s is not supported right now. Use either number or string", value == null ? null : value.getClass())));
        }
        return key;
    }

    public static Key[] createKeys(String schema, String table, Object value) {
        if (value == null) {
            return SneakyThrower.sneakyThrow(new SQLException("Filter by null value is not supported right now"));
        }
        if (value.getClass().isArray()) {
            return createKeysFromArray(schema, table, value);
        }
        if (value instanceof java.sql.Array) {
            return SneakyThrower.get(() -> createKeysFromArray(schema, table, ((java.sql.Array) value).getArray()));
        }
        return createKeysFromArray(schema, table, new Object[] {value});
    }

    private static Key[] createKeysFromArray(String schema, String table, Object value) {
        int n = Array.getLength(value);
        return IntStream.range(0, n).boxed().map(i -> Array.get(value, i)).map(v -> createKey(schema, table, v)).toArray(Key[]::new);
    }
}
