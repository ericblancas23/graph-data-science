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
package org.neo4j.gds.embeddings.graphsage.ddl4j.functions;

import org.neo4j.gds.embeddings.graphsage.ddl4j.ComputationContext;
import org.neo4j.gds.embeddings.graphsage.ddl4j.Variable;
import org.neo4j.gds.embeddings.graphsage.ddl4j.tensor.Matrix;
import org.neo4j.gds.embeddings.graphsage.ddl4j.tensor.Tensor;

import static org.neo4j.gds.embeddings.graphsage.ddl4j.Dimensions.COLUMNS_INDEX;
import static org.neo4j.gds.embeddings.graphsage.ddl4j.Dimensions.ROWS_INDEX;

public class Softmax extends SingleParentVariable<Matrix> {

    private final int rows;
    private final int cols;

    public Softmax(Variable<?> parent) {
        super(parent, parent.dimensions());
        rows = dimension(ROWS_INDEX);
        cols = dimension(COLUMNS_INDEX);
    }

    @Override
    public Matrix apply(ComputationContext ctx) {
        var data = (Matrix) ctx.data(parent());
        var result = data.zeros();
        for (int row = 0; row < rows; row++) {
            double rowSum = 0;
            for (int col = 0; col < cols; col++) {
                var exp = Math.exp(data.dataAt(row * cols + col));
                result.setDataAt(row * cols + col, exp);
                rowSum += exp;
            }
            for (int col = 0; col < cols; col++) {
                var current = result.dataAt(row * cols + col);
                result.setDataAt(row * cols + col, current / rowSum);
            }
        }

        return result;
    }

    @Override
    public Tensor<?> gradient(Variable<?> parent, ComputationContext ctx) {
        var selfData = ctx.data(this);
        var selfGradient= ctx.gradient(this);

        var computedGradient = Matrix.fill(0.0, rows, cols);

        // result[row,col] = sum_{col2} s[row, col2] * (delta(col, col2) - s[row, col]) * grad[row, col2]
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                var indexToUpdate = row * cols + col;
                var softmaxData = selfData.dataAt(indexToUpdate);
                for (int softmaxCol = 0; softmaxCol < cols; softmaxCol++) {
                    computedGradient.addDataAt(
                        indexToUpdate,
                        selfData.dataAt(row * cols + softmaxCol) *
                        ((col == softmaxCol ? 1 : 0) - softmaxData) *
                        selfGradient.dataAt(row * cols + softmaxCol)
                    );
                }
            }
        }
        return computedGradient;
    }
}
