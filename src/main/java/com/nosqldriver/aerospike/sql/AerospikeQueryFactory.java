package com.nosqldriver.aerospike.sql;

import com.aerospike.client.Bin;
import com.aerospike.client.IAerospikeClient;
import com.aerospike.client.Key;
import com.aerospike.client.Record;
import com.aerospike.client.policy.WritePolicy;
import com.aerospike.client.query.PredExp;
import com.nosqldriver.VisibleForPackage;
import com.nosqldriver.aerospike.sql.query.BinaryOperation;
import com.nosqldriver.aerospike.sql.query.BinaryOperation.Operator;
import com.nosqldriver.aerospike.sql.query.ColumnRefPredExp;
import com.nosqldriver.aerospike.sql.query.InnerQueryPredExp;
import com.nosqldriver.aerospike.sql.query.OperatorRefPredExp;
import com.nosqldriver.aerospike.sql.query.PredExpValuePlaceholder;
import com.nosqldriver.aerospike.sql.query.QueryContainer;
import com.nosqldriver.aerospike.sql.query.QueryHolder;
import com.nosqldriver.aerospike.sql.query.QueryHolder.ChainOperation;
import com.nosqldriver.aerospike.sql.query.ValueRefPredExp;
import com.nosqldriver.sql.DataColumn;
import com.nosqldriver.sql.DriverPolicy;
import com.nosqldriver.sql.ScriptEngineFactory;
import com.nosqldriver.sql.JoinType;
import com.nosqldriver.sql.OrderItem;
import com.nosqldriver.sql.RecordExpressionEvaluator;
import com.nosqldriver.util.DateParser;
import com.nosqldriver.util.FunctionManager;
import com.nosqldriver.util.SneakyThrower;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.Alias;
import net.sf.jsqlparser.expression.BinaryExpression;
import net.sf.jsqlparser.expression.DoubleValue;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.ExpressionVisitorAdapter;
import net.sf.jsqlparser.expression.JdbcParameter;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.NullValue;
import net.sf.jsqlparser.expression.Parenthesis;
import net.sf.jsqlparser.expression.SignedExpression;
import net.sf.jsqlparser.expression.StringValue;
import net.sf.jsqlparser.expression.operators.arithmetic.Addition;
import net.sf.jsqlparser.expression.operators.arithmetic.Division;
import net.sf.jsqlparser.expression.operators.arithmetic.Multiplication;
import net.sf.jsqlparser.expression.operators.arithmetic.Subtraction;
import net.sf.jsqlparser.expression.operators.relational.Between;
import net.sf.jsqlparser.expression.operators.relational.ExpressionList;
import net.sf.jsqlparser.expression.operators.relational.InExpression;
import net.sf.jsqlparser.expression.operators.relational.ItemsListVisitorAdapter;
import net.sf.jsqlparser.expression.operators.relational.MultiExpressionList;
import net.sf.jsqlparser.parser.CCJSqlParserManager;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.ShowStatement;
import net.sf.jsqlparser.statement.StatementVisitorAdapter;
import net.sf.jsqlparser.statement.UseStatement;
import net.sf.jsqlparser.statement.create.index.CreateIndex;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.drop.Drop;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.FromItemVisitorAdapter;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.Limit;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectBody;
import net.sf.jsqlparser.statement.select.SelectExpressionItem;
import net.sf.jsqlparser.statement.select.SelectItemVisitorAdapter;
import net.sf.jsqlparser.statement.select.SelectVisitorAdapter;
import net.sf.jsqlparser.statement.select.SetOperation;
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.statement.select.SubSelect;
import net.sf.jsqlparser.statement.select.UnionOp;
import net.sf.jsqlparser.statement.truncate.Truncate;
import net.sf.jsqlparser.statement.update.Update;

import javax.script.ScriptEngine;
import java.io.StringReader;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.LongStream;

import static com.nosqldriver.aerospike.sql.query.KeyFactory.createKey;
import static com.nosqldriver.sql.OrderItem.Direction.ASC;
import static com.nosqldriver.sql.OrderItem.Direction.DESC;
import static com.nosqldriver.sql.PreparedStatementUtil.PS_PLACEHOLDER_PREFIX;
import static com.nosqldriver.sql.PreparedStatementUtil.parseParameters;
import static com.nosqldriver.sql.SqlLiterals.operatorKey;
import static com.nosqldriver.sql.SqlLiterals.predExpOperators;
import static com.nosqldriver.util.IOUtils.stripQuotes;
import static java.lang.String.format;
import static java.util.Collections.singletonMap;
import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.toMap;
import static java.util.stream.Collectors.toSet;

public class AerospikeQueryFactory {
    private static final Collection<Class> INT_CLASSES = new HashSet<>(Arrays.asList(Byte.class, Short.class, Integer.class, Long.class));
    private CCJSqlParserManager parserManager = new CCJSqlParserManager();
    private final Statement statement;
    private String schema;
    private String set;
    private final AerospikePolicyProvider policyProvider;
    private final Collection<String> indexes;
    private final FunctionManager functionManager;
    private final ScriptEngine engine;

    @VisibleForPackage
    AerospikeQueryFactory(Statement statement, String schema, AerospikePolicyProvider policyProvider, Collection<String> indexes, FunctionManager functionManager, DriverPolicy driverPolicy) {
        this.statement = statement;
        this.schema = schema;
        this.policyProvider = policyProvider;
        this.indexes = indexes;
        this.functionManager = functionManager;
        engine = new ScriptEngineFactory(functionManager, driverPolicy).getEngine();
    }

    @VisibleForPackage
    QueryContainer<ResultSet> createQueryPlan(String sql) throws SQLException {
        try {
            QueryHolder queries = new QueryHolder(schema, indexes, policyProvider, functionManager);
            parserManager.parse(new StringReader(sql)).accept(new StatementVisitorAdapter() {
                @Override
                public void visit(Select select) {
                    SelectBody selectBody = select.getSelectBody();
                    createSelect(selectBody, queries);
                }


                @Override
                public void visit(Insert insert) {
                    queries.setSkipDuplicates(insert.isModifierIgnore());
                    Table table = insert.getTable();
                    if (table.getSchemaName() != null) {
                        queries.setSchema(stripQuotes(table.getSchemaName()));
                    }
                    queries.setSetName(stripQuotes(table.getName()), ofNullable(table.getAlias()).map(a -> stripQuotes(a.getName())).orElse(null));
                    set = stripQuotes(table.getName());
                    insert.getColumns().forEach(column -> queries.addName(stripQuotes(column.getColumnName())));

                    // Trick to support both single and multi expression list.
                    // The row data is accumulated in values. queries.addData() copies collection of values to other list
                    // After each row of multiple list the values list is cleaned. After this block queries.addData() is called
                    // again for single data list only if values is not empty. This prevents calling queries.addData() one more time
                    // for multiple data list.
                    List<Object> values = new ArrayList<>();
                    insert.getItemsList().accept(new ItemsListVisitorAdapter() {
                        @Override
                        public void visit(MultiExpressionList multiExprList) {
                            for (ExpressionList list : multiExprList.getExprList()) {
                                values.clear();
                                visit(list);
                                queries.addData(values);
                                values.clear();
                            }
                        }

                        @Override
                        public void visit(ExpressionList expressionList) {
                            expressionList.accept(new ExpressionVisitorAdapter() {
                                @Override
                                public void visit(JdbcParameter parameter) {
                                    values.add(new PredExpValuePlaceholder(parameter.getIndex()));
                                }
                                @Override
                                public void visit(NullValue value) {
                                    values.add(null);
                                }

                                @Override
                                public void visit(DoubleValue value) {
                                    values.add(value.getValue());
                                }

                                @Override
                                public void visit(LongValue value) {
                                    values.add(value.getValue());
                                }

                                @Override
                                public void visit(StringValue value) {
                                    values.add(value.getValue());
                                }

                                @Override
                                public void visit(net.sf.jsqlparser.expression.Function function) {
                                    SneakyThrower.call(() -> values.add(engine.eval(function.toString())));
                                }
                            });
                            //System.out.println("visit expressions: " + expressionList);
                        }
                    });

                    if (!values.isEmpty()) {
                        queries.addData(values);
                    }
                }

                @Override
                public void visit(Update update) {
                    super.visit(update);
                }

                @Override
                public void visit(UseStatement use) {
                    schema = use.getName();
                }

                @Override
                public void visit(ShowStatement show) {
                    queries.setShowTarget(show.getName());
                }

                @Override
                public void visit(CreateIndex createIndex) {
                    createIndex.getIndex().getColumnsNames();
                    String columnName = createIndex.getIndex().getColumnsNames().get(0); // todo : assert if length is not  1
                    set = stripQuotes(createIndex.getTable().getName());
                    String fullIndexName = format("%s.%s.%s.%s.%s", createIndex.getIndex().getType(), schema, set, columnName, createIndex.getIndex().getName());
                    indexes.add(fullIndexName);
                }

                @Override
                public void visit(Drop drop) {
                    set = drop.getName().getSchemaName();
                    String fullIndexName = format("%s.%s.%s", schema, set, stripQuotes(drop.getName().getName()));
                    indexes.add(fullIndexName);
                }
            });

            return queries;
        } catch (JSQLParserException e) {
            String msg = e.getCause() == null ? e.getMessage() : e.getCause().getMessage();
            throw new SQLException(msg, e);
        }
    }



    private void createSelect(SelectBody selectBody, QueryHolder queries) {
        AtomicReference<Class> lastValueType = new AtomicReference<>();

        selectBody.accept(new SelectVisitorAdapter() {
            @Override
            public void visit(SetOperationList setOpList) {
                if (setOpList.getOffset() != null) {
                    queries.setOffset(setOpList.getOffset().getOffset());
                }
                if (setOpList.getLimit() != null && setOpList.getLimit().getRowCount() != null) {
                    setOpList.getLimit().getRowCount().accept(new ExpressionVisitorAdapter() {
                        @Override
                        public void visit(LongValue value) {
                            queries.setLimit(value.getValue());
                        }
                    });
                }
                if (setOpList.getOrderByElements() != null) {
                    setOpList.getOrderByElements().stream().map(o -> new OrderItem(o.getExpression().toString(), o.isAsc() ? ASC :DESC)).forEach(queries::addOrdering);
                }
                List<SetOperation> operations = setOpList.getOperations();
                List<ChainOperation> chainOperations = operations.stream()
                        .map(o -> {
                            if (!(o instanceof UnionOp)) {
                                SneakyThrower.sneakyThrow(new SQLException(format("Unsupported chain operation %s. This version supports UNION and UNION ALL only", o.toString())));
                            }
                            return (UnionOp) o;
                        })
                        .map(union -> union.isAll() ? ChainOperation.UNION_ALL : ChainOperation.UNION)
                        .collect(Collectors.toList());
                chainOperations.add(chainOperations.get(chainOperations.size() - 1));

                List<SelectBody> selects = setOpList.getSelects();
                int n = selects.size();
                for (int i = 0; i < n; i++) {
                    createSelect(selects.get(i), queries.addSubQuery(chainOperations.get(i)));
                }
            }

            @Override
            public void visit(PlainSelect plainSelect) {
                if (plainSelect.getFromItem() != null) {
                    plainSelect.getFromItem().accept(new FromItemVisitorAdapter() {
                             @Override
                             public void visit(Table tableName) {
                                 if (tableName.getSchemaName() != null) {
                                     queries.setSchema(tableName.getSchemaName());
                                 }
                                 queries.setSetName(stripQuotes(tableName.getName()), ofNullable(tableName.getAlias()).map(a -> stripQuotes(a.getName())).orElse(null));
                                 set = stripQuotes(tableName.getName());
                             }

                             @Override
                             public void visit(SubSelect subSelect) {
                                 createSelect(subSelect.getSelectBody(), queries.addSubQuery(ChainOperation.SUB_QUERY));
                             }
                         }
                    );
                }


                plainSelect.getSelectItems().forEach(si -> si.accept(new SelectItemVisitorAdapter() {
                    @Override
                    public void visit(SelectExpressionItem selectExpressionItem) {
                        String alias = ofNullable(selectExpressionItem.getAlias()).map(Alias::getName).orElse(null);
                        Expression expr = selectExpressionItem.getExpression();
                        Object selector = plainSelect.getDistinct() != null ? "distinct" + expr : expr; //TODO: ugly patch.
                        queries.getColumnType(selector).addColumn(expr, alias, true, queries.getSchema(), queries.getSetName());
                    }
                }));


                if (plainSelect.getJoins() != null) {
                    for (Join join : plainSelect.getJoins()) {
                        QueryHolder currentJoin = queries.addJoin(JoinType.skipIfMissing(JoinType.getTypes(join)));

                        FromItem joinFrom = join.getRightItem();
                        if (joinFrom != null) {
                            if (joinFrom instanceof Table) {
                                joinFrom.accept(new FromItemVisitorAdapter() {
                                    @Override
                                    public void visit(Table tableName) {
                                        if (tableName.getSchemaName() != null) {
                                            currentJoin.setSchema(stripQuotes(tableName.getSchemaName()));
                                        }
                                        String joinedSetAlias = ofNullable(tableName.getAlias()).map(a -> stripQuotes(a.getName())).orElse(null);
                                        updateJoinedQuery(queries, currentJoin, stripQuotes(tableName.getName()), joinedSetAlias);
                                    }
                                });
                            } else if (joinFrom instanceof SubSelect) {
                                SubSelect subSelect = (SubSelect)joinFrom;
                                createSelect(subSelect.getSelectBody(), currentJoin);
                                String joinedSetAlias = subSelect.getAlias().getName();
                                updateJoinedQuery(queries, currentJoin, currentJoin.getSetName(), joinedSetAlias);
                            }
                        }

                        join.getOnExpression().accept(new ExpressionVisitorAdapter() {
                            @Override
                            protected void visitBinaryExpression(BinaryExpression expr) {
                                super.visitBinaryExpression(expr);
                                //System.out.println("join visitBinaryExpression " + expr);
                                Optional<Operator> operator = Operator.find(expr.getStringExpression());

                                if (!operator.map(Operator.EQ::equals).orElse(false)) {
                                    SneakyThrower.sneakyThrow(new SQLException(format("Join condition must use = only but was %s", operator.map(Operator::operator).orElse("N/A"))));
                                }
                                currentJoin.addPredExp(new OperatorRefPredExp(expr.getStringExpression()));
                            }

                            @Override
                            public void visit(Column column) {
                                //System.out.println("join visit(Column column): " + column);
                                String table = stripQuotes(column.getTable().getName());
                                String columnName = stripQuotes(column.getColumnName());
                                if (Objects.equals(queries.getSetName(), table) || Objects.equals(queries.getSetAlias(), table)) {
                                    queries.getColumnType(column).addColumn(column, null, false, null, null);
                                    currentJoin.addPredExp(new ValueRefPredExp(table, columnName));
                                } else if (Objects.equals(currentJoin.getSetName(), table) || Objects.equals(currentJoin.getSetAlias(), table)) {
                                    currentJoin.getColumnType(column).addColumn(column, null, false, null, null);
                                    currentJoin.addPredExp(new ColumnRefPredExp(table, columnName));
                                }
                            }
                        });


                        join.getUsingColumns();
                    }
                }



                Expression where = plainSelect.getWhere();
                // Between is not supported by predicates and has to be transformed to expression like filed >= lowerValue and field <= highValue.
                // In terms of predicates additional "stringBin" and "and" predicates must be added. This is implemented using the following variables.
                AtomicBoolean between = new AtomicBoolean(false);
                AtomicInteger betweenEdge = new AtomicInteger(0);
                AtomicBoolean in = new AtomicBoolean(false);
                if (where != null) {
                    String whereExpression = where.toString();
                    //TODO: this regex does not include parentheses because they conflict with "in (1, 2, 3)", so I have to find a way to safely detect composite mathematical expressions and function calls in where clause.
                    if (Pattern.compile("[-+*/]").matcher(whereExpression).find()) {
                        queries.setWhereExpression(whereExpression);
                    }
                    AtomicBoolean predExpsEmpty = new AtomicBoolean(true);
                    BinaryOperation operation = new BinaryOperation();
                    AtomicBoolean ignoreNextOp = new AtomicBoolean(false);
                    AtomicBoolean isPreparedStatement = new AtomicBoolean(false);
                    where.accept(new ExpressionVisitorAdapter() {
                        @Override
                        public void visit(Between expr) {
                            //System.out.println("visitBinaryExpression " + expr + " START");
                            between.set(true);
                            super.visit(expr);
                            Operator.BETWEEN.update(queries, operation);
                            //System.out.println("visitBinaryExpression " + expr + " END");
                            queries.queries(operation.getTable()).addPredExp(predExpOperators.get(operatorKey(lastValueType.get(), "AND")).get());
                            between.set(false);
                            operation.clear();
                        }

                        public void visit(InExpression expr) {
                            //System.out.println("visit(InExpression expr) " + expr);
                            in.set(true);
                            super.visit(expr);
                            AtomicBoolean inExpression = new AtomicBoolean(false);

                            expr.getRightItemsList().accept(new ItemsListVisitorAdapter() {
                                @Override
                                public void visit(ExpressionList expressionList) {
                                    //System.out.println("visit(ExpressionList expressionList) " + expressionList);
                                    inExpression.set(true);
                                }
                                @Override
                                public void visit(SubSelect subSelect) {
                                    //System.out.println("visit(SubSelect subSelect) " + subSelect);
                                    QueryHolder subHolder = new QueryHolder(schema, indexes, policyProvider, functionManager);
                                    SelectBody selectBody = subSelect.getSelectBody();
                                    createSelect(selectBody, subHolder);
                                    operation.addValue(subHolder);
                                }

                            });
                            if (!isPreparedStatement.get() || !"PK".equals(operation.getColumn())) {
                                Operator.IN.update(queries, operation);
                            }
                            if ((!inExpression.get() || isPreparedStatement.get()) && "PK".equals(operation.getColumn())) {
                                queries.queries(operation.getTable()).addPredExp(new OperatorRefPredExp("IN"));
                            }



                            in.set(false);
                        }


                        @Override
                        protected void visitBinaryExpression(BinaryExpression expr) {
                            super.visitBinaryExpression(expr);
                            //System.out.println("visitBinaryExpression " + expr);
                            Optional<Operator> operator = Operator.find(expr.getStringExpression());
                            String op = expr.getStringExpression();

                            expr.getRightExpression().accept(new ExpressionVisitorAdapter() {
                                public void visit(SubSelect subSelect) {
                                    QueryHolder subHolder = new QueryHolder(schema, indexes, policyProvider, functionManager);
                                    SelectBody selectBody = subSelect.getSelectBody();
                                    createSelect(selectBody, subHolder);
                                    operation.addValue(subHolder);
                                    queries.queries(operation.getTable()).addPredExp(PredExp.integerBin(operation.getColumn()));
                                    queries.queries(operation.getTable()).addPredExp(new InnerQueryPredExp(1, subHolder)); // TODO: add normal suport for index (it is 1 here)
                                }
                            });


                            // [ is needed to support access to fields of serialized objects.
                            // TODO: this code does not work now for external select with where statement that does not use computation of access to object fields
                            if (!operator.isPresent() || (operator.get().doesRequireColumn() && operation.getColumn() == null) || whereExpression.contains("[")) {
                                queries.queries(operation.getTable()).removeLastPredicates(4);
                                ignoreNextOp.set(true);
                                queries.setWhereExpression(whereExpression);
                            } else {
                                if (ignoreNextOp.get() && ("AND".equals(op) || "OR".equals(op))) {
                                    ignoreNextOp.set(false);
                                } else {
                                    operator.get().update(queries, operation);
                                    queries.queries(operation.getTable()).addPredExp(predExpOperators.get(operatorKey(lastValueType.get(), op)).get());
                                }
                            }

                            operation.clear();
                        }


                        @Override
                        public void visit(Column column) {
                            //System.out.println("visit(Column column): " + column);
                            if (operation.getColumn() == null) {
                                String table = ofNullable(column.getTable()).map(t -> stripQuotes(t.getName())).orElse(null);
                                String name = stripQuotes(column.getColumnName());
                                if (table == null) {
                                    // assume name is actually alias and try to retrieve real table and column name
                                    Optional<DataColumn> dc = queries.getColumnByAlias(name);
                                    if (dc.isPresent()) {
                                        table = dc.get().getTable();
                                        name = dc.get().getName();
                                    }
                                }
                                operation.setStatement(statement);
                                operation.setTable(table);
                                operation.setColumn(name);
                                predExpsEmpty.set(queries.queries(operation.getTable()).getPredExps().isEmpty());
                            } else {
                                operation.addValue(stripQuotes(column.getColumnName()));
                                lastValueType.set(String.class);
                                queries.queries(operation.getTable()).addPredExp(PredExp.stringBin(operation.getColumn()));
                                queries.queries(operation.getTable()).addPredExp(PredExp.stringValue(column.getColumnName()));
                            }
                        }

                        @Override
                        public void visit(LongValue value) {
                            //System.out.println("visit(LongValue value): " + value);
                            operation.addValue(value.getValue());
                            if (in.get()) {
                                return;
                            }
                            queries.queries(operation.getTable()).addPredExp(PredExp.integerBin(operation.getColumn()));
                            queries.queries(operation.getTable()).addPredExp(PredExp.integerValue(value.getValue()));
                            lastValueType.set(Long.class);
                            if (between.get()) {
                                int edge = betweenEdge.incrementAndGet();
                                switch (edge) {
                                    case 1: queries.queries(operation.getTable()).addPredExp(PredExp.integerGreaterEq()); break;
                                    case 2: queries.queries(operation.getTable()).addPredExp(PredExp.integerLessEq()); break;
                                    default: SneakyThrower.sneakyThrow(new SQLException("BETWEEN with more than 2 edges"));
                                }
                            }
                        }

                        @Override
                        public void visit(StringValue value) {
                            //System.out.println("visit(StringValue value): " + value);
                            operation.addValue(value.getValue());
                            if (in.get()) {
                                return;
                            }
                            queries.queries(operation.getTable()).addPredExp(PredExp.stringBin(operation.getColumn()));
                            queries.queries(operation.getTable()).addPredExp(PredExp.stringValue(value.getValue()));
                            lastValueType.set(String.class);
                        }

                        @Override
                        public void visit(DoubleValue value) {
                            //System.out.println("visit(DoubleValue value): " + value);
                            SneakyThrower.sneakyThrow(new SQLException("BETWEEN can be applied to integer values only"));
                        }

                        @Override
                        public void visit(JdbcParameter parameter) {
                            //System.out.println("visit(JdbcParameter parameter): " + parameter);
                            queries.queries(operation.getTable()).addPredExp(new ColumnRefPredExp(set, operation.getColumn()));
                            queries.queries(operation.getTable()).addPredExp(new PredExpValuePlaceholder(parameter.getIndex()));
                            isPreparedStatement.set(true);
                        }

                        @Override
                        public void visit(net.sf.jsqlparser.expression.Function function) {
                            SneakyThrower.call(() -> operation.addValue(engine.eval(function.toString())));
                        }
                    });

                    if (!predExpsEmpty.get()) {
                        List<PredExp> predExps = queries.queries(operation.getTable()).getPredExps();
                        if (!predExps.isEmpty() && !"AndOr".equals(predExps.get(predExps.size() - 1).getClass().getSimpleName())) {
                            queries.queries(operation.getTable()).addPredExp(PredExp.and(2));
                        }
                    }
                }


                if (plainSelect.getOrderByElements() != null) {
                    plainSelect.getOrderByElements().stream().map(o -> new OrderItem(o.getExpression().toString(), o.isAsc() ? ASC :DESC)).forEach(queries::addOrdering);
                }

                if (plainSelect.getGroupBy() != null) {
                    plainSelect.getGroupBy().getGroupByExpressions().forEach(e -> queries.addGroupField(stripQuotes(((Column) e).getColumnName())));
                }

                if (plainSelect.getHaving() != null) {
                    queries.setHaving(plainSelect.getHaving().toString());
                }

                if (plainSelect.getOffset() != null) {
                    queries.setOffset(plainSelect.getOffset().getOffset());
                }

                if (plainSelect.getLimit() != null && plainSelect.getLimit().getRowCount() != null) {
                    plainSelect.getLimit().getRowCount().accept(new ExpressionVisitorAdapter() {
                        @Override
                        public void visit(LongValue value) {
                            queries.setLimit(value.getValue());
                        }
                    });
                }
            }
        });
    }

    private void updateJoinedQuery(QueryHolder queries, QueryHolder currentJoin, String table, String alias) {
        currentJoin.setSetName(table, alias);
        if (alias != null) {
            queries.copyColumnsForTable(alias, currentJoin);
        }
    }

    @VisibleForPackage
    Function<IAerospikeClient, Integer> createUpdate(Statement statement, String sql) throws SQLException {
        Object[] values = statement instanceof AerospikePreparedStatement ? ((AerospikePreparedStatement)statement).getParameterValues() : new Object[0];
        return createUpdatePlan(sql, values).getQuery(statement);
    }


    private QueryContainer<Integer> createUpdatePlan(String sql, Object[] parameterValues) throws SQLException {
        try {
            AtomicInteger limit = new AtomicInteger(-1);
            AtomicReference<String> tableName = new AtomicReference<>(null);
            AtomicReference<String> schema = new AtomicReference<>(AerospikeQueryFactory.this.schema);
            AtomicReference<String> whereExpr = new AtomicReference<>(null);

            AtomicReference<Predicate<Key>> keyPredicate = new AtomicReference<>(key -> false);
            AtomicReference<Predicate<Record>> recordPredicate = new AtomicReference<>(key -> true);
            AtomicBoolean useWhereRecord = new AtomicBoolean(false);
            AtomicBoolean filterByPk = new AtomicBoolean(false);

            WritePolicy writePolicy = new WritePolicy();

            AtomicReference<BiFunction<IAerospikeClient, Entry<Key, Record>, Boolean>> worker = new AtomicReference<>((c, e) -> false);

            AtomicBoolean truncateFlag = new AtomicBoolean(false);

            Pattern truncateWithDate = Pattern.compile("(truncate\\s+table\\s+\\S+)\\s+'(\\S+)'", Pattern.CASE_INSENSITIVE);
            Matcher m = truncateWithDate.matcher(sql);
            final Calendar truncateCalendar;
            if (m.find()) {
                sql = m.group(1);
                truncateCalendar = Calendar.getInstance();
                truncateCalendar.setTime(DateParser.date(m.group(2)));
            } else {
                truncateCalendar = null;
            }

            parserManager.parse(new StringReader(sql)).accept(new StatementVisitorAdapter() {
                @Override
                public void visit(Delete delete) {
                    Table table = delete.getTable();
                    if (table != null) {
                        tableName.set(stripQuotes(table.getName()));
                        if (table.getSchemaName() != null) {
                            schema.set(stripQuotes(table.getSchemaName()));
                        }
                    }

                    initLimit(delete.getLimit(), limit);
                    Expression where = delete.getWhere();
                    if (where != null) {
                        WhereVisitor whereVisitor = new WhereVisitor(AerospikeQueryFactory.this.schema, tableName.get(), Arrays.asList(parameterValues));
                        where.accept(whereVisitor);
                        useWhereRecord.set(whereVisitor.useWhereRecord);
                        keyPredicate.set(whereVisitor.keyPredicate);
                        filterByPk.set(whereVisitor.filterByPk);
                        whereExpr.set(delete.getWhere().toString());
                    }

                    BiFunction<IAerospikeClient, Entry<Key, Record>, Boolean> deleter = (client, kr) -> client.delete(writePolicy, kr.getKey());
                    worker.set(deleter);
                }


                @Override
                public void visit(Update update) {
                    Table table = update.getTable();
                    tableName.set(stripQuotes(table.getName()));
                    if (table.getSchemaName() != null) {
                        schema.set(stripQuotes(table.getSchemaName()));
                    }

                    List<String> columns = new ArrayList<>();
                    ExpressionVisitorAdapter columnNamesVisitor = new ExpressionVisitorAdapter() {
                        @Override
                        public void visit(Column column) {
                            columns.add(stripQuotes(column.getColumnName()));
                        }
                    };
                    update.getColumns().forEach(c -> c.accept(columnNamesVisitor));

                    List<Function<Record, Object>> valueSuppliers = new ArrayList<>();


                    for (Expression expression : update.getExpressions()) {
                        // If set statement uses any element of expression ()+-*/ or function call - treat it as expression.
                        // Otherwise treat it either as column reference or simple value.
                        Function<Record, Object> evaluator = new RecordExpressionEvaluator(expression.toString(), Collections.emptyMap(), functionManager, policyProvider.getDriverPolicy());
                        AtomicReference<Function<Record, Object>> extractorRef = new AtomicReference<>();

                        expression.accept(new ExpressionVisitorAdapter() {
                            // Expression related visitor
                            @Override
                            public void visit(net.sf.jsqlparser.expression.Function function) {
                                extractorRef.set(evaluator);
                            }

                            @Override
                            public void visit(Subtraction expr) {
                                extractorRef.set(evaluator);
                            }

                            @Override
                            public void visit(Addition expr) {
                                extractorRef.set(evaluator);
                            }

                            @Override
                            public void visit(Multiplication expr) {
                                extractorRef.set(evaluator);
                            }

                            @Override
                            public void visit(Division expr) {
                                extractorRef.set(evaluator);
                            }

                            @Override
                            public void visit(Parenthesis parenthesis) {
                                extractorRef.set(evaluator);
                            }

                            @Override
                            public void visit(SignedExpression expr) {
                                extractorRef.set(evaluator);
                            }

                            // Column and value visitors

                            @Override
                            public void visit(NullValue value) {
                                extractorRef.compareAndSet(null, record -> null);
                            }

                            @Override
                            public void visit(StringValue value) {
                                extractorRef.compareAndSet(null, record -> value.getValue());
                            }

                            @Override
                            public void visit(Column column) {
                                extractorRef.compareAndSet(null, record -> record.getValue(stripQuotes(column.getColumnName())));
                            }

                            @Override
                            public void visit(DoubleValue value) {
                                extractorRef.compareAndSet(null, record -> value.getValue());
                            }

                            @Override
                            public void visit(LongValue value) {
                                extractorRef.compareAndSet(null, record -> value.getValue());
                            }

                            @Override
                            public void visit(JdbcParameter parameter) {
                                extractorRef.compareAndSet(null, record -> parameterValues[parameter.getIndex() - 1]);
                            }
                        });

                        valueSuppliers.add(extractorRef.get());
                    }

                    Map<String, Function<Record, Object>> columnValueSuppliers =
                            IntStream.range(0, columns.size())
                                    .boxed()
                                    .collect(toMap(columns::get, valueSuppliers::get));

                    BiFunction<IAerospikeClient, Entry<Key, Record>, Boolean> updater = (client, kr) -> {
                        client.put(writePolicy, kr.getKey(), bins(kr.getValue(), columnValueSuppliers));
                        return true;
                    };

                    worker.set(updater);


                    initLimit(update.getLimit(), limit);
                    Expression where = update.getWhere();
                    if (where != null) {
                        WhereVisitor whereVisitor = new WhereVisitor(AerospikeQueryFactory.this.schema, tableName.get(), Arrays.asList(parameterValues));
                        where.accept(whereVisitor);
                        useWhereRecord.set(whereVisitor.useWhereRecord);
                        keyPredicate.set(whereVisitor.keyPredicate);
                        filterByPk.set(whereVisitor.filterByPk);
                        whereExpr.set(update.getWhere().toString());
                    }

                }

                @Override
                public void visit(Truncate truncate) {
                    Table table = truncate.getTable();
                    tableName.set(stripQuotes(table.getName()));
                    if (table.getSchemaName() != null) {
                        schema.set(stripQuotes(table.getSchemaName()));
                    }
                    truncateFlag.set(true);
                }
            });

            if (useWhereRecord.get() && whereExpr.get() != null) {
                int totalParamCount = parseParameters(sql, 0).getValue();
                int whereParamCount = parseParameters(whereExpr.get(), 0).getValue();
                int paramOffset = totalParamCount - whereParamCount;
                Map<String, Object> psValues = IntStream.range(0, parameterValues.length).boxed().filter(i -> parameterValues[i] != null).collect(Collectors.toMap(i -> "" + PS_PLACEHOLDER_PREFIX + (i + 1), i -> parameterValues[i]));
                recordPredicate.set(new RecordExpressionEvaluator(parseParameters(whereExpr.get(), paramOffset).getKey(), psValues, functionManager, policyProvider.getDriverPolicy()));
            } else if (filterByPk.get()) {
                recordPredicate.set(r -> false);
            }


            return new QueryContainer<Integer>() {
                @Override
                public Function<IAerospikeClient, Integer> getQuery(Statement statement) {
                    return client -> {
                        if (truncateFlag.get()) {
                            SneakyThrower.sqlCall(() -> truncate(client, schema.get(), tableName.get(), truncateCalendar));
                            return 0;
                        }
                        AtomicInteger count = new AtomicInteger(0);
                        int limitValue = limit.get();
                        client.scanAll(policyProvider.getScanPolicy(), schema.get(), tableName.get(),
                                (key, record) -> {
                                    if ((limitValue < 0 || count.get() < limitValue) && (keyPredicate.get().test(key) || recordPredicate.get().test(record))) {
                                        worker.get().apply(client, singletonMap(key, record).entrySet().iterator().next());
                                        count.incrementAndGet();
                                    }
                                });
                        return count.get();
                    };
                }

                @Override
                public void setParameters(Statement statement, Object... parameters) {
                    //TODO: update parameter values in keyPredicate and recordPredicate
                }

                @Override
                public List<DataColumn> getRequestedColumns() {
                    throw new IllegalStateException(); // this method should not be called here
                }

                @Override
                public List<DataColumn> getFilteredColumns() {
                    throw new UnsupportedOperationException(); //TODO: IMPLEMENT THIS!
                }

                @Override
                public String getSetName() {
                    return null;
                }
            };
        } catch (JSQLParserException e) {
            String msg = e.getCause() == null ? e.getMessage() : e.getCause().getMessage();
            throw new SQLException(msg, e);
        }
    }

    private void truncate(IAerospikeClient client, String schema, String tableName, Calendar calendar) throws SQLException {
        boolean tableExists = false;
        ResultSet rs = statement.getConnection().getMetaData().getTables(schema, null, tableName, null);
        while(rs.next()) {
            String table = rs.getString("TABLE_NAME");
            if (tableName.equals(table)) {
                tableExists = true;
                break;
            }
        }
        if(!tableExists) {
            SneakyThrower.sneakyThrow(new SQLException(format("Table %s.%s doesn't exist", schema, tableName)));
        }
        client.truncate(policyProvider.getInfoPolicy(), schema, tableName, calendar);

    }

    private void initLimit(Limit statementLimit, AtomicInteger limit) {
        if (statementLimit != null) {
            if (statementLimit.getRowCount() != null) {
                statementLimit.getRowCount().accept(new ExpressionVisitorAdapter() {
                    @Override
                    public void visit(LongValue value) {
                        limit.set((int) value.getValue());
                    }
                });
            }
        }
    }

    private Bin[] bins(Record record, Map<String, Function<Record, Object>> columnValueSuppliers) {
        return columnValueSuppliers.entrySet().stream().map(e -> new Bin(e.getKey(), e.getValue().apply(record))).toArray(Bin[]::new);
    }

    private static class WhereVisitor extends ExpressionVisitorAdapter {
        private final String tableName;
        private final String schema;
        private final List<Object> parameterValues;

        private Predicate<Key> keyPredicate = key -> false;
        private boolean useWhereRecord = false;
        private boolean filterByPk = false;


        private String column = null;
        private final List<Object> queryValues = new ArrayList<>();


        private WhereVisitor(String schema, String tableName, List<Object> parameterValues) {
            this.schema = schema;
            this.tableName = tableName;
            this.parameterValues = parameterValues;
        }

        public void visit(Between expr) {
            //System.out.println("visit(Between): " + expr);
            super.visit(expr);
            if (!queryValues.isEmpty()) {
                if (queryValues.stream().anyMatch(p -> !isInt(p))) {
                    SneakyThrower.sneakyThrow(new SQLException("BETWEEN can be applied to integer values only"));
                }
                if ("PK".equals(column)) {
                    Collection<Key> keys = LongStream.rangeClosed(((Number)queryValues.get(0)).longValue(), ((Number)queryValues.get(1)).longValue()).boxed().map(i -> new Key(schema, tableName, i)).collect(toSet());
                    keyPredicate = keys::contains;
                    filterByPk = true;
                } else {
                    useWhereRecord = true;
                }
            }
        }

        public void visit(InExpression expr) {
            super.visit(expr);
            //System.out.println("visit(In): " + expr);
            if ("PK".equals(column)) {
                Collection<Key> keys = queryValues.stream().map(v -> createKey(schema, tableName, v)).collect(toSet());
                keyPredicate = keys::contains;
                filterByPk = true;
            } else {
                useWhereRecord = true;
            }
        }
        protected void visitBinaryExpression(BinaryExpression expr) {
            //System.out.println("visitBinaryExpression(BinaryExpression expr): " + expr);
            super.visitBinaryExpression(expr);

            if ("PK".equals(column)) {
                Key condition = createKey(schema, tableName, queryValues.get(0));
                keyPredicate = condition::equals;
                filterByPk = true;
            } else {
                useWhereRecord = true;
            }
        }

        public void visit(Column col) {
            column = stripQuotes(col.getColumnName());
        }
        public void visit(LongValue value) {
            queryValues.add(value.getValue());
        }
        public void visit(StringValue value) {
            queryValues.add(value.getValue());
        }

        @Override
        public void visit(DoubleValue value) {
            queryValues.add(value.getValue());
        }

        public void visit(JdbcParameter parameter) {
            queryValues.add(parameterValues.get(parameter.getIndex() - 1));
        }

    }


    public String getSchema() {
        return schema;
    }

    public Collection<String> getIndexes() {
        return indexes;
    }

    public String getSet() {
        return set;
    }

    public static boolean isInt(Object v) {
        return INT_CLASSES.contains(v.getClass());
    }
}
