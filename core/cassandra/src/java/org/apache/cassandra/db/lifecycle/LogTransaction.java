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
package org.apache.cassandra.db.lifecycle;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.util.concurrent.Runnables;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.cassandra.concurrent.ScheduledExecutors;
import org.apache.cassandra.config.CFMetaData;
import org.apache.cassandra.db.ColumnFamilyStore;
import org.apache.cassandra.db.Directories;
import org.apache.cassandra.db.SystemKeyspace;
import org.apache.cassandra.db.compaction.OperationType;
import org.apache.cassandra.db.lifecycle.LogRecord.Type;
import org.apache.cassandra.io.sstable.Component;
import org.apache.cassandra.io.sstable.Descriptor;
import org.apache.cassandra.io.sstable.SSTable;
import org.apache.cassandra.io.sstable.SnapshotDeletingTask;
import org.apache.cassandra.io.sstable.format.SSTableReader;
import org.apache.cassandra.utils.*;
import org.apache.cassandra.utils.concurrent.Ref;
import org.apache.cassandra.utils.concurrent.RefCounted;
import org.apache.cassandra.utils.concurrent.Transactional;

/**
 * IMPORTANT: When this object is involved in a transactional graph, and is not encapsulated in a LifecycleTransaction,
 * for correct behaviour its commit MUST occur before any others, since it may legitimately fail. This is consistent
 * with the Transactional API, which permits one failing action to occur at the beginning of the commit phase, but also
 * *requires* that the prepareToCommit() phase only take actions that can be rolled back.
 *
 * IMPORTANT: The transaction must complete (commit or abort) before any temporary files are deleted, even though the
 * txn log file itself will not be deleted until all tracked files are deleted. This is required by FileLister to ensure
 * a consistent disk state. LifecycleTransaction ensures this requirement, so this class should really never be used
 * outside of LT. @see FileLister.classifyFiles(TransactionData txn)
 *
 * A class that tracks sstable files involved in a transaction across sstables:
 * if the transaction succeeds the old files should be deleted and the new ones kept; vice-versa if it fails.
 *
 * The transaction log file contains new and old sstables as follows:
 *
 * add:[sstable-2][CRC]
 * remove:[sstable-1,max_update_time,num files][CRC]
 *
 * where sstable-2 is a new sstable to be retained if the transaction succeeds and sstable-1 is an old sstable to be
 * removed. CRC is an incremental CRC of the file content up to this point. For old sstable files we also log the
 * last update time of all files for the sstable descriptor and a checksum of vital properties such as update times
 * and file sizes.
 *
 * Upon commit we add a final line to the log file:
 *
 * commit:[commit_time][CRC]
 *
 * When the transaction log is cleaned-up by the TransactionTidier, which happens only after any old sstables have been
 * osoleted, then any sstable files for old sstables are removed before deleting the transaction log if the transaction
 * was committed, vice-versa if the transaction was aborted.
 *
 * On start-up we look for any transaction log files and repeat the cleanup process described above.
 *
 * See CASSANDRA-7066 for full details.
 */
class LogTransaction extends Transactional.AbstractTransactional implements Transactional
{
    private static final Logger logger = LoggerFactory.getLogger(LogTransaction.class);

    /**
     * If the format of the lines in the transaction log is wrong or the checksum
     * does not match, then we throw this exception.
     */
    public static final class CorruptTransactionLogException extends RuntimeException
    {
        public final LogFile txnFile;

        public CorruptTransactionLogException(String message, LogFile txnFile)
        {
            super(message);
            this.txnFile = txnFile;
        }
    }

    private final Tracker tracker;
    private final LogFile txnFile;
    private final Ref<LogTransaction> selfRef;
    // Deleting sstables is tricky because the mmapping might not have been finalized yet,
    // and delete will fail (on Windows) until it is (we only force the unmapping on SUN VMs).
    // Additionally, we need to make sure to delete the data file first, so on restart the others
    // will be recognized as GCable.
    private static final Queue<Runnable> failedDeletions = new ConcurrentLinkedQueue<>();

    LogTransaction(OperationType opType)
    {
        this(opType, null);
    }

    LogTransaction(OperationType opType, Tracker tracker)
    {
        this.tracker = tracker;
        this.txnFile = new LogFile(opType, UUIDGen.getTimeUUID());
        this.selfRef = new Ref<>(this, new TransactionTidier(txnFile));

        if (logger.isTraceEnabled())
            logger.trace("Created transaction logs with id {}", txnFile.id());
    }

    /**
     * Track a reader as new.
     **/
    void trackNew(SSTable table)
    {
        txnFile.add(Type.ADD, table);
    }

    /**
     * Stop tracking a reader as new.
     */
    void untrackNew(SSTable table)
    {
        txnFile.remove(Type.ADD, table);
    }

    /**
     * Schedule a reader for deletion as soon as it is fully unreferenced.
     */
    SSTableTidier obsoleted(SSTableReader reader)
    {
        if (txnFile.contains(Type.ADD, reader))
        {
            if (txnFile.contains(Type.REMOVE, reader))
                throw new IllegalArgumentException();

            return new SSTableTidier(reader, true, this);
        }

        txnFile.add(Type.REMOVE, reader);

        if (tracker != null)
            tracker.notifyDeleting(reader);

        return new SSTableTidier(reader, false, this);
    }

    OperationType type()
    {
        return txnFile.type();
    }

    UUID id()
    {
        return txnFile.id();
    }

    @VisibleForTesting
    LogFile txnFile()
    {
        return txnFile;
    }

    @VisibleForTesting
    List<File> logFiles()
    {
        return txnFile.getFiles();
    }

    @VisibleForTesting
    List<String> logFilePaths()
    {
        return txnFile.getFilePaths();
    }

    static void delete(File file)
    {
        try
        {
            if (logger.isTraceEnabled())
                logger.trace("Deleting {}", file);

            Files.delete(file.toPath());
        }
        catch (NoSuchFileException e)
        {
            logger.error("Unable to delete {} as it does not exist", file);
        }
        catch (IOException e)
        {
            logger.error("Unable to delete {}", file, e);
            throw new RuntimeException(e);
        }
    }

    /**
     * The transaction tidier.
     *
     * When the transaction reference is fully released we try to delete all the obsolete files
     * depending on the transaction result, as well as the transaction log file.
     */
    private static class TransactionTidier implements RefCounted.Tidy, Runnable
    {
        private final LogFile data;

        TransactionTidier(LogFile data)
        {
            this.data = data;
        }

        public void tidy() throws Exception
        {
            run();
        }

        public String name()
        {
            return data.toString();
        }

        public void run()
        {
            if (logger.isTraceEnabled())
                logger.trace("Removing files for transaction {}", name());

            if (!data.completed())
            { // this happens if we forget to close a txn and the garbage collector closes it for us
                logger.error("{} was not completed, trying to abort it now", data);
                Throwable err = Throwables.perform((Throwable)null, data::abort);
                if (err != null)
                    logger.error("Failed to abort {}", data, err);
            }

            Throwable err = data.removeUnfinishedLeftovers(null);

            if (err != null)
            {
                logger.info("Failed deleting files for transaction {}, we'll retry after GC and on on server restart", name(), err);
                failedDeletions.add(this);
            }
            else
            {
                if (logger.isTraceEnabled())
                    logger.trace("Closing file transaction {}", name());

                data.close();
            }
        }
    }

    static class Obsoletion
    {
        final SSTableReader reader;
        final SSTableTidier tidier;

        Obsoletion(SSTableReader reader, SSTableTidier tidier)
        {
            this.reader = reader;
            this.tidier = tidier;
        }
    }

    /**
     * The SSTableReader tidier. When a reader is fully released and no longer referenced
     * by any one, we run this. It keeps a reference to the parent transaction and releases
     * it when done, so that the final transaction cleanup can run when all obsolete readers
     * are released.
     */
    public static class SSTableTidier implements Runnable
    {
        // must not retain a reference to the SSTableReader, else leak detection cannot kick in
        private final Descriptor desc;
        private final long sizeOnDisk;
        private final Tracker tracker;
        private final boolean wasNew;
        private final Ref<LogTransaction> parentRef;

        public SSTableTidier(SSTableReader referent, boolean wasNew, LogTransaction parent)
        {
            this.desc = referent.descriptor;
            this.sizeOnDisk = referent.bytesOnDisk();
            this.tracker = parent.tracker;
            this.wasNew = wasNew;
            this.parentRef = parent.selfRef.tryRef();
        }

        public void run()
        {
            SystemKeyspace.clearSSTableReadMeter(desc.ksname, desc.cfname, desc.generation);

            try
            {
                // If we can't successfully delete the DATA component, set the task to be retried later: see TransactionTidier
                File datafile = new File(desc.filenameFor(Component.DATA));

                delete(datafile);
                // let the remainder be cleaned up by delete
                SSTable.delete(desc, SSTable.discoverComponentsFor(desc));
            }
            catch (Throwable t)
            {
                logger.error("Failed deletion for {}, we'll retry after GC and on server restart", desc);
                failedDeletions.add(this);
                return;
            }

            if (tracker != null && tracker.cfstore != null && !wasNew)
                tracker.cfstore.metric.totalDiskSpaceUsed.dec(sizeOnDisk);

            // release the referent to the parent so that the all transaction files can be released
            parentRef.release();
        }

        public void abort()
        {
            parentRef.release();
        }
    }


    static void rescheduleFailedDeletions()
    {
        Runnable task;
        while ( null != (task = failedDeletions.poll()))
            ScheduledExecutors.nonPeriodicTasks.submit(task);

        // On Windows, snapshots cannot be deleted so long as a segment of the root element is memory-mapped in NTFS.
        SnapshotDeletingTask.rescheduleFailedTasks();
    }

    static void waitForDeletions()
    {
        FBUtilities.waitOnFuture(ScheduledExecutors.nonPeriodicTasks.schedule(Runnables.doNothing(), 0, TimeUnit.MILLISECONDS));
    }

    @VisibleForTesting
    Throwable complete(Throwable accumulate)
    {
        try
        {
            accumulate = selfRef.ensureReleased(accumulate);
            return accumulate;
        }
        catch (Throwable t)
        {
            logger.error("Failed to complete file transaction {}", id(), t);
            return Throwables.merge(accumulate, t);
        }
    }

    protected Throwable doCommit(Throwable accumulate)
    {
        return complete(Throwables.perform(accumulate, txnFile::commit));
    }

    protected Throwable doAbort(Throwable accumulate)
    {
        return complete(Throwables.perform(accumulate, txnFile::abort));
    }

    protected void doPrepare() { }

    /**
     * Called on startup to scan existing folders for any unfinished leftovers of
     * operations that were ongoing when the process exited. Also called by the standalone
     * sstableutil tool when the cleanup option is specified, @see StandaloneSSTableUtil.
     *
     */
    static void removeUnfinishedLeftovers(CFMetaData metadata)
    {
        removeUnfinishedLeftovers(new Directories(metadata, ColumnFamilyStore.getInitialDirectories()).getCFDirectories());
    }

    @VisibleForTesting
    static void removeUnfinishedLeftovers(List<File> folders)
    {
        LogFilesByName logFiles = new LogFilesByName();
        folders.forEach(logFiles::list);
        logFiles.removeUnfinishedLeftovers();
    }

    private static final class LogFilesByName
    {
        Map<String, List<File>> files = new HashMap<>();

        void list(File folder)
        {
            Arrays.stream(folder.listFiles(LogFile::isLogFile)).forEach(this::add);
        }

        void add(File file)
        {
            List<File> filesByName = files.get(file.getName());
            if (filesByName == null)
            {
                filesByName = new ArrayList<>();
                files.put(file.getName(), filesByName);
            }

            filesByName.add(file);
        }

        void removeUnfinishedLeftovers()
        {
            files.forEach(LogFilesByName::removeUnfinishedLeftovers);
        }

        static void removeUnfinishedLeftovers(String name, List<File> logFiles)
        {

            try(LogFile txn = LogFile.make(name, logFiles))
            {
                if (txn.verify())
                {
                    Throwable failure = txn.removeUnfinishedLeftovers(null);
                    if (failure != null)
                        logger.error("Failed to remove unfinished transaction leftovers for txn {}", txn, failure);
                }
                else
                {
                    logger.error("Unexpected disk state: failed to read transaction txn {}", txn);
                }
            }
        }
    }
}
