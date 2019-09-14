package com.nosqldriver.aerospike.sql;

import com.aerospike.client.Key;
import com.aerospike.client.Record;
import com.nosqldriver.sql.ByteArrayBlob;
import com.nosqldriver.sql.StringClob;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.NClob;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.HashMap;
import java.util.Map;

import static com.nosqldriver.aerospike.sql.TestDataUtils.NAMESPACE;
import static com.nosqldriver.aerospike.sql.TestDataUtils.PEOPLE;
import static com.nosqldriver.aerospike.sql.TestDataUtils.client;
import static com.nosqldriver.aerospike.sql.TestDataUtils.deleteAllRecords;
import static com.nosqldriver.aerospike.sql.TestDataUtils.testConn;
import static java.lang.System.currentTimeMillis;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests of INSERT SQL statement
 */
class InsertTest {
    private static final String DATA = "data";

    @BeforeEach
    @AfterEach
    void dropAll() {
        deleteAllRecords(NAMESPACE, PEOPLE);
        deleteAllRecords(NAMESPACE, DATA);
    }


    @Test
    void insertOneRow() throws SQLException {
        insert("insert into people (PK, id, first_name, last_name, year_of_birth, kids_count) values (1, 1, 'John', 'Lennon', 1940, 2)", 1);
        Record record = client.get(null, new Key("test", "people", 1));
        assertNotNull(record);
        Map<String, Object> expectedData = new HashMap<>();
        expectedData.put("id", 1L);
        expectedData.put("first_name", "John");
        expectedData.put("last_name", "Lennon");
        expectedData.put("year_of_birth", 1940L);
        expectedData.put("kids_count", 2L);
        assertEquals(expectedData, record.bins);
    }

    @Test
    void insertDuplicateRow() throws SQLException {
        insert("insert into people (PK, id, first_name, last_name, year_of_birth, kids_count) values (1, 1, 'John', 'Lennon', 1940, 2)", 1);
        Record record = client.get(null, new Key("test", "people", 1));
        assertNotNull(record);
        Map<String, Object> expectedData = new HashMap<>();
        expectedData.put("id", 1L);
        expectedData.put("first_name", "John");
        expectedData.put("last_name", "Lennon");
        expectedData.put("year_of_birth", 1940L);
        expectedData.put("kids_count", 2L);
        assertEquals(expectedData, record.bins);

        SQLException e = assertThrows(SQLException.class, () ->
            insert("insert into people (PK, id, first_name, last_name, year_of_birth, kids_count) values (1, 1, 'John', 'Lennon', 1940, 2)", 1)
        );
        assertTrue(e.getMessage().contains("Duplicate entries"));
    }

    @Test
    void insertIgnoreDuplicateRow() throws SQLException {
        insert("insert into people (PK, id, first_name, last_name, year_of_birth, kids_count) values (1, 1, 'John', 'Lennon', 1940, 2)", 1);
        Record record = client.get(null, new Key("test", "people", 1));
        assertNotNull(record);
        assertEquals("John", record.getString("first_name"));

        insert("insert ignore into people (PK, id, first_name, last_name, year_of_birth, kids_count) values (1, 1, 'John', 'Lennon', 1940, 2)", 1);
        assertNotNull(record);
        assertEquals("John", record.getString("first_name"));
    }



    @Test
    void insertSeveralRows() throws SQLException {
        insert("insert into people (PK, id, first_name, last_name, year_of_birth, kids_count) values (1, 1, 'John', 'Lennon', 1940, 2), (2, 2, 'Paul', 'McCartney', 1942, 5)", 2);
        assertEquals("John", client.get(null, new Key("test", "people", 1)).getString("first_name"));
        assertEquals("Paul", client.get(null, new Key("test", "people", 2)).getString("first_name"));
    }

    @Test
    void insertSeveralRowsUsingExecute() throws SQLException {
        assertTrue(testConn.createStatement().execute("insert into people (PK, id, first_name, last_name, year_of_birth, kids_count) values (1, 1, 'John', 'Lennon', 1940, 2), (2, 2, 'Paul', 'McCartney', 1942, 5)"));
        assertEquals("John", client.get(null, new Key("test", "people", 1)).getString("first_name"));
        assertEquals("Paul", client.get(null, new Key("test", "people", 2)).getString("first_name"));
    }



    @Test
    void insertOneRowUsingPreparedStatement() throws SQLException {
        PreparedStatement ps = testConn.prepareStatement("insert into people (PK, id, first_name, last_name, year_of_birth, kids_count) values (?, ?, ?, ?, ?, ?)");
        ps.setInt(1, 1);
        ps.setInt(2, 1);
        ps.setString(3, "John");
        ps.setString(4, "Lennon");
        ps.setInt(5, 1940);
        ps.setInt(6, 2);
        int rowCount = ps.executeUpdate();
        assertEquals(1, rowCount);
        Record record = client.get(null, new Key("test", "people", 1));
        assertNotNull(record);
        Map<String, Object> expectedData = new HashMap<>();
        expectedData.put("id", 1L);
        expectedData.put("first_name", "John");
        expectedData.put("last_name", "Lennon");
        expectedData.put("year_of_birth", 1940L);
        expectedData.put("kids_count", 2L);
        assertEquals(expectedData, record.bins);
    }

    @Test
    void insertOneRowUsingPreparedStatementVariousTypes() throws SQLException {
        Key key1 = new Key(NAMESPACE, DATA, 1);
        assertNull(client.get(null, key1));
        PreparedStatement ps = testConn.prepareStatement(
                "insert into data (PK, byte, short, int, long, boolean, float_number, double_number, bigdecimal, string, nstring, blob, clob, nclob, t, ts, d) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
        );

        long now = currentTimeMillis();
        String helloWorld = "hello, world!";
        ps.setInt(1, 1);
        ps.setByte(2, (byte)8);
        ps.setShort(3, (short)512);
        ps.setInt(4, 1024);
        ps.setLong(5, now);
        ps.setBoolean(6, true);
        ps.setFloat(7, 3.14f);
        ps.setDouble(8, 2.718281828);
        ps.setBigDecimal(9, BigDecimal.valueOf(Math.PI));

        ps.setString(10, "hello");
        ps.setNString(11, helloWorld);

        Blob blob = testConn.createBlob();
        blob.setBytes(1, helloWorld.getBytes());
        ps.setBlob(12, blob);

        Clob clob = testConn.createClob();
        clob.setString(1, helloWorld);
        ps.setClob(13, clob);

        NClob nclob = testConn.createNClob();
        nclob.setString(1, helloWorld);
        ps.setNClob(14, nclob);

        ps.setTime(15, new Time(now));
        ps.setTimestamp(16, new Timestamp(now));
        ps.setDate(17, new java.sql.Date(now));


        assertEquals(1, ps.executeUpdate());

        Record record1 = client.get(null, key1);
        assertNotNull(record1);
        assertEquals(8, record1.getByte("byte"));
        assertEquals((short)512, record1.getValue("short")); // work around on Aerospike client bug: getShort() fails on casting
        assertEquals(1024, record1.getInt("int"));
        assertEquals(now, record1.getLong("long"));
        assertTrue(record1.getBoolean("boolean"));
        assertEquals(3.14f, record1.getFloat("float_number"));
        assertEquals(2.718281828, record1.getDouble("double_number"));
        assertEquals(Math.PI, record1.getDouble("bigdecimal"));
        assertEquals("hello", record1.getString("string"));
        assertEquals(helloWorld, record1.getString("nstring"));
        assertArrayEquals(helloWorld.getBytes(), (byte[])record1.getValue("blob"));
        assertEquals(helloWorld, record1.getString("clob"));
        assertEquals(helloWorld, record1.getString("nclob"));
        assertEquals(new Time(now), record1.getValue("t"));
        assertEquals(new Timestamp(now), record1.getValue("ts"));
        assertEquals(new java.sql.Date(now), record1.getValue("d"));


        PreparedStatement query = testConn.prepareStatement("select byte, short, int, long, boolean, float_number, double_number, bigdecimal, string, nstring, blob, clob, nclob, t, ts, d from data where PK=?");
        query.setInt(1, 1);

        ResultSet rs = query.executeQuery();
        ResultSetMetaData md = rs.getMetaData();

        int n = md.getColumnCount();

        for (int i = 1; i <= n; i++) {
            assertEquals(DATA, md.getTableName(i));
            assertEquals(NAMESPACE, md.getCatalogName(i));
        }

        // All ints except short are  stored as longs
        assertEquals(Types.BIGINT, md.getColumnType(1));
        assertEquals(Types.SMALLINT, md.getColumnType(2));
        assertEquals(Types.BIGINT, md.getColumnType(3));
        assertEquals(Types.BIGINT, md.getColumnType(4));
        assertEquals(Types.BIGINT, md.getColumnType(5));

        assertEquals(Types.DOUBLE, md.getColumnType(6));
        assertEquals(Types.DOUBLE, md.getColumnType(7));
        assertEquals(Types.DOUBLE, md.getColumnType(8));

        assertEquals(Types.VARCHAR, md.getColumnType(9));
        assertEquals(Types.VARCHAR, md.getColumnType(10));
        assertEquals(Types.BLOB, md.getColumnType(11));
        // There is not way to distinguish between clob, nclob and string. All are VARCHARs
        assertEquals(Types.VARCHAR, md.getColumnType(12));
        assertEquals(Types.VARCHAR, md.getColumnType(13));
        assertEquals(Types.TIME, md.getColumnType(14));
        assertEquals(Types.TIMESTAMP, md.getColumnType(15));
        assertEquals(Types.DATE, md.getColumnType(16));

        assertTrue(rs.first());

        assertEquals(8, rs.getByte(1));
        assertEquals(8, rs.getByte("byte"));

        assertEquals((short)512, rs.getShort(2));
        assertEquals((short)512, rs.getShort("short"));

        assertEquals(1024, rs.getInt(3));
        assertEquals(1024, rs.getInt("int"));

        assertEquals(now, rs.getLong(4));
        assertEquals(now, rs.getLong("long"));

        assertTrue(rs.getBoolean(5));
        assertTrue(rs.getBoolean("boolean"));

        assertEquals(3.14f, rs.getFloat(6));
        assertEquals(3.14f, rs.getFloat("float_number"));

        assertEquals(2.718281828, rs.getDouble(7));
        assertEquals(2.718281828, rs.getDouble("double_number"));

        assertEquals(Math.PI, rs.getBigDecimal(8).doubleValue());
        assertEquals(Math.PI, rs.getBigDecimal("bigdecimal").doubleValue());

        assertEquals("hello", rs.getString(9));
        assertEquals("hello", rs.getString("string"));

        assertEquals(helloWorld, rs.getNString(10));
        assertEquals(helloWorld, rs.getNString("nstring"));

        assertArrayEquals(helloWorld.getBytes(), getBytes(rs.getBlob(11)));
        assertArrayEquals(helloWorld.getBytes(), getBytes(rs.getBlob("blob")));

        assertEquals(helloWorld, getString(rs.getClob(12)));
        assertEquals(helloWorld, getString(rs.getClob("clob")));

        assertEquals(helloWorld, getString(rs.getClob(13)));
        assertEquals(helloWorld, getString(rs.getClob("nclob")));

        assertEquals(new Time(now), rs.getTime(14));
        assertEquals(new Time(now), rs.getTime("t"));

        assertEquals(new Timestamp(now), rs.getTimestamp(15));
        assertEquals(new Timestamp(now), rs.getTimestamp("ts"));

        assertEquals(new java.sql.Date(now), rs.getDate(16));
        assertEquals(new java.sql.Date(now), rs.getDate("d"));

        assertFalse(rs.next());
    }


    @Test
    void insertBlobs() throws SQLException {
        Key key1 = new Key(NAMESPACE, DATA, 1);
        assertNull(client.get(null, key1));
        PreparedStatement ps = testConn.prepareStatement("insert into data (PK, blob, input_stream, limited_is) values (?, ?, ?, ?)");

        String text = "hello, blob!";
        byte[] bytes = text.getBytes();
        Blob blob = new ByteArrayBlob();
        blob.setBytes(1, text.getBytes());


        ps.setInt(1, 1);
        ps.setBlob(2, blob);
        ps.setBlob(3, new ByteArrayInputStream(bytes));
        ps.setBlob(4, new ByteArrayInputStream(bytes), bytes.length);

        assertEquals(1, ps.executeUpdate());

        Record record1 = client.get(null, key1);
        assertNotNull(record1);
        assertArrayEquals(bytes, (byte[])record1.getValue("blob"));
        assertArrayEquals(bytes, (byte[])record1.getValue("input_stream"));
        assertArrayEquals(bytes, (byte[])record1.getValue("limited_is"));
    }

    @Test
    void insertBinaryStreams() throws SQLException {
        Key key1 = new Key(NAMESPACE, DATA, 1);
        assertNull(client.get(null, key1));
        PreparedStatement ps = testConn.prepareStatement("insert into data (PK, bin_stream, bin_stream_i, bin_stream_l) values (?, ?, ?, ?)");

        String text = "hello, binary stream!";
        byte[] bytes = text.getBytes();

        ps.setInt(1, 1);
        ps.setBinaryStream(2, new ByteArrayInputStream(bytes));
        ps.setBinaryStream(3, new ByteArrayInputStream(bytes), bytes.length);
        ps.setBinaryStream(4, new ByteArrayInputStream(bytes), (long)bytes.length);

        assertEquals(1, ps.executeUpdate());

        Record record1 = client.get(null, key1);
        assertNotNull(record1);
        assertArrayEquals(bytes, (byte[])record1.getValue("bin_stream"));
        assertArrayEquals(bytes, (byte[])record1.getValue("bin_stream_i"));
        assertArrayEquals(bytes, (byte[])record1.getValue("bin_stream_l"));
    }



    @Test
    void insertClobs() throws SQLException {
        Key key1 = new Key(NAMESPACE, DATA, 1);
        assertNull(client.get(null, key1));
        PreparedStatement ps = testConn.prepareStatement("insert into data (PK, clob, reader, limited_reader) values (?, ?, ?, ?)");

        String text = "hello, clob!";
        Clob clob = new StringClob();
        clob.setString(1, text);

        ps.setInt(1, 1);

        ps.setClob(2, clob);
        ps.setClob(3, new StringReader(text));
        ps.setClob(4, new StringReader(text), text.length());

        assertEquals(1, ps.executeUpdate());

        Record record1 = client.get(null, key1);
        assertNotNull(record1);
        assertEquals(text, record1.getString("clob"));
        assertEquals(text, record1.getString("reader"));
        assertEquals(text, record1.getString("limited_reader"));
    }

    @Test
    void insertNClobs() throws SQLException {
        Key key1 = new Key(NAMESPACE, DATA, 1);
        assertNull(client.get(null, key1));
        PreparedStatement ps = testConn.prepareStatement("insert into data (PK, clob, reader, limited_reader) values (?, ?, ?, ?)");

        String text = "hello, clob!";
        NClob clob = new StringClob();
        clob.setString(1, text);

        ps.setInt(1, 1);

        ps.setNClob(2, clob);
        ps.setNClob(3, new StringReader(text));
        ps.setNClob(4, new StringReader(text), text.length());

        assertEquals(1, ps.executeUpdate());

        Record record1 = client.get(null, key1);
        assertNotNull(record1);
        assertEquals(text, record1.getString("clob"));
        assertEquals(text, record1.getString("reader"));
        assertEquals(text, record1.getString("limited_reader"));
    }


    @Test
    void insertAsciiStreams() throws SQLException, IOException {
        Key key1 = new Key(NAMESPACE, DATA, 1);
        assertNull(client.get(null, key1));
        PreparedStatement ps = testConn.prepareStatement("insert into data (PK, ascii_stream, ascii_stream_i, ascii_stream_l) values (?, ?, ?, ?)");

        String text = "hello, ascii stream!";
        byte[] bytes = text.getBytes();
        ps.setInt(1, 1);
        ps.setAsciiStream(2, new ByteArrayInputStream(bytes));
        ps.setAsciiStream(3, new ByteArrayInputStream(bytes), text.length());
        ps.setAsciiStream(4, new ByteArrayInputStream(bytes), (long)text.length());

        assertEquals(1, ps.executeUpdate());

        Record record1 = client.get(null, key1);
        assertNotNull(record1);
        assertEquals(text, record1.getString("ascii_stream"));
        assertEquals(text, record1.getString("ascii_stream_i"));
        assertEquals(text, record1.getString("ascii_stream_l"));
    }

    @Test
    void insertCharacterStreams() throws SQLException, IOException {
        Key key1 = new Key(NAMESPACE, DATA, 1);
        assertNull(client.get(null, key1));
        PreparedStatement ps = testConn.prepareStatement("insert into data (PK, char_stream, char_stream_i, char_stream_l) values (?, ?, ?, ?)");

        String text = "hello, character stream!";
        byte[] bytes = text.getBytes();
        ps.setInt(1, 1);
        ps.setCharacterStream(2, new StringReader(text));
        ps.setCharacterStream(3, new StringReader(text), text.length());
        ps.setCharacterStream(4, new StringReader(text), (long)text.length());

        assertEquals(1, ps.executeUpdate());

        Record record1 = client.get(null, key1);
        assertNotNull(record1);
        assertEquals(text, record1.getString("char_stream"));
        assertEquals(text, record1.getString("char_stream_i"));
        assertEquals(text, record1.getString("char_stream_l"));
    }


    private String getString(Clob clob) throws SQLException {
        return clob.getSubString(1, (int)clob.length());
    }

    private byte[] getBytes(Blob blob) throws SQLException {
        return blob.getBytes(1, (int)blob.length());
    }

    private void insert(String sql, int expectedRowCount) throws SQLException {
        int rowCount = testConn.createStatement().executeUpdate(sql);
        assertEquals(expectedRowCount, rowCount);
    }
}
