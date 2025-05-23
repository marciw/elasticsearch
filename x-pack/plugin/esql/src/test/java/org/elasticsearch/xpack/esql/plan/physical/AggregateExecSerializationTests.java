/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.plan.physical;

import org.elasticsearch.compute.aggregation.AggregatorMode;
import org.elasticsearch.xpack.esql.core.expression.Attribute;
import org.elasticsearch.xpack.esql.core.expression.Expression;
import org.elasticsearch.xpack.esql.core.expression.NamedExpression;
import org.elasticsearch.xpack.esql.core.tree.Source;
import org.elasticsearch.xpack.esql.plan.logical.AggregateSerializationTests;

import java.io.IOException;
import java.util.List;

import static org.elasticsearch.xpack.esql.plan.logical.AbstractLogicalPlanSerializationTests.randomFieldAttributes;

public class AggregateExecSerializationTests extends AbstractPhysicalPlanSerializationTests<AggregateExec> {
    public static AggregateExec randomAggregateExec(int depth) {
        Source source = randomSource();
        PhysicalPlan child = randomChild(depth);
        List<Expression> groupings = randomFieldAttributes(0, 5, false).stream().map(a -> (Expression) a).toList();
        List<? extends NamedExpression> aggregates = AggregateSerializationTests.randomAggregates();
        AggregatorMode mode = randomFrom(AggregatorMode.values());
        List<Attribute> intermediateAttributes = randomFieldAttributes(0, 5, false);
        Integer estimatedRowSize = randomEstimatedRowSize();
        if (randomBoolean()) {
            return new AggregateExec(source, child, groupings, aggregates, mode, intermediateAttributes, estimatedRowSize);
        } else {
            return new TimeSeriesAggregateExec(source, child, groupings, aggregates, mode, intermediateAttributes, estimatedRowSize, null);
        }
    }

    @Override
    protected AggregateExec createTestInstance() {
        return randomAggregateExec(0);
    }

    @Override
    protected AggregateExec mutateInstance(AggregateExec instance) throws IOException {
        PhysicalPlan child = instance.child();
        List<? extends Expression> groupings = instance.groupings();
        List<? extends NamedExpression> aggregates = instance.aggregates();
        List<Attribute> intermediateAttributes = instance.intermediateAttributes();
        AggregatorMode mode = instance.getMode();
        Integer estimatedRowSize = instance.estimatedRowSize();
        switch (between(0, 5)) {
            case 0 -> child = randomValueOtherThan(child, () -> randomChild(0));
            case 1 -> groupings = randomValueOtherThan(groupings, () -> randomFieldAttributes(0, 5, false));
            case 2 -> aggregates = randomValueOtherThan(aggregates, AggregateSerializationTests::randomAggregates);
            case 3 -> mode = randomValueOtherThan(mode, () -> randomFrom(AggregatorMode.values()));
            case 4 -> intermediateAttributes = randomValueOtherThan(intermediateAttributes, () -> randomFieldAttributes(0, 5, false));
            case 5 -> estimatedRowSize = randomValueOtherThan(
                estimatedRowSize,
                AbstractPhysicalPlanSerializationTests::randomEstimatedRowSize
            );
            default -> throw new IllegalStateException();
        }
        if (instance instanceof TimeSeriesAggregateExec) {
            return new TimeSeriesAggregateExec(
                instance.source(),
                child,
                groupings,
                aggregates,
                mode,
                intermediateAttributes,
                estimatedRowSize,
                null
            );
        } else {
            return new AggregateExec(instance.source(), child, groupings, aggregates, mode, intermediateAttributes, estimatedRowSize);
        }
    }

    @Override
    protected boolean alwaysEmptySource() {
        return true;
    }
}
