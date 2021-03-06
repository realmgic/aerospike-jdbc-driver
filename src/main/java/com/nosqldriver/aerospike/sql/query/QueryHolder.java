package com.nosqldriver.aerospike.sql.query;

import com.aerospike.client.IAerospikeClient;
import com.aerospike.client.Key;
import com.aerospike.client.Value;
import com.aerospike.client.Value.StringValue;
import com.aerospike.client.query.Filter;
import com.aerospike.client.query.PredExp;
import com.aerospike.client.query.Statement;
import com.nosqldriver.VisibleForPackage;
import com.nosqldriver.aerospike.sql.AerospikeDatabaseMetadata;
import com.nosqldriver.aerospike.sql.AerospikePolicyProvider;
import com.nosqldriver.aerospike.sql.AerospikeStatement;
import com.nosqldriver.aerospike.sql.KeyRecordFetcherFactory;
import com.nosqldriver.aerospike.sql.SpecialField;
import com.nosqldriver.aerospike.sql.query.BinaryOperation.Operator;
import com.nosqldriver.aerospike.sql.query.BinaryOperation.PrimaryKeyEqualityPredicate;
import com.nosqldriver.sql.AggregatedValues;
import com.nosqldriver.sql.ChainedResultSetWrapper;
import com.nosqldriver.sql.DataColumn;
import com.nosqldriver.sql.ExpressionAwareResultSetFactory;
import com.nosqldriver.sql.FilteredResultSet;
import com.nosqldriver.sql.JoinedResultSet;
import com.nosqldriver.sql.ListRecordSet;
import com.nosqldriver.sql.NameCheckResultSetWrapper;
import com.nosqldriver.sql.OffsetLimit;
import com.nosqldriver.sql.OrderItem;
import com.nosqldriver.sql.ResultSetDistinctFilter;
import com.nosqldriver.sql.ResultSetHashExtractor;
import com.nosqldriver.sql.ResultSetRowFilter;
import com.nosqldriver.sql.ResultSetWrapper;
import com.nosqldriver.sql.SortedResultSet;
import com.nosqldriver.util.ByteArrayComparator;
import com.nosqldriver.util.FunctionManager;
import com.nosqldriver.util.SneakyThrower;
import net.sf.jsqlparser.expression.ArrayExpression;
import net.sf.jsqlparser.expression.BinaryExpression;
import net.sf.jsqlparser.expression.DoubleValue;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.SignedExpression;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;

import java.lang.reflect.Array;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.NavigableSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.nosqldriver.aerospike.sql.query.KeyFactory.createKey;
import static com.nosqldriver.aerospike.sql.query.KeyFactory.createKeys;
import static com.nosqldriver.aerospike.sql.query.PredExpUtil.extractType;
import static com.nosqldriver.aerospike.sql.query.PredExpUtil.getValue;
import static com.nosqldriver.aerospike.sql.query.PredExpUtil.isValue;
import static com.nosqldriver.sql.DataColumn.DataColumnRole.AGGREGATED;
import static com.nosqldriver.sql.DataColumn.DataColumnRole.DATA;
import static com.nosqldriver.sql.DataColumn.DataColumnRole.EXPRESSION;
import static com.nosqldriver.sql.DataColumn.DataColumnRole.GROUP;
import static com.nosqldriver.sql.DataColumn.DataColumnRole.HIDDEN;
import static com.nosqldriver.sql.DataColumn.DataColumnRole.PK;
import static com.nosqldriver.sql.DataColumn.DataColumnRole.PK_DIGEST;
import static com.nosqldriver.sql.SqlLiterals.operatorKey;
import static com.nosqldriver.sql.SqlLiterals.predExpOperators;
import static com.nosqldriver.util.IOUtils.stripQuotes;
import static java.lang.Math.max;
import static java.lang.Math.min;
import static java.lang.String.format;
import static java.lang.String.join;
import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.toList;

public class QueryHolder implements QueryContainer<ResultSet> {
    private String schema;
    private final Collection<String> indexes;
    private final AerospikePolicyProvider policyProvider;
    private final KeyRecordFetcherFactory keyRecordFetcherFactory;
    private String set;
    private String setAlias;
    private String having = null;
    private List<DataColumn> columns = new ArrayList<>();
    private List<List<Object>> data = new ArrayList<>();
    private boolean skipDuplicates = false;
    private String whereExpression = null;

    private final Statement statement;
    private AerospikeBatchQueryBySecondaryIndex secondayIndexQuery = null;
    private AerospikeQueryByPk pkQuery = null;
    private AerospikeBatchQueryByPk pkBatchQuery = null;
    private AerospikeScanQuery scanQuery = null;
    private Filter filter;
    private List<PredExp> predExps = new ArrayList<>();
    private long offset = -1;
    private long limit = -1;

    private List<OrderItem> ordering = new ArrayList<>();
    private Collection<QueryHolder> subQeueries = new ArrayList<>();
    private Collection<QueryHolder> joins = new ArrayList<>();
    private boolean skipIfMissing;
    private ChainOperation chainOperation = null;
    private boolean indexByName = false;
    private final Collection<SpecialField> specialFields;
    private String show;

    private final FunctionManager functionManager;
    private final ExpressionAwareResultSetFactory expressionResultSetWrappingFactory;
    private final ColumnType[] types;

    public enum ChainOperation {
        UNION, UNION_ALL, SUB_QUERY
    }

    public QueryHolder(String schema, Collection<String> indexes, AerospikePolicyProvider policyProvider, FunctionManager functionManager) {
        this.schema = schema;
        this.indexes = indexes;
        this.policyProvider = policyProvider;
        keyRecordFetcherFactory = new KeyRecordFetcherFactory(policyProvider.getQueryPolicy());
        this.functionManager = functionManager;
        expressionResultSetWrappingFactory = new ExpressionAwareResultSetFactory(functionManager, policyProvider.getDriverPolicy());
        statement = new Statement();
        if (schema != null) {
            statement.setNamespace(schema);
        }
        specialFields = SpecialField.specialFields(policyProvider);
        types = initTypes();
    }

    public Function<IAerospikeClient, ResultSet> getQuery(java.sql.Statement sqlStatement) {
        if (show != null) {
            return show(sqlStatement);
        }
        if (!subQeueries.isEmpty()) {
            return getQueryWithSubQueries(sqlStatement);
        }

        if (pkQuery != null) {
            assertNull(pkBatchQuery, secondayIndexQuery, scanQuery);
            return wrap(sqlStatement, pkQuery);
        }
        if (pkBatchQuery != null) {
            assertNull(pkQuery, secondayIndexQuery, scanQuery);
            return wrap(sqlStatement, pkBatchQuery);
        }
        if (scanQuery != null) {
            assertNull(pkQuery, pkBatchQuery, secondayIndexQuery);
            return wrap(sqlStatement, scanQuery);
        }
        if (secondayIndexQuery != null) {
            assertNull(pkQuery, pkBatchQuery, scanQuery);
            return wrap(sqlStatement, secondayIndexQuery);
        }
        if (!data.isEmpty()) {
            return new AerospikeInsertQuery(sqlStatement, schema, set, columns, data, policyProvider.getWritePolicy(), skipDuplicates, keyRecordFetcherFactory, functionManager, specialFields);
        }

        return wrap(sqlStatement, createSecondaryIndexQuery(sqlStatement));
    }

    public Function<IAerospikeClient, ResultSet> show(java.sql.Statement sqlStatement) {
        return SneakyThrower.get(() -> {
            AerospikeDatabaseMetadata md = ((AerospikeDatabaseMetadata) sqlStatement.getConnection().getMetaData());
            String name = show.toUpperCase();
            if (Arrays.stream(ShowRetriever.values()).noneMatch(r -> r.name().equals(name))) {
                SneakyThrower.sneakyThrow(
                        new SQLException(format("You have an error in your SQL syntax; check the manual that corresponds to your MySQL server version for the right syntax to use near '%s'", show))
                );
            }
            ShowRetriever retriever = ShowRetriever.valueOf(name);
            ResultSet rs = new ListRecordSet(
                    sqlStatement,
                    schema,
                    null,
                    retriever.columns(),
                    retriever.apply(md, schema));
            return client -> rs;
        });
    }

    private Function<IAerospikeClient, ResultSet> getQueryWithSubQueries(java.sql.Statement sqlStatement) {
        //TODO: add support of chained UNION and sub queries
        final Function<IAerospikeClient, ResultSet> query;
        if (subQeueries.stream().anyMatch(q -> ChainOperation.UNION_ALL.equals(q.chainOperation))) {
            query = wrap(sqlStatement, client -> new ChainedResultSetWrapper(
                    sqlStatement,
                    subQeueries.stream()
                            .filter(q -> ChainOperation.UNION_ALL.equals(q.chainOperation))
                            .map(queryHolder -> queryHolder.getQuery(sqlStatement)).map(f -> f.apply(client)).collect(toList()), indexByName));
        } else if (subQeueries.stream().anyMatch(q -> ChainOperation.UNION.equals(q.chainOperation))) {
            Function<IAerospikeClient, ResultSet> chained = client -> new ChainedResultSetWrapper(
                    sqlStatement,
                    subQeueries.stream()
                            .filter(q -> ChainOperation.UNION.equals(q.chainOperation))
                            .map(queryHolder -> queryHolder.getQuery(sqlStatement)).map(f -> f.apply(client)).collect(toList()), indexByName);


            query = wrap(sqlStatement, client -> new FilteredResultSet(
                    chained.apply(client), columns,
                    new ResultSetDistinctFilter<>(new ResultSetHashExtractor(), new TreeSet<>(new ByteArrayComparator())), indexByName));
        } else if (subQeueries.stream().anyMatch(q -> ChainOperation.SUB_QUERY.equals(q.chainOperation))) { // nested queries
            List<QueryHolder> all = subQeueries.stream().filter(q -> ChainOperation.SUB_QUERY.equals(q.chainOperation)).collect(toList());
            all.add(0, this);
            Collections.reverse(all);
            all.forEach(h -> h.indexByName = true);
            QueryHolder real = all.remove(0);
            Function<IAerospikeClient, ResultSet> realRs = real.getQuery(sqlStatement);
            AtomicReference<Function<IAerospikeClient, ResultSet>> currentRsRef = new AtomicReference<>(realRs);
            for (QueryHolder h : all) {
                currentRsRef.set(h.wrap(sqlStatement, currentRsRef.get()));
            }
            query = currentRsRef.get();
        } else {
            query = null;
        }

        return query;
    }


    @Override
    public void setParameters(java.sql.Statement sqlStatement, Object[] parameters) {
        int dataIndex = 0;
        for (List<Object> list : data) {
            for (int i = 0; i < list.size(); i++) {
                Object d = list.get(i);
                if (d instanceof PredExpValuePlaceholder) {
                    list.set(i, parameters[dataIndex]);
                    dataIndex++;
                }
            }
        }

        // Re-use prepared statement after execution
        if (dataIndex == 0 && !data.isEmpty() && data.get(0).size() == parameters.length && predExps.isEmpty()) {
            data.clear();
            data.add(Arrays.asList(parameters));
        }

        NavigableSet<Integer> indexesToRemove = new TreeSet<>();
        Class paramType = null;
        Class type = null;
        String columnName = null;
        PREDICATES:
        for (int i = 0; i < predExps.size(); i++) {
            PredExp predExp = predExps.get(i);

            if (predExp instanceof ColumnRefPredExp) {
                columnName = ((ColumnRefPredExp)predExp).getName();
            }


            if (predExp instanceof PredExpValuePlaceholder) {
                PredExpValuePlaceholder placeholder = ((PredExpValuePlaceholder)predExp);
                final Object parameter;
                if (predExp instanceof InnerQueryPredExp) {
                    parameter = retrieveParameterValueFromInnerQuery(sqlStatement, i, predExp, columnName, indexesToRemove);
                } else {
                    parameter = parameters[dataIndex + placeholder.getIndex() - 1];
                }

                paramType = parameter == null ? String.class : parameter.getClass();
                type = paramType;
                if (!(predExp instanceof InnerQueryPredExp) && predExps.get(i - 1) instanceof ColumnRefPredExp) {
                    String binName = ((ColumnRefPredExp)predExps.get(i - 1)).getName();
                    if ("PK".equals(binName)) {
                        String op = predExps.stream().skip(i).filter(exp -> exp instanceof OperatorRefPredExp).findFirst().map(exp -> ((OperatorRefPredExp) exp).getOp()).orElseThrow(() -> new IllegalStateException("Cannot find operation"));
                        createQuery(sqlStatement, parameter, op);
                        indexesToRemove.addAll(asList(i - 1, i, i +1 ));
                    } else {
                        type = placeholder.updatePreExp(predExps, i, parameter);
                    }
                    PredExp binExp = PredExpValuePlaceholder.createBinPredExp(binName, type);
                    predExps.set(i - 1, binExp);
                }

                if (predExps.get(i - 1) instanceof ColumnRefPredExp) {
                    String binName = ((ColumnRefPredExp)predExps.get(i - 1)).getName();
                    if ("PK".equals(binName)) {
                        String op = predExps.stream().skip(i).filter(exp -> exp instanceof OperatorRefPredExp).findFirst().map(exp -> ((OperatorRefPredExp) exp).getOp()).orElseThrow(() -> new IllegalStateException("Cannot find operation"));
                        createQuery(sqlStatement, parameter, op);
                        break PREDICATES;
                    }
                }
            }

            if (predExp instanceof OperatorRefPredExp) {
                if (paramType == null) {
                    SneakyThrower.sneakyThrow(new SQLException("Cannot retrieve type of parameter of prepared statement"));
                }
                predExps.set(i, predExpOperators.get(operatorKey(type, ((OperatorRefPredExp)predExp).getOp())).get());
            }
        }

        for (int i : indexesToRemove.descendingSet()) {
            predExps.remove(i);
        }

        // this happens when inner sql is used: select * from table where id in (select ...)
        // This is a patch, better solition should be found
        if (!predExps.isEmpty() && "com.aerospike.client.query.PredExp$AndOr".equals(predExps.get(0).getClass().getName())) {
            predExps.remove(0);
        }
    }

    private Object retrieveParameterValueFromInnerQuery(java.sql.Statement sqlStatement, int i, PredExp predExp, String columnName, Collection<Integer> indexesToRemove) {
        Object parameter = null;
        QueryHolder holder = ((InnerQueryPredExp)predExp).getHolder();
        AerospikeStatement statement = (AerospikeStatement)sqlStatement;
        try {
            ResultSet rs = holder.getQuery(statement.getConnection().createStatement()).apply(statement.getClient());
            ResultSetMetaData md = rs.getMetaData();
            int n = md.getColumnCount();
            if (n != 1) {
                SneakyThrower.sneakyThrow(new SQLException("Inner query must return only one column but were " + n));
            }
            List<Object> values = new ArrayList<>();
            Set<Class> types = new HashSet<>();
            while (rs.next()) {
                Object value = rs.getObject(1);
                values.add(value);
                types.add(value == null ? Object.class : value.getClass());
            }
            Class elemType = types.size() == 1 ? types.iterator().next() : Object.class;
            Object array = Array.newInstance(elemType, values.size());
            for (int j = 0; j < values.size(); j++) {
                Array.set(array, j, values.get(j));
            }
            parameter = array;

            PredExp nextExp = predExps.get(i + 1);
            if (nextExp instanceof OperatorRefPredExp) {
                BinaryOperation operation = new BinaryOperation();
                operation.setStatement(statement);
                operation.setColumn(columnName);
                values.forEach(operation::addValue);
                Optional<Operator> operatorOpt = Operator.find(((OperatorRefPredExp)nextExp).getOp());
                if (operatorOpt.isPresent()) {
                    Operator operator = operatorOpt.get();
                    if (operator.isBinaryComparison()) {
                        Object value = null;
                        int paramsCount = Array.getLength(parameter);
                        switch (paramsCount) {
                            case 0: value = null; break;
                            case 1: value = Array.get(parameter, 0); break;
                            default: SneakyThrower.sneakyThrow(new SQLException(format("Operator %s requires 1 parameter but were %d", operator, paramsCount)));
                        }
                        parameter = value;

                        if (value instanceof Long || value instanceof Integer || value instanceof Short || value instanceof Byte) {
                            predExps.set(i, PredExp.integerValue(((Number)value).longValue()));
                        } else if (value instanceof String) {
                            predExps.set(i, PredExp.stringValue((String)value));
                        } else {
                            SneakyThrower.sneakyThrow(new SQLException(format("Value %s belongs to unsupported type %s. Only string and integer types are supported", value, value.getClass())));
                        }
                    } else {
                        indexesToRemove.addAll(asList(i - 1, i, i +1 )); // IN
                    }

                } else {
                    SneakyThrower.sneakyThrow(new SQLException(format("Operator %s does not exist", ((OperatorRefPredExp)nextExp).getOp())));
                }

                Operator.find(((OperatorRefPredExp)nextExp).getOp()).map(op -> op.update(this, operation));
            }
        } catch (SQLException e) {
            SneakyThrower.sneakyThrow(e);
        }

        return parameter;
    }

    private void createQuery(java.sql.Statement sqlStatement, Object parameter, String op) {
        switch(op) {
            case "=":
                createPkQuery(sqlStatement, createKey(getSchema(), getSetName(), parameter));
                break;
            case "IN":
                createPkBatchQuery(sqlStatement, createKeys(getSchema(), getSetName(), parameter));
                break;
            case "!=":
            case "<>":
                createScanQuery(sqlStatement, new PrimaryKeyEqualityPredicate(createKey(getSchema(), getSetName(), parameter), false));
                break;
            default:
                SneakyThrower.sneakyThrow(new SQLException("Unsupported PK operation " + op));
        }

    }



    @SafeVarargs
    private final void assertNull(Function<IAerospikeClient, ResultSet>... queries) {
        if (Arrays.stream(queries).anyMatch(Objects::nonNull)) {
            SneakyThrower.sneakyThrow(new SQLException("More than one queries have been created"));
        }
    }

    private String[] getNames() {
        return columns.stream().filter(c -> c.getName() != null && !SpecialField.isSpecialField(c.getName())).map(DataColumn::getName).toArray(String[]::new);
    }

    public void setSchema(String schema) {
        this.schema = schema;
        statement.setNamespace(schema);
    }

    public String getSchema() {
        return schema;
    }

    public void setSetName(String set, String alias) {
        this.set = set;
        this.setAlias = alias;
        statement.setSetName(set);
    }

    private Function<IAerospikeClient, ResultSet>  createSecondaryIndexQuery(java.sql.Statement sqlStatement) {
        return createSecondaryIndexQuery(sqlStatement, filter, predExps);
    }


    private Function<IAerospikeClient, ResultSet> createSecondaryIndexQuery(java.sql.Statement sqlStatement, Filter filter, List<PredExp> predExps) {
        statement.setFilter(filter);
        if ((predExps.size() == 3) && predExps.stream().anyMatch(e -> PredExpUtil.isBin(extractType(e)) && "PK".equals(getValue(e)))) {
            Optional<Object> value = predExps.stream().filter(e -> isValue(extractType(e))).map(PredExpUtil::getValue).findFirst();
            if (value.isPresent()) {
                Key key = createKey(schema, set, value.get());
                return new AerospikeQueryByPk(sqlStatement, schema, columns, key, policyProvider.getQueryPolicy(), keyRecordFetcherFactory, functionManager, specialFields);
            }
        }
        if (predExps.size() >= 3) {
            statement.setPredExp(predExps.toArray(new PredExp[0]));
        }
        if (!joins.isEmpty() && columns.stream().allMatch(c -> HIDDEN.equals(c.getRole()))) {
            statement.setBinNames();
        }

        List<String> groupColumnNames = columns.stream().filter(c-> GROUP.equals(c.getRole())).map(c -> "groupby:" + c.getName()).collect(Collectors.toList());
        if (!groupColumnNames.isEmpty()) {
            Value[] args = Stream.concat(
                    groupColumnNames.stream(),
                    columns.stream().filter(c -> AGGREGATED.equals(c.getRole())).map(DataColumn::getName).filter(expr -> expr.contains("(")).map(expr -> expr.replace('(', ':').replace(")", "")))
                    .map(StringValue::new).toArray(Value[]::new);
            statement.setAggregateFunction(getClass().getClassLoader(), "groupby.lua", "groupby", "groupby", args);
            return new AerospikeDistinctQuery(sqlStatement, schema, columns, statement, policyProvider.getQueryPolicy(), having == null ? rs -> true : new ResultSetRowFilter(having, functionManager, policyProvider.getDriverPolicy()), keyRecordFetcherFactory, functionManager, specialFields);
        }

        List<DataColumn> aggregationColumns = columns.stream().filter(c-> AGGREGATED.equals(c.getRole())).collect(Collectors.toList());
        int aggregationColumnsCount = aggregationColumns.size();
        if (aggregationColumnsCount > 0) {
            Pattern functionCall = Pattern.compile("\\w+\\((.*)\\)");
            Value[] fieldsForAggregation = aggregationColumns.stream()
                    .map(DataColumn::getName)
                    .map(functionCall::matcher)
                    .filter(Matcher::find)
                    .map(m -> m.group(1))
                    .filter(name -> !"*".equals(name))
                    .distinct()
                    .map(StringValue::new)
                    .toArray(Value[]::new);
            if (statement.getBinNames() != null && statement.getBinNames().length > 0) {
                SneakyThrower.sneakyThrow(new SQLException("Cannot perform aggregation operation with query that contains regular fields"));
            }
            Pattern p = Pattern.compile("distinct\\((\\w+)\\)");
            Optional<String> distinctExpression = aggregationColumns.stream().map(DataColumn::getName).filter(s -> p.matcher(s).find()).findAny();
            if (distinctExpression.isPresent()) {
                if (aggregationColumnsCount > 1) {
                    SneakyThrower.sneakyThrow(new SQLException("Wrong query syntax: distinct is used together with other fields"));
                }

                Matcher m = p.matcher(distinctExpression.get());
                if (!m.find()) {
                    throw new IllegalStateException(); // actually cannot happen
                }
                String groupField = m.group(1);
                statement.setAggregateFunction(getClass().getClassLoader(), "distinct.lua", "distinct", "distinct", new StringValue(groupField));
                return new AerospikeDistinctQuery(sqlStatement, schema, columns, statement, policyProvider.getQueryPolicy(), keyRecordFetcherFactory, functionManager, specialFields);
            }


            statement.setAggregateFunction(getClass().getClassLoader(), "stats.lua", "stats", "single_bin_stats", fieldsForAggregation);
            return new AerospikeAggregationQuery(sqlStatement, schema, set, columns, statement, policyProvider.getQueryPolicy(), keyRecordFetcherFactory, functionManager, specialFields);
        }


        return secondayIndexQuery = new AerospikeBatchQueryBySecondaryIndex(sqlStatement, schema, columns, statement, policyProvider.getQueryPolicy(), keyRecordFetcherFactory, functionManager, specialFields);
    }

    @VisibleForPackage
    void createPkQuery(java.sql.Statement statement, Key key) {
        pkQuery = new AerospikeQueryByPk(statement, schema, columns, key, policyProvider.getQueryPolicy(), keyRecordFetcherFactory, functionManager, specialFields);
    }

    @VisibleForPackage
    void createPkBatchQuery(java.sql.Statement statement, Key ... keys) {
        Key[] allKeys = keys;
        if (pkBatchQuery != null) {
            Key[] existingKesys = pkBatchQuery.criteria;
            allKeys = new Key[existingKesys.length + keys.length];
            System.arraycopy(existingKesys, 0, allKeys, 0, existingKesys.length);
            System.arraycopy(keys, 0, allKeys, existingKesys.length, keys.length);
        }

        pkBatchQuery = new AerospikeBatchQueryByPk(statement, schema, set, columns, allKeys, policyProvider.getBatchPolicy(), keyRecordFetcherFactory, functionManager, specialFields);
    }

    @VisibleForPackage
    void createScanQuery(java.sql.Statement statement, Predicate<ResultSet> predicate) {
        scanQuery = new AerospikeScanQuery(statement, schema, set, columns, predicate, policyProvider.getScanPolicy(), keyRecordFetcherFactory, functionManager, specialFields);
    }



    public String getSetName() {
        return set;
    }

    public String getSetAlias() {
        return setAlias;
    }

    public void removeLastPredicates(int n) {
        for (int i = 0; i < n && !predExps.isEmpty(); i++) {
            predExps.remove(predExps.size() - 1);
        }
    }

    public void addPredExp(PredExp predExp) {
        predExps.add(predExp);
    }


    public List<PredExp> getPredExps() {
        return predExps;
    }


    public void setFilter(Filter filter, String binName) {
        if (indexes.contains(join(".", schema, set, binName))) {
            this.filter = filter;
        }
    }

    public void setOffset(long offset) {
        this.offset = offset;
    }

    public void setLimit(long limit) {
        this.limit = limit;
    }

    public void setShowTarget(String show) {
        this.show = show;
    }

    private Function<IAerospikeClient, ResultSet> wrap(java.sql.Statement sqlStatement, Function<IAerospikeClient, ResultSet> nakedQuery) {
        final Function<IAerospikeClient, ResultSet> expressioned;
        Pattern p = Pattern.compile("distinct\\((\\w+)\\)");
        Optional<DataColumn> distinctColumn = columns.stream().filter(c -> c.getName() != null).filter(c -> p.matcher(c.getName()).find()).findAny();
        if (set == null && distinctColumn.isPresent()) {
            if (columns.size() > 1) {
                SneakyThrower.sneakyThrow(new SQLException("Wrong query syntax: distinct is used together with other fields"));
            }

            String distinctColumnExpression = distinctColumn.get().getName();
            Matcher m = p.matcher(distinctColumn.get().getName());
            if (!m.find()) {
                throw new IllegalStateException(); // actually cannot happen
            }

            String distinctField = m.group(1);
            expressioned = client -> new FilteredResultSet(
                    nakedQuery.apply(client), columns,
                    new ResultSetDistinctFilter<>(new ResultSetHashExtractor(distinctField::equals), new TreeSet<>(new ByteArrayComparator())), indexByName) {
                @Override
                protected String getName(String alias) throws SQLException {
                    String name = super.getName(alias);
                    return distinctColumnExpression.equals(name) ? distinctField : name;
                }
            };
        } else if(set == null && columns.stream().map(DataColumn::getRole).anyMatch(r -> AGGREGATED.equals(r) || GROUP.equals(r))) {
            expressioned = client -> new FilteredResultSet(new ListRecordSet(sqlStatement, schema, set, columns, new AggregatedValues(nakedQuery.apply(client), columns).read()), columns, having == null ? rs -> true : new ResultSetRowFilter(having, functionManager, policyProvider.getDriverPolicy()), true);
        } else {
            expressioned = client -> expressionResultSetWrappingFactory.wrap(new ResultSetWrapper(nakedQuery.apply(client), columns, indexByName), functionManager, columns, indexByName);
        }

        Function<IAerospikeClient, ResultSet> filtered = whereExpression != null ? client -> new FilteredResultSet(expressioned.apply(client), columns, new ResultSetRowFilter(whereExpression, functionManager, policyProvider.getDriverPolicy()), indexByName) : expressioned;
        Function<IAerospikeClient, ResultSet> joined = joins.isEmpty() ? filtered : client -> new JoinedResultSet(filtered.apply(client), joins.stream().map(join -> new JoinHolder(new JoinRetriever(sqlStatement, client, join, functionManager), new ResultSetMetadataSupplier(sqlStatement, client, join, functionManager), join.skipIfMissing)).collect(toList()));
        Function<IAerospikeClient, ResultSet> ordered = !ordering.isEmpty() ? client -> new SortedResultSet(joined.apply(client), ordering, min(max(offset, 0) + (limit >=0 ? limit : Integer.MAX_VALUE), Integer.MAX_VALUE), functionManager, policyProvider.getDriverPolicy()) : joined;
        Function<IAerospikeClient, ResultSet> limited = offset >= 0 || limit >= 0 ? client -> new FilteredResultSet(ordered.apply(client), columns, new OffsetLimit(offset < 0 ? 0 : offset, limit < 0 ? Long.MAX_VALUE : limit), indexByName) : ordered;
        return client -> new NameCheckResultSetWrapper(limited.apply(client), columns, indexByName);

    }

    public abstract class ColumnType {
        private final Predicate<Object> locator;

        protected ColumnType(Predicate<Object> locator) {
            this.locator = locator;
        }
        protected abstract String getCatalog(Expression expr);
        protected abstract String getTable(Expression expr);
        protected abstract String getText(Expression expr);
        public abstract void addColumn(Expression expr, String alias, boolean visible, String schema, String table);
    }

    private ColumnType[] initTypes() {
        return new ColumnType[]{
                new ColumnType((e) -> e instanceof Column) {

                    @Override
                    protected String getCatalog(Expression expr) {
                        return ofNullable(((Column) expr).getTable()).map(Table::getSchemaName).orElse(schema);
                    }

                    @Override
                    protected String getTable(Expression expr) {
                        String table = ofNullable(((Column) expr).getTable()).map(t -> stripQuotes(t.getName())).orElse(set);
                        // resolve alias
                        if (Objects.equals(table, setAlias)) {
                            return set;
                        }
                        Optional<String> joinAlias = joins.stream().filter(j -> Objects.equals(table, j.setAlias)).findFirst().map(j -> j.setAlias);
                        return joinAlias.orElse(table);
                    }

                    @Override
                    protected String getText(Expression expr) {
                        return stripQuotes(((Column) expr).getColumnName());
                    }

                    @Override
                    public void addColumn(Expression expr, String alias, boolean visible, String catalog, String table) {
                        String name = getText(expr);
                        DataColumn.DataColumnRole role = getColumnRole(name, visible);
                        if (PK.equals(role)) {
                            name = expr.toString();
                        }
                        columns.add(role.create(getCatalog(expr), getTable(expr), name, alias));
                        statement.setBinNames(getNames());
                    }

                    private DataColumn.DataColumnRole getColumnRole(String name, boolean visible) {
                        if (!visible) {
                            return HIDDEN;
                        }
                        return PK.name().equals(name) ? PK : PK_DIGEST.name().equals(name) ? PK_DIGEST : DATA;
                    }
                },

                new ColumnType(e -> e instanceof BinaryExpression || e instanceof LongValue || e instanceof DoubleValue || e instanceof net.sf.jsqlparser.expression.StringValue || (e instanceof net.sf.jsqlparser.expression.Function && expressionResultSetWrappingFactory.getClientSideFunctionNames().contains(((net.sf.jsqlparser.expression.Function) e).getName())) || e instanceof SignedExpression) {
                    @Override
                    protected String getCatalog(Expression expr) {
                        return schema;
                    }

                    @Override
                    protected String getTable(Expression expr) {
                        return set;
                    }

                    @Override
                    protected String getText(Expression expr) {
                        return expr.toString();
                    }

                    @Override
                    public void addColumn(Expression expr, String alias, boolean visible, String catalog, String table) {
                        String column = getText(expr);
                        columns.add(EXPRESSION.create(getCatalog(expr), getTable(expr), getText(expr), alias));
                        expressionResultSetWrappingFactory.getVariableNames(column).forEach(v -> columns.add(HIDDEN.create(getCatalog(expr), getTable(expr), v, alias)));
                        statement.setBinNames(getNames());
                    }
                },


                new ColumnType(e -> e instanceof net.sf.jsqlparser.expression.Function) {
                    @Override
                    protected String getCatalog(Expression expr) {
                        return schema;
                    }

                    @Override
                    protected String getTable(Expression expr) {
                        return set;
                    }

                    @Override
                    protected String getText(Expression expr) {
                        return expr.toString();
                    }

                    @Override
                    public void addColumn(Expression expr, String alias, boolean visible, String catalog, String table) {
                        columns.add(AGGREGATED.create(getCatalog(expr), getTable(expr), getText(expr), alias));
                    }
                },

                new ColumnType(e -> e instanceof String && ((String) e).startsWith("distinct")) {
                    @Override
                    protected String getCatalog(Expression expr) {
                        return ofNullable(((Column) expr).getTable()).map(Table::getSchemaName).orElse(null);
                    }

                    @Override
                    protected String getTable(Expression expr) {
                        return ofNullable(((Column) expr).getTable()).map(t -> stripQuotes(t.getName())).orElse(null);
                    }

                    @Override
                    protected String getText(Expression expr) {
                        return "distinct" + expr.toString();
                    }

                    @Override
                    public void addColumn(Expression expr, String alias, boolean visible, String catalog, String table) {
                        columns.add(AGGREGATED.create(catalog, table, getText(expr), alias));
                    }
                },
                new ColumnType(e -> e instanceof ArrayExpression) {

                    @Override
                    protected String getCatalog(Expression expr) {
                        return schema;
                    }

                    @Override
                    protected String getTable(Expression expr) {
                        return null;
                    }

                    @Override
                    protected String getText(Expression expr) {
                        return expr == null ? null : expr.toString();
                    }

                    @Override
                    public void addColumn(Expression expr, String alias, boolean visible, String schema, String table) {
                        columns.add(DATA.create(getCatalog(expr), getTable(expr), getText(expr), alias));
                    }
                }
        };
    }

    public void addGroupField(String field) {
        boolean updated = false;
        for (DataColumn c : columns) {
            if (c.getName().equals(field)) {
                c.updateRole(GROUP);
                updated = true;
            }
        }
        if (!updated) {
            columns.add(GROUP.create(schema, set, field, field));
        }
    }

    public void setHaving(String having) {
        this.having = having;
    }

    public ColumnType getColumnType(Object expr) {
        return Arrays.stream(types).filter(t -> t.locator.test(expr)).findFirst().orElseThrow(() -> new IllegalArgumentException(format("Column type %s is not supported", expr)));
    }

    public QueryHolder addJoin(boolean skipIfMissing) {
        QueryHolder join = new QueryHolder(schema, indexes, policyProvider, functionManager);
        join.skipIfMissing = skipIfMissing;
        joins.add(join);
        return join;
    }

    public void addOrdering(OrderItem orderItem) {
        ordering.add(orderItem);
    }

    public QueryHolder addSubQuery(ChainOperation operation) {
        QueryHolder subQuery = new QueryHolder(schema, indexes, policyProvider, functionManager);
        subQeueries.add(subQuery);
        subQuery.chainOperation = operation;
        return subQuery;
    }


    private static class JoinRetriever implements Function<ResultSet, ResultSet> {
        private final java.sql.Statement sqlStatement;
        private final IAerospikeClient client;
        private final QueryHolder joinQuery;
        private final FunctionManager functionManager1;

        public JoinRetriever(java.sql.Statement sqlStatement, IAerospikeClient client, QueryHolder joinQuery, FunctionManager functionManager) {
            this.sqlStatement = sqlStatement;
            this.client = client;
            this.joinQuery = joinQuery;
            this.functionManager1 = functionManager;
        }

        @Override
        public ResultSet apply(ResultSet rs) {
            QueryHolder holder = new QueryHolder(joinQuery.schema, joinQuery.indexes, joinQuery.policyProvider, functionManager1);
            holder.setSetName(joinQuery.getSetName(), joinQuery.setAlias);
            joinQuery.copyColumnsForTable(joinQuery.setAlias, holder);
            holder.predExps = preparePredicates(rs, joinQuery.predExps);
            return holder.getQuery(sqlStatement).apply(client);
        }


        // TODO: try to refactor this code.
        // TODO: this method uses data from AerospikeQueryFactory. Move this data to shared place.
        private List<PredExp> preparePredicates(ResultSet rs, List<PredExp> original) {
            int n = original.size();
            List<PredExp> result = new ArrayList<>(original);
            for (int i = 0; i < n; i++) {
                PredExp exp = result.get(i);
                if (exp instanceof ValueRefPredExp) {
                    ValueRefPredExp ref = (ValueRefPredExp)exp;
                    final Object value = SneakyThrower.get(() -> rs.getObject(ref.getName()));

                    if (value instanceof String) {
                        result.set(i, PredExp.stringValue((String)value));
                        if (i > 0 && result.get(i - 1) instanceof ColumnRefPredExp) {
                            result.set(i - 1, PredExp.stringBin(((ColumnRefPredExp)result.get(i - 1)).getName()));
                            if (i < n - 1 && result.get(i + 1) instanceof OperatorRefPredExp) {
                                result.set(i + 1, predExpOperators.get(operatorKey(String.class, ((OperatorRefPredExp)result.get(i + 1)).getOp())).get());
                            }
                        } else if (i < n - 1 && result.get(i + 1) instanceof ColumnRefPredExp) {
                            result.set(i + 1, PredExp.stringBin(((ColumnRefPredExp)result.get(i + 1)).getName()));
                            if (i < n - 2 && result.get(i + 2) instanceof OperatorRefPredExp) {
                                result.set(i + 2, predExpOperators.get(operatorKey(String.class, ((OperatorRefPredExp)result.get(i + 2)).getOp())).get());
                            }
                        }
                    } else if (value instanceof Number) {
                        result.set(i, PredExp.integerValue(((Number)value).longValue()));
                        if (i > 0 && result.get(i - 1) instanceof ColumnRefPredExp) {
                            result.set(i - 1, PredExp.integerBin(((ColumnRefPredExp)result.get(i - 1)).getName()));
                            if (i < n - 1 && result.get(i + 1) instanceof OperatorRefPredExp) {
                                result.set(i + 1, predExpOperators.get(operatorKey(Long.class, ((OperatorRefPredExp)result.get(i + 1)).getOp())).get());
                            }
                        } else if (i < n - 1 && result.get(i + 1) instanceof ColumnRefPredExp) {
                            result.set(i + 1, PredExp.integerBin(((ColumnRefPredExp)result.get(i + 1)).getName()));
                            if (i < n - 2 && result.get(i + 2) instanceof OperatorRefPredExp) {
                                result.set(i + 2, predExpOperators.get(operatorKey(Long.class, ((OperatorRefPredExp)result.get(i + 2)).getOp())).get());
                            }
                        }
                    } else {
                        throw new IllegalStateException(value != null ? value.getClass().getName() : null);
                    }
                }
            }
            return result;
        }
    }


    public void copyColumnsForTable(String tableAlias, QueryHolder other) {
        int n = columns.size();
        for (int i = 0; i < n; i++) {
            if (tableAlias.equals(columns.get(i).getTable())) {
                other.columns.add(columns.get(i));
            }
        }
    }

    public QueryHolder queries(String table) {
        return table == null || table.equals(setAlias) ? this : joins.stream().filter(j -> table.equals(j.setAlias)).findFirst().orElseThrow(() -> new IllegalArgumentException(format("Cannot find query for table  %s", table)));
    }

    public Optional<DataColumn> getColumnByAlias(String alias) {
        return columns.stream().filter(c -> alias.equals(c.getLabel())).findFirst();
    }

    public void addName(String name)  {
        columns.add(DATA.create(schema, set, name, null));
    }

    public void addData(List<Object> dataRow)  {
        data.add(new ArrayList<>(dataRow));
    }

    public void setSkipDuplicates(boolean skipDuplicates) {
        this.skipDuplicates = skipDuplicates;
    }

    public void setWhereExpression(String whereExpression) {
        this.whereExpression = whereExpression;
    }


    @Override
    public List<DataColumn> getRequestedColumns() {
        return columns.isEmpty() ? singletonList(HIDDEN.create(schema, set, "*", "*")) : columns;
    }

    public List<DataColumn> getFilteredColumns() {
        return predExps.stream().filter(exp -> exp instanceof ColumnRefPredExp)
                .map(e -> (ColumnRefPredExp)e)
                .map(c -> DATA.create(schema, c.getTable(), null, c.getName()))
                .collect(toList());
    }

    public boolean isPkQuerySupported() {
        return specialFields.contains(SpecialField.PK);
    }



    private static class ResultSetMetadataSupplier implements Supplier<ResultSetMetaData> {
        private final java.sql.Statement sqlStatement;
        private final IAerospikeClient client;
        private final QueryHolder metadataQueryHolder;
        private ResultSetMetaData metaData;

        public ResultSetMetadataSupplier(java.sql.Statement sqlStatement, IAerospikeClient client, QueryHolder queryHolder, FunctionManager functionManager) {
            this.sqlStatement = sqlStatement;
            this.client = client;
            this.metadataQueryHolder = new QueryHolder(queryHolder.schema, queryHolder.indexes, queryHolder.policyProvider, functionManager);
            this.metadataQueryHolder.setSetName(queryHolder.getSetName(), queryHolder.setAlias);
            queryHolder.copyColumnsForTable(queryHolder.setAlias, this.metadataQueryHolder);
            this.metadataQueryHolder.setLimit(1);
        }

        @Override
        public ResultSetMetaData get() {
            return SneakyThrower.get(() -> {
                if (metaData == null) {
                    metaData = metadataQueryHolder.getQuery(sqlStatement).apply(client).getMetaData();
                }
                return metaData;
            });
        }
    }
}
