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

package org.apache.hadoop.hdds.scm.block;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.protobuf.ByteString;
import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.hadoop.hdds.conf.OzoneConfiguration;
import org.apache.hadoop.hdds.protocol.proto.HddsProtos.DeletedBlocksTransactionSummary;
import org.apache.hadoop.hdds.protocol.proto.StorageContainerDatanodeProtocolProtos.DeletedBlocksTransaction;
import org.apache.hadoop.hdds.scm.ScmConfigKeys;
import org.apache.hadoop.hdds.scm.container.ContainerManager;
import org.apache.hadoop.hdds.scm.ha.SCMContext;
import org.apache.hadoop.hdds.scm.ha.SCMHADBTransactionBuffer;
import org.apache.hadoop.hdds.scm.ha.SCMHADBTransactionBufferImpl;
import org.apache.hadoop.hdds.scm.ha.SCMHAManagerImpl;
import org.apache.hadoop.hdds.scm.ha.SCMHANodeDetails;
import org.apache.hadoop.hdds.scm.ha.SCMNodeDetails;
import org.apache.hadoop.hdds.scm.ha.SCMRatisServerImpl;
import org.apache.hadoop.hdds.scm.ha.SCMServiceManager;
import org.apache.hadoop.hdds.scm.ha.SCMSnapshotProvider;
import org.apache.hadoop.hdds.scm.ha.SequenceIdGenerator;
import org.apache.hadoop.hdds.scm.metadata.SCMMetadataStore;
import org.apache.hadoop.hdds.scm.metadata.SCMMetadataStoreImpl;
import org.apache.hadoop.hdds.scm.node.NodeDecommissionManager;
import org.apache.hadoop.hdds.scm.safemode.SCMSafeModeManager;
import org.apache.hadoop.hdds.scm.server.SCMDatanodeProtocolServer;
import org.apache.hadoop.hdds.scm.server.StorageContainerManager;
import org.apache.hadoop.hdds.scm.server.upgrade.FinalizationManager;
import org.apache.hadoop.hdds.security.SecurityConfig;
import org.apache.hadoop.hdds.security.x509.certificate.client.CertificateClient;
import org.apache.hadoop.hdds.utils.TransactionInfo;
import org.apache.hadoop.hdds.utils.db.CodecException;
import org.apache.hadoop.hdds.utils.db.RocksDatabaseException;
import org.apache.hadoop.hdds.utils.db.Table;
import org.apache.hadoop.ozone.container.common.SCMTestUtils;
import org.apache.ozone.test.GenericTestUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Regression test for SCM leader transfer flush can split a delete
 * transaction apply and persist a summary undercount.
 */
public class TestDeletedBlockSummaryFlushRace {

  private static final TransactionInfo TRX_INFO_T1 = TransactionInfo.valueOf(1, 1);
  private static final TransactionInfo TRX_INFO_T2 = TransactionInfo.valueOf(1, 2);
  private static final long TX_ID_1 = 1L;
  private static final long TX_ID_2 = 2L;
  private static final long CONTAINER_ID = 100L;

  @TempDir
  private File testDir;

  private final AtomicLong clockMillis = new AtomicLong(0);
  private SCMMetadataStore metadataStore;
  private ScmBlockDeletingServiceMetrics metrics;
  private BlockManager blockManager;

  @BeforeEach
  public void setUp() throws Exception {
    OzoneConfiguration conf = SCMTestUtils.getConf(testDir);
    metadataStore = new SCMMetadataStoreImpl(conf);
    blockManager = mock(BlockManager.class);
    when(blockManager.getDeletedBlockLog()).thenReturn(mock(DeletedBlockLogImpl.class));
    metrics = ScmBlockDeletingServiceMetrics.create(blockManager);
    SCMDeletedBlockTransactionStatusManager.setDisableDataDistributionForTest(false);
  }

  @AfterEach
  public void tearDown() throws Exception {
    SCMDeletedBlockTransactionStatusManager.setDisableDataDistributionForTest(false);
    ScmBlockDeletingServiceMetrics.unRegister();
    if (metadataStore != null) {
      metadataStore.stop();
    }
  }

  @Test
  public void testNotifyLeaderChangedFlushCausesPermanentSummaryUndercount() throws Throwable {
    CountDownLatch afterFirstRowWrite = new CountDownLatch(1);
    CountDownLatch beforeSummaryWrite = new CountDownLatch(1);
    PausingBuffer buffer = new PausingBuffer(buildMockScm(), afterFirstRowWrite, beforeSummaryWrite);

    DeletedBlockLogStateManagerImpl stateManager = new DeletedBlockLogStateManagerImpl(
        metadataStore.getDeletedBlocksTXTable(),
        metadataStore.getStatefulServiceConfigTable(),
        mock(ContainerManager.class),
        buffer);
    SCMDeletedBlockTransactionStatusManager statusManager =
        new SCMDeletedBlockTransactionStatusManager(stateManager,
            metadataStore.getStatefulServiceConfigTable(),
            mock(ContainerManager.class), metrics, Long.MAX_VALUE);

    // Phase 1: commit Tx1 normally so the DB has a valid baseline summary.
    // After this flush: DB = {Tx1 row, summary S0 = {txCount=1, blockCount=5}}
    // In-memory: {txCount=1, blockCount=5}
    statusManager.addTransactions(toList(buildTx(TX_ID_1, 5)));
    buffer.updateLatestTrxInfo(TRX_INFO_T1);
    buffer.flush();
    assertEquals(1, statusManager.getSummary().getTotalTransactionCount(),
        "baseline: in-memory must reflect the one committed transaction");

    // Phase 2: arm the pause so the next addToBuffer call (the Tx2 row) blocks
    // before the summary addToBuffer call, opening the race window.
    buffer.armPause(afterFirstRowWrite, beforeSummaryWrite);

    // The apply thread simulates the Ratis applier applying a delete transaction.
    // Inside addTransactions:
    //   1. incrDeletedBlocksSummary increments in-memory to {txCount=2, blockCount=10}.
    //   2. addTransactionsToDB buffers the Tx2 row — PausingBuffer fires afterFirstRowWrite
    //      and blocks before the summary addToBuffer call.
    AtomicReference<Throwable> applyError = new AtomicReference<>();
    Thread applyThread = new Thread(() -> {
      try {
        statusManager.addTransactions(toList(buildTx(TX_ID_2, 5)));
        buffer.updateLatestTrxInfo(TRX_INFO_T2);
      } catch (Throwable t) {
        applyError.set(t);
      }
    });
    applyThread.start();

    assertTrue(afterFirstRowWrite.await(10, TimeUnit.SECONDS),
        "Timed out: Tx2 row was not buffered by the apply thread");
    AtomicReference<Throwable> flushError = new AtomicReference<>();
    Thread flushThread = new Thread(() -> {
      try {
        buffer.flush();
      } catch (Throwable t) {
        flushError.set(t);
      }
    });
    flushThread.start();

    // Give the background flush a moment to either commit (without fix) or block
    // on the write-lock (with fix), then release the apply thread to complete
    // the summary write so both threads can finish.
    Thread.sleep(200);
    beforeSummaryWrite.countDown();

    flushThread.join(15_000);
    if (flushError.get() != null) {
      throw flushError.get();
    }
    applyThread.join(15_000);
    if (applyError.get() != null) {
      throw applyError.get();
    }

    // After both threads complete:
    //   Without the fix: flush split the batch — Tx2 row is durable but DB still
    //     has S0 (the apply thread wrote S1 to the new batch, not yet flushed).
    //   With the fix: flush committed row + summary atomically; DB has S1.
    // Either way, Tx2 row must be durable.
    assertNotNull(metadataStore.getDeletedBlocksTXTable().get(TX_ID_2),
        "Tx2 row must be durable after the flush");

    // Simulate onBecomeLeader. With the bug, initDataDistributionData reloads
    // the stale S0 (split DB) and resets the in-memory counters from 2 to 1.
    // With the fix, it reads the atomically-committed S1 and keeps in-memory at 2.
    statusManager.onBecomeLeader();

    assertEquals(2, statusManager.getSummary().getTotalTransactionCount(),
        "After onBecomeLeader, in-memory summary must be 2 (both Tx1 and Tx2 applied). "
            + "BUG: initDataDistributionData reloaded the stale S0 (txCount=1) from the "
            + "split-flush DB, discarding the correctly-incremented in-memory value.");

    // Flush the new batch so S1 is now durable.
    buffer.flush();
    ByteString rawSummaryFinal =
        metadataStore.getStatefulServiceConfigTable()
            .get(DeletedBlockLogStateManagerImpl.SERVICE_DEFINITION.getServiceName());
    DeletedBlocksTransactionSummary dbSummaryFinal =
        DeletedBlocksTransactionSummary.parseFrom(rawSummaryFinal);
    assertEquals(2, dbSummaryFinal.getTotalTransactionCount(),
        "DB summary must be S1 (txCount=2) after the apply thread completed and flushed");
    assertEquals(2, statusManager.getSummary().getTotalTransactionCount(),
        "In-memory summary must be 2 after apply completes and DB is consistent. "
            + "BUG: the split flush + onBecomeLeader permanently baked S0 (txCount=1) "
            + "into the in-memory counters; they remain undercounted even after "
            + "the correct S1 is durable.");
  }

  /**
   * Verifies the complementary safe path: {@code flushIfNeeded} with an active
   * apply (applyingTransactions > 0) does NOT flush, so row and summary are
   * always committed together and {@code onBecomeLeader} reloads a consistent
   * summary. This is the behavior {@code SCMHATransactionBufferMonitorTask}
   * relies on; {@code notifyLeaderChanged} must adopt the same guard.
   */
  @Test
  public void testFlushIfNeededDoesNotSplitWritesDuringApply() throws Throwable {
    PausingBuffer buffer = new PausingBuffer(buildMockScm(),
        new CountDownLatch(1), new CountDownLatch(1));

    DeletedBlockLogStateManagerImpl stateManager = new DeletedBlockLogStateManagerImpl(
        metadataStore.getDeletedBlocksTXTable(),
        metadataStore.getStatefulServiceConfigTable(),
        mock(ContainerManager.class),
        buffer);
    SCMDeletedBlockTransactionStatusManager statusManager =
        new SCMDeletedBlockTransactionStatusManager(stateManager,
            metadataStore.getStatefulServiceConfigTable(),
            mock(ContainerManager.class), metrics, Long.MAX_VALUE);

    // Commit Tx1 as baseline.
    statusManager.addTransactions(toList(buildTx(TX_ID_1, 5)));
    buffer.updateLatestTrxInfo(TRX_INFO_T1);
    buffer.flush();

    // Simulate applyTransaction wrapping the addTransactions call.
    buffer.beginApplyingTransaction();
    statusManager.addTransactions(toList(buildTx(TX_ID_2, 5)));

    // flushIfNeeded skips flush because applyingTransactions > 0.
    buffer.flushIfNeeded(0);

    // Nothing new is durable yet — the batch still holds both the row and the
    // updated summary together, which is the safe state.
    assertNull(metadataStore.getDeletedBlocksTXTable().get(TX_ID_2),
        "flushIfNeeded must not flush while an apply is in progress");
    ByteString rawSummary =
        metadataStore.getStatefulServiceConfigTable()
            .get(DeletedBlockLogStateManagerImpl.SERVICE_DEFINITION.getServiceName());
    DeletedBlocksTransactionSummary dbSummary = DeletedBlocksTransactionSummary.parseFrom(rawSummary);
    assertEquals(1, dbSummary.getTotalTransactionCount(),
        "DB summary must still be S0 — flushIfNeeded deferred the write");

    buffer.updateLatestTrxInfo(TRX_INFO_T2);
    buffer.endApplyingTransaction();

    // Now the full batch (row + summary) is committed atomically.
    clockMillis.addAndGet(2000);
    buffer.flushIfNeeded(1000);

    assertNotNull(metadataStore.getDeletedBlocksTXTable().get(TX_ID_2),
        "Tx2 row must be durable after the guarded flush");
    ByteString rawSummaryFinal =
        metadataStore.getStatefulServiceConfigTable()
            .get(DeletedBlockLogStateManagerImpl.SERVICE_DEFINITION.getServiceName());
    DeletedBlocksTransactionSummary dbSummaryFinal =
        DeletedBlocksTransactionSummary.parseFrom(rawSummaryFinal);
    assertEquals(2, dbSummaryFinal.getTotalTransactionCount(),
        "DB summary must be S1 (txCount=2) after the guarded flush committed the full batch");

    // onBecomeLeader now reloads a consistent S1 from DB.
    statusManager.onBecomeLeader();
    assertEquals(2, statusManager.getSummary().getTotalTransactionCount(),
        "After onBecomeLeader with a consistent DB, in-memory must be S1 (txCount=2)");
  }

  /**
   * Regression test proving the core fix: while {@code addTransactions()} is blocked inside
   * {@code deletedBlockLogStateManager.addTransactionsToDB} — in production, the synchronous
   * wait for Ratis consensus — it no longer holds the SCM-wide {@link SCMHADBTransactionBufferImpl}
   * lock. Before the fix, {@code transactionBuffer.lock()} wrapped the whole {@code addTransactions}
   * call, so a slow Ratis round-trip held the buffer's shared read lock for its full duration;
   * any writer on the same buffer (e.g. SCM's periodic/leader-change flush, or an unrelated
   * Pipeline/Container state manager update) then had to wait for that read lock to be released,
   * stalling unrelated SCM writes for as long as the Ratis call took. Simulates the blocking
   * Ratis call with a decorator that pauses before delegating to the real
   * {@code addTransactionsToDB}, and asserts a concurrent {@code flushAndRun} completes promptly
   * instead of waiting out the pause.
   */
  @Test
  public void testAddTransactionsDoesNotBlockUnrelatedBufferFlushDuringDBWrite() throws Throwable {
    SCMHADBTransactionBufferImpl buffer = new SCMHADBTransactionBufferImpl(buildMockScm());
    DeletedBlockLogStateManagerImpl realStateManager = new DeletedBlockLogStateManagerImpl(
        metadataStore.getDeletedBlocksTXTable(),
        metadataStore.getStatefulServiceConfigTable(),
        mock(ContainerManager.class),
        buffer);

    CountDownLatch dbWriteStarted = new CountDownLatch(1);
    CountDownLatch releaseDbWrite = new CountDownLatch(1);
    BlockingStateManager stateManager =
        new BlockingStateManager(realStateManager, dbWriteStarted, releaseDbWrite);
    SCMDeletedBlockTransactionStatusManager statusManager =
        new SCMDeletedBlockTransactionStatusManager(stateManager,
            metadataStore.getStatefulServiceConfigTable(),
            mock(ContainerManager.class), metrics, Long.MAX_VALUE);

    AtomicReference<Throwable> applyError = new AtomicReference<>();
    Thread applyThread = new Thread(() -> {
      try {
        statusManager.addTransactions(toList(buildTx(TX_ID_1, 5)));
      } catch (Throwable t) {
        applyError.set(t);
      }
    });
    applyThread.start();

    assertTrue(dbWriteStarted.await(10, TimeUnit.SECONDS),
        "Timed out waiting for addTransactions to reach the simulated blocking DB write");

    // addTransactions is now "stuck" where, in production, it would be waiting on Ratis
    // consensus. An unrelated writer on the same shared buffer must not be blocked by that wait.
    long startNanos = System.nanoTime();
    buffer.flushAndRun(() -> { });
    long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000;
    assertTrue(elapsedMillis < 2000,
        "flushAndRun must not block on addTransactions' in-flight DB write; took " + elapsedMillis
            + "ms. BUG: the shared buffer lock is held across the blocking Ratis call again.");

    releaseDbWrite.countDown();
    applyThread.join(15_000);
    if (applyError.get() != null) {
      throw applyError.get();
    }

    buffer.flush();
    assertNotNull(metadataStore.getDeletedBlocksTXTable().get(TX_ID_1),
        "Tx1 row must be durable once addTransactions completes and the buffer is flushed");
  }

  /**
   * Regression test for the reviewer-flagged gap: {@code addTransactions()} used to evaluate
   * {@code getSummary()} before entering {@code addTransactionsToDB()}'s buffer lock, so a
   * concurrent {@code onBecomeLeader()} could reload the old DB summary between the counter
   * update and the snapshot, and that stale snapshot would clobber the in-memory counters. The
   * fix tracks in-flight updates with {@code inFlightOps} instead of relying on the shared
   * buffer lock, so {@code initDataDistributionData()} can detect the still-in-flight update
   * (contending on {@code summaryLock}, then checking {@code inFlightOps}) and skip the reload
   * instead of clobbering it. Verifies the final in-memory counters reflect the just-applied
   * increment even when {@code onBecomeLeader} races to interleave exactly in the
   * counter-update-to-snapshot window, and that the DB eventually reflects the same values once
   * flushed again. Uses the real {@link DeletedBlockLogStateManagerImpl} and
   * {@link SCMHADBTransactionBufferImpl}, matching
   * {@link #testNotifyLeaderChangedFlushCausesPermanentSummaryUndercount}.
   */
  @Test
  public void testOnBecomeLeaderCannotInterleaveDuringAddTransactionsSnapshot() throws Throwable {
    SCMHADBTransactionBufferImpl buffer = new SCMHADBTransactionBufferImpl(buildMockScm());
    DeletedBlockLogStateManagerImpl stateManager = new DeletedBlockLogStateManagerImpl(
        metadataStore.getDeletedBlocksTXTable(),
        metadataStore.getStatefulServiceConfigTable(),
        mock(ContainerManager.class),
        buffer);

    // Commit Tx1 as baseline (as if it was already durably committed).
    CountDownLatch afterUpdate = new CountDownLatch(1);
    CountDownLatch beforeSnapshot = new CountDownLatch(1);
    PausingStatusManager statusManager = new PausingStatusManager(stateManager,
        metadataStore.getStatefulServiceConfigTable(), mock(ContainerManager.class), metrics, Long.MAX_VALUE);
    statusManager.addTransactions(toList(buildTx(TX_ID_1, 5)));
    buffer.updateLatestTrxInfo(TRX_INFO_T1);
    buffer.flush();
    assertEquals(1, statusManager.getSummary().getTotalTransactionCount(),
        "baseline: in-memory must reflect the one committed transaction");

    statusManager.armPause(afterUpdate, beforeSnapshot);

    AtomicReference<Throwable> applyError = new AtomicReference<>();
    Thread applyThread = new Thread(() -> {
      try {
        statusManager.addTransactions(toList(buildTx(TX_ID_2, 5)));
        buffer.updateLatestTrxInfo(TRX_INFO_T2);
      } catch (Throwable t) {
        applyError.set(t);
      }
    });
    applyThread.start();

    assertTrue(afterUpdate.await(10, TimeUnit.SECONDS),
        "Timed out waiting for the apply thread to update the in-memory summary");

    // Simulate a concurrent SCM leader change trying to flush and reload the (still stale)
    // DB summary right in the update-to-snapshot window.
    Thread leaderThread = new Thread(() -> {
      try {
        buffer.flushAndRun(statusManager::onBecomeLeader);
      } catch (Throwable t) {
        applyError.compareAndSet(null, t);
      }
    });
    leaderThread.start();

    leaderThread.join(300);
    assertTrue(leaderThread.isAlive(),
        "onBecomeLeader must block until the apply thread finishes update+snapshot as one unit; "
            + "if it proceeded here, the update-to-snapshot race is unguarded.");

    beforeSnapshot.countDown();

    applyThread.join(15_000);
    if (applyError.get() != null) {
      throw applyError.get();
    }
    leaderThread.join(15_000);
    if (applyError.get() != null) {
      throw applyError.get();
    }

    // The leader thread's flushAndRun races ahead of the apply thread: it grabs the buffer's
    // write lock and flushes before the apply thread (held up on summaryLock until
    // beforeSnapshot counts down) ever buffers Tx2's row/summary, so Tx2 is not durable from
    // *this* flush alone. That is expected under the fix, which removes the shared buffer lock
    // from addTransactions()'s scope precisely so a concurrent flush/leader-change no longer
    // waits on it. Durability resumes on the next flush (e.g. the periodic
    // SCMHATransactionBufferMonitorTask); simulate that here.
    buffer.flush();

    assertNotNull(metadataStore.getDeletedBlocksTXTable().get(TX_ID_2),
        "Tx2 row must be durable once the apply thread completes and the buffer is flushed again");
    ByteString rawSummary =
        metadataStore.getStatefulServiceConfigTable()
            .get(DeletedBlockLogStateManagerImpl.SERVICE_DEFINITION.getServiceName());
    DeletedBlocksTransactionSummary dbSummary = DeletedBlocksTransactionSummary.parseFrom(rawSummary);
    assertEquals(2, dbSummary.getTotalTransactionCount(),
        "The durably persisted summary must reflect Tx2's increment once flushed.");
    assertEquals(2, statusManager.getSummary().getTotalTransactionCount(),
        "After onBecomeLeader, in-memory summary must still be 2 (both Tx1 and Tx2 applied). BUG: "
            + "a concurrent onBecomeLeader reset landed between the counter update and "
            + "getSummary(), clobbering the in-memory value with the stale pre-update summary.");
  }

  /**
   * Analogous regression test for the remove path, per the reviewer's explicit request to
   * also cover {@code removeTransactions()}.
   */
  @Test
  public void testOnBecomeLeaderCannotInterleaveDuringRemoveTransactionsSnapshot() throws Throwable {
    SCMHADBTransactionBufferImpl buffer = new SCMHADBTransactionBufferImpl(buildMockScm());
    DeletedBlockLogStateManagerImpl stateManager = new DeletedBlockLogStateManagerImpl(
        metadataStore.getDeletedBlocksTXTable(),
        metadataStore.getStatefulServiceConfigTable(),
        mock(ContainerManager.class),
        buffer);

    // Commit Tx1 and Tx2 as baseline (as if both were already durably committed).
    CountDownLatch afterUpdate = new CountDownLatch(1);
    CountDownLatch beforeSnapshot = new CountDownLatch(1);
    PausingStatusManager statusManager = new PausingStatusManager(stateManager,
        metadataStore.getStatefulServiceConfigTable(), mock(ContainerManager.class), metrics, Long.MAX_VALUE);
    statusManager.addTransactions(toList(buildTx(TX_ID_1, 5), buildTx(TX_ID_2, 5)));
    buffer.updateLatestTrxInfo(TRX_INFO_T1);
    buffer.flush();
    assertEquals(2, statusManager.getSummary().getTotalTransactionCount(),
        "baseline: in-memory must reflect both committed transactions");
    // Tx2 must be tracked in txSizeMap for removeTransactions to decrement the summary for it;
    // addTransactions() itself does not populate it (only DeletedBlockLogImpl.getTransaction() does).
    statusManager.getTxSizeMap().put(TX_ID_2,
        new SCMDeletedBlockTransactionStatusManager.TxBlockInfo(TX_ID_2, CONTAINER_ID, 5, 50, 0));

    statusManager.armPause(afterUpdate, beforeSnapshot);

    AtomicReference<Throwable> applyError = new AtomicReference<>();
    Thread applyThread = new Thread(() -> {
      try {
        statusManager.removeTransactions(toLongList(TX_ID_2));
        buffer.updateLatestTrxInfo(TRX_INFO_T2);
      } catch (Throwable t) {
        applyError.set(t);
      }
    });
    applyThread.start();

    assertTrue(afterUpdate.await(10, TimeUnit.SECONDS),
        "Timed out waiting for the apply thread to update the in-memory summary");

    Thread leaderThread = new Thread(() -> {
      try {
        buffer.flushAndRun(statusManager::onBecomeLeader);
      } catch (Throwable t) {
        applyError.compareAndSet(null, t);
      }
    });
    leaderThread.start();

    leaderThread.join(300);
    assertTrue(leaderThread.isAlive(),
        "onBecomeLeader must block until the apply thread finishes update+snapshot as one unit; "
            + "if it proceeded here, the update-to-snapshot race is unguarded.");

    beforeSnapshot.countDown();

    applyThread.join(15_000);
    if (applyError.get() != null) {
      throw applyError.get();
    }
    leaderThread.join(15_000);
    if (applyError.get() != null) {
      throw applyError.get();
    }

    // See testOnBecomeLeaderCannotInterleaveDuringAddTransactionsSnapshot: the leader thread's
    // flushAndRun races ahead and flushes before the apply thread buffers the decremented
    // summary, so that update is not durable from *this* flush alone. Simulate the next flush.
    buffer.flush();

    ByteString rawSummary =
        metadataStore.getStatefulServiceConfigTable()
            .get(DeletedBlockLogStateManagerImpl.SERVICE_DEFINITION.getServiceName());
    DeletedBlocksTransactionSummary dbSummary = DeletedBlocksTransactionSummary.parseFrom(rawSummary);
    assertEquals(1, dbSummary.getTotalTransactionCount(),
        "The durably persisted summary must reflect Tx2's removal once flushed.");
    assertEquals(1, statusManager.getSummary().getTotalTransactionCount(),
        "After onBecomeLeader, in-memory summary must still be 1 (Tx2 removed). BUG: a concurrent "
            + "onBecomeLeader reset landed between the counter update and getSummary(), "
            + "clobbering the in-memory value with the stale pre-update summary.");
  }

  /**
   * End-to-end regression test exercising the same race through the real Ratis HA proxy, per
   * smengcl's explicit request to "cover the interleaving through the HA proxy." Unlike
   * {@link #testOnBecomeLeaderCannotInterleaveDuringAddTransactionsSnapshot}, which calls
   * {@code addTransactionsToDB} on a raw {@link DeletedBlockLogStateManagerImpl} in-process, this
   * test builds the proxy production code actually uses: {@code DeletedBlockLogStateManagerImpl
   * .Builder#build()} wraps the impl in a {@code DeletedBlockLogStateManagerInvoker} and registers
   * it with a real single-node {@link SCMRatisServerImpl}, so {@code addTransactions()} on the
   * returned proxy submits a real Ratis client request; the DB write happens later, applied on the
   * Ratis state machine's own apply thread, never the calling thread. Reproduces the same
   * counter-update-to-snapshot race and asserts {@code summaryLock}/{@code inFlightOps} still
   * correctly serialize {@code onBecomeLeader()} against the update even across that extra hop.
   */
  @Test
  public void testOnBecomeLeaderCannotInterleaveWhenAddTransactionsGoesThroughRealRatisHAProxy() throws Throwable {
    SCMHAManagerImpl haManager = buildAndStartSingleNodeRealRatisHAManager();
    try {
      SCMHADBTransactionBuffer buffer = haManager.asSCMHADBTransactionBuffer();
      DeletedBlockLogStateManager ratisBackedStateManager = DeletedBlockLogStateManagerImpl.newBuilder()
          .setDeletedBlocksTable(metadataStore.getDeletedBlocksTXTable())
          .setContainerManager(mock(ContainerManager.class))
          .setRatisServer(haManager.getRatisServer())
          .setSCMDBTransactionBuffer(buffer)
          .setStatefulConfigTable(metadataStore.getStatefulServiceConfigTable())
          .build();

      CountDownLatch afterUpdate = new CountDownLatch(1);
      CountDownLatch beforeSnapshot = new CountDownLatch(1);
      PausingStatusManager statusManager = new PausingStatusManager(ratisBackedStateManager,
          metadataStore.getStatefulServiceConfigTable(), mock(ContainerManager.class), metrics, Long.MAX_VALUE);

      // Commit Tx1 as baseline through the real Ratis proxy; the state machine's apply thread
      // calls transactionBuffer.updateLatestTrxInfo(...) itself (SCMStateMachine#applyTransaction),
      // so by the time addTransactions() returns here, it is already reflected in the buffer.
      statusManager.addTransactions(toList(buildTx(TX_ID_1, 5)));
      buffer.flush();
      assertEquals(1, statusManager.getSummary().getTotalTransactionCount(),
          "baseline: in-memory must reflect the one committed transaction");

      statusManager.armPause(afterUpdate, beforeSnapshot);

      AtomicReference<Throwable> applyError = new AtomicReference<>();
      Thread applyThread = new Thread(() -> {
        try {
          // Travels through DeletedBlockLogStateManagerInvoker -> ScmInvoker -> a real Ratis
          // client request, applied on the state machine's own apply thread.
          statusManager.addTransactions(toList(buildTx(TX_ID_2, 5)));
        } catch (Throwable t) {
          applyError.set(t);
        }
      });
      applyThread.start();

      assertTrue(afterUpdate.await(10, TimeUnit.SECONDS),
          "Timed out waiting for the apply thread to update the in-memory summary");

      // Simulate a concurrent SCM leader change trying to flush and reload the (still stale)
      // DB summary right in the update-to-snapshot window.
      Thread leaderThread = new Thread(() -> {
        try {
          buffer.flushAndRun(statusManager::onBecomeLeader);
        } catch (Throwable t) {
          applyError.compareAndSet(null, t);
        }
      });
      leaderThread.start();

      leaderThread.join(300);
      assertTrue(leaderThread.isAlive(),
          "onBecomeLeader must block until the apply thread finishes update+snapshot as one unit, "
              + "even when the update travels through the real Ratis HA proxy; if it proceeded "
              + "here, the update-to-snapshot race is unguarded.");

      beforeSnapshot.countDown();

      applyThread.join(15_000);
      if (applyError.get() != null) {
        throw applyError.get();
      }
      leaderThread.join(15_000);
      if (applyError.get() != null) {
        throw applyError.get();
      }

      // See testOnBecomeLeaderCannotInterleaveDuringAddTransactionsSnapshot: the leader thread's
      // flushAndRun races ahead and flushes before the apply thread buffers Tx2's row/summary, so
      // Tx2 is not durable from *this* flush alone. Simulate the next flush.
      buffer.flush();

      assertNotNull(metadataStore.getDeletedBlocksTXTable().get(TX_ID_2),
          "Tx2 row must be durable once the apply thread completes and the buffer is flushed again");
      ByteString rawSummary =
          metadataStore.getStatefulServiceConfigTable()
              .get(DeletedBlockLogStateManagerImpl.SERVICE_DEFINITION.getServiceName());
      DeletedBlocksTransactionSummary dbSummary = DeletedBlocksTransactionSummary.parseFrom(rawSummary);
      assertEquals(2, dbSummary.getTotalTransactionCount(),
          "The durably persisted summary must reflect Tx2's increment once flushed.");
      assertEquals(2, statusManager.getSummary().getTotalTransactionCount(),
          "After onBecomeLeader, in-memory summary must still be 2 (both Tx1 and Tx2 applied) even "
              + "though addTransactions traveled through the real Ratis HA proxy.");
    } finally {
      haManager.stop();
    }
  }

  /**
   * Builds, starts, and returns a single-node, leader-ready real {@link SCMHAManagerImpl}, backed
   * by this test's real {@link #metadataStore}, modeled on {@code TestSCMHAManagerImpl
   * #getMockStorageContainerManager} but trimmed to a single node (no follower, no HA ring
   * membership changes needed). {@code SCMRatisServerImpl#initialize} pre-populates the Raft
   * group's storage with this node as its sole peer, so {@code start()} skips the bootstrap
   * self-add path. The periodic transaction-buffer-flush monitor it starts never actually fires:
   * it is only driven by a real {@code SCMServiceManager}, which is mocked here, matching
   * {@code TestSCMHAManagerImpl}'s already-proven-safe setup.
   */
  private SCMHAManagerImpl buildAndStartSingleNodeRealRatisHAManager()
      throws IOException, TimeoutException, InterruptedException {
    String clusterID = UUID.randomUUID().toString();
    String scmId = UUID.randomUUID().toString();
    int ratisPort;
    try (ServerSocket socket = new ServerSocket(0)) {
      socket.setReuseAddress(true);
      ratisPort = socket.getLocalPort();
    }

    OzoneConfiguration conf = new OzoneConfiguration();
    conf.set(ScmConfigKeys.OZONE_SCM_HA_RATIS_STORAGE_DIR, new File(testDir, "ratis").getAbsolutePath());
    conf.set(ScmConfigKeys.OZONE_SCM_RATIS_PORT_KEY, String.valueOf(ratisPort));

    SCMNodeDetails nodeDetails = mock(SCMNodeDetails.class);
    when(nodeDetails.getRatisHostPortStr()).thenReturn("localhost:" + ratisPort);
    SCMHANodeDetails haNodeDetails = mock(SCMHANodeDetails.class);
    when(haNodeDetails.getLocalNodeDetails()).thenReturn(nodeDetails);

    StorageContainerManager scm = mock(StorageContainerManager.class);
    when(scm.getClusterId()).thenReturn(clusterID);
    when(scm.getConfiguration()).thenReturn(conf);
    when(scm.getScmId()).thenReturn(scmId);
    when(scm.getScmMetadataStore()).thenReturn(metadataStore);
    when(scm.getScmNodeDetails()).thenReturn(nodeDetails);
    when(scm.getSCMHANodeDetails()).thenReturn(haNodeDetails);
    when(scm.getScmCertificateClient()).thenReturn(mock(CertificateClient.class));
    when(scm.getScmBlockManager()).thenReturn(blockManager);
    when(scm.getScmContext()).thenReturn(mock(SCMContext.class));
    when(scm.getSequenceIdGen()).thenReturn(mock(SequenceIdGenerator.class));
    when(scm.getScmDecommissionManager()).thenReturn(mock(NodeDecommissionManager.class));
    when(scm.getSCMServiceManager()).thenReturn(mock(SCMServiceManager.class));
    when(scm.getFinalizationManager()).thenReturn(mock(FinalizationManager.class));
    when(scm.getScmSafeModeManager()).thenReturn(mock(SCMSafeModeManager.class));
    when(scm.getDatanodeProtocolServer()).thenReturn(mock(SCMDatanodeProtocolServer.class));
    when(scm.getSystemClock()).thenReturn(Clock.systemUTC());

    SCMSnapshotProvider scmSnapshotProvider = mock(SCMSnapshotProvider.class);
    SCMHAManagerImpl haManager = new SCMHAManagerImpl(conf, new SecurityConfig(conf), scm) {
      @Override
      protected SCMSnapshotProvider newScmSnapshotProvider(StorageContainerManager storageContainerManager) {
        return scmSnapshotProvider;
      }
    };
    when(scm.getScmHAManager()).thenReturn(haManager);

    SCMRatisServerImpl.initialize(clusterID, scmId, nodeDetails, conf);
    haManager.start();
    GenericTestUtils.waitFor(() -> haManager.getRatisServer().getDivision().getInfo().isLeaderReady(),
        1000, 10000);
    return haManager;
  }

  // -------------------------------------------------------------------------

  private StorageContainerManager buildMockScm() {
    Clock clock = mock(Clock.class);
    when(clock.millis()).thenAnswer(inv -> clockMillis.get());
    when(blockManager.getDeletedBlockLog()).thenReturn(mock(DeletedBlockLogImpl.class));
    StorageContainerManager scm = mock(StorageContainerManager.class);
    when(scm.getScmMetadataStore()).thenReturn(metadataStore);
    when(scm.getSystemClock()).thenReturn(clock);
    when(scm.getScmBlockManager()).thenReturn(blockManager);
    return scm;
  }

  private static DeletedBlocksTransaction buildTx(long txId, int blockCount) {
    DeletedBlocksTransaction.Builder b = DeletedBlocksTransaction.newBuilder()
        .setTxID(txId)
        .setContainerID(CONTAINER_ID)
        .setCount(0)
        .setTotalBlockSize((long) blockCount * 10);
    for (int i = 0; i < blockCount; i++) {
      b.addLocalID((long) i);
    }
    return b.build();
  }

  private static ArrayList<DeletedBlocksTransaction> toList(DeletedBlocksTransaction... txs) {
    return new ArrayList<>(Arrays.asList(txs));
  }

  private static ArrayList<Long> toLongList(Long... ids) {
    return new ArrayList<>(Arrays.asList(ids));
  }

  /**
   * A {@link SCMHADBTransactionBufferImpl} subclass that, once armed, pauses
   * after the first {@code addToBuffer} call (the transaction-row write) and
   * before the second (the summary write). This replicates the race window in
   * {@code DeletedBlockLogStateManagerImpl.addTransactionsToDB} where
   * {@code notifyLeaderChanged}'s unguarded {@code flush()} can interpose.
   */
  static class PausingBuffer extends SCMHADBTransactionBufferImpl {

    private volatile CountDownLatch afterFirstAdd;
    private volatile CountDownLatch beforeSecondAdd;
    private volatile boolean armed = false;

    PausingBuffer(StorageContainerManager scm,
        CountDownLatch afterFirstAdd, CountDownLatch beforeSecondAdd)
        throws RocksDatabaseException, CodecException {
      super(scm);
      this.afterFirstAdd = afterFirstAdd;
      this.beforeSecondAdd = beforeSecondAdd;
    }

    void armPause(CountDownLatch afterFirst, CountDownLatch beforeSecond) {
      this.afterFirstAdd = afterFirst;
      this.beforeSecondAdd = beforeSecond;
      this.armed = true;
    }

    @Override
    public <KEY, VALUE> void addToBuffer(Table<KEY, VALUE> table, KEY key, VALUE value)
        throws RocksDatabaseException, CodecException {
      super.addToBuffer(table, key, value);
      if (armed) {
        armed = false;
        afterFirstAdd.countDown();
        try {
          beforeSecondAdd.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
    }
  }

  /**
   * Decorates a real {@link DeletedBlockLogStateManager}, pausing before delegating
   * {@code addTransactionsToDB}/{@code removeTransactionsFromDB} to simulate the blocking wait
   * for Ratis consensus that, in production, happens before the real DB write (on the Ratis
   * state machine's apply thread) ever touches the shared buffer.
   */
  static class BlockingStateManager implements DeletedBlockLogStateManager {

    private final DeletedBlockLogStateManager delegate;
    private final CountDownLatch started;
    private final CountDownLatch release;

    BlockingStateManager(DeletedBlockLogStateManager delegate, CountDownLatch started, CountDownLatch release) {
      this.delegate = delegate;
      this.started = started;
      this.release = release;
    }

    private void pause() {
      started.countDown();
      try {
        release.await(10, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    @Override
    public void addTransactionsToDB(ArrayList<DeletedBlocksTransaction> txs, DeletedBlocksTransactionSummary summary)
        throws IOException {
      pause();
      delegate.addTransactionsToDB(txs, summary);
    }

    @Override
    public void removeTransactionsFromDB(ArrayList<Long> txIDs, DeletedBlocksTransactionSummary summary)
        throws IOException {
      pause();
      delegate.removeTransactionsFromDB(txIDs, summary);
    }

    @Override
    public Table.KeyValueIterator<Long, DeletedBlocksTransaction> getReadOnlyIterator() throws IOException {
      return delegate.getReadOnlyIterator();
    }

    @Override
    public void onFlush() {
      delegate.onFlush();
    }

    @Override
    public void reinitialize(Table<Long, DeletedBlocksTransaction> deletedBlocksTXTable,
        Table<String, ByteString> statefulConfigTable) {
      delegate.reinitialize(deletedBlocksTXTable, statefulConfigTable);
    }
  }

  /**
   * A {@link SCMDeletedBlockTransactionStatusManager} subclass that, once armed, pauses
   * inside the {@code summaryLock}-guarded window: after the summary counters are updated
   * and before they are snapshotted via {@code getSummary()}. This lets a test deterministically
   * race a concurrent {@code onBecomeLeader()} against exactly that window.
   */
  static class PausingStatusManager extends SCMDeletedBlockTransactionStatusManager {

    private volatile CountDownLatch afterUpdate;
    private volatile CountDownLatch beforeSnapshot;
    private volatile boolean armed = false;

    PausingStatusManager(DeletedBlockLogStateManager deletedBlockLogStateManager,
        Table<String, ByteString> statefulServiceConfigTable, ContainerManager containerManager,
        ScmBlockDeletingServiceMetrics metrics, long scmCommandTimeoutMs) throws IOException {
      super(deletedBlockLogStateManager, statefulServiceConfigTable, containerManager, metrics,
          scmCommandTimeoutMs);
    }

    void armPause(CountDownLatch afterUpdateLatch, CountDownLatch beforeSnapshotLatch) {
      this.afterUpdate = afterUpdateLatch;
      this.beforeSnapshot = beforeSnapshotLatch;
      this.armed = true;
    }

    @Override
    protected void onSummaryUpdatedForTest() {
      if (armed) {
        armed = false;
        afterUpdate.countDown();
        try {
          beforeSnapshot.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
    }
  }
}
