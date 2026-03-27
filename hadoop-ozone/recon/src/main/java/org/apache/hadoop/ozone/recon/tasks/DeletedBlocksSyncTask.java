/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.ozone.recon.tasks;

import org.apache.hadoop.hdds.protocol.proto.HddsProtos.DeletedBlocksTransactionSummary;
import org.apache.hadoop.hdds.protocol.proto.StorageContainerLocationProtocolProtos
    .RepairDeletedBlocksTxnSummaryFromCheckpointResponseProto;
import org.apache.hadoop.hdds.scm.protocol.StorageContainerLocationProtocol;
import org.apache.hadoop.ozone.recon.metrics.DeletedBlocksSyncTaskMetrics;
import org.apache.hadoop.ozone.recon.scm.ReconScmTask;
import org.apache.hadoop.ozone.recon.tasks.updater.ReconTaskStatusUpdaterManager;
import org.apache.hadoop.util.Time;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Recon monitoring task that periodically checks for discrepancies between
 * SCM's checkpoint-actual deleted-blocks counters and the checkpoint-persisted
 * summary, and automatically applies a repair when a difference is detected.
 *
 * <p>The task calls two SCM admin RPC methods:
 * <ol>
 *   <li>{@code getDeletedBlockSummaryFromCheckpoint()} – takes a RocksDB
 *       snapshot and returns both the actual row counts and the persisted
 *       summary stored inside the same checkpoint.</li>
 *   <li>{@code repairDeletedBlockSummaryFromCheckpoint()} – re-derives the
 *       summary from the checkpoint and applies the diff to the live in-memory
 *       counters.</li>
 * </ol>
 *
 * <p>Metrics are emitted on every run so that the diff values can be used for
 * ongoing debugging even when no repair was needed.  The task interval is
 * configured via
 * {@code ozone.recon.task.deletedblockssync.interval} (default 300 s).
 */
public class DeletedBlocksSyncTask extends ReconScmTask {

  private static final Logger LOG =
      LoggerFactory.getLogger(DeletedBlocksSyncTask.class);

  private final StorageContainerLocationProtocol scmClient;
  private final long interval;
  private final DeletedBlocksSyncTaskMetrics taskMetrics;

  public DeletedBlocksSyncTask(
      StorageContainerLocationProtocol scmClient,
      ReconTaskConfig reconTaskConfig,
      ReconTaskStatusUpdaterManager taskStatusUpdaterManager) {
    super(taskStatusUpdaterManager);
    this.scmClient = scmClient;
    this.interval = reconTaskConfig.getDeletedBlocksSyncTaskInterval().toMillis();
    this.taskMetrics = DeletedBlocksSyncTaskMetrics.create();
    LOG.info("Initialized DeletedBlocksSyncTask with interval={}ms", interval);
  }

  @Override
  protected void run() {
    try {
      while (canRun()) {
        initializeAndRunTask();
        Thread.sleep(interval);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      LOG.info("DeletedBlocksSyncTask interrupted");
    } catch (Throwable t) {
      LOG.error("Unexpected exception in DeletedBlocksSyncTask", t);
      getTaskStatusUpdater().setLastTaskRunStatus(-1);
      getTaskStatusUpdater().recordRunCompletion();
    }
  }

  @Override
  protected void runTask() throws Exception {
    long start = Time.monotonicNow();
    boolean succeeded = false;
    try {
      RepairDeletedBlocksTxnSummaryFromCheckpointResponseProto resp =
          scmClient.getDeletedBlockSummaryFromCheckpoint();

      DeletedBlocksTransactionSummary actual =
          resp.hasCheckpointActual() ? resp.getCheckpointActual() : null;
      DeletedBlocksTransactionSummary persisted =
          resp.hasCheckpointPersisted() ? resp.getCheckpointPersisted() : null;

      long txnDiff = 0L;
      long blockDiff = 0L;
      long sizeDiff = 0L;
      long replDiff = 0L;

      if (actual != null && persisted != null) {
        txnDiff   = actual.getTotalTransactionCount()    - persisted.getTotalTransactionCount();
        blockDiff = actual.getTotalBlockCount()          - persisted.getTotalBlockCount();
        sizeDiff  = actual.getTotalBlockSize()           - persisted.getTotalBlockSize();
        replDiff  = actual.getTotalBlockReplicatedSize() - persisted.getTotalBlockReplicatedSize();
      }

      taskMetrics.setDiff(txnDiff, blockDiff, sizeDiff, replDiff);

      boolean hasDiff = txnDiff != 0 || blockDiff != 0 || sizeDiff != 0 || replDiff != 0;
      if (hasDiff) {
        LOG.warn("DeletedBlocksSyncTask detected discrepancy between " +
                "checkpoint-actual and checkpoint-persisted: " +
                "txnDiff={} blockDiff={} sizeDiff={} replDiff={}. Triggering repair.",
            txnDiff, blockDiff, sizeDiff, replDiff);
        scmClient.repairDeletedBlockSummaryFromCheckpoint();
        taskMetrics.incrRepairCount();
        LOG.info("DeletedBlocksSyncTask repair complete.");
      } else {
        LOG.info("DeletedBlocksSyncTask: no discrepancy detected, skipping repair.");
      }

      taskMetrics.incrSuccess();
      succeeded = true;
    } catch (Exception e) {
      taskMetrics.incrFailure();
      throw e;
    } finally {
      long durationMs = Time.monotonicNow() - start;
      taskMetrics.addRunTime(durationMs);
      LOG.info("DeletedBlocksSyncTask completed with status={} in {} ms",
          succeeded ? "success" : "failure", durationMs);
    }
  }

  @Override
  public synchronized void stop() {
    super.stop();
    taskMetrics.unRegister();
  }
}
