package org.apache.cassandra;
/*
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
 */

import java.io.EOFException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.collect.Iterators;
import org.apache.commons.lang3.StringUtils;

import org.apache.cassandra.config.CFMetaData;
import org.apache.cassandra.config.ColumnDefinition;
import org.apache.cassandra.cql3.ColumnIdentifier;
import org.apache.cassandra.cql3.Operator;
import org.apache.cassandra.db.*;
import org.apache.cassandra.db.Slice.Bound;
import org.apache.cassandra.db.rows.*;
import org.apache.cassandra.db.compaction.AbstractCompactionTask;
import org.apache.cassandra.db.compaction.CompactionManager;
import org.apache.cassandra.db.filter.*;
import org.apache.cassandra.db.marshal.AbstractType;
import org.apache.cassandra.db.partitions.*;
import org.apache.cassandra.dht.IPartitioner;
import org.apache.cassandra.dht.RandomPartitioner.BigIntegerToken;
import org.apache.cassandra.dht.Range;
import org.apache.cassandra.dht.Token;
import org.apache.cassandra.gms.ApplicationState;
import org.apache.cassandra.gms.Gossiper;
import org.apache.cassandra.gms.VersionedValue;
import org.apache.cassandra.io.sstable.Descriptor;
import org.apache.cassandra.io.sstable.format.SSTableReader;
import org.apache.cassandra.service.StorageService;
import org.apache.cassandra.utils.ByteBufferUtil;
import org.apache.cassandra.utils.CounterId;
import org.apache.cassandra.utils.FBUtilities;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class Util
{
    private static List<UUID> hostIdPool = new ArrayList<UUID>();

    public static DecoratedKey dk(String key)
    {
        return StorageService.getPartitioner().decorateKey(ByteBufferUtil.bytes(key));
    }

    public static DecoratedKey dk(String key, AbstractType type)
    {
        return StorageService.getPartitioner().decorateKey(type.fromString(key));
    }

    public static DecoratedKey dk(ByteBuffer key)
    {
        return StorageService.getPartitioner().decorateKey(key);
    }

    public static PartitionPosition rp(String key)
    {
        return rp(key, StorageService.getPartitioner());
    }

    public static PartitionPosition rp(String key, IPartitioner partitioner)
    {
        return PartitionPosition.ForKey.get(ByteBufferUtil.bytes(key), partitioner);
    }

    public static Cell getRegularCell(CFMetaData metadata, Row row, String name)
    {
        ColumnDefinition column = metadata.getColumnDefinition(ByteBufferUtil.bytes(name));
        assert column != null;
        return row.getCell(column);
    }

    public static ClusteringPrefix clustering(ClusteringComparator comparator, Object... o)
    {
        return comparator.make(o).clustering();
    }

    /*
    public static CellName cellname(String... strs)
    {
        ByteBuffer[] bbs = new ByteBuffer[strs.length];
        for (int i = 0; i < strs.length; i++)
            bbs[i] = ByteBufferUtil.bytes(strs[i]);
        return cellname(bbs);
    }

    public static CellName cellname(int i)
    {
        return CellNames.simpleDense(ByteBufferUtil.bytes(i));
    }

    public static CellName cellname(long l)
    {
        return CellNames.simpleDense(ByteBufferUtil.bytes(l));
    }

    public static Cell column(String name, String value, long timestamp)
    {
        return new BufferCell(cellname(name), ByteBufferUtil.bytes(value), timestamp);
    }

    public static Cell expiringColumn(String name, String value, long timestamp, int ttl)
    {
        return new BufferExpiringCell(cellname(name), ByteBufferUtil.bytes(value), timestamp, ttl);
    }
    */

    public static Token token(String key)
    {
        return StorageService.getPartitioner().getToken(ByteBufferUtil.bytes(key));
    }


    public static Range<PartitionPosition> range(String left, String right)
    {
        return new Range<>(rp(left), rp(right));
    }

    public static Range<PartitionPosition> range(IPartitioner p, String left, String right)
    {
        return new Range<>(rp(left, p), rp(right, p));
    }

    //Test helper to make an iterator iterable once
    public static <T> Iterable<T> once(final Iterator<T> source)
    {
        return new Iterable<T>()
        {
            private AtomicBoolean exhausted = new AtomicBoolean();
            public Iterator<T> iterator()
            {
                Preconditions.checkState(!exhausted.getAndSet(true));
                return source;
            }
        };
    }

    /*
    public static Bounds<PartitionPosition> bounds(String left, String right)
    {
        return new Bounds<PartitionPosition>(rp(left), rp(right));
    }

    public static void addMutation(Mutation rm, String columnFamilyName, String superColumnName, long columnName, String value, long timestamp)
    {
        CellName cname = superColumnName == null
                       ? CellNames.simpleDense(getBytes(columnName))
                       : CellNames.compositeDense(ByteBufferUtil.bytes(superColumnName), getBytes(columnName));
        rm.add(columnFamilyName, cname, ByteBufferUtil.bytes(value), timestamp);
    }
    */

    public static ByteBuffer getBytes(long v)
    {
        byte[] bytes = new byte[8];
        ByteBuffer bb = ByteBuffer.wrap(bytes);
        bb.putLong(v);
        bb.rewind();
        return bb;
    }

    public static ByteBuffer getBytes(int v)
    {
        byte[] bytes = new byte[4];
        ByteBuffer bb = ByteBuffer.wrap(bytes);
        bb.putInt(v);
        bb.rewind();
        return bb;
    }

    public static UnfilteredPartitionIterator getRangeSlice(ColumnFamilyStore cfs)
    {
        return getRangeSlice(cfs, null);
    }
    public static UnfilteredPartitionIterator getRangeSlice(ColumnFamilyStore cfs, ByteBuffer superColumn)
    {
        ColumnFilter filter = ColumnFilter.create();
        if (superColumn != null)
            filter.add(cfs.metadata.compactValueColumn(), Operator.EQ, superColumn);
        
        ReadCommand command = new PartitionRangeReadCommand(cfs.metadata, FBUtilities.nowInSeconds(), filter, DataLimits.cqlLimits(100000), DataRange.allData(cfs.metadata, cfs.partitioner));

        return command.executeLocally(cfs);
    }
    public static PartitionIterator getRangeSlice(ColumnFamilyStore cfs,
                                             ByteBuffer startKey,
                                             ByteBuffer endKey,
                                             ByteBuffer superColumn,
                                             ColumnFilter filter,
                                             ByteBuffer... columns)
    {
        return makeReadCommand(cfs, startKey, endKey, superColumn, filter, columns).executeLocally();
    }

    public static ReadCommand makeReadCommand(ColumnFamilyStore cfs,
                                              ByteBuffer startKey,
                                              ByteBuffer endKey,
                                              ByteBuffer superColumn,
                                              ColumnFilter filter,
                                              ByteBuffer... columns)
    {
        AbstractReadCommandBuilder builder = new PartitionRangeReadBuilder(cfs, FBUtilities.nowInSeconds())
                .setKeyBounds(startKey, endKey)
                .replaceFilter(filter);

        for (ByteBuffer bb : columns)
            builder.addColumn(bb);

        if (superColumn != null)
            builder.setSuper(superColumn);

        return builder.build();
    }

    /**
     * Writes out a bunch of mutations for a single column family.
     *
     * @param mutations A group of Mutations for the same keyspace and column family.
     * @return The ColumnFamilyStore that was used.
     */
    public static ColumnFamilyStore writeColumnFamily(List<Mutation> mutations)
    {
        IMutation first = mutations.get(0);
        String keyspaceName = first.getKeyspaceName();
        UUID cfid = first.getColumnFamilyIds().iterator().next();

        for (Mutation rm : mutations)
            rm.applyUnsafe();

        ColumnFamilyStore store = Keyspace.open(keyspaceName).getColumnFamilyStore(cfid);
        store.forceBlockingFlush();
        return store;
    }

    /*
    public static ColumnFamily getColumnFamily(Keyspace keyspace, DecoratedKey key, String cfName)
    {
        ColumnFamilyStore cfStore = keyspace.getColumnFamilyStore(cfName);
        assert cfStore != null : "Table " + cfName + " has not been defined";
        return cfStore.getColumnFamily(QueryFilter.getIdentityFilter(key, cfName, System.currentTimeMillis()));
    }
    */

    public static boolean equalsCounterId(CounterId n, ByteBuffer context, int offset)
    {
        return CounterId.wrap(context, context.position() + offset).equals(n);
    }

    /*
    public static ColumnFamily cloneAndRemoveDeleted(ColumnFamily cf, int gcBefore)
    {
        return ColumnFamilyStore.removeDeleted(cf.cloneMe(), gcBefore);
    }
    */

    /**
     * Creates initial set of nodes and tokens. Nodes are added to StorageService as 'normal'
     */
    public static void createInitialRing(StorageService ss, IPartitioner partitioner, List<Token> endpointTokens,
                                   List<Token> keyTokens, List<InetAddress> hosts, List<UUID> hostIds, int howMany)
        throws UnknownHostException
    {
        // Expand pool of host IDs as necessary
        for (int i = hostIdPool.size(); i < howMany; i++)
            hostIdPool.add(UUID.randomUUID());

        for (int i=0; i<howMany; i++)
        {
            endpointTokens.add(new BigIntegerToken(String.valueOf(10 * i)));
            keyTokens.add(new BigIntegerToken(String.valueOf(10 * i + 5)));
            hostIds.add(hostIdPool.get(i));
        }

        for (int i=0; i<endpointTokens.size(); i++)
        {
            InetAddress ep = InetAddress.getByName("127.0.0." + String.valueOf(i + 1));
            Gossiper.instance.initializeNodeUnsafe(ep, hostIds.get(i), 1);
            Gossiper.instance.injectApplicationState(ep, ApplicationState.TOKENS, new VersionedValue.VersionedValueFactory(partitioner).tokens(Collections.singleton(endpointTokens.get(i))));
            ss.onChange(ep,
                        ApplicationState.STATUS,
                        new VersionedValue.VersionedValueFactory(partitioner).normal(Collections.singleton(endpointTokens.get(i))));
            hosts.add(ep);
        }

        // check that all nodes are in token metadata
        for (int i=0; i<endpointTokens.size(); ++i)
            assertTrue(ss.getTokenMetadata().isMember(hosts.get(i)));
    }

    public static Future<?> compactAll(ColumnFamilyStore cfs, int gcBefore)
    {
        List<Descriptor> descriptors = new ArrayList<>();
        for (SSTableReader sstable : cfs.getSSTables())
            descriptors.add(sstable.descriptor);
        return CompactionManager.instance.submitUserDefined(cfs, descriptors, gcBefore);
    }

    public static void compact(ColumnFamilyStore cfs, Collection<SSTableReader> sstables)
    {
        int gcBefore = cfs.gcBefore(FBUtilities.nowInSeconds());
        AbstractCompactionTask task = cfs.getCompactionStrategy().getUserDefinedTask(sstables, gcBefore);
        task.execute(null);
    }

    public static void expectEOF(Callable<?> callable)
    {
        expectException(callable, EOFException.class);
    }

    public static void expectException(Callable<?> callable, Class<?> exception)
    {
        boolean thrown = false;

        try
        {
            callable.call();
        }
        catch (Throwable e)
        {
            assert e.getClass().equals(exception) : e.getClass().getName() + " is not " + exception.getName();
            thrown = true;
        }

        assert thrown : exception.getName() + " not received";
    }

    @SuppressWarnings("rawtypes")
    public static UnfilteredRowIterator readFullPartition(ColumnFamilyStore cfs, DecoratedKey key)
    {
        SinglePartitionReadCommand cmd = SinglePartitionReadCommand.fullPartitionRead(cfs.metadata, FBUtilities.nowInSeconds(), key);
        return UnfilteredPartitionIterators.getOnlyElement(cmd.executeLocally(cfs), cmd);
    }

    @SuppressWarnings("rawtypes")
    public static UnfilteredRowIterator readPartitionWithLimit(ColumnFamilyStore cfs, DecoratedKey key, int limit)
    {
        SlicePartitionFilter filter = new SlicePartitionFilter(cfs.metadata.partitionColumns(), Slices.ALL, false);
        SinglePartitionReadCommand cmd = SinglePartitionReadCommand.create(cfs.metadata, FBUtilities.nowInSeconds(), ColumnFilter.NONE, DataLimits.cqlLimits(limit), key, filter);
        return UnfilteredPartitionIterators.getOnlyElement(cmd.executeLocally(cfs), cmd);
    }

    @SuppressWarnings("rawtypes")
    public static UnfilteredRowIterator readPartitionWithBounds(ColumnFamilyStore cfs, DecoratedKey key, Bound start, Bound end)
    {
        Slices.Builder sb = new Slices.Builder(cfs.getComparator());
        SlicePartitionFilter filter = new SlicePartitionFilter(cfs.metadata.partitionColumns(), sb.build(), false);
        SinglePartitionReadCommand cmd = SinglePartitionReadCommand.create(cfs.metadata, FBUtilities.nowInSeconds(), ColumnFilter.NONE, DataLimits.NONE, key, filter);
        return UnfilteredPartitionIterators.getOnlyElement(cmd.executeLocally(cfs), cmd);
    }

    public static ArrayBackedPartition materializePartition(ColumnFamilyStore cfs, DecoratedKey key)
    {
        try (UnfilteredRowIterator iter = readFullPartition(cfs, key))
        {
            return FilteredPartition.create(iter);
        }
    }

    public static UnfilteredRowIterator apply(Mutation mutation)
    {
        mutation.apply();
        assert mutation.getPartitionUpdates().size() == 1;
        return mutation.getPartitionUpdates().iterator().next().unfilteredIterator();
    }

    public static Row getSingleRow(ColumnFamilyStore cfs, DecoratedKey dk)
    {
        return materializePartition(cfs, dk).lastRow();
    }

    public static void consume(UnfilteredRowIterator iter)
    {
        try (UnfilteredRowIterator iterator = iter)
        {
            while (iter.hasNext())
                iter.next();
        }
    }

   /* public static QueryFilter namesQueryFilter(ColumnFamilyStore cfs, DecoratedKey key)
    {
        SortedSet<CellName> s = new TreeSet<CellName>(cfs.getComparator());
        return QueryFilter.getNamesFilter(key, cfs.name, s, System.currentTimeMillis());
    }

    public static QueryFilter namesQueryFilter(ColumnFamilyStore cfs, DecoratedKey key, String... names)
    {
        SortedSet<CellName> s = new TreeSet<CellName>(cfs.getComparator());
        for (String str : names)
            s.add(cellname(str));
        return QueryFilter.getNamesFilter(key, cfs.name, s, System.currentTimeMillis());
    }

    public static QueryFilter namesQueryFilter(ColumnFamilyStore cfs, DecoratedKey key, CellName... names)
    {
        SortedSet<CellName> s = new TreeSet<CellName>(cfs.getComparator());
        for (CellName n : names)
            s.add(n);
        return QueryFilter.getNamesFilter(key, cfs.name, s, System.currentTimeMillis());
    }

    public static NamesQueryFilter namesFilter(ColumnFamilyStore cfs, String... names)
    {
        SortedSet<CellName> s = new TreeSet<CellName>(cfs.getComparator());
        for (String str : names)
            s.add(cellname(str));
        return new NamesQueryFilter(s);
    }

    public static String string(ByteBuffer bb)
    {
        try
        {
            return ByteBufferUtil.string(bb);
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }
    }

    public static RangeTombstone tombstone(String start, String finish, long timestamp, int localtime)
    {
        Composite startName = CellNames.simpleDense(ByteBufferUtil.bytes(start));
        Composite endName = CellNames.simpleDense(ByteBufferUtil.bytes(finish));
        return new RangeTombstone(startName, endName, timestamp , localtime);
    } */

    public static CBuilder getCBuilderForCFM(CFMetaData cfm)
    {
        List<ColumnDefinition> clusteringColumns = cfm.clusteringColumns();
        List<AbstractType<?>> types = new ArrayList<>(clusteringColumns.size());
        for (ColumnDefinition def : clusteringColumns)
            types.add(def.type);
        return CBuilder.create(new ClusteringComparator(types));
    }

    // moved & refactored from KeyspaceTest in < 3.0
    public static void assertColumns(Row row, String... expectedColumnNames)
    {
        Iterator<Cell> cells = row == null ? Iterators.<Cell>emptyIterator() : row.iterator();
        String[] actual = Iterators.toArray(Iterators.transform(cells, new Function<Cell, String>()
        {
            public String apply(Cell cell)
            {
                return cell.column().name.toString();
            }
        }), String.class);

        assert Arrays.equals(actual, expectedColumnNames)
        : String.format("Columns [%s])] is not expected [%s]",
                        ((row == null) ? "" : row.columns().toString()),
                        StringUtils.join(expectedColumnNames, ","));
    }

    public static void assertColumn(CFMetaData cfm, Row row, String name, String value, long timestamp)
    {
        Cell cell = row.getCell(cfm.getColumnDefinition(new ColumnIdentifier(name, true)));
        assertColumn(cell, value, timestamp);
    }

    public static void assertColumn(Cell cell, String value, long timestamp)
    {
        assertNotNull(cell);
        assertEquals(0, ByteBufferUtil.compareUnsigned(cell.value(), ByteBufferUtil.bytes(value)));
        assertEquals(timestamp, cell.livenessInfo().timestamp());
    }

    public static void assertClustering(CFMetaData cfm, Row row, Object... clusteringValue)
    {
        assertEquals(row.clustering().size(), clusteringValue.length);
        assertEquals(0, cfm.comparator.compare(row.clustering(), cfm.comparator.make(clusteringValue)));
    }
}
