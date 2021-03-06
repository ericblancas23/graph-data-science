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
package org.neo4j.gds.ml.nodemodels.multiclasslogisticregression;

import org.neo4j.gds.embeddings.graphsage.ddl4j.Variable;
import org.neo4j.gds.embeddings.graphsage.ddl4j.functions.ConstantScale;
import org.neo4j.gds.embeddings.graphsage.ddl4j.functions.ElementSum;
import org.neo4j.gds.embeddings.graphsage.ddl4j.functions.L2NormSquared;
import org.neo4j.gds.embeddings.graphsage.ddl4j.functions.MatrixConstant;
import org.neo4j.gds.embeddings.graphsage.ddl4j.functions.MultiClassCrossEntropyLoss;
import org.neo4j.gds.embeddings.graphsage.ddl4j.functions.Weights;
import org.neo4j.gds.embeddings.graphsage.ddl4j.tensor.Matrix;
import org.neo4j.gds.embeddings.graphsage.ddl4j.tensor.Scalar;
import org.neo4j.gds.embeddings.graphsage.ddl4j.tensor.Tensor;
import org.neo4j.gds.embeddings.graphsage.subgraph.LocalIdMap;
import org.neo4j.gds.ml.Objective;
import org.neo4j.gds.ml.batch.Batch;
import org.neo4j.gds.ml.features.BiasFeature;
import org.neo4j.gds.ml.features.FeatureExtraction;
import org.neo4j.graphalgo.api.Graph;

import java.util.Collection;
import java.util.List;
import java.util.TreeSet;

public class MultiClassNLRObjective implements Objective<MultiClassNLRData> {

    private final String targetPropertyKey;
    private final Graph graph;
    private final double penalty;
    private final MultiClassNLRPredictor predictor;

    public MultiClassNLRObjective(
        List<String> featureProperties,
        String targetPropertyKey,
        Graph graph,
        double penalty
    ) {
        this.predictor = new MultiClassNLRPredictor(makeData(
            featureProperties,
            targetPropertyKey,
            graph
        ), featureProperties);
        this.targetPropertyKey = targetPropertyKey;
        this.graph = graph;
        this.penalty = penalty;
    }

    private static MultiClassNLRData makeData(
        Collection<String> featureProperties,
        String targetPropertyKey,
        Graph graph
    ) {
        var classIdMap = makeClassIdMap(graph, targetPropertyKey);
        var weights = initWeights(graph, classIdMap.size(), featureProperties);
        return MultiClassNLRData.builder()
            .classIdMap(classIdMap)
            .weights(weights)
            .build();
    }

    private static LocalIdMap makeClassIdMap(Graph graph, String targetPropertyKey) {
        var classSet = new TreeSet<Long>();
        var classIdMap = new LocalIdMap();
        graph.forEachNode(nodeId -> {
            classSet.add(graph.nodeProperties(targetPropertyKey).longValue(nodeId));
            return true;
        });
        classSet.forEach(classIdMap::toMapped);
        return classIdMap;
    }

    private static Weights<Matrix> initWeights(Graph graph, int numberOfClasses, Collection<String> featureProperties) {
        var featureExtractors = FeatureExtraction.propertyExtractors(graph, featureProperties);
        featureExtractors.add(new BiasFeature());
        var featuresPerClass = FeatureExtraction.featureCount(featureExtractors);
        return new Weights<>(Matrix.fill(0.0, numberOfClasses, featuresPerClass));
    }

    @Override
    public List<Weights<? extends Tensor<?>>> weights() {
        return List.of(modelData().weights());
    }

    @Override
    public Variable<Scalar> loss(Batch batch, long trainSize) {
        var targets = makeTargets(batch);
        var predictions = predictor.predictionsVariable(graph, batch);
        var unpenalizedLoss = new MultiClassCrossEntropyLoss(
            predictions,
            targets
        );
        var penaltyVariable = new ConstantScale<>(new L2NormSquared(modelData().weights()), batch.size() * penalty / trainSize);
        return new ElementSum(List.of(unpenalizedLoss, penaltyVariable));
    }

    private MatrixConstant makeTargets(Batch batch) {
        Iterable<Long> nodeIds = batch.nodeIds();
        int numberOfNodes = batch.size();
        double[] targets = new double[numberOfNodes];
        int nodeOffset = 0;
        var localIdMap = modelData().classIdMap();
        var targetNodeProperty = graph.nodeProperties(targetPropertyKey);
        for (long nodeId : nodeIds) {
            var targetValue = targetNodeProperty.doubleValue(nodeId);
            targets[nodeOffset] = localIdMap.toMapped((long) targetValue);
            nodeOffset++;
        }
        return new MatrixConstant(targets, numberOfNodes, 1);
    }

    @Override
    public MultiClassNLRData modelData() {
        return predictor.modelData();
    }
}
