package com.nosqldriver.sql;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.function.Predicate;

public class FilteredResultSet extends ResultSetWrapper {
    private final ResultSet rs;
    private final Predicate<ResultSet> filter;
    private int row = 0;

    public FilteredResultSet(ResultSet rs, Predicate<ResultSet> filter) {
        super(rs);
        this.rs = rs;
        this.filter = filter;
    }


    @Override
    public boolean next() throws SQLException {
        while (rs.next()) {
            if (filter.test(rs)) {
                row++;
                return true;
            }
        }
        return false;
    }



    @Override
    public boolean isLast() throws SQLException {
        return rs.isLast() && filter.test(rs);
    }


    @Override
    public boolean first() throws SQLException {
        if(rs.first()) {
            boolean next = false;
            while (!filter.test(rs)) {
                next = rs.next();
                if (!next) {
                    return false;
                }
            }
            row = 1;
            return true;
        }
        row = 0;
        return false;
    }

    @Override
    public boolean last() throws SQLException {
        row = 0;
        return rs.last() && filter.test(rs);
    }

    @Override
    public int getRow() throws SQLException {
        return row;
    }

    //TODO: shold absolute() and realative() be overloaded too?
    @Override
    public boolean absolute(int row) throws SQLException {
        return rs.absolute(row);
    }

    @Override
    public boolean relative(int rows) throws SQLException {
        return rs.relative(rows);
    }

    @Override
    public boolean previous() throws SQLException {
        while (rs.previous()) {
            if (filter.test(rs)) {
                return true;
            }
        }
        return false;
    }
}