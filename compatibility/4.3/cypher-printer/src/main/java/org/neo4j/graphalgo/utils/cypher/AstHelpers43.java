/*
 * Copyright (c) "Neo4j"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.graphalgo.utils.cypher;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.neo4j.cypher.internal.expressions.DecimalDoubleLiteral;
import org.neo4j.cypher.internal.expressions.Divide;
import org.neo4j.cypher.internal.expressions.ExplicitParameter;
import org.neo4j.cypher.internal.expressions.Expression;
import org.neo4j.cypher.internal.expressions.False;
import org.neo4j.cypher.internal.expressions.ListLiteral;
import org.neo4j.cypher.internal.expressions.MapExpression;
import org.neo4j.cypher.internal.expressions.PropertyKeyName;
import org.neo4j.cypher.internal.expressions.SignedDecimalIntegerLiteral;
import org.neo4j.cypher.internal.expressions.StringLiteral;
import org.neo4j.cypher.internal.expressions.True;
import org.neo4j.cypher.internal.expressions.Variable;
import org.neo4j.cypher.internal.util.InputPosition;
import org.neo4j.cypher.internal.util.symbols.AnyType;
import scala.Tuple2;
import scala.collection.Seq;

import java.util.Map;

import static org.neo4j.graphalgo.utils.StringFormatting.formatWithLocale;

final class AstHelpers43 {

    private static final InputPosition NO_POS = InputPosition.NONE();

    private static Expression string(@NotNull CharSequence value) {
        return StringLiteral.apply(value.toString(), NO_POS);
    }

    private static Expression parameter(@NotNull String value) {
        return ExplicitParameter.apply(value, AnyType.instance(), NO_POS);
    }

    private static Expression variable(@NotNull String value) {
        return Variable.apply(value, NO_POS);
    }

    private static Expression number(@NotNull Number value) {
        double v = value.doubleValue();
        if (Double.isNaN(v)) {
            return Divide.apply(
                DecimalDoubleLiteral.apply("0.0", InputPosition.NONE()),
                DecimalDoubleLiteral.apply("0.0", InputPosition.NONE()),
                NO_POS
            );
        }
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            return SignedDecimalIntegerLiteral.apply(Long.toString(value.longValue()), NO_POS);
        }
        return DecimalDoubleLiteral.apply(value.toString(), NO_POS);
    }

    private static Expression bool(@NotNull Boolean value) {
        return Boolean.TRUE.equals(value)
            ? True.apply(NO_POS)
            : False.apply(NO_POS);
    }

    private static @Nullable Expression list(@NotNull Iterable<?> values) {
        ScalaListBuilder<Expression> list = new ScalaListBuilder<>();
        for (Object value : values) {
            Expression expression = any(value);
            if (expression != null) {
                list.add(expression);
            }
        }
        Seq<Expression> expressions = list.build();
        if (expressions.nonEmpty()) {
            return ListLiteral.apply(expressions, NO_POS);
        } else {
            return null;
        }
    }

    private static @Nullable Expression map(@NotNull Map<?, ?> values) {
        ScalaListBuilder<Tuple2<PropertyKeyName, Expression>> list = new ScalaListBuilder<>();
        values.forEach((key, value) -> {
            Expression expression = any(value);
            if (expression != null) {
                list.add(ScalaHelpers.<PropertyKeyName, Expression>pair(propertyKey(key), expression));
            }
        });
        Seq<Tuple2<PropertyKeyName, Expression>> entries = list.build();
        if (entries.nonEmpty()) {
            return MapExpression.apply(entries, NO_POS);
        } else {
            return null;
        }
    }

    static @Nullable Expression any(@Nullable Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Expression) {
            return (Expression) value;
        }
        if (value instanceof CharSequence) {
            return string((CharSequence) value);
        }
        if (value instanceof Number) {
            return number((Number) value);
        }
        if (value instanceof Boolean) {
            return bool((Boolean) value);
        }
        if (value instanceof Enum) {
            return string(((Enum<?>) value).name());
        }
        if (value instanceof CypherPrinterApi.CypherParameter) {
            String name = ((CypherPrinterApi.CypherParameter) value).name();
            // 3.5 erroneously throws on an empty string, but we test for that
            //  reintroduce the 3.5 bug to maintain the same "feature"
            name.chars().iterator().nextInt();
            return parameter(name);
        }
        if (value instanceof CypherPrinterApi.CypherVariable) {
            String name = ((CypherPrinterApi.CypherVariable) value).name();
            // 3.5 erroneously throws on an empty string, but we test for that
            //  reintroduce the 3.5 bug to maintain the same "feature"
            name.chars().iterator().nextInt();
            return variable(name);
        }
        if (value instanceof Iterable) {
            return list((Iterable<?>) value);
        }
        if (value instanceof Map) {
            return map((Map<?, ?>) value);
        }
        throw new IllegalArgumentException(formatWithLocale(
            "Unsupported type [%s] of value [%s]",
            value.getClass().getSimpleName(),
            value
        ));
    }

    private static PropertyKeyName propertyKey(Object value) {
        return PropertyKeyName.apply(String.valueOf(value), NO_POS);
    }

    private AstHelpers43() {}
}
