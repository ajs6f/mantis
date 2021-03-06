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

package org.seaborne.dboe.transaction.txn;

import static org.seaborne.dboe.transaction.txn.TxnState.INACTIVE ;
import org.apache.jena.query.ReadWrite ;

/** 
 * A view that provides information about a transaction
 * @see Transaction
 */
public interface TransactionInfo {

    /** The transaction lifecycle state */
    public TxnState getState() ;

    /**
     * Each transaction is allocated a serialization point by the transaction
     * coordinator. Normally, this is related to this number and it increases
     * over time as the data changes. Two readers can have the same
     * serialization point - they are working with the same view of the data.
     */
    public long getDataEpoch() ;

    /** Has the transaction started? */ 
    public boolean hasStarted() ;
    
    /** Has the transaction finished (has commit/abort/end been called)? */ 
    public boolean hasFinished() ; 

    /** Has the transaction gone through all lifecycle states? */ 
    public boolean hasFinalised() ; 

    /** Get the trasnaction id for this transaction. Unique within this OS process (JVM) at least . */
    public TxnId getTxnId() ; 

    /** What mode is this transaction?
     *  This may change from {@code READ} to {@code WRITE} in a transactions lifetime.  
     */
    public ReadWrite getMode() ;
    
    /** Is this a view of a READ transaction?
     * Convenience operation equivalent to {@code (getMode() == READ)}
     */
    public default boolean isReadTxn()  { return getMode() == ReadWrite.READ ; }
    
    /** Is this a view of a WRITE transaction?
     * Convenience operation equivalent to {@code (getMode() == WRITE)}
     */
    public default boolean isWriteTxn()  { return getMode() == ReadWrite.WRITE ; }
    
    /** Is this a view of a transaction that is active?
     * Equivalent to {@code getState() != INACTIVE} 
     */
    public default boolean isActiveTxn() { 
        return getState() != INACTIVE ;
    }

}

