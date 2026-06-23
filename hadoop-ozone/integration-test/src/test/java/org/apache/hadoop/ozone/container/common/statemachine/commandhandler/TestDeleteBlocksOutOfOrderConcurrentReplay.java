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

package org.apache.hadoop.ozone.container.common.statemachine.commandhandler;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.apache.hadoop.ozone.OzoneConfigKeys.OZONE_BLOCK_DELETING_SERVICE_INTERVAL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.hadoop.hdds.client.RatisReplicationConfig;
import org.apache.hadoop.hdds.conf.OzoneConfiguration;
import org.apache.hadoop.hdds.protocol.datanode.proto.ContainerProtos;
import org.apache.hadoop.hdds.protocol.proto.HddsProtos;
import org.apache.hadoop.hdds.protocol.proto.StorageContainerDatanodeProtocolProtos.ContainerBlocksDeletionACKProto.DeleteBlockTransactionResult;
import org.apache.hadoop.hdds.protocol.proto.StorageContainerDatanodeProtocolProtos.DeletedBlocksTransaction;
import org.apache.hadoop.hdds.scm.ScmConfig;
import org.apache.hadoop.ozone.HddsDatanodeService;
import org.apache.hadoop.ozone.MiniOzoneCluster;
import org.apache.hadoop.ozone.OzoneTestUtils;
import org.apache.hadoop.ozone.client.ObjectStore;
import org.apache.hadoop.ozone.client.OzoneBucket;
import org.apache.hadoop.ozone.client.OzoneClient;
import org.apache.hadoop.ozone.client.OzoneVolume;
import org.apache.hadoop.ozone.client.io.OzoneOutputStream;
import org.apache.hadoop.ozone.container.common.impl.ContainerSet;
import org.apache.hadoop.ozone.container.common.interfaces.Container;
import org.apache.hadoop.ozone.container.common.interfaces.DBHandle;
import org.apache.hadoop.ozone.container.common.statemachine.DatanodeConfiguration;
import org.apache.hadoop.ozone.container.common.statemachine.commandhandler.DeleteBlocksCommandHandler.DeleteBlockTransactionExecutionResult;
import org.apache.hadoop.ozone.container.keyvalue.KeyValueContainerData;
import org.apache.hadoop.ozone.container.keyvalue.helpers.BlockUtils;
import org.apache.hadoop.ozone.container.ozoneimpl.OzoneContainer;
import org.apache.ozone.test.GenericTestUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Integration test that reproduces Scenario 1: concurrent out-of-order
 * transaction processing within a single {@code DeleteBlocksCommand} causes
 * {@code pendingDeletionBytes} to be double-counted on the datanode.
 *
 * <h3>Root cause</h3>
 * {@link DeleteBlocksCommandHandler#isDuplicateTransaction} returns
 * {@code true} only for the exact-equal case
 * ({@code txID == deleteTransactionId}). When {@code txID < deleteTransactionId}
 * it returns {@code false}, so an older transaction that arrives after a newer
 * one has already been committed is silently re-processed — and its
 * {@code pendingDeletionBytes} are added a second time.
 *
 * <h3>Trigger sequence</h3>
 * <ol>
 *   <li>A prior command already committed {@code txLow} (txID=5) on the DN,
 *       advancing {@code deleteTransactionId} to 5.</li>
 *   <li>A new command bundles {@code txHigh} (txID=10, new) and
 *       {@code txLow} (txID=5, SCM retry because the ACK was lost).</li>
 *   <li>The thread pool acquires the container write-lock for {@code txHigh}
 *       first, advancing {@code deleteTransactionId} to 10.</li>
 *   <li>The thread pool then processes {@code txLow} retry:
 *       {@code isDuplicateTransaction(5 < 10)} returns {@code false} — the
 *       {@code pendingDeletionBytes} for {@code txLow} are added again.</li>
 * </ol>
 *
 * <h3>Observable symptom</h3>
 * Recon's Storage Distribution API shows the DN reporting an inflated
 * {@code pendingDeletionSize} equal to exactly one transaction's
 * {@code sizePerReplica} more than OM / SCM. The value self-corrects after
 * {@link org.apache.hadoop.ozone.container.common.impl.BlockDeletingService}
 * finds an empty delete-transaction table and calls
 * {@link KeyValueContainerData#resetPendingDeleteBlockCount} (~2 minutes in
 * production).
 */
public class TestDeleteBlocksOutOfOrderConcurrentReplay {

  private static final Logger LOG = LoggerFactory
      .getLogger(TestDeleteBlocksOutOfOrderConcurrentReplay.class);

  // Matches one of the block sizes from the bug report (32 MiB, reported as
  // 33,554,431 bytes extra on the DN).
  private static final long SIZE_PER_REPLICA = 33_554_432L;

  // Synthetic local block IDs — these do not need to exist in the container's
  // block data table; the SchemaV3 marker only writes to the delete-txn table.
  private static final long[] LOW_LOCAL_IDS  = {100L, 101L, 102L};
  private static final long[] HIGH_LOCAL_IDS = {200L, 201L, 202L};

  private static final long LOW_TX_ID  = 5L;
  private static final long HIGH_TX_ID = 10L;

  private MiniOzoneCluster cluster;
  private OzoneClient client;
  private ObjectStore store;

  @BeforeEach
  public void setup() throws Exception {
    OzoneConfiguration conf = new OzoneConfiguration();

    // Disable automatic block deletion on both SCM and DNs so that neither
    // service interferes with in-memory counters during the test.
    conf.setTimeDuration(OZONE_BLOCK_DELETING_SERVICE_INTERVAL, 1, TimeUnit.HOURS);

    DatanodeConfiguration dnConf = conf.getObject(DatanodeConfiguration.class);
    dnConf.setBlockDeletionInterval(Duration.ofHours(1));
    conf.setFromObject(dnConf);

    ScmConfig scmConfig = conf.getObject(ScmConfig.class);
    scmConfig.setBlockDeletionInterval(Duration.ofHours(1));
    conf.setFromObject(scmConfig);

    cluster = MiniOzoneCluster.newBuilder(conf).setNumDatanodes(3).build();
    cluster.waitForClusterToBeReady();
    client = cluster.newClient();
    store = client.getObjectStore();
  }

  @AfterEach
  public void teardown() throws Exception {
    if (client != null) {
      client.close();
    }
    if (cluster != null) {
      cluster.shutdown();
    }
  }

  /**
   * Reproduces the double-counting of {@code pendingDeletionBytes} caused by
   * the out-of-order transaction replay gap in {@code isDuplicateTransaction}.
   *
   * <p>A {@link CountDownLatch} deterministically forces {@code txHigh} to
   * finish before {@code txLow}'s retry starts within a single
   * {@code executeCmdWithRetry} call, mirroring production thread-pool ordering
   * where the higher-txID task acquires the container write-lock first.
   */
  @Test
  public void testConcurrentHighBeforeLowDoubleCountsPendingDeletionBytes()
      throws Exception {

    // Step 1 — Write a key, close containers, pick the first closed container on DN-0.
    final KeyValueContainerData containerData = setupClosedContainer();
    final long containerId = containerData.getContainerID();
    LOG.info("Using container {} (schema={})",
        containerId, containerData.getSupportedSchemaVersionOrDefault());

    // Step 2 — Build a spy handler using DN-0's real OzoneContainer / RocksDB.
    HddsDatanodeService dn = cluster.getHddsDatanodes().get(0);
    OzoneContainer ozoneContainer = dn.getDatanodeStateMachine().getContainer();
    OzoneConfiguration clusterConf = cluster.getConf();
    DatanodeConfiguration clusterDnConf = clusterConf.getObject(DatanodeConfiguration.class);
    DeleteBlocksCommandHandler handler =
        spy(new DeleteBlocksCommandHandler(ozoneContainer, clusterConf, clusterDnConf, "test-ooo-"));

    try {
      // Step 3 — Two synthetic transactions for the same container.
      DeletedBlocksTransaction txLow = buildTx(LOW_TX_ID, containerId, LOW_LOCAL_IDS, SIZE_PER_REPLICA);
      DeletedBlocksTransaction txHigh = buildTx(HIGH_TX_ID, containerId, HIGH_LOCAL_IDS, SIZE_PER_REPLICA);

      // PHASE 1: Prior SCM command committed txLow → deleteTransactionId = 5.
      runPhase1(handler, txLow, containerData);

      // PHASE 2: New command bundles txHigh (new) with txLow (SCM retry after lost ACK).
      //          Latch forces txHigh first: isDuplicateTransaction(5 < 10) = false,
      //          causing pendingDeletionBytes to be incremented a second time.
      runPhase2WithControlledOrder(handler, txHigh, txLow, containerData, containerId);

      // Step 4: assertEquals asserts CORRECT state — FAILS when bug is present,
      //         showing: expected=<2×SIZE> but was=<3×SIZE>.
      assertPendingCounters(containerData);

      // Step 5: resetPendingDeleteBlockCount (called by BlockDeletingService when
      //         the txn table is empty) heals the inflation → counters drop to 0.
      verifySelfHeal(containerData, clusterConf);
    } finally {
      handler.stop();
    }
  }

  // ---------------------------------------------------------------------------
  // Step helpers
  // ---------------------------------------------------------------------------

  /**
   * Writes one small RATIS-3 key, closes all containers, waits for DN-0 to
   * have at least one CLOSED {@code KeyValueContainer}, and returns its data.
   */
  private KeyValueContainerData setupClosedContainer() throws Exception {
    String volumeName = UUID.randomUUID().toString();
    String bucketName = UUID.randomUUID().toString();
    store.createVolume(volumeName);
    OzoneVolume volume = store.getVolume(volumeName);
    volume.createBucket(bucketName);
    OzoneBucket bucket = volume.getBucket(bucketName);

    byte[] content = RandomStringUtils.secure().next(512).getBytes(UTF_8);
    try (OzoneOutputStream out = bucket.createKey(
        "probe-key", content.length,
        RatisReplicationConfig.getInstance(HddsProtos.ReplicationFactor.THREE),
        new HashMap<>())) {
      out.write(content);
    }

    OzoneTestUtils.closeAllContainers(
        cluster.getStorageContainerManager().getEventQueue(),
        cluster.getStorageContainerManager());

    ContainerSet containerSet = cluster.getHddsDatanodes().get(0)
        .getDatanodeStateMachine().getContainer().getContainerSet();

    GenericTestUtils.waitFor((BooleanSupplier) () ->
        containerSet.getContainerMap().values()
            .stream().anyMatch(c -> c.getContainerData().isClosed()),
        500, 20_000);

    KeyValueContainerData found = null;
    for (Container<?> c : containerSet.getContainerMap().values()) {
      if (c.getContainerData().isClosed()
          && c.getContainerData().getContainerType()
              == ContainerProtos.ContainerType.KeyValueContainer) {
        found = (KeyValueContainerData) c.getContainerData();
        break;
      }
    }
    assertNotNull(found, "Expected at least one closed KeyValueContainer on DN-0");
    return found;
  }

  /**
   * Phase 1: processes {@code txLow} as a standalone command (simulating a
   * prior SCM command) and asserts the initial counter state.
   */
  private void runPhase1(DeleteBlocksCommandHandler handler,
      DeletedBlocksTransaction txLow, KeyValueContainerData containerData) {
    handler.executeCmdWithRetry(Arrays.asList(txLow));

    assertEquals(LOW_TX_ID, containerData.getDeleteTransactionId(),
        "After phase 1, deleteTransactionId must equal LOW_TX_ID");
    assertEquals(SIZE_PER_REPLICA, containerData.getBlockPendingDeletionBytes(),
        "After phase 1, pendingBytes must equal SIZE_PER_REPLICA");
    LOG.info("Phase 1 complete: deleteTransactionId={}, pendingBytes={}, pendingBlocks={}",
        containerData.getDeleteTransactionId(),
        containerData.getBlockPendingDeletionBytes(),
        containerData.getNumPendingDeletionBlocks());
  }

  /**
   * Phase 2: intercepts {@code submitTasks} to force {@code txHigh} to commit
   * before {@code txLow}'s retry. The {@link CountDownLatch} makes the production
   * race condition deterministic so the test is reproducible.
   */
  private void runPhase2WithControlledOrder(
      DeleteBlocksCommandHandler handler,
      DeletedBlocksTransaction txHigh,
      DeletedBlocksTransaction txLow,
      KeyValueContainerData containerData,
      long containerId) throws InterruptedException {

    CountDownLatch highTxCommitted = new CountDownLatch(1);
    ExecutorService workerPool = Executors.newCachedThreadPool();

    doAnswer(invocation -> {
      @SuppressWarnings("unchecked")
      List<DeletedBlocksTransaction> txns =
          (List<DeletedBlocksTransaction>) invocation.getArgument(0);
      List<Future<DeleteBlockTransactionExecutionResult>> futures = new ArrayList<>(txns.size());
      for (DeletedBlocksTransaction tx : txns) {
        final DeletedBlocksTransaction finalTx = tx;
        futures.add(CompletableFuture.supplyAsync(() ->
            processOneTx(finalTx, handler, containerData, containerId, highTxCommitted),
            workerPool));
      }
      return futures;
    }).when(handler).submitTasks(any());

    handler.executeCmdWithRetry(Arrays.asList(txHigh, txLow));
    workerPool.shutdown();
    workerPool.awaitTermination(15, TimeUnit.SECONDS);
  }

  /** Processes one transaction inside the phase-2 worker pool. */
  private DeleteBlockTransactionExecutionResult processOneTx(
      DeletedBlocksTransaction tx,
      DeleteBlocksCommandHandler handler,
      KeyValueContainerData containerData,
      long containerId,
      CountDownLatch highTxCommitted) {
    try {
      if (tx.getTxID() == LOW_TX_ID) {
        // txLow retry blocks until txHigh has committed and advanced
        // deleteTransactionId to HIGH_TX_ID — the exact race window in prod.
        if (!highTxCommitted.await(10, TimeUnit.SECONDS)) {
          LOG.warn("Timed out waiting for txHigh to commit");
        }
      }
      String schema = containerData.getSupportedSchemaVersionOrDefault();
      handler.getSchemaHandlers().get(schema).handle(containerData, tx);
      if (tx.getTxID() == HIGH_TX_ID) {
        highTxCommitted.countDown();
      }
      return new DeleteBlockTransactionExecutionResult(
          DeleteBlockTransactionResult.newBuilder()
              .setTxID(tx.getTxID()).setContainerID(containerId).setSuccess(true).build(),
          false);
    } catch (Exception e) {
      LOG.error("Error processing tx {} in test", tx.getTxID(), e);
      return new DeleteBlockTransactionExecutionResult(
          DeleteBlockTransactionResult.newBuilder()
              .setTxID(tx.getTxID()).setContainerID(containerId).setSuccess(false).build(),
          false);
    }
  }

  /**
   * Asserts that the pending-deletion counters match the CORRECT expected values.
   * The assertions FAIL when the bug is present, showing the inflation clearly.
   */
  private void assertPendingCounters(KeyValueContainerData containerData) {
    // Correct: txLow once (phase 1) + txHigh once (phase 2) = 2 × SIZE_PER_REPLICA / 6 blocks.
    long expectedPendingBytes  = 2 * SIZE_PER_REPLICA;
    long expectedPendingBlocks = 6L;

    long actualPendingBytes  = containerData.getBlockPendingDeletionBytes();
    long actualPendingBlocks = containerData.getNumPendingDeletionBlocks();

    LOG.info("After phase 2: deleteTransactionId={}, pendingBytes={} (expected {}), "
            + "pendingBlocks={} (expected {})",
        containerData.getDeleteTransactionId(),
        actualPendingBytes, expectedPendingBytes,
        actualPendingBlocks, expectedPendingBlocks);

    // Fails with bug: expected=<2×SIZE> but was=<3×SIZE>.
    assertEquals(expectedPendingBytes, actualPendingBytes,
        String.format(
            "pendingDeletionBytes is over-counted. txLow (txID=%d) was re-processed after "
                + "txHigh (txID=%d) advanced deleteTransactionId to %d, because "
                + "isDuplicateTransaction(%d < %d) returned false instead of true. "
                + "Extra bytes = %d (one txLow sizePerReplica).",
            LOW_TX_ID, HIGH_TX_ID, HIGH_TX_ID, LOW_TX_ID, HIGH_TX_ID,
            actualPendingBytes - expectedPendingBytes));

    // Fails with bug: expected=<6> but was=<9>.
    assertEquals(expectedPendingBlocks, actualPendingBlocks,
        String.format("pendingDeletionBlocks is over-counted. "
                + "txLow's %d blocks were counted twice. Expected %d, got %d.",
            LOW_LOCAL_IDS.length, expectedPendingBlocks, actualPendingBlocks));

    assertEquals(HIGH_TX_ID, containerData.getDeleteTransactionId(),
        "deleteTransactionId must remain HIGH_TX_ID after the concurrent phase");
  }

  /**
   * Calls {@code resetPendingDeleteBlockCount} directly — the same call that
   * {@code BlockDeletingService} makes after finding an empty txn table (~2 min
   * in production) — and verifies that both counters drop to zero.
   */
  private void verifySelfHeal(KeyValueContainerData containerData,
      OzoneConfiguration clusterConf) throws IOException {
    try (DBHandle db = BlockUtils.getDB(containerData, clusterConf)) {
      containerData.resetPendingDeleteBlockCount(db);
    }
    assertEquals(0L, containerData.getBlockPendingDeletionBytes(),
        "After resetPendingDeleteBlockCount the byte counter must be zero");
    assertEquals(0L, containerData.getNumPendingDeletionBlocks(),
        "After resetPendingDeleteBlockCount the block counter must be zero");
    LOG.info("Self-heal verified: pendingDeletionBytes and pendingDeletionBlocks reset to 0");
  }

  // ---------------------------------------------------------------------------
  // Builder helper
  // ---------------------------------------------------------------------------

  /**
   * Builds a {@link DeletedBlocksTransaction} for the given container with an
   * explicit per-replica byte size so the {@code pendingDeletionBytes} counter
   * is exercised by the real schema handler.
   */
  private static DeletedBlocksTransaction buildTx(
      long txId, long containerId, long[] localIds, long sizePerReplica) {
    DeletedBlocksTransaction.Builder b = DeletedBlocksTransaction.newBuilder()
        .setTxID(txId)
        .setContainerID(containerId)
        .setTotalSizePerReplica(sizePerReplica)
        .setTotalBlockSize(sizePerReplica);
    for (long lid : localIds) {
      b.addLocalID(lid);
    }
    return b.build();
  }
}
