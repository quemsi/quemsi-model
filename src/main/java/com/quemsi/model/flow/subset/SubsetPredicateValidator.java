package com.quemsi.model.flow.subset;

import java.util.Collection;
import java.util.List;

import com.quemsi.commons.util.Exceptions;
import com.quemsi.commons.util.StringUtils;

import net.sf.jsqlparser.expression.BinaryExpression;
import net.sf.jsqlparser.expression.CaseExpression;
import net.sf.jsqlparser.expression.CastExpression;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.NotExpression;
import net.sf.jsqlparser.expression.Parenthesis;
import net.sf.jsqlparser.expression.SignedExpression;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.conditional.OrExpression;
import net.sf.jsqlparser.expression.operators.relational.Between;
import net.sf.jsqlparser.expression.operators.relational.ExistsExpression;
import net.sf.jsqlparser.expression.operators.relational.ExpressionList;
import net.sf.jsqlparser.expression.operators.relational.InExpression;
import net.sf.jsqlparser.expression.operators.relational.IsNullExpression;
import net.sf.jsqlparser.expression.operators.relational.LikeExpression;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.statement.select.AllColumns;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectItem;
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.statement.select.WithItem;

/**
 * Validates a user-supplied WHERE fragment for subset drivers.
 * The fragment is wrapped by Quemsi as {@code WHERE (<fragment>)} on a single-table query aliased {@code t}.
 * Compatible with JSqlParser 4.9 (Spring Data JPA 3.3.x managed version).
 */
public final class SubsetPredicateValidator {
    public static final String TABLE_ALIAS = "t";

    private SubsetPredicateValidator() {
    }

    public static void validate(String whereFragment) {
        if (StringUtils.isEmptyOrNull(whereFragment)) {
            return;
        }
        String trimmed = whereFragment.trim();
        if (trimmed.contains(";")) {
            throw Exceptions.badRequest("subset-predicate-invalid")
                .withExtra("reason", "semicolons-not-allowed")
                .get();
        }
        Expression expression;
        try {
            expression = CCJSqlParserUtil.parseCondExpression(trimmed, false);
        } catch (Exception e) {
            throw Exceptions.badRequest("subset-predicate-invalid")
                .withExtra("reason", "parse-failed")
                .withExtra("message", e.getMessage())
                .withCause(e)
                .get();
        }
        validateExpression(expression, false);
    }

    private static void validateExpression(Expression expression, boolean insideSubquery) {
        if (expression == null) {
            return;
        }
        if (expression instanceof Parenthesis parenthesis) {
            validateExpression(parenthesis.getExpression(), insideSubquery);
            return;
        }
        if (expression instanceof NotExpression not) {
            validateExpression(not.getExpression(), insideSubquery);
            return;
        }
        if (expression instanceof AndExpression and) {
            validateExpression(and.getLeftExpression(), insideSubquery);
            validateExpression(and.getRightExpression(), insideSubquery);
            return;
        }
        if (expression instanceof OrExpression or) {
            validateExpression(or.getLeftExpression(), insideSubquery);
            validateExpression(or.getRightExpression(), insideSubquery);
            return;
        }
        if (expression instanceof BinaryExpression binary) {
            validateExpression(binary.getLeftExpression(), insideSubquery);
            validateExpression(binary.getRightExpression(), insideSubquery);
            return;
        }
        if (expression instanceof Between between) {
            validateExpression(between.getLeftExpression(), insideSubquery);
            validateExpression(between.getBetweenExpressionStart(), insideSubquery);
            validateExpression(between.getBetweenExpressionEnd(), insideSubquery);
            return;
        }
        if (expression instanceof LikeExpression like) {
            validateExpression(like.getLeftExpression(), insideSubquery);
            validateExpression(like.getRightExpression(), insideSubquery);
            return;
        }
        if (expression instanceof IsNullExpression isNull) {
            validateExpression(isNull.getLeftExpression(), insideSubquery);
            return;
        }
        if (expression instanceof InExpression in) {
            validateExpression(in.getLeftExpression(), insideSubquery);
            validateExpression(in.getRightExpression(), insideSubquery);
            return;
        }
        if (expression instanceof ExpressionList list) {
            validateExpressionList(list, insideSubquery);
            return;
        }
        if (expression instanceof ExistsExpression exists) {
            Expression right = exists.getRightExpression();
            if (right instanceof Select select) {
                validateSelect(select, true);
            } else {
                validateExpression(right, true);
            }
            return;
        }
        if (expression instanceof Function function) {
            if (function.getParameters() != null) {
                validateExpressionList(function.getParameters(), insideSubquery);
            }
            return;
        }
        if (expression instanceof CaseExpression caseExpr) {
            validateExpression(caseExpr.getSwitchExpression(), insideSubquery);
            if (caseExpr.getWhenClauses() != null) {
                for (var when : caseExpr.getWhenClauses()) {
                    validateExpression(when.getWhenExpression(), insideSubquery);
                    validateExpression(when.getThenExpression(), insideSubquery);
                }
            }
            validateExpression(caseExpr.getElseExpression(), insideSubquery);
            return;
        }
        if (expression instanceof CastExpression cast) {
            validateExpression(cast.getLeftExpression(), insideSubquery);
            return;
        }
        if (expression instanceof SignedExpression signed) {
            validateExpression(signed.getExpression(), insideSubquery);
            return;
        }
        if (expression instanceof Select select) {
            validateSelect(select, true);
            return;
        }
        if (expression instanceof Column || isLiteralOrLeaf(expression)) {
            return;
        }
        throw Exceptions.badRequest("subset-predicate-invalid")
            .withExtra("reason", "unsupported-expression")
            .withExtra("type", expression.getClass().getSimpleName())
            .get();
    }

    private static void validateExpressionList(ExpressionList<?> list, boolean insideSubquery) {
        if (list == null) {
            return;
        }
        for (Expression item : list) {
            validateExpression(item, insideSubquery);
        }
    }

    private static boolean isLiteralOrLeaf(Expression expression) {
        String name = expression.getClass().getSimpleName();
        return name.equals("LongValue")
            || name.equals("DoubleValue")
            || name.equals("StringValue")
            || name.equals("NullValue")
            || name.equals("DateValue")
            || name.equals("TimestampValue")
            || name.equals("TimeValue")
            || name.equals("HexValue")
            || name.equals("JdbcParameter")
            || name.equals("JdbcNamedParameter")
            || name.equals("BooleanValue")
            || name.equals("ExtractExpression")
            || name.equals("IntervalExpression")
            || name.equals("AnalyticExpression")
            || name.equals("DateTimeLiteralExpression")
            || name.equals("TimeKeyExpression")
            || name.equals("NextValExpression")
            || name.equals("OracleHierarchicalExpression");
    }

    private static void validateSelect(Select select, boolean insideSubquery) {
        if (select.getWithItemsList() != null) {
            for (WithItem withItem : select.getWithItemsList()) {
                if (withItem.getSelect() != null) {
                    validateSelect(withItem.getSelect(), true);
                }
            }
        }
        if (select instanceof ParenthesedSelect parenthesed) {
            if (parenthesed.getSelect() != null) {
                validateSelect(parenthesed.getSelect(), true);
            }
            return;
        }
        if (select instanceof PlainSelect plain) {
            validatePlainSelect(plain, insideSubquery);
            return;
        }
        if (select instanceof SetOperationList setOps) {
            Collection<?> selects = setOps.getSelects();
            if (selects != null) {
                for (Object nested : selects) {
                    if (nested instanceof Select selectNested) {
                        validateSelect(selectNested, true);
                    }
                }
            }
            return;
        }
        throw Exceptions.badRequest("subset-predicate-invalid")
            .withExtra("reason", "unsupported-select")
            .withExtra("type", select.getClass().getSimpleName())
            .get();
    }

    private static void validatePlainSelect(PlainSelect plain, boolean insideSubquery) {
        if (plain.getJoins() != null && !plain.getJoins().isEmpty() && !insideSubquery) {
            throw Exceptions.badRequest("subset-predicate-invalid")
                .withExtra("reason", "outer-join-not-allowed")
                .get();
        }
        if (plain.getFromItem() instanceof PlainSelect nestedFrom) {
            validatePlainSelect(nestedFrom, true);
        } else if (plain.getFromItem() instanceof Select nestedSelect) {
            validateSelect(nestedSelect, true);
        }
        if (plain.getJoins() != null) {
            plain.getJoins().forEach(join -> {
                if (join.getRightItem() instanceof Select nested) {
                    validateSelect(nested, true);
                }
                if (join.getOnExpressions() != null) {
                    join.getOnExpressions().forEach(on -> validateExpression(on, true));
                }
            });
        }
        List<SelectItem<?>> items = plain.getSelectItems();
        if (items != null) {
            for (SelectItem<?> item : items) {
                Expression expr = item.getExpression();
                if (!(expr instanceof AllColumns)) {
                    validateExpression(expr, insideSubquery);
                }
            }
        }
        validateExpression(plain.getWhere(), insideSubquery);
        validateExpression(plain.getHaving(), insideSubquery);
        if (plain.getOracleHierarchical() != null) {
            throw Exceptions.badRequest("subset-predicate-invalid")
                .withExtra("reason", "hierarchical-query-not-allowed")
                .get();
        }
    }
}
