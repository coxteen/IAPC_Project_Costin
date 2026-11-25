package com.photopulse.assistant.filter;

import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;

import java.util.List;

public sealed interface ValidatedMetadataFilter
        permits ValidatedMetadataFilter.Condition, ValidatedMetadataFilter.Compound {

    enum Operator {
        EQ, NE, IN
    }

    enum LogicalCombinator {
        AND, OR
    }

    record Condition(String field, Operator operator, List<String> values) implements ValidatedMetadataFilter {
        public Condition {
            values = List.copyOf(values);
        }
    }

    record Compound(LogicalCombinator combinator, List<ValidatedMetadataFilter> children) implements ValidatedMetadataFilter {
        public Compound {
            children = List.copyOf(children);
        }
    }

    default Filter.Expression toSpringAiExpression() {
        FilterExpressionBuilder b = new FilterExpressionBuilder();
        return toOp(b).build();
    }

    default FilterExpressionBuilder.Op toOp(FilterExpressionBuilder b) {
        if (this instanceof Condition c) {
            return switch (c.operator()) {
                case EQ -> b.eq(c.field(), c.values().getFirst());
                case NE -> b.ne(c.field(), c.values().getFirst());
                case IN -> b.in(c.field(), c.values().toArray());
            };
        }

        Compound compound = (Compound) this;
        List<ValidatedMetadataFilter> children = compound.children();
        FilterExpressionBuilder.Op acc = children.getFirst().toOp(b);

        for (int i = 1; i < children.size(); i++) {
            FilterExpressionBuilder.Op next = children.get(i).toOp(b);
            acc = (compound.combinator() == LogicalCombinator.AND)
                    ? b.and(acc, next)
                    : b.or(acc, next);
        }
        return acc;
    }
}