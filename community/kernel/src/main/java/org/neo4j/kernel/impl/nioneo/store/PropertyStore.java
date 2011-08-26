/**
 * Copyright (c) 2002-2011 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
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
package org.neo4j.kernel.impl.nioneo.store;

import static org.neo4j.kernel.Config.ARRAY_BLOCK_SIZE;
import static org.neo4j.kernel.Config.STRING_BLOCK_SIZE;
import static org.neo4j.kernel.impl.util.Bits.bits;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

import org.neo4j.kernel.IdGeneratorFactory;
import org.neo4j.kernel.IdType;
import org.neo4j.kernel.impl.util.Bits;

/**
 * Implementation of the property store. This implementation has two dynamic
 * stores. One used to store keys and another for string property values.
 */
public class PropertyStore extends AbstractStore implements Store
{
    public static final int DEFAULT_DATA_BLOCK_SIZE = 120;
    public static final int DEFAULT_PAYLOAD_SIZE = 32;

    // store version, each store ends with this string (byte encoded)
    private static final String VERSION = "PropertyStore v0.9.9";

    public static final int RECORD_SIZE = 5+DEFAULT_PAYLOAD_SIZE+5/*temporary for prev*/;

    private DynamicStringStore stringPropertyStore;
    private PropertyIndexStore propertyIndexStore;
    private DynamicArrayStore arrayPropertyStore;

    /**
     * See {@link AbstractStore#AbstractStore(String, Map)}
     */
    public PropertyStore( String fileName, Map<?,?> config )
    {
        super( fileName, config, IdType.PROPERTY );
    }

    /**
     * See {@link AbstractStore#AbstractStore(String)}
     */
//    public PropertyStore( String fileName )
//    {
//        super( fileName );
//    }

    @Override
    protected void initStorage()
    {
        stringPropertyStore = new DynamicStringStore( getStorageFileName()
            + ".strings", getConfig(), IdType.STRING_BLOCK );
        propertyIndexStore = new PropertyIndexStore( getStorageFileName()
            + ".index", getConfig() );
        arrayPropertyStore = new DynamicArrayStore( getStorageFileName()
            + ".arrays", getConfig(), IdType.ARRAY_BLOCK );
    }

    @Override
    protected void setRecovered()
    {
        super.setRecovered();
        stringPropertyStore.setRecovered();
        propertyIndexStore.setRecovered();
        arrayPropertyStore.setRecovered();
    }

    @Override
    protected void unsetRecovered()
    {
        super.unsetRecovered();
        stringPropertyStore.unsetRecovered();
        propertyIndexStore.unsetRecovered();
        arrayPropertyStore.unsetRecovered();
    }

    @Override
    protected void closeStorage()
    {
        if ( stringPropertyStore != null )
        {
            stringPropertyStore.close();
            stringPropertyStore = null;
        }
        if ( propertyIndexStore != null )
        {
            propertyIndexStore.close();
            propertyIndexStore = null;
        }
        if ( arrayPropertyStore != null )
        {
            arrayPropertyStore.close();
            arrayPropertyStore = null;
        }
    }

    @Override
    public void flushAll()
    {
        stringPropertyStore.flushAll();
        propertyIndexStore.flushAll();
        arrayPropertyStore.flushAll();
        super.flushAll();
    }

    @Override
    public String getTypeAndVersionDescriptor()
    {
        return VERSION;
    }

    @Override
    public int getRecordSize()
    {
        return RECORD_SIZE;
    }

    /**
     * Creates a new property store contained in <CODE>fileName</CODE> If
     * filename is <CODE>null</CODE> or the file already exists an
     * <CODE>IOException</CODE> is thrown.
     *
     * @param fileName
     *            File name of the new property store
     * @throws IOException
     *             If unable to create property store or name null
     */
    public static void createStore( String fileName, Map<?,?> config )
    {
        IdGeneratorFactory idGeneratorFactory = (IdGeneratorFactory) config.get(
                IdGeneratorFactory.class );

        createEmptyStore( fileName, VERSION, idGeneratorFactory );
        int stringStoreBlockSize = DEFAULT_DATA_BLOCK_SIZE;
        int arrayStoreBlockSize = DEFAULT_DATA_BLOCK_SIZE;
        try
        {
            String stringBlockSize = (String) config.get( STRING_BLOCK_SIZE );
            String arrayBlockSize = (String) config.get( ARRAY_BLOCK_SIZE );
            if ( stringBlockSize != null )
            {
                int value = Integer.parseInt( stringBlockSize );
                if ( value > 0 )
                {
                    stringStoreBlockSize = value;
                }
            }
            if ( arrayBlockSize != null )
            {
                int value = Integer.parseInt( arrayBlockSize );
                if ( value > 0 )
                {
                    arrayStoreBlockSize = value;
                }
            }
        }
        catch ( Exception e )
        {
            logger.log( Level.WARNING, "Exception creating store", e );
        }

        DynamicStringStore.createStore( fileName + ".strings",
            stringStoreBlockSize, idGeneratorFactory, IdType.STRING_BLOCK );
        PropertyIndexStore.createStore( fileName + ".index", idGeneratorFactory );
        DynamicArrayStore.createStore( fileName + ".arrays",
            arrayStoreBlockSize, idGeneratorFactory );
    }

    private long nextStringBlockId()
    {
        return stringPropertyStore.nextBlockId();
    }

    public void freeStringBlockId( long blockId )
    {
        stringPropertyStore.freeBlockId( blockId );
    }

    private long nextArrayBlockId()
    {
        return arrayPropertyStore.nextBlockId();
    }

    public void freeArrayBlockId( long blockId )
    {
        arrayPropertyStore.freeBlockId( blockId );
    }

    public PropertyIndexStore getIndexStore()
    {
        return propertyIndexStore;
    }

    public void updateRecord( PropertyRecord record, boolean recovered )
    {
        assert recovered;
        setRecovered();
        try
        {
            updateRecord( record );
            registerIdFromUpdateRecord( record.getId() );
        }
        finally
        {
            unsetRecovered();
        }
    }

    public void updateRecord( PropertyRecord record )
    {
        PersistenceWindow window = acquireWindow( record.getId(),
            OperationType.WRITE );
        try
        {
            updateRecord( record, window );
        }
        finally
        {
            releaseWindow( window );
        }
        if ( !record.isLight() )
        {
            for ( DynamicRecord valueRecord : record.getValueRecords() )
            {
                if ( valueRecord.getType() == PropertyType.STRING.intValue() )
                {
                    stringPropertyStore.updateRecord( valueRecord );
                }
                else if ( valueRecord.getType() ==
                    PropertyType.ARRAY.intValue() )
                {
                    arrayPropertyStore.updateRecord( valueRecord );
                }
                else
                {
                    throw new InvalidRecordException( "Unknown dynamic record" );
                }
            }
        }
    }

    private void updateRecord( PropertyRecord record, PersistenceWindow window )
    {
        long id = record.getId();
        Buffer buffer = window.getOffsettedBuffer( id );
        if ( record.inUse() )
        {
            Bits bits = new Bits( RECORD_SIZE );

            short prevModifier = record.getPrevProp() == Record.NO_NEXT_RELATIONSHIP.intValue() ? 0 : (short)((record.getPrevProp() & 0xF00000000L) >> 32);
            short nextModifier = record.getNextProp() == Record.NO_NEXT_RELATIONSHIP.intValue() ? 0 : (short)((record.getNextProp() & 0xF00000000L) >> 32);
            bits.or( prevModifier, 0xF ).shiftLeft( 4 ).or( nextModifier, 0xF );
            bits.shiftLeft( 32 ).or( (int)record.getPrevProp() );
            bits.shiftLeft( 32 ).or( (int)record.getNextProp() );

            for ( long block : record.getPropBlock() )
            {
                bits.shiftLeft( 64 ).or( block );
            }

            bits.apply( buffer );
        }
        else
        {
            buffer.put( Record.NOT_IN_USE.byteValue() );
            if ( !isInRecoveryMode() )
            {
                freeId( id );
            }
        }
    }

    public PropertyRecord getLightRecord( long id )
    {
        PersistenceWindow window = acquireWindow( id, OperationType.READ );
        try
        {
            PropertyRecord record = getRecord( id, window );
            record.setIsLight( true );
            return record;
        }
        finally
        {
            releaseWindow( window );
        }
    }

    public void makeHeavy( PropertyRecord record )
    {
        record.setIsLight( false );
        if ( record.getType() == PropertyType.STRING )
        {
            Collection<DynamicRecord> stringRecords = stringPropertyStore.getLightRecords( record.getSinglePropBlock() & 0xFFFFFFFFFL );
            for ( DynamicRecord stringRecord : stringRecords )
            {
                stringRecord.setType( PropertyType.STRING.intValue() );
                record.addValueRecord( stringRecord );
            }
        }
        else if ( record.getType() == PropertyType.ARRAY )
        {
            Collection<DynamicRecord> arrayRecords = arrayPropertyStore.getLightRecords( record.getSinglePropBlock() & 0xFFFFFFFFFL );
            for ( DynamicRecord arrayRecord : arrayRecords )
            {
                arrayRecord.setType( PropertyType.ARRAY.intValue() );
                record.addValueRecord( arrayRecord );
            }
        }
    }

    public PropertyRecord getRecord( long id )
    {
        PropertyRecord record;
        PersistenceWindow window = acquireWindow( id, OperationType.READ );
        try
        {
            record = getRecord( id, window );
        }
        finally
        {
            releaseWindow( window );
        }
        if ( record.getType() == PropertyType.STRING )
        {
            Collection<DynamicRecord> stringRecords = stringPropertyStore.getLightRecords( record.getSinglePropBlock() & 0xFFFFFFFFFL );
            record.setIsLight( false );
            for ( DynamicRecord stringRecord : stringRecords )
            {
                stringRecord.setType( PropertyType.STRING.intValue() );
                record.addValueRecord( stringRecord );
            }
        }
        else if ( record.getType() == PropertyType.ARRAY )
        {
            Collection<DynamicRecord> arrayRecords = arrayPropertyStore.getLightRecords( record.getSinglePropBlock() & 0xFFFFFFFFFL );
            record.setIsLight( false );
            for ( DynamicRecord arrayRecord : arrayRecords )
            {
                arrayRecord.setType( PropertyType.ARRAY.intValue() );
                record.addValueRecord( arrayRecord );
            }
        }
        return record;
    }

    private PropertyRecord getRecord( long id, PersistenceWindow window )
    {
        Buffer buffer = window.getOffsettedBuffer( id );

        Bits bits = new Bits( RECORD_SIZE );
        bits.read( buffer );

        long[] propBlock = new long[PropertyType.getPayloadSizeLongs()];
        boolean someBlockInUse = false;
        for ( int i = propBlock.length-1; i >= 0; i-- )
        {
            propBlock[i] = bits.getLong( 0xFFFFFFFFFFFFFFFFL );
            bits.shiftRight( 64 );
            if ( propBlock[i] != 0 ) someBlockInUse = true;
        }
        if ( !someBlockInUse )
        {
            throw new InvalidRecordException( "Record[" + id + "] not in use" );
        }

        PropertyRecord record = new PropertyRecord( id );
        record.setInUse( true );
        record.setPropBlock( propBlock );

        long nextProp = bits.getLong( 0xFFFFFFFFL );
        bits.shiftRight( 32 );
        long prevProp = bits.getLong( 0xFFFFFFFFL );
        bits.shiftRight( 32 );
        long nextMod = (long)bits.getByte( (byte)0xF ) << 32;
        bits.shiftRight( 4 );
        long prevMod = (long)bits.getByte( (byte)0xF ) << 32;
        record.setPrevProp( longFromIntAndMod( prevProp, prevMod ) );
        record.setNextProp( longFromIntAndMod( nextProp, nextMod ) );

        return record;
    }

    public Object getValue( PropertyRecord propertyRecord )
    {
        return propertyRecord.getType().getValue( propertyRecord, this );
    }

    @Override
    public void makeStoreOk()
    {
        propertyIndexStore.makeStoreOk();
        stringPropertyStore.makeStoreOk();
        arrayPropertyStore.makeStoreOk();
        super.makeStoreOk();
    }

    @Override
    public void rebuildIdGenerators()
    {
        propertyIndexStore.rebuildIdGenerators();
        stringPropertyStore.rebuildIdGenerators();
        arrayPropertyStore.rebuildIdGenerators();
        super.rebuildIdGenerators();
    }

    public void updateIdGenerators()
    {
        propertyIndexStore.updateIdGenerators();
        stringPropertyStore.updateHighId();
        arrayPropertyStore.updateHighId();
        this.updateHighId();
    }

    private Collection<DynamicRecord> allocateStringRecords( long valueBlockId,
        char[] chars )
    {
        return stringPropertyStore.allocateRecords( valueBlockId, chars );
    }

    private Collection<DynamicRecord> allocateArrayRecords( long valueBlockId,
        Object array )
    {
        return arrayPropertyStore.allocateRecords( valueBlockId, array );
    }

    public void encodeValue( PropertyRecord record, int keyId, Object value )
    {
        if ( value instanceof String )
        {
            String string = (String) value;
//            if ( ShortString.encode( keyId, string, record ) ) return;
            if ( LongerShortString.encode( keyId, string, record,
                    PropertyType.getPayloadSize() ) ) return;

            Bits bits = bits32WithKeyAndType( keyId, PropertyType.STRING );
            long stringBlockId = nextStringBlockId();
            bits.or( stringBlockId, 0xFFFFFFFFFL );
            record.setSinglePropBlock( bits.getLongs()[0] );
            int length = string.length();
            char[] chars = new char[length];
            string.getChars( 0, length, chars, 0 );
            Collection<DynamicRecord> valueRecords = allocateStringRecords(
                stringBlockId, chars );
            for ( DynamicRecord valueRecord : valueRecords )
            {
                valueRecord.setType( PropertyType.STRING.intValue() );
                record.addValueRecord( valueRecord );
            }
        }
        else if ( value instanceof Integer )
        {
            Bits bits = bits32WithKeyAndType( keyId, PropertyType.INT ).or( ((Integer)value).intValue() );
            record.setSinglePropBlock( bits.getLongs()[0] );
        }
        else if ( value instanceof Boolean )
        {
            Bits bits = bits32WithKeyAndType( keyId, PropertyType.BOOL ).or( ((Boolean)value).booleanValue() ? 1 : 0, 0x1 );
            record.setSinglePropBlock( bits.getLongs()[0] );
        }
        else if ( value instanceof Float )
        {
            Bits bits = bits32WithKeyAndType( keyId, PropertyType.FLOAT ).or( Float.floatToRawIntBits( ((Float) value).floatValue() ) );
            record.setSinglePropBlock( bits.getLongs()[0] );
        }
        else if ( value instanceof Long )
        {
            Bits bits = bits64WithKeyAndType( keyId, PropertyType.LONG ).or( ((Long)value).longValue() );
            record.setPropBlock( bits.getLongs() );
        }
        else if ( value instanceof Double )
        {
            Bits bits = bits64WithKeyAndType( keyId, PropertyType.DOUBLE ).or( Double.doubleToRawLongBits( ((Double)value).doubleValue() ) );
            record.setPropBlock( bits.getLongs() );
        }
        else if ( value instanceof Byte )
        {
            Bits bits = bits32WithKeyAndType( keyId, PropertyType.BYTE ).or( ((Byte)value).byteValue() );
            record.setSinglePropBlock( bits.getLongs()[0] );
        }
        else if ( value instanceof Character )
        {
            Bits bits = bits32WithKeyAndType( keyId, PropertyType.CHAR ).or( ((Character)value).charValue() );
            record.setSinglePropBlock( bits.getLongs()[0] );
        }
        else if ( value instanceof Short )
        {
            Bits bits = bits32WithKeyAndType( keyId, PropertyType.SHORT ).or( ((Short)value).shortValue() );
            record.setSinglePropBlock( bits.getLongs()[0] );
        }
        else if ( value.getClass().isArray() )
        {
            if ( ShortArray.encode( keyId, value, record, DEFAULT_PAYLOAD_SIZE ) ) return;
            long arrayBlockId = nextArrayBlockId();
            Bits bits = bits32WithKeyAndType( keyId, PropertyType.ARRAY ).or( arrayBlockId, 0xFFFFFFFFFL );
            record.setSinglePropBlock( bits.getLongs()[0] );
            Collection<DynamicRecord> arrayRecords = allocateArrayRecords(
                arrayBlockId, value );
            for ( DynamicRecord valueRecord : arrayRecords )
            {
                valueRecord.setType( PropertyType.ARRAY.intValue() );
                record.addValueRecord( valueRecord );
            }
        }
        else
        {
            throw new IllegalArgumentException( "Unknown property type on: "
                + value + ", " + value.getClass() );
        }
    }

    // TODO Assume only one prop per record for now
    private Bits bits32WithKeyAndType( int keyId, PropertyType type )
    {
        return bits( 8 ).or( keyId, 0xFFFFFF ).shiftLeft( 4 ).or( type.intValue(), 0xF ).shiftLeft( 36 );
    }

    // TODO Assume only one prop per record for now
    private Bits bits64WithKeyAndType( int keyId, PropertyType type )
    {
        return bits( 16 ).or( keyId, 0xFFFFFF ).shiftLeft( 4 ).or( type.intValue(), 0xF ).shiftLeft( 36+64 );
    }

    public Object getStringFor( PropertyRecord propRecord )
    {
        long recordToFind = propRecord.getSinglePropBlock() & 0xFFFFFFFFFL;
        Map<Long,DynamicRecord> recordsMap = new HashMap<Long,DynamicRecord>();
        for ( DynamicRecord record : propRecord.getValueRecords() )
        {
            recordsMap.put( record.getId(), record );
        }
        List<char[]> charList = new LinkedList<char[]>();
//        int totalSize = 0;
        while ( recordToFind != Record.NO_NEXT_BLOCK.intValue() )
        {
            DynamicRecord record = recordsMap.get( recordToFind );
            if ( record.isLight() )
            {
                stringPropertyStore.makeHeavy( record );
            }
            if ( !record.isCharData() )
            {
                ByteBuffer buf = ByteBuffer.wrap( record.getData() );
                char[] chars = new char[record.getData().length / 2];
//                totalSize += chars.length;
                buf.asCharBuffer().get( chars );
                charList.add( chars );
            }
            else
            {
                charList.add( record.getDataAsChar() );
            }
            recordToFind = record.getNextBlock();
        }
        StringBuffer buf = new StringBuffer();
        for ( char[] str : charList )
        {
            buf.append( str );
        }
        return buf.toString();
    }

    public Object getArrayFor( PropertyRecord propertyRecord )
    {
        return getArrayFor( propertyRecord.getSinglePropBlock() & 0xFFFFFFFFFL,
                propertyRecord.getValueRecords(), arrayPropertyStore );
    }

    public static Object getArrayFor( long startRecord, Iterable<DynamicRecord> records,
            DynamicArrayStore arrayPropertyStore )
    {
        long recordToFind = startRecord;
        Map<Long,DynamicRecord> recordsMap = new HashMap<Long,DynamicRecord>();
        for ( DynamicRecord record : records )
        {
            recordsMap.put( record.getId(), record );
        }
        List<byte[]> byteList = new LinkedList<byte[]>();
        int totalSize = 0;
        while ( recordToFind != Record.NO_NEXT_BLOCK.intValue() )
        {
            DynamicRecord record = recordsMap.get( recordToFind );
            if ( record.isLight() )
            {
                arrayPropertyStore.makeHeavy( record );
            }
            if ( !record.isCharData() )
            {
                ByteBuffer buf = ByteBuffer.wrap( record.getData() );
                byte[] bytes = new byte[record.getData().length];
                totalSize += bytes.length;
                buf.get( bytes );
                byteList.add( bytes );
            }
            else
            {
                throw new InvalidRecordException(
                    "Expected byte data on record " + record );
            }
            recordToFind = record.getNextBlock();
        }
        byte[] bArray = new byte[totalSize];
        int offset = 0;
        for ( byte[] currentArray : byteList )
        {
            System.arraycopy( currentArray, 0, bArray, offset,
                currentArray.length );
            offset += currentArray.length;
        }
        return arrayPropertyStore.getRightArray( bArray );
    }

    @Override
    protected boolean versionFound( String version )
    {
        if ( !version.startsWith( "PropertyStore" ) )
        {
            // non clean shutdown, need to do recover with right neo
            return false;
        }
//        if ( version.equals( "PropertyStore v0.9.3" ) )
//        {
//            rebuildIdGenerator();
//            closeIdGenerator();
//            return true;
//        }
        if ( version.equals( "PropertyStore v0.9.5" ) )
        {
            return true;
        }
        throw new IllegalStoreVersionException( "Store version [" + version  +
            "]. Please make sure you are not running old Neo4j kernel " +
            " towards a store that has been created by newer version " +
            " of Neo4j." );
    }

    @Override
    public List<WindowPoolStats> getAllWindowPoolStats()
    {
        List<WindowPoolStats> list = new ArrayList<WindowPoolStats>();
        list.add( stringPropertyStore.getWindowPoolStats() );
        list.add( arrayPropertyStore.getWindowPoolStats() );
        list.add( getWindowPoolStats() );
        return list;
    }

    public int getStringBlockSize()
    {
        return stringPropertyStore.getBlockSize();
    }

    public int getArrayBlockSize()
    {
        return arrayPropertyStore.getBlockSize();
    }
}