package com.nosqldriver.sql;

import com.nosqldriver.aerospike.sql.TestDataUtils;
import com.nosqldriver.util.FunctionManager;
import com.nosqldriver.util.PojoHelper;
import org.junit.jupiter.api.Test;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;

import static com.nosqldriver.aerospike.sql.TestDataUtils.beatles;
import static com.nosqldriver.sql.DataColumn.DataColumnRole.DATA;
import static com.nosqldriver.sql.OrderItem.Direction.DESC;
import static com.nosqldriver.util.PojoHelper.fieldNames;
import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static java.util.stream.Collectors.toList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SortedResultSetTest {
    private static final String NAMESPACE = "namespace";
    private static final String TABLE = "table";
    private static final List<DataColumn> dataColumn = singletonList(DATA.create(NAMESPACE, TABLE, "data", "data"));
    private static final List<DataColumn> peopleColumns = fieldNames(beatles[0]).stream().map(name -> DATA.create(NAMESPACE, TABLE, name, name)).collect(toList());

    @Test
    void emptyAll() throws SQLException {
        assertFalse(new SortedResultSet(new ListRecordSet(null, NAMESPACE, TABLE, emptyList(), emptyList()), emptyList(), new FunctionManager(null), new DriverPolicy()).next());
    }

    @Test
    void oneColumnNoRecords() throws SQLException {
        assertFalse(new SortedResultSet(new ListRecordSet(null, NAMESPACE, TABLE, dataColumn, emptyList()), emptyList(), new FunctionManager(null), new DriverPolicy()).next());
    }

    @Test
    void oneColumnOneOrderByNoRecords() throws SQLException {
        assertFalse(new SortedResultSet(new ListRecordSet(null, NAMESPACE, TABLE, dataColumn, emptyList()), singletonList(new OrderItem("data")), new FunctionManager(null), new DriverPolicy()).next());
    }



    @Test
    void oneColumnOneOrderByOneRecord() throws SQLException {
        assertTrue(new SortedResultSet(new ListRecordSet(null, NAMESPACE, TABLE, dataColumn, singletonList(singletonList("a"))), singletonList(new OrderItem("data")), new FunctionManager(null), new DriverPolicy()).next());
    }

    @Test
    void assertOneColumnOneOrderBySeveralRecords() throws SQLException {
        assertOneColumnOneOrderBySeveralRecords(dataColumn, asList(singletonList("a"), singletonList("c"), singletonList("b")), new OrderItem("data"), "data", new String[] {"a", "b", "c"});
    }

    @Test
    void oneColumnOneOrderByDescSeveralRecords() throws SQLException {
        assertOneColumnOneOrderBySeveralRecords(dataColumn, asList(singletonList("a"), singletonList("c"), singletonList("b")), new OrderItem("data", DESC), "data", new String[] {"c", "b", "a"});
    }

    @Test
    void oneColumnOneOrderBySeveralRecordsDuplicateRecords() throws SQLException {
        assertOneColumnOneOrderBySeveralRecords(dataColumn, asList(singletonList("a"), singletonList("c"), singletonList("a"), singletonList("b")), new OrderItem("data"), "data", new String[] {"a", "a", "b", "c"});
    }


    @Test
    void severalColumnsOrderByOne() throws SQLException {
        List<List<?>> data = Arrays.stream(beatles).map(PojoHelper::fieldValues).collect(toList());
        assertOneColumnOneOrderBySeveralRecords(peopleColumns, data, new OrderItem("firstName"), "firstName", new String[] {"George", "John", "Paul", "Ringo"});
    }

    @Test
    void severalColumnsOrderBySeveral() throws SQLException {
        List<List<?>> data = Arrays.stream(beatles).map(PojoHelper::fieldValues).collect(toList());
        assertOneColumnSeveralOrderBySeveralRecords(peopleColumns, data, asList(new OrderItem("yearOfBirth"), new OrderItem("kidsCount")), "firstName", new String[] {"John", "Ringo", "Paul", "George"});
    }

    @Test
    void severalColumnsOrderBySeveralAscDesc() throws SQLException {
        List<List<?>> data = Arrays.stream(beatles).map(PojoHelper::fieldValues).collect(toList());
        assertOneColumnSeveralOrderBySeveralRecords(peopleColumns, data, asList(new OrderItem("yearOfBirth"), new OrderItem("kidsCount", DESC)), "firstName", new String[] {"Ringo", "John", "Paul", "George"});
    }

    @Test
    void highLimit() {
        assertTrue(assertThrows(IllegalArgumentException.class, () -> new SortedResultSet(new ListRecordSet(null, NAMESPACE, TABLE, dataColumn, emptyList()), emptyList(), Integer.MAX_VALUE + 1L, new FunctionManager(null), new DriverPolicy())).getMessage().startsWith("Cannot cast value"));
    }

    private ResultSet dataRs(List<DataColumn> columns, Iterable<List<?>> data) {
        return new ListRecordSet(null, NAMESPACE, TABLE, columns, data);
    }

    private void assertOneColumnOneOrderBySeveralRecords(List<DataColumn> columns, List<List<?>> data, OrderItem orderBy, String extractColumn, String[] expected) throws SQLException {
        assertEquals(
                asList(expected),
                TestDataUtils.toListOfMaps(new SortedResultSet(dataRs(columns, data), singletonList(orderBy), new FunctionManager(null), new DriverPolicy())).stream().map(row -> row.get(extractColumn)).collect(toList()));
    }

    private void assertOneColumnSeveralOrderBySeveralRecords(List<DataColumn> columns, List<List<?>> data, List<OrderItem> orderBy, String extractColumn, String[] expected) throws SQLException {
        assertEquals(
                asList(expected),
                TestDataUtils.toListOfMaps(new SortedResultSet(dataRs(columns, data), orderBy, new FunctionManager(null), new DriverPolicy())).stream().map(row -> row.get(extractColumn)).collect(toList()));
    }

}