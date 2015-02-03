package org.apache.cassandra.db.index;

import org.apache.cassandra.config.CFMetaData;
import org.apache.cassandra.config.ColumnDefinition;
import org.apache.cassandra.db.ColumnFamily;
import org.apache.cassandra.db.ColumnFamilyStore;
import org.apache.cassandra.db.Mutation;
import org.apache.cassandra.db.composites.CellName;
import org.apache.cassandra.db.composites.CellNameType;
import org.apache.cassandra.db.marshal.AbstractType;
import org.apache.cassandra.dht.LocalPartitioner;

import java.nio.ByteBuffer;
import java.util.Collection;

public class GlobalIndex
{
    private ColumnDefinition target;
    private Collection<ColumnDefinition> denormalized;

    private String indexName;
    private ColumnFamilyStore baseCfs;
    private ColumnFamilyStore indexCfs;

    private void createIndexCfs()
    {
        assert baseCfs != null && target != null;

        CellNameType indexComparator = SecondaryIndex.getIndexComparator(baseCfs.metadata, target);
        CFMetaData indexedCfMetadata = CFMetaData.newIndexMetadata(baseCfs.metadata, target, indexComparator);
        indexCfs = ColumnFamilyStore.createColumnFamilyStore(baseCfs.keyspace,
                indexedCfMetadata.cfName,
                new LocalPartitioner(target.type),
                indexedCfMetadata);
    }

    /**
     * Check to see if any value that is part of the index is updated. If so, we possibly need to mutate the index.
     *
     * @param cf Column family to check for indexed values with
     * @return True if any of the indexed or denormalized values are contained in the column family.
     */
    private boolean containsIndex(ColumnFamily cf)
    {
        // If we are denormalizing all of the columns, then any non-empty column family will need to be indexed
        if (denormalized == null && !cf.isEmpty())
            return true;

        for (CellName cellName : cf.getColumnNames())
        {
            AbstractType<?> indexComparator = cf.metadata().getColumnDefinitionComparator(target);
            if (indexComparator.compare(target.name.bytes, cellName.toByteBuffer()) == 0)
                return true;
            for (ColumnDefinition column : denormalized)
            {
                AbstractType<?> denormalizedComparator = cf.metadata().getColumnDefinitionComparator(column);
                if (denormalizedComparator.compare(column.name.bytes, cellName.toByteBuffer()) == 0)
                    return true;
            }
        }
        return false;
    }

    public Collection<Mutation> createMutations(ByteBuffer key, ColumnFamily cf)
    {
        if (!containsIndex(cf))
        {
            return null;
        }

        return null;
    }
}