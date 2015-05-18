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
package org.apache.cassandra.db.index;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;

import com.google.common.base.Objects;
import com.google.common.collect.Lists;
import com.google.common.util.concurrent.Striped;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.cassandra.config.ColumnDefinition;
import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.config.GlobalIndexDefinition;
import org.apache.cassandra.cql3.ColumnIdentifier;
import org.apache.cassandra.db.ColumnFamily;
import org.apache.cassandra.db.ColumnFamilyStore;
import org.apache.cassandra.db.ConsistencyLevel;
import org.apache.cassandra.db.IMutation;
import org.apache.cassandra.db.Keyspace;
import org.apache.cassandra.db.Mutation;
import org.apache.cassandra.db.SystemKeyspace;
import org.apache.cassandra.exceptions.InvalidRequestException;
import org.apache.cassandra.exceptions.WriteTimeoutException;
import org.apache.cassandra.service.StorageProxy;
import org.apache.cassandra.service.StorageService;
import org.apache.cassandra.utils.ByteBufferUtil;

public class GlobalIndexManager implements IndexManager
{
    private static final Striped<Lock> LOCKS = Striped.lazyWeakLock(DatabaseDescriptor.getConcurrentCounterWriters() * 1024);
    private static final Logger logger = LoggerFactory.getLogger(GlobalIndexManager.class);

    private final Set<GlobalIndex> allIndexes;

    /**
     * Organizes the indexes by column name
     */
    private final ConcurrentNavigableMap<ByteBuffer, GlobalIndex> indexesByColumn;

    private final ColumnFamilyStore baseCfs;

    public GlobalIndexManager(ColumnFamilyStore baseCfs)
    {
        this.indexesByColumn = new ConcurrentSkipListMap<>();
        this.allIndexes = Collections.newSetFromMap(new ConcurrentHashMap<GlobalIndex, Boolean>());

        this.baseCfs = baseCfs;
    }

    public void reload()
    {
        Map<ByteBuffer, GlobalIndexDefinition> newIndexesByColumn = new HashMap<>();
        for (GlobalIndexDefinition definition: baseCfs.metadata.getGlobalIndexes().values())
        {
            newIndexesByColumn.put(definition.target.bytes, definition);
        }

        for (ByteBuffer indexedColumn: indexesByColumn.keySet())
        {
            if (!newIndexesByColumn.containsKey(indexedColumn))
                removeIndexedColumn(indexedColumn);
        }

        for (ByteBuffer indexedColumn: newIndexesByColumn.keySet())
        {
            if (!indexesByColumn.containsKey(indexedColumn))
                addIndexedColumn(newIndexesByColumn.get(indexedColumn));
        }

        for (GlobalIndex index: allIndexes)
            index.reload();
    }

    public void buildIfRequired()
    {
        for (GlobalIndex index: allIndexes)
            index.build();
    }

    private void removeIndexedColumn(ByteBuffer column)
    {
        GlobalIndex index = indexesByColumn.remove(column);

        if (index == null)
            return;

        allIndexes.remove(index);

        SystemKeyspace.setIndexRemoved(baseCfs.metadata.ksName, index.indexName);
    }

    public void addIndexedColumn(GlobalIndexDefinition definition)
    {
        ColumnDefinition targetCd = baseCfs.metadata.getColumnDefinition(definition.target);
        assert targetCd != null;

        Collection<ColumnDefinition> includedDefs = new ArrayList<>();
        for (ColumnIdentifier identifier : definition.included)
        {
            ColumnDefinition cfDef = baseCfs.metadata.getColumnDefinition(identifier);
            assert cfDef != null;
            includedDefs.add(cfDef);
        }

        GlobalIndex index = new GlobalIndex(definition, targetCd, includedDefs, baseCfs);

        indexesByColumn.put(definition.target.bytes, index);

        allIndexes.add(index);
    }

    public GlobalIndex getIndexForColumn(ByteBuffer column)
    {
        return indexesByColumn.get(column);
    }

    public void pushReplicaMutations(ByteBuffer key, ColumnFamily cf)
    throws WriteTimeoutException
    {
        // TODO - When we are replaying the commitlog, we haven't yet joined the ring, so we can't push new mutations
        if (!StorageService.instance.isJoined()) return;

        List<Mutation> mutations = null;
        for (GlobalIndex index: allIndexes)
        {
            Collection<Mutation> indexMutations = index.createMutations(key, cf, ConsistencyLevel.ONE, false);
            if (indexMutations != null && !indexMutations.isEmpty())
            {
                if (mutations == null)
                    mutations = Lists.newLinkedList();
                mutations.addAll(indexMutations);
            }
        }
        if (mutations != null)
        {
            StorageProxy.mutateGI(key, mutations);
        }
    }

    public boolean cfModifiesIndexedColumn(ColumnFamily cf)
    {
        for (GlobalIndex index: allIndexes)
        {
            if (index.cfModifiesIndexedColumn(cf))
                return true;
        }
        return false;
    }

    public Lock acquireLockFor(ByteBuffer key)
    {
        Lock lock = LOCKS.get(Objects.hashCode(baseCfs.metadata.cfId, key));
        try
        {
            long timeout = TimeUnit.NANOSECONDS.convert(DatabaseDescriptor.getWriteRpcTimeout(), TimeUnit.MILLISECONDS);
            long startTime = System.nanoTime();
            while (!lock.tryLock(1, TimeUnit.MILLISECONDS))
            {
                if (System.nanoTime() - startTime > timeout)
                {
                    throw new InterruptedException();
                }
            }
            return lock;
        }
        catch (InterruptedException e)
        {
            logger.info("Could not acquire lock for {}", ByteBufferUtil.bytesToHex(key));
            throw new InvalidRequestException("Could not obtain lock for " + ByteBufferUtil.bytesToHex(key));
        }
    }

    public static boolean touchesIndexedColumns(Collection<? extends IMutation> mutations)
    {
        for (IMutation mutation : mutations)
        {
            for (ColumnFamily cf : mutation.getColumnFamilies())
            {
                GlobalIndexManager indexManager = Keyspace.open(cf.metadata().ksName)
                                                          .getColumnFamilyStore(cf.metadata().cfId).globalIndexManager;
                if (indexManager.cfModifiesIndexedColumn(cf))
                    return true;
            }
        }
        return false;
    }
}
