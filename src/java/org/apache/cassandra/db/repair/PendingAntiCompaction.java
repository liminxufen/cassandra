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

package org.apache.cassandra.db.repair;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Predicate;
import com.google.common.collect.Iterables;
import com.google.common.util.concurrent.AsyncFunction;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListenableFutureTask;
import com.google.common.util.concurrent.MoreExecutors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.cassandra.db.ColumnFamilyStore;
import org.apache.cassandra.db.compaction.CompactionInfo;
import org.apache.cassandra.db.compaction.CompactionManager;
import org.apache.cassandra.db.compaction.OperationType;
import org.apache.cassandra.db.lifecycle.LifecycleTransaction;
import org.apache.cassandra.dht.Range;
import org.apache.cassandra.dht.Token;
import org.apache.cassandra.io.sstable.format.SSTableReader;
import org.apache.cassandra.io.sstable.metadata.StatsMetadata;
import org.apache.cassandra.locator.RangesAtEndpoint;
import org.apache.cassandra.service.ActiveRepairService;
import org.apache.cassandra.utils.concurrent.Refs;

import static org.apache.cassandra.service.ActiveRepairService.NO_PENDING_REPAIR;
import static org.apache.cassandra.service.ActiveRepairService.UNREPAIRED_SSTABLE;

/**
 * Performs an anti compaction on a set of tables and token ranges, isolating the unrepaired sstables
 * for a give token range into a pending repair group so they can't be compacted with other sstables
 * while they are being repaired.
 */
public class PendingAntiCompaction
{
    private static final Logger logger = LoggerFactory.getLogger(PendingAntiCompaction.class);

    static class AcquireResult
    {
        final ColumnFamilyStore cfs;
        final Refs<SSTableReader> refs;
        final LifecycleTransaction txn;

        AcquireResult(ColumnFamilyStore cfs, Refs<SSTableReader> refs, LifecycleTransaction txn)
        {
            this.cfs = cfs;
            this.refs = refs;
            this.txn = txn;
        }

        void abort()
        {
            if (txn != null)
                txn.abort();
            if (refs != null)
                refs.release();
        }
    }

    static class SSTableAcquisitionException extends RuntimeException
    {
        SSTableAcquisitionException(String message)
        {
            super(message);
        }
    }

    @VisibleForTesting
    static class AntiCompactionPredicate implements Predicate<SSTableReader>
    {
        private final Collection<Range<Token>> ranges;
        private final UUID prsid;

        public AntiCompactionPredicate(Collection<Range<Token>> ranges, UUID prsid)
        {
            this.ranges = ranges;
            this.prsid = prsid;
        }

        public boolean apply(SSTableReader sstable)
        {
            if (!sstable.intersects(ranges))
                return false;

            StatsMetadata metadata = sstable.getSSTableMetadata();

            // exclude repaired sstables
            if (metadata.repairedAt != UNREPAIRED_SSTABLE)
                return false;

            // exclude sstables pending repair, but record session ids for
            // non-finalized sessions for a later error message
            if (metadata.pendingRepair != NO_PENDING_REPAIR)
            {
                if (!ActiveRepairService.instance.consistent.local.isSessionFinalized(metadata.pendingRepair))
                {
                    String message = String.format("Prepare phase for incremental repair session %s has failed because it encountered " +
                                                   "intersecting sstables belonging to another incremental repair session (%s). This is " +
                                                   "caused by starting an incremental repair session before a previous one has completed. " +
                                                   "Check nodetool repair_admin for hung sessions and fix them.", prsid, metadata.pendingRepair);
                    throw new SSTableAcquisitionException(message);
                }
                return false;
            }
            CompactionInfo ci = CompactionManager.instance.active.getCompactionForSSTable(sstable);
            if (ci != null && ci.getTaskType() == OperationType.ANTICOMPACTION)
            {
                // todo: start tracking the parent repair session id that created the anticompaction to be able to give a better error messsage here:
                String message = String.format("Prepare phase for incremental repair session %s has failed because it encountered " +
                                               "intersecting sstables belonging to another incremental repair session. This is " +
                                               "caused by starting multiple conflicting incremental repairs at the same time", prsid);
                throw new SSTableAcquisitionException(message);
            }
            return true;
        }
    }

    static class AcquisitionCallable implements Callable<AcquireResult>
    {
        private final ColumnFamilyStore cfs;
        private final UUID sessionID;
        private final AntiCompactionPredicate predicate;

        public AcquisitionCallable(ColumnFamilyStore cfs, Collection<Range<Token>> ranges, UUID sessionID)
        {
            this.cfs = cfs;
            this.sessionID = sessionID;
            predicate = new AntiCompactionPredicate(ranges, sessionID);
        }

        @SuppressWarnings("resource")
        private AcquireResult acquireTuple()
        {
            // this method runs with compactions stopped & disabled
            try
            {
                // using predicate might throw if there are conflicting ranges
                Set<SSTableReader> sstables = cfs.getLiveSSTables().stream().filter(predicate).collect(Collectors.toSet());
                if (sstables.isEmpty())
                    return new AcquireResult(cfs, null, null);

                LifecycleTransaction txn = cfs.getTracker().tryModify(sstables, OperationType.ANTICOMPACTION);
                if (txn != null)
                    return new AcquireResult(cfs, Refs.ref(sstables), txn);
            }
            catch (SSTableAcquisitionException e)
            {
                logger.warn(e.getMessage());
                logger.debug("Got exception trying to acquire sstables", e);
            }

            return null;
        }

        public AcquireResult call()
        {
            logger.debug("acquiring sstables for pending anti compaction on session {}", sessionID);
            // try to modify after cancelling running compactions. This will attempt to cancel in flight compactions including the given sstables for
            // up to a minute, after which point, null will be returned
            try
            {
                // Note that anticompactions are not disabled when running this. This is safe since runWithCompactionsDisabled
                // is synchronized - acquireTuple and predicate can only be run by a single thread (for the given cfs).
                return cfs.runWithCompactionsDisabled(this::acquireTuple, predicate, false, false);
            }
            catch (SSTableAcquisitionException e)
            {
                logger.warn(e.getMessage());
                logger.debug("Got exception trying to acquire sstables", e);
            }
            return null;
        }
    }

    static class AcquisitionCallback implements AsyncFunction<List<AcquireResult>, Object>
    {
        private final UUID parentRepairSession;
        private final RangesAtEndpoint tokenRanges;

        public AcquisitionCallback(UUID parentRepairSession, RangesAtEndpoint tokenRanges)
        {
            this.parentRepairSession = parentRepairSession;
            this.tokenRanges = tokenRanges;
        }

        ListenableFuture<?> submitPendingAntiCompaction(AcquireResult result)
        {
            return CompactionManager.instance.submitPendingAntiCompaction(result.cfs, tokenRanges, result.refs, result.txn, parentRepairSession);
        }

        private static boolean shouldAbort(AcquireResult result)
        {
            if (result == null)
                return true;

            // sstables in the acquire result are now marked compacting and are locked to this anti compaction. If any
            // of them are marked repaired or pending repair, acquisition raced with another pending anti-compaction, or
            // possibly even a repair session, and we need to abort to prevent sstables from moving between sessions.
            return result.refs != null && Iterables.any(result.refs, sstable -> {
                StatsMetadata metadata = sstable.getSSTableMetadata();
                return metadata.pendingRepair != NO_PENDING_REPAIR || metadata.repairedAt != UNREPAIRED_SSTABLE;
            });
        }

        public ListenableFuture apply(List<AcquireResult> results) throws Exception
        {
            if (Iterables.any(results, AcquisitionCallback::shouldAbort))
            {
                // Release all sstables, and report failure back to coordinator
                for (AcquireResult result : results)
                {
                    if (result != null)
                    {
                        logger.info("Releasing acquired sstables for {}.{}", result.cfs.metadata.keyspace, result.cfs.metadata.name);
                        result.abort();
                    }
                }
                String message = String.format("Prepare phase for incremental repair session %s was unable to " +
                                               "acquire exclusive access to the neccesary sstables. " +
                                               "This is usually caused by running multiple incremental repairs on nodes that share token ranges",
                                               parentRepairSession);
                logger.warn(message);
                return Futures.immediateFailedFuture(new SSTableAcquisitionException(message));
            }
            else
            {
                List<ListenableFuture<?>> pendingAntiCompactions = new ArrayList<>(results.size());
                for (AcquireResult result : results)
                {
                    if (result.txn != null)
                    {
                        ListenableFuture<?> future = submitPendingAntiCompaction(result);
                        pendingAntiCompactions.add(future);
                    }
                }

                return Futures.allAsList(pendingAntiCompactions);
            }
        }
    }

    private final UUID prsId;
    private final Collection<ColumnFamilyStore> tables;
    private final RangesAtEndpoint tokenRanges;
    private final ExecutorService executor;

    public PendingAntiCompaction(UUID prsId,
                                 Collection<ColumnFamilyStore> tables,
                                 RangesAtEndpoint tokenRanges,
                                 ExecutorService executor)
    {
        this.prsId = prsId;
        this.tables = tables;
        this.tokenRanges = tokenRanges;
        this.executor = executor;
    }

    public ListenableFuture run()
    {
        List<ListenableFutureTask<AcquireResult>> tasks = new ArrayList<>(tables.size());
        for (ColumnFamilyStore cfs : tables)
        {
            cfs.forceBlockingFlush();
            ListenableFutureTask<AcquireResult> task = ListenableFutureTask.create(getAcquisitionCallable(cfs, tokenRanges.ranges(), prsId));
            executor.submit(task);
            tasks.add(task);
        }
        ListenableFuture<List<AcquireResult>> acquisitionResults = Futures.successfulAsList(tasks);
        ListenableFuture compactionResult = Futures.transformAsync(acquisitionResults, getAcquisitionCallback(prsId, tokenRanges), MoreExecutors.directExecutor());
        return compactionResult;
    }

    @VisibleForTesting
    protected AcquisitionCallable getAcquisitionCallable(ColumnFamilyStore cfs, Set<Range<Token>> ranges, UUID prsId)
    {
        return new AcquisitionCallable(cfs, ranges, prsId);
    }

    @VisibleForTesting
    protected AcquisitionCallback getAcquisitionCallback(UUID prsId, RangesAtEndpoint tokenRanges)
    {
        return new AcquisitionCallback(prsId, tokenRanges);
    }
}
