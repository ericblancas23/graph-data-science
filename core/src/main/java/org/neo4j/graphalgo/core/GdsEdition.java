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
package org.neo4j.graphalgo.core;

import org.jetbrains.annotations.TestOnly;
import org.neo4j.graphalgo.utils.CheckedRunnable;

import java.util.Optional;

public final class GdsEdition {

    private static final GdsEdition INSTANCE = new GdsEdition();

    public static GdsEdition instance() {
        return INSTANCE;
    }

    private enum State {
        ENTERPRISE,
        COMMUNITY,
        INVALID_LICENSE
    }

    private State currentState;

    private Optional<String> errorMessage;

    private GdsEdition() {
        this.currentState = State.COMMUNITY;
    }

    public boolean isOnEnterpriseEdition() {
        return get() == State.ENTERPRISE;
    }

    public boolean isOnCommunityEdition() {
        return get() == State.COMMUNITY;
    }

    public boolean isInvalidLicense() {
        return get() == State.INVALID_LICENSE;
    }

    public Optional<String> errorMessage() {
        return errorMessage;
    }

    public void setToEnterpriseEdition() {
        set(State.ENTERPRISE);
        this.errorMessage = Optional.empty();
    }

    public void setToCommunityEdition() {
        set(State.COMMUNITY);
        this.errorMessage = Optional.empty();
    }

    public void setToInvalidLicense(String errorMessage) {
        set(State.INVALID_LICENSE);
        this.errorMessage = Optional.of(errorMessage);
    }

    @TestOnly
    public <E extends Exception> void setToEnterpriseAndRun(CheckedRunnable<E> code) throws E {
        setToStateAndRun(State.ENTERPRISE, code);
    }

    @TestOnly
    public <E extends Exception> void setToCommunityAndRun(CheckedRunnable<E> code) throws E {
        setToStateAndRun(State.COMMUNITY, code);
    }

    @TestOnly
    private synchronized <E extends Exception> void setToStateAndRun(
        State state,
        CheckedRunnable<E> code
    ) throws E {
        var before = get();
        set(state);
        try {
            code.checkedRun();
        } finally {
            set(before);
        }
    }

    private void set(State state) {
        this.currentState = state;
    }

    private State get() {
        return currentState;
    }
}
