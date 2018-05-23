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

import org.apache.commons.configuration.Configuration;
import org.apache.tinkerpop.gremlin.process.computer.*;
import org.apache.tinkerpop.gremlin.process.computer.traversal.TraversalVertexProgram;
import org.apache.tinkerpop.gremlin.process.computer.traversal.step.map.ProgramVertexProgramStep;
import org.apache.tinkerpop.gremlin.process.computer.traversal.step.map.VertexProgramStep;
import org.apache.tinkerpop.gremlin.process.computer.util.AbstractVertexProgramBuilder;
import org.apache.tinkerpop.gremlin.process.traversal.*;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.apache.tinkerpop.gremlin.process.traversal.lambda.ConstantTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.lambda.ElementValueTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.lambda.IdentityTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.step.util.ImmutablePath;
import org.apache.tinkerpop.gremlin.process.traversal.traverser.util.IndexedTraverserSet;
import org.apache.tinkerpop.gremlin.process.traversal.traverser.util.TraverserSet;
import org.apache.tinkerpop.gremlin.process.traversal.util.PureTraversal;
import org.apache.tinkerpop.gremlin.structure.*;
import org.apache.tinkerpop.gremlin.structure.util.StringFactory;
import org.apache.tinkerpop.gremlin.util.NumberHelper;
import org.javatuples.Pair;
import org.javatuples.Triplet;

import java.util.*;
import java.util.function.Function;

/**
 * @author Daniel Kuppitz (http://gremlin.guru)
 */
public class ShortestPathVertexProgram implements VertexProgram<Triplet<Path, Edge, Number>> {

    public static final String SHORTEST_PATHS = "gremlin.shortestPathVertexProgram.shortestPaths";

    private static final String SOURCE_VERTEX_FILTER = "gremlin.shortestPathVertexProgram.sourceVertexFilter";
    private static final String TARGET_VERTEX_FILTER = "gremlin.shortestPathVertexProgram.targetVertexFilter";
    private static final String EDGE_TRAVERSAL = "gremlin.shortestPathVertexProgram.edgeTraversal";
    private static final String DISTANCE_TRAVERSAL = "gremlin.shortestPathVertexProgram.distanceTraversal";
    private static final String MAX_DISTANCE = "gremlin.shortestPathVertexProgram.maxDistance";
    private static final String INCLUDE_EDGES = "gremlin.shortestPathVertexProgram.includeEdges";

    private static final String STATE = "gremlin.shortestPathVertexProgram.state";
    private static final String PATHS = "gremlin.shortestPathVertexProgram.paths";
    private static final String VOTE_TO_HALT = "gremlin.shortestPathVertexProgram.voteToHalt";

    public static final PureTraversal<Vertex, ?> DEFAULT_VERTEX_FILTER_TRAVERSAL = new PureTraversal<>(new IdentityTraversal<>());
    public static final PureTraversal<Vertex, Edge> DEFAULT_EDGE_TRAVERSAL = new PureTraversal<>(__.bothE().asAdmin());
    public static final PureTraversal<Edge, Number> DEFAULT_DISTANCE_TRAVERSAL = new PureTraversal<>(new ConstantTraversal<>(1));

    private TraverserSet<Vertex> haltedTraversers;
    private IndexedTraverserSet<Vertex, Vertex> haltedTraversersIndex;
    private PureTraversal<?, ?> traversal;
    private PureTraversal<Vertex, ?> sourceVertexFilterTraversal = DEFAULT_VERTEX_FILTER_TRAVERSAL.clone();
    private PureTraversal<Vertex, ?> targetVertexFilterTraversal = DEFAULT_VERTEX_FILTER_TRAVERSAL.clone();
    private PureTraversal<Vertex, Edge> edgeTraversal = DEFAULT_EDGE_TRAVERSAL.clone();
    private PureTraversal<Edge, Number> distanceTraversal = DEFAULT_DISTANCE_TRAVERSAL.clone();
    private Step<Vertex, Path> programStep;
    private Number maxDistance;
    private boolean distanceEqualsNumberOfHops;
    private boolean includeEdges;
    private boolean standalone;

    private static final Set<VertexComputeKey> VERTEX_COMPUTE_KEYS = new HashSet<>(Arrays.asList(
            VertexComputeKey.of(PATHS, true),
            VertexComputeKey.of(TraversalVertexProgram.HALTED_TRAVERSERS, false)));

    private final Set<MemoryComputeKey> memoryComputeKeys = new HashSet<>(Arrays.asList(
            MemoryComputeKey.of(VOTE_TO_HALT, Operator.and, false, true),
            MemoryComputeKey.of(STATE, Operator.assign, true, true)));

    private ShortestPathVertexProgram() {

    }

    @Override
    public void loadState(final Graph graph, final Configuration configuration) {

        if (configuration.containsKey(SOURCE_VERTEX_FILTER))
            this.sourceVertexFilterTraversal = PureTraversal.loadState(configuration, SOURCE_VERTEX_FILTER, graph);

        if (configuration.containsKey(TARGET_VERTEX_FILTER))
            this.targetVertexFilterTraversal = PureTraversal.loadState(configuration, TARGET_VERTEX_FILTER, graph);

        if (configuration.containsKey(EDGE_TRAVERSAL))
            this.edgeTraversal = PureTraversal.loadState(configuration, EDGE_TRAVERSAL, graph);

        if (configuration.containsKey(DISTANCE_TRAVERSAL))
            this.distanceTraversal = PureTraversal.loadState(configuration, DISTANCE_TRAVERSAL, graph);

        if (configuration.containsKey(MAX_DISTANCE))
            this.maxDistance = (Number) configuration.getProperty(MAX_DISTANCE);

        this.distanceEqualsNumberOfHops = this.distanceTraversal.equals(DEFAULT_DISTANCE_TRAVERSAL);
        this.includeEdges = configuration.getBoolean(INCLUDE_EDGES, false);
        this.standalone = !configuration.containsKey(VertexProgramStep.ROOT_TRAVERSAL);

        if (!this.standalone) {
            this.traversal = PureTraversal.loadState(configuration, VertexProgramStep.ROOT_TRAVERSAL, graph);
            final String programStepId = configuration.getString(ProgramVertexProgramStep.STEP_ID);
            for (final Step step : this.traversal.get().getSteps()) {
                if (step.getId().equals(programStepId)) {
                    //noinspection unchecked
                    this.programStep = step;
                    break;
                }
            }
        }

        this.haltedTraversers = TraversalVertexProgram.loadHaltedTraversers(configuration);
        this.memoryComputeKeys.add(MemoryComputeKey.of(SHORTEST_PATHS, Operator.addAll, true, !standalone));
    }

    @Override
    public void storeState(final Configuration configuration) {
        VertexProgram.super.storeState(configuration);
        this.sourceVertexFilterTraversal.storeState(configuration, SOURCE_VERTEX_FILTER);
        this.targetVertexFilterTraversal.storeState(configuration, TARGET_VERTEX_FILTER);
        this.edgeTraversal.storeState(configuration, EDGE_TRAVERSAL);
        this.distanceTraversal.storeState(configuration, DISTANCE_TRAVERSAL);
        configuration.setProperty(INCLUDE_EDGES, this.includeEdges);
        if (this.maxDistance != null)
            configuration.setProperty(MAX_DISTANCE, maxDistance);
        if (this.traversal != null) {
            this.traversal.storeState(configuration, ProgramVertexProgramStep.ROOT_TRAVERSAL);
            configuration.setProperty(ProgramVertexProgramStep.STEP_ID, this.programStep.getId());
        }
    }

    @Override
    public Set<VertexComputeKey> getVertexComputeKeys() {
        return VERTEX_COMPUTE_KEYS;
    }

    @Override
    public Set<MemoryComputeKey> getMemoryComputeKeys() {
        return memoryComputeKeys;
    }

    @Override
    public Set<MessageScope> getMessageScopes(final Memory memory) {
        return Collections.emptySet();
    }

    @Override
    public VertexProgram<Triplet<Path, Edge, Number>> clone() {
        try {
            final ShortestPathVertexProgram clone = (ShortestPathVertexProgram) super.clone();
            if (null != this.edgeTraversal)
                clone.edgeTraversal = this.edgeTraversal.clone();
            if (null != this.sourceVertexFilterTraversal)
                clone.sourceVertexFilterTraversal = this.sourceVertexFilterTraversal.clone();
            if (null != this.targetVertexFilterTraversal)
                clone.targetVertexFilterTraversal = this.targetVertexFilterTraversal.clone();
            if (null != this.distanceTraversal)
                clone.distanceTraversal = this.distanceTraversal.clone();
            if (null != this.traversal) {
                clone.traversal = this.traversal.clone();
                for (final Step step : clone.traversal.get().getSteps()) {
                    if (step.getId().equals(this.programStep.getId())) {
                        //noinspection unchecked
                        clone.programStep = step;
                        break;
                    }
                }
            }
            return clone;
        } catch (final CloneNotSupportedException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    @Override
    public GraphComputer.ResultGraph getPreferredResultGraph() {
        return GraphComputer.ResultGraph.ORIGINAL;
    }

    @Override
    public GraphComputer.Persist getPreferredPersist() {
        return GraphComputer.Persist.NOTHING;
    }

    @Override
    public void setup(final Memory memory) {

        this.haltedTraversersIndex = new IndexedTraverserSet<>(v -> v);
        for (final Traverser.Admin<Vertex> traverser : this.haltedTraversers) {
            this.haltedTraversersIndex.add(traverser.split());
        }
        this.haltedTraversers.clear();

        memory.set(VOTE_TO_HALT, true);
        memory.set(STATE, State.SEARCH);
    }

    @Override
    public void execute(final Vertex vertex, final Messenger<Triplet<Path, Edge, Number>> messenger, final Memory memory) {

        switch (memory.<State>get(STATE)) {

            case COLLECT_PATHS:
                collectShortestPaths(vertex, memory);
                return;

            case UPDATE_HALTED_TRAVERSERS:
                updateHaltedTraversers(vertex, memory);
                return;
        }

        boolean voteToHalt = true;

        if (memory.isInitialIteration()) {

            copyHaltedTraversersFromMemory(vertex);

            if (!isStartVertex(vertex)) return;

            final Map<Vertex, Pair<Number, Set<Path>>> paths = new HashMap<>();
            final Path path;
            final Set<Path> pathSet = new HashSet<>();

            pathSet.add(path = makePath(vertex));
            paths.put(vertex, Pair.with(0, pathSet));

            vertex.property(VertexProperty.Cardinality.single, PATHS, paths);

            processEdges(vertex, path, 0, messenger);

            voteToHalt = false;

        } else {

            final Map<Vertex, Pair<Number, Set<Path>>> paths =
                    vertex.<Map<Vertex, Pair<Number, Set<Path>>>>property(PATHS).orElseGet(HashMap::new);
            final Iterator<Triplet<Path, Edge, Number>> iterator = messenger.receiveMessages();

            while (iterator.hasNext()) {

                final Triplet<Path, Edge, Number> triplet = iterator.next();
                final Path sourcePath = triplet.getValue0();
                final Number distance = triplet.getValue2();
                final Vertex sourceVertex = sourcePath.get(0);

                Path newPath = null;

                if (paths.containsKey(sourceVertex)) {

                    final Number currentShortestDistance = paths.get(sourceVertex).getValue0();
                    final int cmp = NumberHelper.compare(distance, currentShortestDistance);

                    if (cmp <= 0) {
                        newPath = extendPath(sourcePath, triplet.getValue1(), vertex);
                        if (cmp < 0) {
                            final Set<Path> pathSet = new HashSet<>();
                            pathSet.add(newPath);
                            paths.put(sourceVertex, Pair.with(distance, pathSet));
                        } else {
                            paths.get(sourceVertex).getValue1().add(newPath);
                        }
                    }
                } else if (!exceedsMaxDistance(distance)) {
                    final Set<Path> pathSet = new HashSet<>();
                    pathSet.add(newPath = extendPath(sourcePath, triplet.getValue1(), vertex));
                    paths.put(sourceVertex, Pair.with(distance, pathSet));
                }

                if (newPath != null) {
                    vertex.property(VertexProperty.Cardinality.single, PATHS, paths);
                    processEdges(vertex, newPath, distance, messenger);
                    voteToHalt = false;
                }
            }
        }

        memory.add(VOTE_TO_HALT, voteToHalt);
    }

    @Override
    public boolean terminate(final Memory memory) {
        if (memory.isInitialIteration() && this.haltedTraversersIndex != null) {
            this.haltedTraversersIndex.clear();
        }
        final boolean voteToHalt = memory.get(VOTE_TO_HALT);
        if (voteToHalt) {
            final State state = memory.get(STATE);
            if (state.equals(State.COLLECT_PATHS)) {
                if (this.standalone) return true;
                memory.set(STATE, State.UPDATE_HALTED_TRAVERSERS);
                return false;
            }
            if (state.equals(State.UPDATE_HALTED_TRAVERSERS)) return true;
            if (state.equals(State.COLLECT_PATHS)) memory.set(STATE, State.UPDATE_HALTED_TRAVERSERS);
            else memory.set(STATE, State.COLLECT_PATHS);
            return false;
        } else {
            memory.set(VOTE_TO_HALT, true);
            return false;
        }
    }

    @Override
    public String toString() {

        final List<String> options = new ArrayList<>();
        final Function<String, String> shortName = name -> name.substring(name.lastIndexOf(".") + 1);

        if (!this.sourceVertexFilterTraversal.equals(DEFAULT_VERTEX_FILTER_TRAVERSAL)) {
            options.add(shortName.apply(SOURCE_VERTEX_FILTER) + "=" + this.sourceVertexFilterTraversal.get());
        }

        if (!this.targetVertexFilterTraversal.equals(DEFAULT_VERTEX_FILTER_TRAVERSAL)) {
            options.add(shortName.apply(TARGET_VERTEX_FILTER) + "=" + this.targetVertexFilterTraversal.get());
        }

        if (!this.edgeTraversal.equals(DEFAULT_EDGE_TRAVERSAL)) {
            options.add(shortName.apply(EDGE_TRAVERSAL) + "=" + this.edgeTraversal.get());
        }

        if (!this.distanceTraversal.equals(DEFAULT_DISTANCE_TRAVERSAL)) {
            options.add(shortName.apply(DISTANCE_TRAVERSAL) + "=" + this.distanceTraversal.get());
        }

        options.add(shortName.apply(INCLUDE_EDGES) + "=" + this.includeEdges);

        return StringFactory.vertexProgramString(this, String.join(", ", options));
    }

    //////////////////////////////

    private void copyHaltedTraversersFromMemory(final Vertex vertex) {
        final Collection<Traverser.Admin<Vertex>> traversers = this.haltedTraversersIndex.get(vertex);
        if (traversers != null) {
            final TraverserSet<Vertex> newHaltedTraversers = new TraverserSet<>();
            newHaltedTraversers.addAll(traversers);
            vertex.property(VertexProperty.Cardinality.single, TraversalVertexProgram.HALTED_TRAVERSERS, newHaltedTraversers);
        }
    }


    private static Path makePath(final Vertex newVertex) {
        return extendPath(null, newVertex);
    }

    private static Path extendPath(final Path currentPath, final Element... elements) {
        Path result = ImmutablePath.make();
        if (currentPath != null) {
            for (final Object o : currentPath.objects()) {
                result = result.extend(o, Collections.emptySet());
            }
        }
        for (final Element element : elements) {
            if (element != null) result = result.extend(element, Collections.emptySet());
        }
        return result;
    }

    private boolean isStartVertex(final Vertex vertex) {
        if (this.standalone) {
            final Traversal.Admin<Vertex, ?> filterTraversal = this.sourceVertexFilterTraversal.getPure();
            filterTraversal.addStart(filterTraversal.getTraverserGenerator().generate(vertex, filterTraversal.getStartStep(), 1));
            return filterTraversal.hasNext();
        }
        if (vertex.property(TraversalVertexProgram.HALTED_TRAVERSERS).isPresent()) return true;
        for (final Traverser<?> traverser : this.haltedTraversers) {
            if (vertex.equals(traverser.get())) return true;
        }
        return false;
    }

    private boolean isEndVertex(final Vertex vertex, final Vertex startVertex) {
        final Traversal.Admin<Vertex, ?> filterTraversal = this.targetVertexFilterTraversal.getPure();
        //noinspection unchecked
        final Step<Vertex, Vertex> startStep = (Step<Vertex, Vertex>) filterTraversal.getStartStep();
        if (this.standalone) {
            filterTraversal.addStart(filterTraversal.getTraverserGenerator().generate(vertex, startStep, 1));
        } else {
            final Property<TraverserSet<Vertex>> haltedTraversers = startVertex.property(TraversalVertexProgram.HALTED_TRAVERSERS);
            if (haltedTraversers.isPresent()) {
                for (final Traverser.Admin<Vertex> traverser : haltedTraversers.value()) {
                    filterTraversal.addStart(traverser.split(vertex, startStep));
                }
            }
        }
        return filterTraversal.hasNext();
    }

    private void processEdges(final Vertex vertex, final Path currentPath, final Number currentDistance,
                              final Messenger<Triplet<Path, Edge, Number>> messenger) {

        final Traversal.Admin<Vertex, Edge> edgeTraversal = this.edgeTraversal.getPure();
        edgeTraversal.addStart(edgeTraversal.getTraverserGenerator().generate(vertex, edgeTraversal.getStartStep(), 1));

        while (edgeTraversal.hasNext()) {
            final Edge edge = edgeTraversal.next();
            final Number distance = getDistance(edge);

            Vertex otherV = edge.inVertex();
            if (otherV.equals(vertex))
                otherV = edge.outVertex();

            if (!currentPath.objects().contains(otherV)) {
                messenger.sendMessage(MessageScope.Global.of(otherV),
                        Triplet.with(currentPath, this.includeEdges ? edge : null,
                                NumberHelper.add(currentDistance, distance)));
            }
        }
    }

    private void updateHaltedTraversers(final Vertex vertex, final Memory memory) {
        if (isStartVertex(vertex)) {
            final List<Path> paths = memory.get(SHORTEST_PATHS);
            if (vertex.property(TraversalVertexProgram.HALTED_TRAVERSERS).isPresent()) {
                final TraverserSet<Vertex> haltedTraversers = vertex.value(TraversalVertexProgram.HALTED_TRAVERSERS);
                final TraverserSet<Path> newHaltedTraversers = new TraverserSet<>();
                for (final Traverser.Admin<Vertex> traverser : haltedTraversers) {
                    final Vertex v = traverser.get();
                    for (final Path path : paths) {
                        if (path.get(0).equals(v)) {
                            newHaltedTraversers.add(traverser.split(path, this.programStep));
                        }
                    }
                }
                vertex.property(VertexProperty.Cardinality.single, TraversalVertexProgram.HALTED_TRAVERSERS, newHaltedTraversers);
            }
        }
    }

    private Number getDistance(final Edge edge) {
        if (this.distanceEqualsNumberOfHops) return 1;
        final Traversal.Admin<Edge, Number> traversal = this.distanceTraversal.getPure();
        traversal.addStart(traversal.getTraverserGenerator().generate(edge, traversal.getStartStep(), 1));
        return traversal.tryNext().orElse(0);
    }

    private boolean exceedsMaxDistance(final Number distance) {
        // This method is used to stop the message sending for paths that exceed the specified maximum distance. Since
        // custom distances can be negative, this method should only return true if the distance is calculated based on
        // the number of hops.
        return this.distanceEqualsNumberOfHops && this.maxDistance != null
                && NumberHelper.compare(distance, this.maxDistance) > 0;
    }

    private void collectShortestPaths(final Vertex vertex, final Memory memory) {

        final VertexProperty<Map<Vertex, Pair<Number, Set<Path>>>> pathProperty = vertex.property(PATHS);

        if (pathProperty.isPresent()) {

            final Map<Vertex, Pair<Number, Set<Path>>> paths = pathProperty.value();
            final List<Path> result = new ArrayList<>();

            for (final Pair<Number, Set<Path>> pair : paths.values()) {
                for (final Path path : pair.getValue1()) {
                    if (isEndVertex(path.get(path.size() - 1), path.get(0))) {
                        result.add(path);
                    }
                }
            }

            pathProperty.remove();

            memory.add(SHORTEST_PATHS, result);
        }
    }

    //////////////////////////////

    public static Builder build() {
        return new Builder();
    }

    @SuppressWarnings("WeakerAccess")
    public static final class Builder extends AbstractVertexProgramBuilder<Builder> {


        private Builder() {
            super(ShortestPathVertexProgram.class);
        }

        public Builder source(final Traversal<Vertex, ?> sourceVertexFilter) {
            if (null == sourceVertexFilter) throw Graph.Exceptions.argumentCanNotBeNull("sourceVertexFilter");
            PureTraversal.storeState(this.configuration, SOURCE_VERTEX_FILTER, sourceVertexFilter.asAdmin());
            return this;
        }

        public Builder target(final Traversal<Vertex, ?> targetVertexFilter) {
            if (null == targetVertexFilter) throw Graph.Exceptions.argumentCanNotBeNull("targetVertexFilter");
            PureTraversal.storeState(this.configuration, TARGET_VERTEX_FILTER, targetVertexFilter.asAdmin());
            return this;
        }

        public Builder edgeDirection(final Direction direction) {
            if (null == direction) throw Graph.Exceptions.argumentCanNotBeNull("direction");
            return edgeTraversal(__.toE(direction));
        }

        public Builder edgeTraversal(final Traversal<Vertex, Edge> edgeTraversal) {
            if (null == edgeTraversal) throw Graph.Exceptions.argumentCanNotBeNull("edgeTraversal");
            PureTraversal.storeState(this.configuration, EDGE_TRAVERSAL, edgeTraversal.asAdmin());
            return this;
        }

        public Builder distanceProperty(final String distance) {
            //noinspection unchecked
            return distance != null
                    ? distanceTraversal((Traversal) new ElementValueTraversal<>(distance))
                    : distanceTraversal(DEFAULT_DISTANCE_TRAVERSAL.getPure());
        }

        public Builder distanceTraversal(final Traversal<Edge, Number> distanceTraversal) {
            if (null == distanceTraversal) throw Graph.Exceptions.argumentCanNotBeNull("distanceTraversal");
            PureTraversal.storeState(this.configuration, DISTANCE_TRAVERSAL, distanceTraversal.asAdmin());
            return this;
        }

        public Builder maxDistance(final Number distance) { // todo: implement test
            if (null != distance)
                this.configuration.setProperty(MAX_DISTANCE, distance);
            else
                this.configuration.clearProperty(MAX_DISTANCE);
            return this;
        }

        public Builder includeEdges(final boolean include) {
            this.configuration.setProperty(INCLUDE_EDGES, include);
            return this;
        }
    }

    ////////////////////////////

    @Override
    public Features getFeatures() {
        return new Features() {
            @Override
            public boolean requiresGlobalMessageScopes() {
                return true;
            }

            @Override
            public boolean requiresVertexPropertyAddition() {
                return true;
            }
        };
    }

    private enum State {
        SEARCH, COLLECT_PATHS, UPDATE_HALTED_TRAVERSERS
    }
}