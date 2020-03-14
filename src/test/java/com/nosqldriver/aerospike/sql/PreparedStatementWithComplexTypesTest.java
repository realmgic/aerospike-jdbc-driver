package com.nosqldriver.aerospike.sql;

import com.aerospike.client.Key;
import com.aerospike.client.Record;
import com.nosqldriver.util.IOUtils;
import com.nosqldriver.util.SneakyThrower;
import com.nosqldriver.util.ThrowingConsumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.NotSerializableException;
import java.io.Serializable;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.NClob;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;

import static com.nosqldriver.aerospike.sql.TestDataUtils.NAMESPACE;
import static com.nosqldriver.aerospike.sql.TestDataUtils.PEOPLE;
import static com.nosqldriver.aerospike.sql.TestDataUtils.client;
import static com.nosqldriver.aerospike.sql.TestDataUtils.deleteAllRecords;
import static com.nosqldriver.aerospike.sql.TestDataUtils.testConn;
import static java.lang.String.format;
import static java.lang.System.currentTimeMillis;
import static java.util.Arrays.asList;
import static java.util.Collections.singleton;
import static java.util.stream.Collectors.toList;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.ParameterizedTest.ARGUMENTS_PLACEHOLDER;

class PreparedStatementWithComplexTypesTest {
    private static final String DATA = "data";

    @BeforeEach
    @AfterEach
    void dropAll() {
        deleteAllRecords(NAMESPACE, PEOPLE);
        deleteAllRecords(NAMESPACE, DATA);
    }

    @Test
    void insertOneRowUsingPreparedStatementWithIntKey() throws SQLException {
        assertOneInsertedRowUsingPreparedStatementWithDifferentKeyType(ps -> ps.setInt(1, 1), new Key("test", "people", 1));
    }

    @Test
    void insertOneRowUsingPreparedStatementWithLongKey() throws SQLException {
        assertOneInsertedRowUsingPreparedStatementWithDifferentKeyType(ps -> ps.setLong(1, 1L), new Key("test", "people", 1L));
    }

    @Test
    void insertOneRowUsingPreparedStatementWithShortKey() throws SQLException {
        assertOneInsertedRowUsingPreparedStatementWithDifferentKeyType(ps -> ps.setShort(1, (short)1), new Key("test", "people", 1));
    }

    @Test
    @Disabled // Double cannot be used in predicates; this test performs query that adds predicate even if it is used on PK. The real fix is to avoid creating predicates when querying PK
    void insertOneRowUsingPreparedStatementWithDoubleKey() throws SQLException {
        assertOneInsertedRowUsingPreparedStatementWithDifferentKeyType(ps -> ps.setDouble(1, 1.0), new Key("test", "people", 1));
    }

    @Test
    void insertOneRowUsingPreparedStatementWithStringKey() throws SQLException {
        assertOneInsertedRowUsingPreparedStatementWithDifferentKeyType(ps -> ps.setString(1, "one"), new Key("test", "people", "one"));
    }

    @Test
    @Disabled // Byte array cannot be used in predicates; this test performs query that adds predicate even if it is used on PK. The real fix is to avoid creating predicates when querying PK
    void insertOneRowUsingPreparedStatementWithByteArrayKey() throws SQLException {
        assertOneInsertedRowUsingPreparedStatementWithDifferentKeyType(ps -> ps.setBytes(1, new byte[] {1, 2, 3}), new Key("test", "people", new byte[] {1, 2, 3}));
    }

    @Test
    void calendar() throws SQLException {
        PreparedStatement insert = testConn.prepareStatement("insert into data (PK, date) values (?, ?)");
        insert.setInt(1, 1);
        Calendar c = Calendar.getInstance();
        final long NANOS_PER_MILLIS = 1000000L; // this is how aerospike date predicate works
        long millis = c.getTimeInMillis();
        long nanos = c.getTimeInMillis() * NANOS_PER_MILLIS;
        insert.setLong(2, nanos);
        assertEquals(1, insert.executeUpdate());

        assertFilteringByDate(format("select * from data where date in (date(%d))", millis), nanos);
        assertFilteringByDate(format("select * from data where date in (calendar(%d))", millis), nanos);
    }

    private void assertFilteringByDate(String sql, long nanos) throws SQLException {
        ResultSet rs = testConn.createStatement().executeQuery(sql);
        assertTrue(rs.next());
        assertEquals(nanos, rs.getLong(1));
        assertFalse(rs.next());
    }

    private void assertOneInsertedRowUsingPreparedStatementWithDifferentKeyType(ThrowingConsumer<PreparedStatement, SQLException> setter, Key key) throws SQLException {
        PreparedStatement insert = testConn.prepareStatement("insert into people (PK, id, first_name, last_name, kids) values (?, ?, ?, ?, ?)");
        setter.accept(insert);

        insert.setInt(2, 1);
        insert.setString(3, "John");
        insert.setString(4, "Lennon");
        insert.setArray(5, testConn.createArrayOf("varchar", new String[] {"Sean", "Julian"}));
        int rowCount = insert.executeUpdate();
        assertEquals(1, rowCount);
        Record record = client.get(null, key);
        assertNotNull(record);
        Map<String, Object> expectedData = new HashMap<>();
        expectedData.put("id", 1L);
        expectedData.put("first_name", "John");
        expectedData.put("last_name", "Lennon");
        expectedData.put("kids", Arrays.asList("Sean", "Julian"));
        assertEquals(expectedData, record.bins);


        PreparedStatement select = testConn.prepareStatement("select id, first_name, last_name, kids from people where PK=?");
        setter.accept(select);
        ResultSet rs = select.executeQuery();
        assertTrue(rs.next());

        assertEquals(1, rs.getInt(1));
        assertEquals(1, rs.getInt("id"));

        assertThrows(SQLException.class, () -> rs.getAsciiStream(1));
        assertThrows(SQLException.class, () -> rs.getAsciiStream("id"));
        assertThrows(SQLException.class, () -> rs.getBinaryStream(1));
        assertThrows(SQLException.class, () -> rs.getBinaryStream("id"));
        assertThrows(SQLException.class, () -> rs.getCharacterStream(1));
        assertThrows(SQLException.class, () -> rs.getCharacterStream("id"));
        assertThrows(SQLException.class, () -> rs.getNCharacterStream(1));
        assertThrows(SQLException.class, () -> rs.getNCharacterStream("id"));

        assertEquals("John", rs.getString(2));
        assertEquals("John", rs.getString("first_name"));
        assertEquals("Lennon", rs.getString(3));
        assertEquals("Lennon", rs.getString("last_name"));

        List<String> expectedKids = asList("Sean", "Julian");
        assertEquals(expectedKids, rs.getObject(4));
        assertEquals(expectedKids, rs.getObject("kids"));

        assertEquals(expectedKids, asList((Object[])rs.getArray(4).getArray()));
        assertEquals(expectedKids, asList((Object[])rs.getArray("kids").getArray()));


        ResultSet arrayRs = rs.getArray(4).getResultSet();
        assertTrue(arrayRs.next());
        assertEquals(1, arrayRs.getInt(1));
        assertEquals("Sean", arrayRs.getString(2));
        assertTrue(arrayRs.next());
        assertEquals(2, arrayRs.getInt(1));
        assertEquals("Julian", arrayRs.getString(2));
        assertFalse(arrayRs.next());


        assertFalse(rs.next());
    }



    @Test
    void insertOneRowUsingPreparedStatementVariousArrayTypes() throws SQLException {
        Key key1 = new Key(NAMESPACE, DATA, 1);
        assertNull(client.get(null, key1));
        PreparedStatement ps = testConn.prepareStatement(
                "insert into data (PK, byte, short, int, long, boolean, string, nstring, blob, clob, nclob, t, ts, d, fval, dval) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
        );

        long now = currentTimeMillis();
        String helloWorld = "hello, world!";
        ps.setInt(1, 1);
        ps.setObject(2, new byte[] {(byte)8});
        ps.setObject(3, new short[] {(short)512});
        ps.setObject(4, new int[] {1024});
        ps.setObject(5, new long[] {now});
        ps.setObject(6, new boolean[] {true});
        ps.setObject(7, new String[] {"hello"});
        ps.setObject(8, new String[] {helloWorld});

        Blob blob = testConn.createBlob();
        blob.setBytes(1, helloWorld.getBytes());
        ps.setObject(9, new Blob[] {blob});

        Clob clob = testConn.createClob();
        clob.setString(1, helloWorld);
        ps.setObject(10, new Clob[] {clob});

        NClob nclob = testConn.createNClob();
        nclob.setString(1, helloWorld);
        ps.setObject(11, new NClob[] {nclob});

        ps.setObject(12, new Time[] {new Time(now)});
        ps.setObject(13, new Timestamp[] {new Timestamp(now)});
        ps.setObject(14, new java.sql.Date[] {new java.sql.Date(now)});
        ps.setObject(15, new float[] {3.14f, 2.7f});
        ps.setObject(16, new double[] {3.1415926, 2.718281828});


        assertEquals(1, ps.executeUpdate());

        Record record1 = client.get(null, key1);
        assertNotNull(record1);
        assertArrayEquals(new byte[]{8}, (byte[])record1.getValue("byte")); // byte array is special case since it is stored as byte array
        // int primitives (short, int, long) are stored as longs in Aerospike
        assertEquals(new ArrayList<>(singleton(512L)), record1.getList("short"));
        assertEquals(new ArrayList<>(singleton(1024L)), record1.getList("int"));
        assertEquals(new ArrayList<>(singleton(now)), record1.getList("long"));
        assertEquals(new ArrayList<>(singleton(true)), record1.getList("boolean"));
        assertEquals(new ArrayList<>(singleton("hello")), record1.getList("string"));
        assertEquals(new ArrayList<>(singleton(helloWorld)), record1.getList("nstring"));

        List<?> actualBlob = record1.getList("blob");
        assertEquals(1, actualBlob.size());
        assertArrayEquals(helloWorld.getBytes(), (byte[])actualBlob.get(0));


        assertEquals(new ArrayList<>(singleton(helloWorld)), record1.getList("clob"));
        assertEquals(new ArrayList<>(singleton(helloWorld)), record1.getList("nclob"));
        assertEquals(new ArrayList<>(singleton(new Time(now))), record1.getList("t"));
        assertEquals(new ArrayList<>(singleton(new Timestamp(now))), record1.getList("ts"));
        assertEquals(new ArrayList<>(singleton(new java.sql.Date(now))), record1.getList("d"));

        assertEquals(new ArrayList<>(asList(3.14f, 2.7f)), record1.getList("fval").stream().map(v -> ((Number)v).floatValue()).collect(toList()));
        assertEquals(new ArrayList<>(asList(3.1415926, 2.718281828)), record1.getList("dval"));


        PreparedStatement query = testConn.prepareStatement("select byte, short, int, long, boolean, string, nstring, blob, clob, nclob, t, ts, d, fval, dval from data where PK=?");
        query.setInt(1, 1);

        ResultSet rs = query.executeQuery();
        ResultSetMetaData md = rs.getMetaData();

        int n = md.getColumnCount();

        for (int i = 1; i <= n; i++) {
            assertEquals(DATA, md.getTableName(i));
            assertEquals(NAMESPACE, md.getCatalogName(i));
        }

        // All ints except short are  stored as longs
        assertEquals(Types.BLOB, md.getColumnType(1)); // byte array is converted to blob
        for (int i = 2; i <= n; i++) {
            assertEquals(Types.ARRAY, md.getColumnType(i), format("Wrong type of column %d: expected ARRAY(%d) but was %d", i, Types.ARRAY, md.getColumnType(i)));
        }

        assertTrue(rs.first());


        byte[] expectedBytes = {8};
        assertArrayEquals(expectedBytes, rs.getBytes(1));
        assertArrayEquals(expectedBytes, rs.getBytes("byte"));
        assertArrayEquals(expectedBytes, getBytes(rs.getBlob(1)));
        assertArrayEquals(expectedBytes, getBytes(rs.getBlob("byte")));


        Long[] expectedShorts = {512L};
        assertArrayEquals(expectedShorts, (Object[])rs.getArray(2).getArray());
        assertArrayEquals(expectedShorts, (Object[])rs.getArray("short").getArray());

        Long[] expectedInts = {1024L};
        assertArrayEquals(expectedInts, (Object[])rs.getArray(3).getArray());
        assertArrayEquals(expectedInts, (Object[])rs.getArray("int").getArray());

        Long[] expectedLongs = {now};
        assertArrayEquals(expectedLongs, (Object[])rs.getArray(4).getArray());
        assertArrayEquals(expectedLongs, (Object[])rs.getArray("long").getArray());


        Boolean[] expectedBooleans = {true};
        assertArrayEquals(expectedBooleans, (Object[])rs.getArray(5).getArray());
        assertArrayEquals(expectedBooleans, (Object[])rs.getArray("boolean").getArray());

        String[] expectedStrings = {"hello"};
        assertArrayEquals(expectedStrings, (Object[])rs.getArray(6).getArray());
        assertArrayEquals(expectedStrings, (Object[])rs.getArray("string").getArray());


        String[] expectedNStrings = {helloWorld};
        assertArrayEquals(expectedNStrings, (Object[])rs.getArray(7).getArray());
        assertArrayEquals(expectedNStrings, (Object[])rs.getArray("nstring").getArray());


        byte[][] expectedBlobs = {helloWorld.getBytes()};

        assertArrayEquals(expectedBlobs, Arrays.stream(((Object[]) rs.getArray(8).getArray())).map(b -> getBytes((Blob)b)).toArray());
        assertArrayEquals(expectedBlobs, Arrays.stream(((Object[]) rs.getArray("blob").getArray())).map(b -> getBytes((Blob)b)).toArray());

        // no way to distinguish between clob and string when rading
        assertArrayEquals(expectedNStrings, Arrays.stream(((Object[]) rs.getArray(9).getArray())).toArray());
        assertArrayEquals(expectedNStrings, Arrays.stream(((Object[]) rs.getArray("clob").getArray())).toArray());

        assertArrayEquals(expectedNStrings, Arrays.stream(((Object[]) rs.getArray(10).getArray())).toArray());
        assertArrayEquals(expectedNStrings, Arrays.stream(((Object[]) rs.getArray("nclob").getArray())).toArray());


        Time[] expectedTimes = {new Time(now)};
        assertArrayEquals(expectedTimes, (Object[])rs.getArray(11).getArray());
        assertArrayEquals(expectedTimes, (Object[])rs.getArray("t").getArray());

        Time[] expectedTimestamps = {new Time(now)};
        assertArrayEquals(expectedTimestamps, (Object[])rs.getArray(12).getArray());
        assertArrayEquals(expectedTimestamps, (Object[])rs.getArray("ts").getArray());

        java.sql.Date[] expectedDates = {new java.sql.Date(now)};
        assertArrayEquals(expectedDates, (Object[])rs.getArray(12).getArray());
        assertArrayEquals(expectedDates, (Object[])rs.getArray("d").getArray());

        Object[] expectedFloats = new Object[] {3.14f, 2.7f};
        assertArrayEquals(expectedFloats, Arrays.stream((Object[]) rs.getArray(14).getArray()).map(v -> ((Number)v).floatValue()).toArray());
        assertArrayEquals(expectedFloats, Arrays.stream((Object[]) rs.getArray("fval").getArray()).map(v -> ((Number)v).floatValue()).toArray());

        Object[] expectedDoubles = new Object[] {3.1415926, 2.718281828};
        assertArrayEquals(expectedDoubles, (Object[])rs.getArray(15).getArray());
        assertArrayEquals(expectedDoubles, (Object[])rs.getArray("dval").getArray());

        assertFalse(rs.next());
    }


    @ParameterizedTest(name = ARGUMENTS_PLACEHOLDER)
    @ValueSource(strings = {
            "select id, bytes, blob, clob from data",
            "select id, bytes, blob, clob from data as d left join other as o on d.id=o.id"
    })
    void insertOneRowUsingPreparedStatementVariousCompositeTypes(String sql) throws SQLException, IOException {
        Key key1 = new Key(NAMESPACE, DATA, 1);
        assertNull(client.get(null, key1));
        PreparedStatement ps = testConn.prepareStatement(
                "insert into data (PK, id, bytes, blob, clob) values (?, ?, ?, ?, ?)"
        );

        String helloWorld = "hello, world!";
        ps.setInt(1, 1);
        ps.setInt(2, 1);
        ps.setBytes(3, new byte[] {(byte)8});


        Blob blob = testConn.createBlob();
        blob.setBytes(1, helloWorld.getBytes());
        ps.setBlob(4, blob);

        Clob clob = testConn.createClob();
        clob.setString(1, helloWorld);
        ps.setClob(5, clob);

        assertEquals(1, ps.executeUpdate());


        ResultSet rs  = testConn.createStatement().executeQuery(sql);
        assertTrue(rs.next());
        assertArrayEquals(new byte[] {(byte)8}, rs.getBytes(2));
        assertArrayEquals(new byte[] {(byte)8}, IOUtils.toByteArray(rs.getBinaryStream(2)));
        assertArrayEquals(new byte[] {(byte)8}, (byte[])rs.getObject(2));
        assertArrayEquals(new byte[] {(byte)8}, IOUtils.toByteArray(rs.getBlob(2).getBinaryStream()));

        assertEquals(blob, rs.getBlob(3));
        assertArrayEquals(helloWorld.getBytes(), rs.getBytes(3));

        assertEquals(clob, rs.getClob(4));

        assertEquals(helloWorld, new String(IOUtils.toByteArray(rs.getAsciiStream(4))));
        assertEquals(helloWorld, new String(IOUtils.toByteArray(rs.getUnicodeStream(4))));
        assertEquals(helloWorld, IOUtils.toString(rs.getNCharacterStream(4)));
        assertEquals(helloWorld, IOUtils.toString(rs.getCharacterStream(4)));
    }


    @Test
    void insertOneRowWithStringKey() throws SQLException {
        insertOneRowWithTypedKey("one", new Key("test", "people", "one"));
    }


    @Test
    void insertOneRowWithByteArrayKey() throws SQLException {
        insertOneRowWithTypedKey("one".getBytes(), new Key("test", "people", "one".getBytes()));
    }

    @Test
    void insertOneRowWithKeyOfUnsupportedType() throws SQLException {
        PreparedStatement insert = testConn.prepareStatement("insert into people (PK, id, first_name, last_name) values (?, ?, ?, ?, ?)");
        insert.setObject(1, new ArrayList<>());
        insert.setInt(2, 1);
        insert.setString(3, "John");
        insert.setString(4, "Lennon");
        assertThrows(SQLException.class, insert::executeUpdate);
    }


    @Test
    void insertEmptyMap() throws SQLException {
        Map<?,?> map = Collections.emptyMap();
        assertEquals(map, insertAndSelectMap(map));
    }

    @Test
    void insertStringMap() throws SQLException {
        Map<String, String> map = new TreeMap<>();
        map.put("one", "first");
        map.put("two", "second");
        assertEquals(map, insertAndSelectMap(map));
    }

    @Test
    void insertLongStringMap() throws SQLException {
        Map<Long, String> map = new HashMap<>();
        map.put(1L, "first");
        map.put(2L, "second");
        assertEquals(map, insertAndSelectMap(map));
    }

    @Test
    void insertStringLongMap() throws SQLException {
        Map<String, Long> map = new HashMap<>();
        map.put("first", 1L);
        map.put("second", 2L);
        assertEquals(map, insertAndSelectMap(map));
    }

    @Test
    void insertComplexMap() throws SQLException {
        Map<String, Object> map = new HashMap<>();
        map.put("long", 12345L);
        map.put("double", 3.14);
        map.put("list", asList("abc", "xyz"));
        map.put("array", new String[] {"abc", "xyz"});

        Map<String, Object> outMap = insertAndSelectMap(map);
        assertEquals(map.size(), outMap.size());

        map.keySet().forEach(k -> {
            Object v = map.get(k);
            if (v != null && v.getClass().isArray()) {
                assertArrayEquals((Object[])v, (Object[])outMap.get(k));
            } else {
                assertEquals(v, outMap.get(k));
            }
        });
    }

    @Test
    void setNotSerializableObject() throws SQLException {
        PreparedStatement insert = testConn.prepareStatement("insert into data (PK, data) values (?, ?)");
        insert.setObject(1, 1);
        insert.setObject(2, new MyNotSerializableClass(123, "text"));
        SQLException sqlException = assertThrows(SQLException.class, insert::execute);
        NotSerializableException nse = null;
        for(Throwable t = sqlException; t != null && t != t.getCause(); t = t.getCause()) {
            if (t instanceof NotSerializableException) {
                nse = (NotSerializableException)t;
                break;
            }
        }
        assertNotNull(nse);
    }

    @Test
    void setSerializableObject() throws SQLException {
        PreparedStatement insert = testConn.prepareStatement("insert into data (PK, data) values (?, ?)");
        insert.setObject(1, 1);
        MySerializableClass obj1 = new MySerializableClass(123, "something");
        insert.setObject(2, obj1);
        assertTrue(insert.execute());

        PreparedStatement select = testConn.prepareStatement("select * from data where PK=?");
        select.setObject(1, 1);
        ResultSet rs = select.executeQuery();
        ResultSetMetaData md = rs.getMetaData();
        assertEquals(1, md.getColumnCount());
        assertEquals("data", md.getColumnName(1));

        assertTrue(rs.next());

        MySerializableClass obj2 = (MySerializableClass)rs.getObject(1);
        assertNotNull(obj2);
        assertNotSame(obj1, obj2);
        assertEquals(obj1.number, obj2.number);
        assertEquals(obj1.text, obj2.text);

        assertFalse(rs.next());
    }

    @Test
    @Disabled
    void getFieldsOfMap() throws SQLException {
        Map<String, Object> map = new TreeMap<>();
        map.put("number", 123456);
        String text = "my number is the best one";
        map.put("text", text);
        PreparedStatement insert = testConn.prepareStatement("insert into data (PK, map) values (?, ?)");
        insert.setInt(1, 1);
        insert.setObject(2, map);
        assertEquals(1, insert.executeUpdate());
        ResultSet rs = testConn.createStatement().executeQuery("select map[number], map[text] from (select map from data)");

        ResultSetMetaData md = rs.getMetaData();
        assertEquals(2, md.getColumnCount());
        assertEquals("map[number]", md.getColumnName(1));
        assertEquals("map[text]", md.getColumnName(2));


//        assertTrue(rs.next());
//        assertEquals(123456, rs.getInt(1));
//        assertEquals(123456, rs.getInt("map[number]"));
//        assertEquals(text, rs.getString(2));
//        assertEquals(text, rs.getString("map[text]"));
//
//        assertFalse(rs.next());
    }



    @ParameterizedTest(name = ARGUMENTS_PLACEHOLDER)
    @ValueSource(strings = {
            "select struct[number], struct[text] from (select struct from data)",
            "select struct[number], struct[text] from (select struct from data where PK=1)",
            "select struct[number], struct[text] from (select struct from data where 1=1)",
            "select struct[number], struct[text] from (select struct from data) where struct[number]=123",
    })
    void getFieldsSerializableClass(String query) throws SQLException {
        String text = "my number is the best one";
        int n = 123;
        insertOneRowCustomClass(text, n);
        ResultSet rs = testConn.createStatement().executeQuery(query);

        ResultSetMetaData md = rs.getMetaData();
        assertEquals(2, md.getColumnCount());
        assertEquals("struct[number]", md.getColumnName(1));
        assertEquals("struct[text]", md.getColumnName(2));
        assertEquals(Types.INTEGER, md.getColumnType(1));
        assertEquals(Types.VARCHAR, md.getColumnType(2));


        assertTrue(rs.next());
        assertEquals(n, rs.getInt(1));
        assertEquals(n, rs.getInt("struct[number]"));
        assertEquals(text, rs.getString(2));
        assertEquals(text, rs.getString("struct[text]"));

        assertFalse(rs.next());
    }

    @ParameterizedTest(name = ARGUMENTS_PLACEHOLDER)
    @ValueSource(strings = {
            "select struct[number], struct[text] from (select struct from data where PK=0)",
            "select struct[number], struct[text] from (select struct from data where 1=2)",
            "select struct[number], struct[text] from (select struct from data) where struct[number]=321",
    })
    void getFieldsSerializableClassFalseFilter(String query) throws SQLException {
        insertOneRowCustomClass("my number is the best one", 123);
        ResultSet rs = testConn.createStatement().executeQuery(query);

        ResultSetMetaData md = rs.getMetaData();
        assertEquals(2, md.getColumnCount());
        assertEquals("struct[number]", md.getColumnName(1));
        assertEquals("struct[text]", md.getColumnName(2));
        assertEquals(Types.INTEGER, md.getColumnType(1));
        assertEquals(Types.VARCHAR, md.getColumnType(2));

        assertFalse(rs.next());
    }

    @ParameterizedTest(name = ARGUMENTS_PLACEHOLDER)
    @ValueSource(strings = {
            "select struct[nothing] from (select struct from data)",
    })
    void getNotExistingFieldsInSelectSerializableClass(String query) throws SQLException {
        insertOneRowCustomClass("my number is the best one", 123);

        ResultSet rs = testConn.createStatement().executeQuery(query);

        ResultSetMetaData md = rs.getMetaData();
        assertEquals(1, md.getColumnCount());
        assertTrue(rs.next());

        assertThrows(SQLException.class, () -> rs.getObject(1));
    }

    @ParameterizedTest(name = ARGUMENTS_PLACEHOLDER)
    @ValueSource(strings = {
            "select struct[number] from (select struct from data) where struct[nothing]=321",
    })
    void getNotExistingFieldsInWhereSerializableClass(String query) throws SQLException {
        insertOneRowCustomClass("my number is the best one", 123);
        ResultSet rs = testConn.createStatement().executeQuery(query);

        ResultSetMetaData md = rs.getMetaData();
        assertEquals(1, md.getColumnCount());
        assertThrows(SQLException.class, rs::next);
    }

    @Test
    void setNotSerializableObjectWithCustomSerialization() throws SQLException, IOException {
        PreparedStatement insert = testConn.prepareStatement("insert into data (PK, blob) values (?, ?)");
        insert.setObject(1, 1);
        MyNotSerializableClass obj1 = new MyNotSerializableClass(123, "something");
        insert.setObject(2, MyNotSerializableClass.serialize(obj1));
        assertTrue(insert.execute());

        Connection conn = DriverManager.getConnection("jdbc:aerospike:localhost/test?custom.function.deserialize=com.nosqldriver.aerospike.sql.PreparedStatementWithComplexTypesTest$MyCustomDeserializer");
        PreparedStatement select = conn.prepareStatement("select deserialize(blob) as object from data where PK=?");
        select.setObject(1, 1);
        ResultSet rs = select.executeQuery();
        ResultSetMetaData md = rs.getMetaData();
        assertEquals(1, md.getColumnCount());
        assertEquals("deserialize(blob)", md.getColumnName(1));
        assertEquals("object", md.getColumnLabel(1));

        assertTrue(rs.next());

        MyNotSerializableClass obj2 = (MyNotSerializableClass)rs.getObject(1);
        assertNotNull(obj2);
        assertNotSame(obj1, obj2);
        assertEquals(obj1.number, obj2.number);
        assertEquals(obj1.text, obj2.text);

        assertFalse(rs.next());
    }



    @ParameterizedTest(name = ARGUMENTS_PLACEHOLDER)
    @ValueSource(strings = {
            "select custom[number], custom[text] from (select deserialize(blob) as custom from data)",
            "select custom[number], custom[text] from (select deserialize(blob) as custom from data) where custom[number]=321",
            "select custom[number], custom[text] from (select deserialize(blob) as custom from data) where custom[text]='my text'",
    })
    void writeAndReadObjectUsingCustomSerialization(String query) throws SQLException, IOException {
        int n = 321;
        String text = "my text";
        MyNotSerializableClass obj = new MyNotSerializableClass(n, text);

        Connection conn = DriverManager.getConnection("jdbc:aerospike:localhost/test?custom.function.deserialize=com.nosqldriver.aerospike.sql.PreparedStatementWithComplexTypesTest$MyCustomDeserializer");
        PreparedStatement insert = conn.prepareStatement("insert into data (PK, blob) values (?, ?)");
        insert.setInt(1, 1);
        insert.setBytes(2, MyNotSerializableClass.serialize(obj));
        assertEquals(1, insert.executeUpdate());

        ResultSet rs = conn.createStatement().executeQuery(query);
        ResultSetMetaData md = rs.getMetaData();
        assertEquals(2, md.getColumnCount());
        assertEquals("custom[number]", md.getColumnName(1));
        assertEquals("custom[text]", md.getColumnName(2));
        assertEquals(Types.INTEGER, md.getColumnType(1));
        assertEquals(Types.VARCHAR, md.getColumnType(2));
        assertTrue(rs.next());

        assertEquals(n, rs.getInt(1));
        assertEquals(n, rs.getInt("custom[number]"));
        assertEquals(text, rs.getString(2));
        assertEquals(text, rs.getString("custom[text]"));

        assertFalse(rs.next());

    }

    @ParameterizedTest(name = ARGUMENTS_PLACEHOLDER)
    @ValueSource(strings = {
            "select custom[number], custom[text] from (select deserialize(blob) as custom from data) where custom[number]=567",
            "select custom[number], custom[text] from (select deserialize(blob) as custom from data) where custom[text]='other text'",
    })
    void writeAndReadObjectUsingCustomSerializationWithFalseFilter(String query) throws SQLException, IOException {
        int n = 321;
        String text = "my text";
        MyNotSerializableClass obj = new MyNotSerializableClass(n, text);

        Connection conn = DriverManager.getConnection("jdbc:aerospike:localhost/test?custom.function.deserialize=com.nosqldriver.aerospike.sql.PreparedStatementWithComplexTypesTest$MyCustomDeserializer");
        PreparedStatement insert = conn.prepareStatement("insert into data (PK, blob) values (?, ?)");
        insert.setInt(1, 1);
        insert.setBytes(2, MyNotSerializableClass.serialize(obj));
        assertEquals(1, insert.executeUpdate());

        ResultSet rs = conn.createStatement().executeQuery(query);
        ResultSetMetaData md = rs.getMetaData();
        assertEquals(2, md.getColumnCount());
        assertEquals("custom[number]", md.getColumnName(1));
        assertEquals("custom[text]", md.getColumnName(2));
        assertEquals(Types.INTEGER, md.getColumnType(1));
        assertEquals(Types.VARCHAR, md.getColumnType(2));
        assertFalse(rs.next());
    }



    @Test
    void notExistingCustomFunction() {
        assertThrows(IllegalArgumentException.class, () -> DriverManager.getConnection("jdbc:aerospike:localhost?custom.function.deserialize=DoesNotExist"));
    }

    @Test
    void wrongCustomFunction() {
        assertThrows(IllegalArgumentException.class, () -> DriverManager.getConnection("jdbc:aerospike:localhost?custom.function.deserialize=" + getClass().getName()));
    }


    private void insertOneRowCustomClass(String text, int n) throws SQLException {
        MySerializableClass obj = new MySerializableClass(n, text);

        PreparedStatement insert = testConn.prepareStatement("insert into data (PK, struct) values (?, ?)");
        insert.setInt(1, 1);
        insert.setObject(2, obj);
        assertEquals(1, insert.executeUpdate());
    }

    private <T> void insertOneRowWithTypedKey(T id, Key key) throws SQLException {
        PreparedStatement insert = testConn.prepareStatement("insert into people (PK, id, first_name, last_name) values (?, ?, ?, ?, ?)");
        insert.setObject(1, id);
        insert.setInt(2, 1);
        insert.setString(3, "John");
        insert.setString(4, "Lennon");
        int rowCount = insert.executeUpdate();
        assertEquals(1, rowCount);
        Record record = client.get(null, key);
        assertNotNull(record);
        Map<String, Object> expectedData = new HashMap<>();
        expectedData.put("id", 1L);
        expectedData.put("first_name", "John");
        expectedData.put("last_name", "Lennon");
        assertEquals(expectedData, record.bins);
    }

    private byte[] getBytes(Blob blob) {
        return SneakyThrower.get(() -> blob.getBytes(1, (int)blob.length()));
    }


    private <K, V> Map<K, V> insertAndSelectMap(Map<K, V> inMap) throws SQLException {
        Key key1 = new Key(NAMESPACE, DATA, 1);
        assertNull(client.get(null, key1));

        PreparedStatement insert = testConn.prepareStatement("insert into data (PK, map) values (?, ?)");
        insert.setInt(1, 1);

        insert.setObject(2, inMap);

        assertEquals(1, insert.executeUpdate());

        ResultSet rs = testConn.createStatement().executeQuery("select * from data");

        assertTrue(rs.next());

        @SuppressWarnings("unchecked")
        Map<K, V> outMap = (Map<K, V>)rs.getObject(1);
        assertFalse(rs.next());
        return outMap;
    }

    public static class MyNotSerializableClass {
        private final int number;
        private final String text;

        public MyNotSerializableClass(int number, String text) {
            this.number = number;
            this.text = text;
        }

        public int getNumber() {
            return number;
        }

        public String getText() {
            return text;
        }

        public static byte[] serialize(MyNotSerializableClass obj) throws IOException {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(baos);
            dos.writeInt(obj.number);
            dos.writeUTF(obj.text);
            dos.flush();
            dos.close();
            return baos.toByteArray();
        }

        public static MyNotSerializableClass deserialize(byte[] bytes) throws IOException {
            DataInputStream dis = new DataInputStream(new ByteArrayInputStream(bytes));
            return new MyNotSerializableClass(dis.readInt(), dis.readUTF());
        }
    }


    public static class MyCustomDeserializer implements Function<byte[], MyNotSerializableClass> {
        @Override
        public MyNotSerializableClass apply(byte[] bytes) {
            DataInputStream dis = new DataInputStream(new ByteArrayInputStream(bytes));
            try {
                return new MyNotSerializableClass(dis.readInt(), dis.readUTF());
            } catch (IOException e) {
                throw new IllegalArgumentException(e);
            }
        }
    }


    public static class MySerializableClass implements Serializable {
        private final int number;
        private final String text;

        public MySerializableClass(int number, String text) {
            this.number = number;
            this.text = text;
        }

        public int getNumber() {
            return number;
        }

        public String getText() {
            return text;
        }
    }
}
