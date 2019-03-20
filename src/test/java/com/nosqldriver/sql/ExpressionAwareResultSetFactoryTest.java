package com.nosqldriver.sql;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ExpressionAwareResultSetFactoryTest {
    private final ExpressionAwareResultSetFactory exprFactory = new ExpressionAwareResultSetFactory();

    @Test
    void emptyExpression() {
        getVariableNames("", emptyList());
    }

    @Test
    void intNumberConstant() {
        getVariableNames("1", emptyList());
        getVariableNames("1234", emptyList());
    }

    @Test
    void floatNumber() {
        getVariableNames("3.14", emptyList());
    }

    @Test
    void positiveFloatNumber() {
        getVariableNames("+3.14", emptyList());
    }

    @Test
    void negativeFloatNumber() {
        getVariableNames("+3.14", emptyList());
    }

    @Test
    void numberWithExponent() {
        asList("+6e23", "+6e-23", "+678e+42", "6.022e+23").forEach(n -> getVariableNames(n, emptyList()));
    }

    @Test
    void variableOnly() {
        asList("x", "xyz", "a1").forEach(v -> getVariableNames(v, singletonList(v)));
    }

    @Test
    void numericExpression() {
        asList("1+2", "3 + 4", "(1+2) / 3").forEach(v -> getVariableNames(v, emptyList()));
    }

    @Test
    void numericExpressionWithVariable() {
        asList("x+1", "x * 2").forEach(v -> getVariableNames(v, singletonList("x")));
        asList("factor *= 5", "4 * ( factor + 2)").forEach(v -> getVariableNames(v, singletonList("factor")));
    }


    @Test
    void numericExpressionWithSevaralVariables() {
        asList("x+y", "x * y").forEach(v -> getVariableNames(v, asList("x", "y")));
    }

    @Test
    void numericExpressionWithRepatedVariables() {
        asList("x+x", "x * x").forEach(v -> getVariableNames(v, singletonList("x")));
    }

    @Test
    void functionCall() {
        asList("len(x)", "LEN(x)").forEach(v -> getVariableNames(v, singletonList("x")));
    }


    private void getVariableNames(String expr, List<String> expectedVariables) {
        assertEquals(expectedVariables, new ArrayList<>((exprFactory.getVariableNames(expr))));
    }

}