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

package org.apache.hadoop.ozone.recon.metrics;

import org.apache.hadoop.hdds.annotation.InterfaceAudience;
import org.apache.hadoop.metrics2.MetricsSystem;
import org.apache.hadoop.metrics2.annotation.Metric;
import org.apache.hadoop.metrics2.annotation.Metrics;
import org.apache.hadoop.metrics2.lib.DefaultMetricsSystem;
import org.apache.hadoop.metrics2.lib.MutableCounterLong;
import org.apache.hadoop.metrics2.lib.MutableGaugeLong;
import org.apache.hadoop.metrics2.lib.MutableRate;
import org.apache.hadoop.ozone.OzoneConsts;

/**
 * Metrics for the DeletedBlocksSyncTask that periodically checks for
 * discrepancies between SCM's checkpoint-actual and checkpoint-persisted
 * deleted-blocks counters and repairs them automatically.
 *
 * <p>The diff gauges reflect the most recent observed difference
 * (checkpoint-actual minus checkpoint-persisted) for each dimension and are
 * set to 0 when the counters are consistent.  Non-zero values indicate an
 * inconsistency was detected during that run.
 */
@InterfaceAudience.Private
@Metrics(about = "DeletedBlocksSyncTask Metrics", context = OzoneConsts.OZONE)
public final class DeletedBlocksSyncTaskMetrics {

  private static final String SOURCE_NAME =
      DeletedBlocksSyncTaskMetrics.class.getSimpleName();

  @Metric(about = "DeletedBlocksSyncTask runtime in milliseconds")
  private MutableRate runTimeMs;

  @Metric(about = "Number of successful DeletedBlocksSyncTask runs")
  private MutableCounterLong runSuccessCount;

  @Metric(about = "Number of failed DeletedBlocksSyncTask runs")
  private MutableCounterLong runFailureCount;

  @Metric(about = "Number of times a diff was detected and repair was applied")
  private MutableCounterLong repairCount;

  /** checkpoint-actual minus checkpoint-persisted: transaction count. */
  @Metric(about = "Diff in total transaction count (actual - persisted) from last run")
  private MutableGaugeLong diffTotalTransactionCount;

  /** checkpoint-actual minus checkpoint-persisted: block count. */
  @Metric(about = "Diff in total block count (actual - persisted) from last run")
  private MutableGaugeLong diffTotalBlockCount;

  /** checkpoint-actual minus checkpoint-persisted: block size in bytes. */
  @Metric(about = "Diff in total block size bytes (actual - persisted) from last run")
  private MutableGaugeLong diffTotalBlockSize;

  /** checkpoint-actual minus checkpoint-persisted: replicated size in bytes. */
  @Metric(about = "Diff in total replicated size bytes (actual - persisted) from last run")
  private MutableGaugeLong diffTotalBlockReplicatedSize;

  private DeletedBlocksSyncTaskMetrics() {
  }

  public static DeletedBlocksSyncTaskMetrics create() {
    MetricsSystem ms = DefaultMetricsSystem.instance();
    return ms.register(SOURCE_NAME,
        "DeletedBlocksSyncTask Metrics",
        new DeletedBlocksSyncTaskMetrics());
  }

  public void unRegister() {
    MetricsSystem ms = DefaultMetricsSystem.instance();
    ms.unregisterSource(SOURCE_NAME);
  }

  public void addRunTime(long runtimeMs) {
    runTimeMs.add(runtimeMs);
  }

  public void incrSuccess() {
    runSuccessCount.incr();
  }

  public void incrFailure() {
    runFailureCount.incr();
  }

  public void incrRepairCount() {
    repairCount.incr();
  }

  /**
   * Record the per-dimension diffs observed during a checkpoint comparison.
   * Pass 0 for all parameters when the counters are consistent.
   */
  public void setDiff(long txnCountDiff, long blockCountDiff,
      long blockSizeDiff, long blockReplicatedSizeDiff) {
    diffTotalTransactionCount.set(txnCountDiff);
    diffTotalBlockCount.set(blockCountDiff);
    diffTotalBlockSize.set(blockSizeDiff);
    diffTotalBlockReplicatedSize.set(blockReplicatedSizeDiff);
  }
}
