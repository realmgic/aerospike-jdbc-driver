package com.nosqldriver.sql;

import com.nosqldriver.util.SneakyThrower;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.Writer;
import java.sql.Clob;
import java.sql.NClob;
import java.sql.SQLException;

import static com.nosqldriver.util.SneakyThrower.sneakyThrow;
import static java.lang.String.format;

public class StringClob implements NClob {
    private String data;

    public StringClob() {
        this("");
    }

    public StringClob(String data) {
        this.data = data;
    }


    @Override
    public long length() throws SQLException {
        return data.length();
    }

    @Override
    public String getSubString(long pos, int length) throws SQLException {
        if (pos > Integer.MAX_VALUE || pos < 1) {
            throw new SQLException(format("Position must be between 1 and %d but was %d", Integer.MAX_VALUE, pos));
        }
        int from = (int)pos - 1;
        return data.substring(from, length);
    }

    @Override
    public Reader getCharacterStream() throws SQLException {
        return new StringReader(data);
    }

    @Override
    public InputStream getAsciiStream() throws SQLException {
        return new ByteArrayInputStream(data.getBytes());
    }

    @Override
    public long position(String searchstr, long start) throws SQLException {
        if (start > Integer.MAX_VALUE || start < 1) {
            throw new SQLException(format("Position must be between 1 and %d but was %d", Integer.MAX_VALUE, start));
        }
        int from = (int)start - 1;
        return data.indexOf(searchstr, from);
    }

    @Override
    public long position(Clob searchstr, long start) throws SQLException {
        return position(((StringClob)searchstr).data, start);
    }

    @Override
    public int setString(long pos, String str) throws SQLException {
        if (pos > Integer.MAX_VALUE || pos < 1) {
            throw new SQLException(format("Position must be between 1 and %d but was %d", Integer.MAX_VALUE, pos));
        }
        int till = (int)pos;
        data = (data.length() >= till ? data.substring(0, till) : data) + str;
        return data.length();
    }

    @Override
    public int setString(long pos, String str, int offset, int len) throws SQLException {
        if (pos > Integer.MAX_VALUE || offset < 0) {
            throw new SQLException(format("Offset cannot be negative but was %d", offset));
        }
        int till = (int)pos;
        data = (data.length() >= till ? data.substring(0, till) : data) + str.substring(offset, len);
        return len - offset;
    }

    @Override
    public OutputStream setAsciiStream(long pos) throws SQLException {
        return new ByteArrayOutputStream() {
            @Override
            public void close() throws IOException {
                super.close();
                try {
                    setString(pos, new String(toByteArray()));
                } catch (SQLException e) {
                    sneakyThrow(e);
                }
            }
        };
    }

    @Override
    public Writer setCharacterStream(long pos) throws SQLException {

        return new StringWriter() {
            @Override
            public void close() throws IOException {
                super.close();
                try {
                    setString(pos, getBuffer().toString());
                } catch (SQLException e) {
                    sneakyThrow(e);
                }
            }
        };
    }

    @Override
    public void truncate(long len) throws SQLException {
        data = data.substring(0, (int)len);
    }

    @Override
    public void free() throws SQLException {
        data = "";
    }

    @Override
    public Reader getCharacterStream(long pos, long length) throws SQLException {
        return new StringReader(getSubString(pos, (int)length));
    }

}