/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.tinkerpop.gremlin.process.computer.search.path;

import org.apache.tinkerpop.gremlin.process.AbstractGremlinProcessTest;
import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.apache.tinkerpop.gremlin.process.traversal.Path;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.apache.tinkerpop.gremlin.process.traversal.step.util.ImmutablePath;
import org.apache.tinkerpop.gremlin.structure.Edge;
import org.apache.tinkerpop.gremlin.structure.Vertex;

import java.util.Collections;
import java.util.List;

/**
 * @author Daniel Kuppitz (http://gremlin.guru)
 */
public class ShortestPathTestHelper {

    private final AbstractGremlinProcessTest test;
    private final GraphTraversalSource g;

    public ShortestPathTestHelper(final AbstractGremlinProcessTest test, final GraphTraversalSource g) {
        this.test = test;
        this.g = g;
    }

    public void checkResults(final List<Path> expected, final List<Path> actual) {
        AbstractGremlinProcessTest.checkResults(expected, __.inject(actual.toArray(new Path[actual.size()])));
    }

    public Path makePath(final String... names) {
        return makePath(false, names);
    }

    public Path makePath(final boolean includeEdges, final String... names) {
        Path path = ImmutablePath.make();
        boolean first = true;
        for (final String name : names) {
            final Vertex vertex = test.convertToVertex(name);
            if (!first) {
                if (includeEdges) {
                    final Edge edge = g.V(((Vertex) path.get(path.size() - 1)).id())
                            .bothE().filter(__.otherV().is(P.eq(vertex)))
                            .next();
                    path = path.extend(edge, Collections.emptySet());
                }
            }
            path = path.extend(vertex, Collections.emptySet());
            first = false;
        }
        return path;
    }
}
