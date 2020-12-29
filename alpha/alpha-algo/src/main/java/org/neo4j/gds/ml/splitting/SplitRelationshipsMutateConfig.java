/*
 * Copyright (c) 2017-2020 "Neo4j,"
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
package org.neo4j.gds.ml.splitting;

import org.immutables.value.Value;
import org.neo4j.graphalgo.RelationshipType;
import org.neo4j.graphalgo.annotation.Configuration;
import org.neo4j.graphalgo.annotation.ValueClass;
import org.neo4j.graphalgo.config.AlgoBaseConfig;
import org.neo4j.graphalgo.config.GraphCreateConfig;
import org.neo4j.graphalgo.config.MutateConfig;
import org.neo4j.graphalgo.core.CypherMapWrapper;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;


@ValueClass
@Configuration
@SuppressWarnings("immutables:subtype")
public interface SplitRelationshipsMutateConfig extends AlgoBaseConfig, MutateConfig {

    Optional<Long> randomSeed();

    double holdoutFraction();

    @Configuration.ConvertWith("toRelationshipType")
    @Configuration.ToMapValue("org.neo4j.gds.ml.splitting.SplitRelationshipsMutateConfig#typeToString")
    RelationshipType holdoutRelationshipType();

    @Configuration.ConvertWith("toRelationshipType")
    @Configuration.ToMapValue("org.neo4j.gds.ml.splitting.SplitRelationshipsMutateConfig#typeToString")
    RelationshipType remainingRelationshipType();

    @Value.Default
    default List<String> nonNegativeRelationshipTypes() {
        return List.of();
    }

    @Configuration.Ignore
    @Value.Derived
    default List<RelationshipType> superGraphTypes() {
        return Stream.concat(nonNegativeRelationshipTypes()
            .stream(), relationshipTypes().stream())
            .map(RelationshipType::of)
            .collect(Collectors.toList());
    }

    static RelationshipType toRelationshipType(String type) {
        return RelationshipType.of(type);
    }

    static String typeToString(RelationshipType type) {
        return type.name();
    }

    //TODO: should this actually be a non-optional field, but then can we would have to put it in the
    // config map or go outside of the default procedure syntax
    @Value.Check
    default void validate() {
        if (graphName().isEmpty()) {
            throw new IllegalArgumentException("SplitRelationships only supports execution on named graph.");
        }

    }

    static SplitRelationshipsMutateConfig of(
        String username,
        Optional<String> graphName,
        Optional<GraphCreateConfig> maybeImplicitCreate,
        CypherMapWrapper userInput
    ) {
        return new SplitRelationshipsMutateConfigImpl(
            graphName,
            maybeImplicitCreate,
            username,
            userInput
        );
    }
}
