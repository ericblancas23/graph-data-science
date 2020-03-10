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
package org.neo4j.graphalgo;

import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.neo4j.graphalgo.catalog.GraphCreateProc;
import org.neo4j.graphalgo.compat.GraphDbApi;
import org.neo4j.graphalgo.core.loading.GraphStoreCatalog;
import org.neo4j.graphalgo.pagerank.PageRankStreamProc;
import org.neo4j.graphalgo.pagerank.PageRankWriteProc;
import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.Result;
import org.neo4j.kernel.internal.GraphDatabaseAPI;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.neo4j.graphalgo.compat.GraphDatabaseApiProxy.findNode;
import static org.neo4j.graphalgo.compat.GraphDatabaseApiProxy.runInTransaction;

public class NodeFilterTest extends BaseProcTest {

    @BeforeEach
    void setupGraph() throws Exception {

        db = TestDatabaseCreator.createTestDatabase();

        @Language("Cypher") String cypher =
            "CREATE" +
            "  (a:Label1 {name: 'a'})" +
            ", (b:Label1 {name: 'b'})" +
            ", (c:Label2 {name: 'c'})" +
            ", (d:Label3 {name: 'd'})" +
            ", (e:Label4 {name: 'e'})" +
            ", (f:Label5 {name: 'f'})";

        registerProcedures(PageRankStreamProc.class, GraphCreateProc.class);
        registerFunctions(GetNodeFunc.class);
        runQuery(cypher);

        runQuery("CALL gds.graph.create(" +
                 "'myGraph'," +
                 "{" +
                 "  L1: 'Label1', " +
                 "  L2: 'Label2'," +
                 "  L3: 'Label3'," +
                 "  L4: 'Label4'," +
                 "  L5: 'Label5'" +
                 "}, " +
                 "'*')");
    }

    @AfterEach
    void clearCommunities() {
        db.shutdown();
        GraphStoreCatalog.removeAllLoadedGraphs();
    }

    @Test
    void testFilterLabels() {
        String query = "CALL gds.pageRank.stream('myGraph', { nodeLabels: ['L1', 'L5'] }) " +
                       "YIELD nodeId " +
                       "RETURN gds.util.asNode(nodeId).name AS name";
        Set<String> actual = new HashSet<>();
        runQueryWithRowConsumer(query, row -> {
            actual.add(row.getString("name"));
        });

        Set<String> expected = new HashSet<>();
        expected.add("a");
        expected.add("b");
        expected.add("f");

        assertEquals(expected, actual);
    }

}
