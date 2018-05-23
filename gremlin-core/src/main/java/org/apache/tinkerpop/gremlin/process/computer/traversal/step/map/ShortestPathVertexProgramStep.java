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

package org.apache.tinkerpop.gremlin.process.computer.traversal.step.map;

import org.apache.tinkerpop.gremlin.process.computer.*;
import org.apache.tinkerpop.gremlin.process.computer.search.path.ShortestPathVertexProgram;
import org.apache.tinkerpop.gremlin.process.computer.traversal.TraversalVertexProgram;
import org.apache.tinkerpop.gremlin.process.traversal.*;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.apache.tinkerpop.gremlin.process.traversal.step.*;
import org.apache.tinkerpop.gremlin.process.traversal.step.util.Parameters;
import org.apache.tinkerpop.gremlin.process.traversal.traverser.TraverserRequirement;
import org.apache.tinkerpop.gremlin.process.traversal.traverser.util.TraverserSet;
import org.apache.tinkerpop.gremlin.process.traversal.util.PureTraversal;
import org.apache.tinkerpop.gremlin.structure.Direction;
import org.apache.tinkerpop.gremlin.structure.Edge;
import org.apache.tinkerpop.gremlin.structure.Graph;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.structure.util.StringFactory;
import org.apache.tinkerpop.gremlin.util.Serializer;

import java.io.IOException;
import java.util.*;

/**
 * @author Daniel Kuppitz (http://gremlin.guru)
 */
public final class ShortestPathVertexProgramStep extends VertexProgramStep implements TraversalParent, Configuring {

    private Parameters parameters = new Parameters();
    private PureTraversal<Vertex, ?> targetVertexFilter = ShortestPathVertexProgram.DEFAULT_VERTEX_FILTER_TRAVERSAL.clone();
    private PureTraversal<Vertex, Edge> edgeTraversal = ShortestPathVertexProgram.DEFAULT_EDGE_TRAVERSAL.clone();
    private PureTraversal<Edge, Number> distanceTraversal = ShortestPathVertexProgram.DEFAULT_DISTANCE_TRAVERSAL.clone();
    private Number maxDistance;
    private boolean includeEdges;

    public ShortestPathVertexProgramStep(final Traversal.Admin<?, ?> traversal) {
        super(traversal);
    }

    private void setTargetVertexFilter(final Traversal filterTraversal) {
        this.targetVertexFilter = new PureTraversal<>(this.integrateChild(filterTraversal.asAdmin()));
    }

    private void setEdgeTraversal(final Traversal edgeTraversal) {
        this.edgeTraversal = new PureTraversal<>(this.integrateChild(edgeTraversal.asAdmin()));
    }

    private void setDistanceTraversal(final Traversal distanceTraversal) {
        this.distanceTraversal = new PureTraversal<>(this.integrateChild(distanceTraversal.asAdmin()));
    }

    private void setMaxDistance(final Number maxDistance) {
        this.maxDistance = maxDistance;
    }

    private void setIncludeEdges(final boolean includeEdges) {
        this.includeEdges = includeEdges;
    }

    @Override
    public void configure(final Object... keyValues) {
        if (!ShortestPath.configure(this, (String) keyValues[0], keyValues[1])) {
            this.parameters.set(this, keyValues);
        }
    }

    @Override
    public Parameters getParameters() {
        return parameters;
    }

    @Override
    protected Traverser.Admin<ComputerResult> processNextStart() throws NoSuchElementException {
        return super.processNextStart();
    }

    @SuppressWarnings("unchecked")
    @Override
    public List<Traversal.Admin<?, ?>> getLocalChildren() {
        return Arrays.asList(
                this.targetVertexFilter.get(),
                this.edgeTraversal.get(),
                this.distanceTraversal.get());
    }

    @Override
    public String toString() {
        return StringFactory.stepString(this, this.targetVertexFilter.get(), this.edgeTraversal.get(),
                this.distanceTraversal.get(), this.maxDistance, this.includeEdges, new GraphFilter(this.computer));
    }

    @Override
    public ShortestPathVertexProgram generateProgram(final Graph graph, final Memory memory) {

        final ShortestPathVertexProgram.Builder builder = ShortestPathVertexProgram.build()
                .target(this.targetVertexFilter.getPure())
                .edgeTraversal(this.edgeTraversal.getPure())
                .distanceTraversal(this.distanceTraversal.getPure())
                .maxDistance(this.maxDistance)
                .includeEdges(this.includeEdges);

        //noinspection unchecked
        final PureTraversal pureRootTraversal = new PureTraversal<>(this.traversal);
        Object rootTraversalValue;
        try {
            rootTraversalValue = Base64.getEncoder().encodeToString(Serializer.serializeObject(pureRootTraversal));
        } catch (final IOException ignored) {
            rootTraversalValue = pureRootTraversal;
        }

        builder.configure(
                ProgramVertexProgramStep.ROOT_TRAVERSAL, rootTraversalValue,
                ProgramVertexProgramStep.STEP_ID, this.id);

        if (memory.exists(TraversalVertexProgram.HALTED_TRAVERSERS)) {
            final TraverserSet<?> haltedTraversers = memory.get(TraversalVertexProgram.HALTED_TRAVERSERS);
            if (!haltedTraversers.isEmpty()) {
                Object haltedTraversersValue;
                try {
                    haltedTraversersValue = Base64.getEncoder().encodeToString(Serializer.serializeObject(haltedTraversers));
                } catch (final IOException ignored) {
                    haltedTraversersValue = haltedTraversers;
                }
                builder.configure(TraversalVertexProgram.HALTED_TRAVERSERS, haltedTraversersValue);
            }
        }

        return builder.create(graph);
    }

    @Override
    public Set<TraverserRequirement> getRequirements() {
        return TraversalParent.super.getSelfAndChildRequirements();
    }

    @Override
    public ShortestPathVertexProgramStep clone() {
        final ShortestPathVertexProgramStep clone = (ShortestPathVertexProgramStep) super.clone();
        clone.targetVertexFilter = this.targetVertexFilter.clone();
        clone.edgeTraversal = this.edgeTraversal.clone();
        clone.distanceTraversal = this.distanceTraversal.clone();
        return clone;
    }

    @Override
    public void setTraversal(final Traversal.Admin<?, ?> parentTraversal) {
        super.setTraversal(parentTraversal);
        this.integrateChild(this.targetVertexFilter.get());
        this.integrateChild(this.edgeTraversal.get());
        this.integrateChild(this.distanceTraversal.get());
    }

    @Override
    public int hashCode() {
        return super.hashCode();
    }

    /**
     * Configuration options to be passed to the {@link GraphTraversal#with(String, Object)} step.
     */
    public static class ShortestPath {

        /**
         * Configures the traversal to use to filter the target vertices for all shortest paths.
         */
        public static final String TARGET = Graph.Hidden.hide("tinkerpop.shortestPath.target");

        /**
         * Configures the direction or traversal to use to filter the edges traversed during the shortest path search phase.
         */
        public static final String EDGES = Graph.Hidden.hide("tinkerpop.shortestPath.edges");

        /**
         * Configures the edge property or traversal to use for shortest path distance calculations.
         */
        public static final String DISTANCE = Graph.Hidden.hide("tinkerpop.shortestPath.distance");

        /**
         * Configures the maximum distance for all shortest paths. Any path with a distance greater than the specified
         * value will not be returned.
         */
        public static final String MAX_DISTANCE = Graph.Hidden.hide("tinkerpop.shortestPath.maxDistance");

        /**
         * Configures the inclusion of edges in the shortest path computation result.
         */
        public static final String INCLUDE_EDGES = Graph.Hidden.hide("tinkerpop.shortestPath.includeEdges");

        private static boolean configure(final ShortestPathVertexProgramStep step, final String key, final Object value) {

            if (TARGET.equals(key)) {
                if (value instanceof Traversal) {
                    step.setTargetVertexFilter((Traversal) value);
                    return true;
                }
                else throw new IllegalArgumentException("ShortestPath.TARGET requires a Traversal as its argument");
            }
            else if (EDGES.equals(key)) {
                if (value instanceof Traversal) {
                    step.setEdgeTraversal((Traversal) value);
                    return true;
                }
                else if (value instanceof Direction) {
                    step.setEdgeTraversal(__.toE((Direction) value));
                    return true;
                }
                else throw new IllegalArgumentException(
                        "ShortestPath.EDGES requires a Traversal or a Direction as its argument");
            }
            else if (DISTANCE.equals(key)) {
                if (value instanceof Traversal) {
                    step.setDistanceTraversal((Traversal) value);
                    return true;
                }
                else if (value instanceof String) {
                    // todo: new ElementValueTraversal((String) value)
                    step.setDistanceTraversal(__.values((String) value));
                    return true;
                }
                else throw new IllegalArgumentException(
                        "ShortestPath.DISTANCE requires a Traversal or a property name as its argument");
            }
            else if (MAX_DISTANCE.equals(key)) {
                if (value instanceof Number) {
                    step.setMaxDistance((Number) value);
                    return true;
                }
                else throw new IllegalArgumentException("ShortestPath.MAX_DISTANCE requires a Number as its argument");
            }
            else if (INCLUDE_EDGES.equals(key)) {
                if (value instanceof Boolean) {
                    step.setIncludeEdges((Boolean) value);
                    return true;
                }
                else throw new IllegalArgumentException("ShortestPath.INCLUDE_EDGES requires a Boolean as its argument");
            }
            return false;
        }
    }
}