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

package org.seaborne.dboe.transaction.txn.journal;

import static org.seaborne.dboe.sys.SystemLz.SizeOfInt ;

import java.nio.ByteBuffer ;
import java.util.Iterator ;
import java.util.zip.Adler32 ;

import org.apache.jena.atlas.iterator.IteratorSlotted ;
import org.apache.jena.atlas.lib.ByteBufferLib ;
import org.apache.jena.atlas.lib.Closeable ;
import org.apache.jena.atlas.lib.FileOps ;
import org.apache.jena.atlas.lib.Sync ;
import org.seaborne.dboe.base.file.BufferChannel ;
import org.seaborne.dboe.base.file.BufferChannelFile ;
import org.seaborne.dboe.base.file.BufferChannelMem ;
import org.seaborne.dboe.base.file.Location ;
import org.seaborne.dboe.sys.Names ;
import org.seaborne.dboe.transaction.txn.ComponentId ;
import org.seaborne.dboe.transaction.txn.PrepareState ;
import org.seaborne.dboe.transaction.txn.TransactionException ;
import org.slf4j.Logger ;
import org.slf4j.LoggerFactory ;

/** A transaction journal.
* The journal is append-only for writes, with truncation of the file
* every so often. It is read during recovery.
* The size of entries depends on per-component redo/undo records;
* the control records like COMMIT are quite small.
* Entries have a CRC to ensure that part-entries are not acted on.
*/

public final
class Journal implements Sync, Closeable
{
    private static final boolean LOGGING = false ;
    private static Logger log = LoggerFactory.getLogger(Journal.class) ;
    
    private static boolean logging() {
        return LOGGING && log.isInfoEnabled() ;
    }
    
    private static void log(String fmt, Object...args) {
        if ( ! logging() )
            return ;
        log.info(String.format(fmt, args));
    }
    
    private BufferChannel channel ;
    private long position ;
    
    // Header: fixed, inc CRC
    //   length of data             4 bytes
    //   CRC of whole entry.        4 bytes
    //   entry type                 4 bytes (1 byte and 3 alignment)
    //   component                  16 bytes (fixed??)
    // Data area : variable
    //   Bytes
    
    private static final int posnLength     = 0 ;
    private static final int posnCRC        = posnLength + SizeOfInt ;
    private static final int posnEntry      = posnCRC    + SizeOfInt ;
    private static final int posnComponent  = posnEntry  + SizeOfInt ;
    // Start of the compoent data area.
    private static final int posnData       = posnComponent  + ComponentId.SIZE ;
    
    // Currently, the header is fixed size so this is the size.
    private static int HeaderLen            = posnData-posnLength ;
    
    private ByteBuffer header    = ByteBuffer.allocate(HeaderLen) ;
    
    public static boolean exists(Location location) {
        if ( location.isMem() )
            return false ;
        return FileOps.exists(journalFilename(location)) ;
    }

    public static Journal create(Location location) {
        BufferChannel chan ;
        String channelName = journalFilename(location) ;
        if ( location.isMem() )
            chan = BufferChannelMem.create(channelName) ;
        else
            chan = BufferChannelFile.create(channelName) ;
        return create(chan) ;
    }

    public static Journal create(BufferChannel chan) {
        return new Journal(chan) ;
    }

    private static String journalFilename(Location location) {
        return location.absolute(Names.journalFile) ;
    }

    private Journal(BufferChannel channel) {
        this.channel = channel ;
        position = 0 ;
    }

    // synchronized : excessive?
    // Given the calling context, we know it's thread safe.
    
    synchronized public long writeJournal(JournalEntry entry) {
        long posn = write(entry.getType(), entry.getComponentId(), entry.getByteBuffer()) ;

        if ( entry.getPosition() < 0 ) {
            entry.setPosition(posn) ;
            entry.setEndPosition(position) ;
        }
        return posn ;
    }

//    /** Write an entry and return it's location in the journal */
//    synchronized public void write(List<PrepareState> prepareStates) {
//        prepareStates.forEach(this::write) ;
//    }

    public long write(PrepareState prepareState) {
        return write(JournalEntryType.REDO, prepareState.getComponent(), prepareState.getData()) ;
    }
    
    /** Write an entry and return it's location in the journal */
    synchronized public long write(JournalEntryType type, ComponentId componentId, ByteBuffer buffer) {
        // Check buffer set right.
        if ( LOGGING ) {
            log("write@%d >> %s %s", position, componentId == null ? "<null>" : componentId.label(),
                buffer == null ? "<null>" : ByteBufferLib.details(buffer)) ;
        }

        long posn = position ;
        int len = -1 ;
        int bufferLimit = -1 ;
        int bufferPosition = -1 ;

        if ( buffer != null ) {
            bufferLimit = buffer.limit() ;
            bufferPosition = buffer.position() ;
            len = buffer.remaining() ;
        }

        // Header: (length/4, crc/4, entry/4, component/16)

        header.clear() ;
        header.putInt(len) ;
        header.putInt(0) ; // Set CRC to zero
        header.putInt(type.id) ;
        header.put(componentId.bytes()) ;
        header.flip() ;
        // Need to put CRC in before writing.

        Adler32 adler = new Adler32() ;
        adler.update(header.array()) ;

        if ( len > 0 ) {
            buffer.position(bufferPosition) ;
            buffer.limit(bufferLimit) ;
            adler.update(buffer) ;
            // Reset buffer
            buffer.position(bufferPosition) ;
            buffer.limit(bufferLimit) ;
        }

        header.putInt(posnCRC, (int)adler.getValue()) ;
        channel.write(header) ;
        if ( len > 0 ) {
            channel.write(buffer) ;
            buffer.position(bufferPosition) ;
            buffer.limit(bufferLimit) ;
        }
        position += HeaderLen + len ;
        if ( LOGGING )
            log("write@%d << %s", position, componentId.label()) ;
        return posn ;
    }

    synchronized public JournalEntry readJournal(long id) {
        return _readJournal(id) ;
    }

    private JournalEntry _readJournal(long id) {
        long x = channel.position() ;
        if ( x != id )
            channel.position(id) ;
        JournalEntry entry = _read() ;
        long x2 = channel.position() ;
        entry.setPosition(id) ;
        entry.setEndPosition(x2) ;
        if ( x != id )
            channel.position(x) ;
        return entry ;
    }

    // read one entry at the channel position.
    // Move position to end of read.
    private JournalEntry _read() {
        header.clear() ;
        int lenRead = channel.read(header) ;
        if ( lenRead == -1 ) {
            // probably broken file.
            throw new TransactionException("Read off the end of a journal file") ;
            // return null ;
        }
        if ( lenRead != header.capacity() )
            throw new TransactionException("Partial read of journal file") ;
            
        header.rewind() ;
        // Header: (length/4, crc/4, entry/4, component/16)
        int len = header.getInt() ;
        int checksum = header.getInt() ;
        header.putInt(posnCRC, 0) ;
        int entryType = header.getInt() ;
        byte[] bytes = new byte[ComponentId.SIZE] ;
        header.get(bytes) ;
        ComponentId component = new ComponentId(null, bytes) ;

        Adler32 adler = new Adler32() ;
        adler.update(header.array()) ;

        ByteBuffer bb = null ;
        if ( len > 0 ) {
            bb = ByteBuffer.allocate(len) ;
            lenRead = channel.read(bb) ;
            if ( lenRead != len )
                throw new TransactionException("Failed to read the journal entry data: wanted " + len + " bytes, got " + lenRead) ;
            bb.rewind() ;
            adler.update(bb) ;
            bb.rewind() ;
        }

        if ( checksum != (int)adler.getValue() )
            throw new TransactionException("Checksum error reading from the Journal.") ;

        JournalEntryType type = JournalEntryType.type(entryType) ;
        return new JournalEntry(type, component, bb) ;
    }

    /**
     * Iterator of entries from current point in Journal, going forward. Must be
     * JournalEntry aligned at start.
     */
    private class IteratorEntries extends IteratorSlotted<JournalEntry> {
        JournalEntry slot = null ;
        final long   endPoint ;
        long         iterPosn ;

        public IteratorEntries(long startPosition) {
            iterPosn = startPosition ;
            endPoint = channel.size() ;
        }

        @Override
        protected JournalEntry moveToNext() {
            // synchronized necessary? Outer policy is single thread?
            synchronized (Journal.this) {
                if ( iterPosn >= endPoint )
                    return null ;
                JournalEntry e = _readJournal(iterPosn) ;
                iterPosn = e.getEndPosition() ;
                return e ;
            }
        }

        @Override
        protected boolean hasMore() {
            return iterPosn < endPoint ;
        }
    }

    public Iterator<JournalEntry> entries() {
        return new IteratorEntries(0) ;
    }

    synchronized public Iterator<JournalEntry> entries(long startPosition) {
        return new IteratorEntries(startPosition) ;
    }

    @Override
    public void sync()  { channel.sync() ; }

    @Override
    public void close() { channel.close() ; }

    public long size()  { return channel.size() ; }
    
    public boolean isEmpty()  { return channel.size() == 0 ; }

    public void truncate(long size) { channel.truncate(size) ; }
    
//    public void append()    { position(size()) ; }
    
     public long position() { return channel.position() ; }  
    
//    public void position(long posn) { channel.position(posn) ; }
    
    public String getFilename() { return channel.getFilename() ; }
}