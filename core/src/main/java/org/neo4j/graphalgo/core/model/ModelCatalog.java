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
package org.neo4j.graphalgo.core.model;

import org.neo4j.graphalgo.config.BaseConfig;
import org.neo4j.graphalgo.config.TrainConfig;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.neo4j.graphalgo.utils.StringFormatting.formatWithLocale;

public final class ModelCatalog {

    private static final Map<String, Model<?, ?>> modelCatalog = new ConcurrentHashMap<>();

    private ModelCatalog() {}

    public static void set(Model<?, ?> model) {
        modelCatalog.put(model.name(), model);
    }

    public static <D, C extends TrainConfig & BaseConfig> Model<D, C> get(String modelName, Class<D> dataClass, Class<C> configClass) {
        Model<?, ?> model = modelCatalog.get(modelName);
        if (model == null) {
            throw new IllegalArgumentException(formatWithLocale("No model with model name `%s` was found.", modelName));
        }

        var data = model.data();
        if (!dataClass.isInstance(data)) {
            throw new IllegalArgumentException(formatWithLocale(
                "The model `%s` has data with different types than expected. " +
                "Expected data type: `%s`, invoked with model data type: `%s`.",
                modelName,
                data.getClass().getName(),
                dataClass.getName()
                ));
        }
        var config = model.trainConfig();
        if (!configClass.isInstance(config)) {
            throw new IllegalArgumentException(formatWithLocale(
                "The model `%s` has a training config with different types than expected. " +
                "Expected train config type: `%s`, invoked with model config type: `%s`.",
                modelName,
                config.getClass().getName(),
                configClass.getName()
                ));
        }

        // We just did the check
        // noinspection unchecked
        return (Model<D, C>) model;
    }

    public static boolean exists(String modelName) {
        return modelCatalog.containsKey(modelName);
    }

    public static Optional<String> type(String modelName) {
        return Optional.ofNullable(modelCatalog.get(modelName))
            .map(Model::algoType);
    }

    public static Model<?, ?> drop(String modelName) {
        Model<?, ?> model = modelCatalog.remove(modelName);
        if (model == null) {
            throw new IllegalArgumentException(formatWithLocale("Model with name `%s` does not exist and can't be removed.", modelName));
        }
        return model;
    }
}