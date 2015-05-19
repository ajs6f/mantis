/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.seaborne.tdb2.store;

import java.util.Iterator ;

import org.apache.jena.atlas.iterator.Iter ;
import org.apache.jena.atlas.lib.Closeable ;
import org.apache.jena.atlas.lib.Sync ;
import org.apache.jena.atlas.lib.Tuple ;
import org.apache.jena.graph.Graph ;
import org.apache.jena.graph.Node ;
import org.apache.jena.graph.Triple ;
import org.apache.jena.sparql.core.DatasetGraphCaching ;
import org.apache.jena.sparql.core.Quad ;
import org.apache.jena.sparql.engine.optimizer.reorder.ReorderTransformation ;
import org.seaborne.dboe.base.file.Location ;
import org.seaborne.tdb2.lib.NodeLib ;
import org.seaborne.tdb2.setup.StoreParams ;
import org.seaborne.tdb2.store.nodetupletable.NodeTupleTable ;

/** This is the class that provides creates a dataset over the storage via
 *  TripleTable, QuadTable and prefixes.
 *  This is the coordination of the storage.
 *  @see DatasetGraphTxn for the Tarsnaction form.
 */
final
public class DatasetGraphTDB extends DatasetGraphCaching
                             implements /*DatasetGraph,*/ Sync, Closeable
{
    private TripleTable tripleTable ;
    private QuadTable quadTable ;
    private DatasetPrefixesTDB prefixes ;
    private final ReorderTransformation transform ;
    private final StoreParams config ;
    private final Location location ;
    
    private GraphTDB defaultGraphTDB ;
    private boolean closed = false ;

    public DatasetGraphTDB(TripleTable tripleTable, QuadTable quadTable, DatasetPrefixesTDB prefixes, 
                           ReorderTransformation transform, Location location, StoreParams config) {
        this.tripleTable = tripleTable ;
        this.quadTable = quadTable ;
        this.prefixes = prefixes ;
        this.transform = transform ;
        this.config = config ;
        this.location = location ;
        this.defaultGraphTDB = getDefaultGraphTDB() ;
    }

    public QuadTable getQuadTable()         { return quadTable ; }
    public TripleTable getTripleTable()     { return tripleTable ; }
    
    @Override
    protected Iterator<Quad> findInDftGraph(Node s, Node p, Node o) {
        return triples2quadsDftGraph(getTripleTable().find(s, p, o)) ;
    }

    @Override
    protected Iterator<Quad> findInSpecificNamedGraph(Node g, Node s, Node p, Node o)
    { return getQuadTable().find(g, s, p, o) ; }

    @Override
    protected Iterator<Quad> findInAnyNamedGraphs(Node s, Node p, Node o)
    { return getQuadTable().find(Node.ANY, s, p, o) ; }

    protected static Iterator<Quad> triples2quadsDftGraph(Iterator<Triple> iter)
    { return triples2quads(Quad.defaultGraphIRI, iter) ; }
 
    @Override
    protected void addToDftGraph(Node s, Node p, Node o)
    { getTripleTable().add(s,p,o) ; }

    @Override
    protected void addToNamedGraph(Node g, Node s, Node p, Node o)
    { getQuadTable().add(g, s, p, o) ; }

    @Override
    protected void deleteFromDftGraph(Node s, Node p, Node o)
    { getTripleTable().delete(s,p,o) ; }

    @Override
    protected void deleteFromNamedGraph(Node g, Node s, Node p, Node o)
    { getQuadTable().delete(g, s, p, o) ; }
    
    public GraphTDB getDefaultGraphTDB() 
    { return (GraphTDB)getDefaultGraph() ; }

    public GraphTDB getGraphTDB(Node graphNode)
    { return (GraphTDB)getGraph(graphNode) ; }

    @Override
    protected void _close() {
        if ( closed )
            return ;
        closed = true ;
        
        tripleTable.close() ;
        quadTable.close() ;
        prefixes.close();
        // Which will cause reuse to throw exceptions early.
        tripleTable = null ;
        quadTable = null ;
        prefixes = null ;
    }
    
    @Override
    // Empty graphs don't "exist" 
    public boolean containsGraph(Node graphNode) { 
        if ( Quad.isDefaultGraphExplicit(graphNode) || Quad.isUnionGraph(graphNode)  )
            return true ;
        return _containsGraph(graphNode) ; 
    }

    @Override
    protected boolean _containsGraph(Node graphNode) {
        // Have to look explicitly, which is a bit of a nuisance.
        // But does not normally happen for GRAPH <g> because that's rewritten to quads.
        // Only pattern with complex paths go via GRAPH. 
        Iterator<Tuple<NodeId>> x = quadTable.getNodeTupleTable().findAsNodeIds(graphNode, null, null, null) ;
        if ( x == null )
            return false ; 
        
//        NodeId graphNodeId = quadTable.getNodeTupleTable().getNodeTable().getNodeIdForNode(graphNode) ;
//        Tuple<NodeId> pattern = Tuple.create(graphNodeId, null, null, null) ;
//        Iterator<Tuple<NodeId>> x = quadTable.getNodeTupleTable().getTupleTable().find(pattern) ;
        boolean result = x.hasNext() ;
        return result ;
    }

    @Override
    protected Graph _createDefaultGraph()
    { return new GraphTDB(this, null) ; }

    @Override
    protected Graph _createNamedGraph(Node graphNode)
    { return new GraphTDB(this, graphNode) ; }


    public StoreParams getConfig()                          { return config ; }
    
    public ReorderTransformation getReorderTransform()      { return transform ; }
    
    public DatasetPrefixesTDB getPrefixes()                 { return prefixes ; }
    
    @Override
    public Iterator<Node> listGraphNodes()
    {
        Iterator<Tuple<NodeId>> x = quadTable.getNodeTupleTable().findAll() ;
        Iterator<NodeId> z =  Iter.iter(x).map(t -> t.get(0)).distinct() ;
        return NodeLib.nodes(quadTable.getNodeTupleTable().getNodeTable(), z) ;
    }

    @Override
    public long size()                   { return Iter.count(listGraphNodes()) ; }

    @Override
    public boolean isEmpty()            { return getTripleTable().isEmpty() && getQuadTable().isEmpty() ; }

    @Override
    public void clear()
    {
        // Leave the node table alone.
        getTripleTable().clearTriples() ;
        getQuadTable().clearQuads() ;
    }
    
    public NodeTupleTable chooseNodeTupleTable(Node graphNode)
    {
        if ( graphNode == null || Quad.isDefaultGraph(graphNode) )
            return getTripleTable().getNodeTupleTable() ;
        else
            // Includes Node.ANY and union graph
            return getQuadTable().getNodeTupleTable() ;
    }
    
    private static final int sliceSize = 1000 ;
    
    @Override
    public void deleteAny(Node g, Node s, Node p, Node o) {
        // Delete in batches.
        // That way, there is no active iterator when a delete
        // from the indexes happens.

        NodeTupleTable t = chooseNodeTupleTable(g) ;
        @SuppressWarnings("unchecked")
        Tuple<NodeId>[] array = (Tuple<NodeId>[])new Tuple<?>[sliceSize] ;

        while (true) { // Convert/cache s,p,o?
            // The Node Cache will catch these so don't worry unduely.
            Iterator<Tuple<NodeId>> iter = null ;
            if ( g == null )
                iter = t.findAsNodeIds(s, p, o) ;
            else
                iter = t.findAsNodeIds(g, s, p, o) ;

            if ( iter == null )
                // Finished?
                return ;

            // Get a slice
            int len = 0 ;
            for (; len < sliceSize; len++) {
                if ( !iter.hasNext() )
                    break ;
                array[len] = iter.next() ;
            }

            // Delete them.
            for (int i = 0; i < len; i++) {
                t.getTupleTable().delete(array[i]) ;
                array[i] = null ;
            }
            // Finished?
            if ( len < sliceSize )
                break ;
        }
    }
    
    public Location getLocation()       { return location ; }

    @Override
    public void sync()
    {
        tripleTable.sync() ;
        quadTable.sync() ;
        prefixes.sync() ;
    }
    
    @Override
    public void setDefaultGraph(Graph g) { 
        throw new UnsupportedOperationException("Can't set default graph via GraphStore on a TDB-backed dataset") ;
    }
}