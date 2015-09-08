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
package org.apache.cassandra.db.rows;

import java.util.*;

import com.google.common.collect.Iterators;
import com.google.common.collect.PeekingIterator;

import org.apache.cassandra.config.ColumnDefinition;
import org.apache.cassandra.db.*;
import org.apache.cassandra.db.partitions.PartitionStatisticsCollector;
import org.apache.cassandra.utils.MergeIterator;

/**
 * Static utilities to work on Row objects.
 */
public abstract class Rows
{
    private Rows() {}

    public static final Row EMPTY_STATIC_ROW = BTreeRow.emptyRow(Clustering.STATIC_CLUSTERING);

    public static Row.Builder copy(Row row, Row.Builder builder)
    {
        builder.newRow(row.clustering());
        builder.addPrimaryKeyLivenessInfo(row.primaryKeyLivenessInfo());
        builder.addRowDeletion(row.deletion());
        for (ColumnData cd : row)
        {
            if (cd.column().isSimple())
            {
                builder.addCell((Cell)cd);
            }
            else
            {
                ComplexColumnData complexData = (ComplexColumnData)cd;
                builder.addComplexDeletion(complexData.column(), complexData.complexDeletion());
                for (Cell cell : complexData)
                    builder.addCell(cell);
            }
        }
        return builder;
    }

    /**
     * Collect statistics ont a given row.
     *
     * @param row the row for which to collect stats.
     * @param collector the stats collector.
     * @return the total number of cells in {@code row}.
     */
    public static int collectStats(Row row, PartitionStatisticsCollector collector)
    {
        assert !row.isEmpty();

        collector.update(row.primaryKeyLivenessInfo());
        collector.update(row.deletion());

        int columnCount = 0;
        int cellCount = 0;
        for (ColumnData cd : row)
        {
            if (cd.column().isSimple())
            {
                ++columnCount;
                ++cellCount;
                Cells.collectStats((Cell)cd, collector);
            }
            else
            {
                ComplexColumnData complexData = (ComplexColumnData)cd;
                collector.update(complexData.complexDeletion());
                if (complexData.hasCells())
                {
                    ++columnCount;
                    for (Cell cell : complexData)
                    {
                        ++cellCount;
                        Cells.collectStats(cell, collector);
                    }
                }
            }

        }
        collector.updateColumnSetPerRow(columnCount);
        return cellCount;
    }

    /**
     * Given the result ({@code merged}) of merging multiple {@code inputs}, signals the difference between
     * each input and {@code merged} to {@code diffListener}.
     *
     * @param merged the result of merging {@code inputs}.
     * @param inputs the inputs whose merge yielded {@code merged}.
     * @param diffListener the listener to which to signal the differences between the inputs and the merged
     * result.
     */
    public static void diff(RowDiffListener diffListener, Row merged, Row...inputs)
    {
        Clustering clustering = merged.clustering();
        LivenessInfo mergedInfo = merged.primaryKeyLivenessInfo().isEmpty() ? null : merged.primaryKeyLivenessInfo();
        DeletionTime mergedDeletion = merged.deletion().isLive() ? null : merged.deletion();
        for (int i = 0; i < inputs.length; i++)
        {
            Row input = inputs[i];
            LivenessInfo inputInfo = input == null || input.primaryKeyLivenessInfo().isEmpty() ? null : input.primaryKeyLivenessInfo();
            DeletionTime inputDeletion = input == null || input.deletion().isLive() ? null : input.deletion();

            if (mergedInfo != null || inputInfo != null)
                diffListener.onPrimaryKeyLivenessInfo(i, clustering, mergedInfo, inputInfo);
            if (mergedDeletion != null || inputDeletion != null)
                diffListener.onDeletion(i, clustering, mergedDeletion, inputDeletion);
        }

        List<Iterator<ColumnData>> inputIterators = new ArrayList<>(1 + inputs.length);
        inputIterators.add(merged.iterator());
        for (Row row : inputs)
            inputIterators.add(row == null ? Collections.emptyIterator() : row.iterator());

        Iterator<?> iter = MergeIterator.get(inputIterators, ColumnData.comparator, new MergeIterator.Reducer<ColumnData, Object>()
        {
            ColumnData mergedData;
            ColumnData[] inputDatas = new ColumnData[inputs.length];
            public void reduce(int idx, ColumnData current)
            {
                if (idx == 0)
                    mergedData = current;
                else
                    inputDatas[idx - 1] = current;
            }

            protected Object getReduced()
            {
                for (int i = 0 ; i != inputDatas.length ; i++)
                {
                    ColumnData input = inputDatas[i];
                    if (mergedData != null || input != null)
                    {
                        ColumnDefinition column = (mergedData != null ? mergedData : input).column;
                        if (column.isSimple())
                        {
                            diffListener.onCell(i, clustering, (Cell) mergedData, (Cell) input);
                        }
                        else
                        {
                            ComplexColumnData mergedData = (ComplexColumnData) this.mergedData;
                            ComplexColumnData inputData = (ComplexColumnData) input;
                            if (mergedData == null)
                            {
                                // Everything in inputData has been shadowed
                                if (!inputData.complexDeletion().isLive())
                                    diffListener.onComplexDeletion(i, clustering, column, null, inputData.complexDeletion());
                                for (Cell inputCell : inputData)
                                    diffListener.onCell(i, clustering, null, inputCell);
                            }
                            else if (inputData == null)
                            {
                                // Everything in inputData is new
                                if (!mergedData.complexDeletion().isLive())
                                    diffListener.onComplexDeletion(i, clustering, column, mergedData.complexDeletion(), null);
                                for (Cell mergedCell : mergedData)
                                    diffListener.onCell(i, clustering, mergedCell, null);
                            }
                            else
                            {
                                PeekingIterator<Cell> mergedCells = Iterators.peekingIterator(mergedData.iterator());
                                PeekingIterator<Cell> inputCells = Iterators.peekingIterator(inputData.iterator());
                                while (mergedCells.hasNext() && inputCells.hasNext())
                                {
                                    int cmp = column.cellPathComparator().compare(mergedCells.peek().path(), inputCells.peek().path());
                                    if (cmp == 0)
                                        diffListener.onCell(i, clustering, mergedCells.next(), inputCells.next());
                                    else if (cmp < 0)
                                        diffListener.onCell(i, clustering, mergedCells.next(), null);
                                    else // cmp > 0
                                        diffListener.onCell(i, clustering, null, inputCells.next());
                                }
                                while (mergedCells.hasNext())
                                    diffListener.onCell(i, clustering, mergedCells.next(), null);
                                while (inputCells.hasNext())
                                    diffListener.onCell(i, clustering, null, inputCells.next());
                            }
                        }
                    }

                }
                return null;
            }

            protected void onKeyChange()
            {
                mergedData = null;
                Arrays.fill(inputDatas, null);
            }
        });

        while (iter.hasNext())
            iter.next();
    }

    public static Row merge(Row row1, Row row2, int nowInSec)
    {
        Row.Builder builder = BTreeRow.sortedBuilder();
        merge(row1, row2, builder, nowInSec);
        return builder.build();
    }

    // Merge rows in memtable
    // Return the minimum timestamp delta between existing and update
    public static long merge(Row existing,
                             Row update,
                             Row.Builder builder,
                             int nowInSec)
    {
        Clustering clustering = existing.clustering();
        builder.newRow(clustering);

        LivenessInfo existingInfo = existing.primaryKeyLivenessInfo();
        LivenessInfo updateInfo = update.primaryKeyLivenessInfo();
        LivenessInfo mergedInfo = existingInfo.supersedes(updateInfo) ? existingInfo : updateInfo;

        long timeDelta = Math.abs(existingInfo.timestamp() - mergedInfo.timestamp());

        DeletionTime deletion = existing.deletion().supersedes(update.deletion()) ? existing.deletion() : update.deletion();
        if (deletion.deletes(mergedInfo))
            mergedInfo = LivenessInfo.EMPTY;

        builder.addPrimaryKeyLivenessInfo(mergedInfo);

        //If view cleanup tombstone we check if the row marker timestamp is supersedes the view cleanup
        //meaning there is no longer a need for the cleanup. We can ignore this cleanup timestamp
        if (deletion.isViewCleanup && mergedInfo.timestamp() >= deletion.markedForDeleteAt())
            deletion = DeletionTime.LIVE;
        
        builder.addRowDeletion(deletion);

        Iterator<ColumnData> a = existing.iterator();
        Iterator<ColumnData> b = update.iterator();
        ColumnData nexta = a.hasNext() ? a.next() : null, nextb = b.hasNext() ? b.next() : null;
        while (nexta != null | nextb != null)
        {
            int comparison = nexta == null ? 1 : nextb == null ? -1 : nexta.column.compareTo(nextb.column);
            ColumnData cura = comparison <= 0 ? nexta : null;
            ColumnData curb = comparison >= 0 ? nextb : null;
            ColumnDefinition column = (cura != null ? cura : curb).column;
            if (column.isSimple())
            {
                timeDelta = Math.min(timeDelta, Cells.reconcile((Cell) cura, (Cell) curb, deletion, builder, nowInSec));
            }
            else
            {
                ComplexColumnData existingData = (ComplexColumnData) cura;
                ComplexColumnData updateData = (ComplexColumnData) curb;

                DeletionTime existingDt = existingData == null ? DeletionTime.LIVE : existingData.complexDeletion();
                DeletionTime updateDt = updateData == null ? DeletionTime.LIVE : updateData.complexDeletion();
                DeletionTime maxDt = existingDt.supersedes(updateDt) ? existingDt : updateDt;
                if (maxDt.supersedes(deletion))
                    builder.addComplexDeletion(column, maxDt);
                else
                    maxDt = deletion;

                Iterator<Cell> existingCells = existingData == null ? null : existingData.iterator();
                Iterator<Cell> updateCells = updateData == null ? null : updateData.iterator();
                timeDelta = Math.min(timeDelta, Cells.reconcileComplex(column, existingCells, updateCells, maxDt, builder, nowInSec));
            }

            if (cura != null)
                nexta = a.hasNext() ? a.next() : null;
            if (curb != null)
                nextb = b.hasNext() ? b.next() : null;
        }
        return timeDelta;
    }
}
