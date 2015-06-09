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
package org.apache.cassandra.db;

import java.nio.ByteBuffer;
import java.util.*;

import org.apache.cassandra.config.ColumnDefinition;
import org.apache.cassandra.cql3.Operator;
import org.apache.cassandra.db.rows.Row;
import org.apache.cassandra.db.rows.RowIterator;
import org.apache.cassandra.db.filter.*;
import org.apache.cassandra.db.partitions.*;

public abstract class AbstractReadCommandBuilder
{
    protected final ColumnFamilyStore cfs;
    protected final int nowInSeconds;

    protected int cqlLimit = 100000;
    protected int pagingLimit = -1;
    protected ColumnFilter filter;
    protected ByteBuffer superColumn;
    protected boolean reversed = false;
    protected List<ByteBuffer> columns = new ArrayList<>();

    protected Slice.Bound lowerClusteringBound;
    protected Slice.Bound upperClusteringBound;

    public AbstractReadCommandBuilder(ColumnFamilyStore cfs, int nowInSeconds)
    {
        this.cfs = cfs;
        this.nowInSeconds = nowInSeconds;
        filter = ColumnFilter.create();
    }

    public AbstractReadCommandBuilder setClusteringLowerBound(boolean inclusive, ByteBuffer... values)
    {
        ClusteringPrefix.Kind kind = inclusive ? ClusteringPrefix.Kind.INCL_START_BOUND : ClusteringPrefix.Kind.EXCL_START_BOUND;
        lowerClusteringBound = Slice.Bound.create(kind, values);
        return this;
    }

    public AbstractReadCommandBuilder setClusteringUpperBound(boolean inclusive, ByteBuffer... values)
    {
        ClusteringPrefix.Kind kind = inclusive ? ClusteringPrefix.Kind.INCL_END_BOUND : ClusteringPrefix.Kind.EXCL_END_BOUND;
        upperClusteringBound = Slice.Bound.create(kind, values);
        return this;
    }

    public AbstractReadCommandBuilder setReversed(boolean val)
    {
        reversed = val;
        return this;
    }

    public AbstractReadCommandBuilder setCQLLimit(int newLimit)
    {
        cqlLimit = newLimit;
        return this;
    }

    public AbstractReadCommandBuilder setPagingLimit(int newLimit)
    {
        pagingLimit = newLimit;
        return this;
    }

    public AbstractReadCommandBuilder replaceFilter(ColumnFilter filter)
    {
        this.filter = filter;
        return this;
    }

    public AbstractReadCommandBuilder setSuper(ByteBuffer sc)
    {
        this.superColumn = sc;
        return this;
    }

    public AbstractReadCommandBuilder addColumn(ByteBuffer column)
    {
        columns.add(column);
        return this;
    }

    public AbstractReadCommandBuilder addFilter(ColumnDefinition cdef, Operator op, ByteBuffer value)
    {
        filter.add(cdef, op, value);
        return this;
    }

    public PartitionIterator executeLocally()
    {
        return build().executeLocally();
    }

    public OnlyRow getOnlyRow()
    {
        return new OnlyRow(build().executeLocally());
    }

    public static List<Row> getRowList(PartitionIterator iter)
    {
        List<Row> results = new ArrayList<>();
        while (iter.hasNext())
        {
            RowIterator ri = iter.next();
            while (ri.hasNext())
                results.add(ri.next());
        }
        return results;
    }

    public static class OnlyRow implements AutoCloseable
    {
        private final PartitionIterator iterator;
        public final Row row;

        public OnlyRow(PartitionIterator iter)
        {
            iterator = iter;
            while (iterator.hasNext())
            {
                RowIterator ri = iterator.next();
                assert !iterator.hasNext() : "Expected single row result, have more than 1.";
                row = ri.next();
                assert !ri.hasNext() : "Expected single row result, have more than 1.";
                return;
            }
            throw new RuntimeException("Attempted to query single row but no results were in result set.");
        }

        public void close()
        {
            iterator.close();
        }
    }

    public abstract ReadCommand build();
}
