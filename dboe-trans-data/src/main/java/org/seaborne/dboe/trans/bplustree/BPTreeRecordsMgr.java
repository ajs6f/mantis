/*
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 *  See the NOTICE file distributed with this work for additional
 *  information regarding copyright ownership.
 */

package org.seaborne.dboe.trans.bplustree;

import org.seaborne.dboe.base.block.Block ;
import org.seaborne.dboe.base.block.BlockType ;
import org.seaborne.dboe.base.page.BlockConverter ;
import org.seaborne.dboe.base.page.PageBlockMgr ;
import org.seaborne.dboe.base.record.RecordFactory ;
import org.seaborne.dboe.base.recordbuffer.RecordBufferPage ;
import org.seaborne.dboe.base.recordbuffer.RecordBufferPageMgr ;
import org.seaborne.dboe.base.recordbuffer.RecordBufferPageMgr.Block2RecordBufferPage ;

/** Bridge for making, getting and putting BPTreeRecords over a RecordBufferPageMgr */
final public class BPTreeRecordsMgr extends PageBlockMgr<BPTreeRecords>
{
    // Only "public" for external very low level tools in development to access this class.
    // Assume package access.

    private final RecordBufferPageMgr rBuffPageMgr ;
    private final BPlusTree bpTree ;
    
    BPTreeRecordsMgr(BPlusTree bpTree, RecordFactory recordFactory, RecordBufferPageMgr rBuffPageMgr) {
        super(null , rBuffPageMgr.getBlockMgr()) ;
        this.bpTree = bpTree ;
        super.setConverter(new Block2BPTreeRecords(this, recordFactory)) ;
        // bpt is uninitialized at this point.
        // so record rBuffPageMgr
        this.rBuffPageMgr = rBuffPageMgr ;
    }
    
    /** Converter BPTreeRecords -- make a RecordBufferPage and wraps it.*/ 
    static class Block2BPTreeRecords implements BlockConverter<BPTreeRecords> {
        private Block2RecordBufferPage recordBufferConverter ;
        private BPTreeRecordsMgr       recordsMgr ;

        Block2BPTreeRecords(BPTreeRecordsMgr mgr, RecordFactory recordFactory) {
            this.recordsMgr = mgr ;
            this.recordBufferConverter = new RecordBufferPageMgr.Block2RecordBufferPage(recordFactory) ;
        }

        @Override
        public BPTreeRecords fromBlock(Block block) {
            RecordBufferPage rbp = recordBufferConverter.fromBlock(block) ;
            return new BPTreeRecords(recordsMgr, rbp) ;
        }

        @Override
        public Block toBlock(BPTreeRecords t) {
            return recordBufferConverter.toBlock(t.getRecordBufferPage()) ;
        }

        @Override
        public BPTreeRecords createFromBlock(Block block, BlockType bType) {
            RecordBufferPage rbp = recordBufferConverter.createFromBlock(block, bType) ;
            return new BPTreeRecords(recordsMgr, rbp) ;
        }
    }
    
    public BPTreeRecords create() {
        return super.create(BlockType.RECORD_BLOCK) ;
//        
//        RecordBufferPage rbp = rBuffPageMgr.create() ;
//        BPTreeRecords bRec = new BPTreeRecords(bpTree, rbp) ;
//        return bRec ;
    }
    
    public RecordBufferPageMgr getRecordBufferPageMgr() { return rBuffPageMgr ; }
    public BPlusTree getBPTree()                        { return bpTree ; }

    boolean isWritable(int id) {
        //System.err.println("BPTreeRecordsMgr.isWritable") ;
        return false ;
    }

    @Override
    public void startRead() {
        rBuffPageMgr.startRead() ;
    }

    @Override
    public void finishRead() {
        rBuffPageMgr.finishRead() ;
    }

    @Override
    public void startUpdate() {
        rBuffPageMgr.startUpdate() ;
    }

    @Override
    public void finishUpdate() {
        rBuffPageMgr.finishUpdate() ;
    }
    
    @Override
    public void close() {
        rBuffPageMgr.close() ;
    }
}
