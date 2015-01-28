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

package org.seaborne.dboe.base.block;

import org.apache.jena.atlas.lib.FileOps ;
import org.apache.jena.atlas.logging.LogCtl ;
import org.junit.After ;
import org.junit.AfterClass ;
import org.junit.BeforeClass ;
import org.seaborne.dboe.ConfigTest ;
import org.seaborne.dboe.base.block.BlockMgr ;
import org.seaborne.dboe.base.block.BlockMgrFileAccess ;
import org.seaborne.dboe.base.file.BlockAccess ;
import org.seaborne.dboe.base.file.BlockAccessMapped ;

public class TestBlockMgrMapped extends AbstractTestBlockMgr
{
    static boolean logging = false ;
    
    static { if ( logging ) LogCtl.setLog4j() ; }
    
    static final String filename = ConfigTest.getTestingDir()+"/block-mgr" ;
    
    // Windows is iffy about deleting memory mapped files.
    
    @After public void after1()     { clearBlockMgr() ; }
    
    private void clearBlockMgr()
    {
        if ( blockMgr != null )
        {
            blockMgr.close() ;
            FileOps.deleteSilent(filename) ;
            blockMgr = null ;
        }
    }
    
    @BeforeClass static public void remove1() { FileOps.deleteSilent(filename) ; }
    @AfterClass  static public void remove2() { FileOps.deleteSilent(filename) ; }
    
    @Override
    protected BlockMgr make()
    { 
        clearBlockMgr() ;
        BlockAccess file = new BlockAccessMapped(filename, BlkSize) ;
        return new BlockMgrFileAccess(file, BlkSize) ;
    }
}