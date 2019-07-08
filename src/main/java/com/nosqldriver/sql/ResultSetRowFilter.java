package com.nosqldriver.sql;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

public class ResultSetRowFilter extends ExpressionEvaluator<ResultSet> {
    public ResultSetRowFilter(String expr) {
        super(expr);
    }

    @Override
    protected Map<String, Object> toMap(ResultSet rs) {
        try {
            Map<String, Object> ctx = new HashMap<>();
            ResultSetMetaData md = rs.getMetaData();
            int n = md.getColumnCount();
            for (int i = 1; i <= n; i++) {
                String name = md.getColumnName(i);
                ctx.put(name, rs.getObject(i));
                String label = md.getColumnLabel(i);
                if (label != null) {
                    ctx.put(label, rs.getObject(i));
                }
            }
            return ctx;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}