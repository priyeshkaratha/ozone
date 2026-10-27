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

import { Popover, Tag, Typography } from 'antd';
import React from 'react';
import AutoReloadPanel from '@/components/autoReloadPanel/autoReloadPanel';

import './capacity.less';
import { showDataFetchError } from '@/utils/common'
import moment from 'moment';
import CapacityBreakdown from '@/v2/pages/capacity/components/CapacityBreakdown';
import CapacityDetail from '@/v2/pages/capacity/components/CapacityDetail';
import {
  datanodesPendingDeletionDesc,
  nodeSelectorMessage,
  otherUsedSpaceDesc,
  ozoneUsedSpaceDesc
} from '@/v2/pages/capacity/constants/descriptions.constants';
import WrappedInfoIcon from '@/v2/pages/capacity/components/WrappedInfoIcon';
import filesize from 'filesize';
import { InfoCircleOutlined, WarningFilled, CheckCircleFilled } from '@ant-design/icons';
import { useApiData } from '@/v2/hooks/useAPIData.hook';
import * as CONSTANTS from '@/v2/constants/capacity.constants';
import { UtilizationResponse, SCMPendingDeletion, OMPendingDeletion, DNPendingDeletion, DataNodeUsage } from '@/v2/types/capacity.types';
import { useAutoReload } from '@/v2/hooks/useAutoReload.hook';

type CapacityState = {
  lastUpdated: number;
};

type PendingScanStatus = "NOT_STARTED" | "IN_PROGRESS" | "FINISHED" | "FAILED";

// Polls `poll()` at `intervalMs` while `status` is IN_PROGRESS, then calls
// `onFinished()` exactly once when it transitions to FINISHED (not on the
// initial load, where the endpoint was already fetched together with others).
function usePendingScanPoll(
  status: PendingScanStatus,
  poll: () => void,
  onFinished: () => void,
  intervalMs: number
) {
  const pollRef = React.useRef(poll);
  pollRef.current = poll;
  const onFinishedRef = React.useRef(onFinished);
  onFinishedRef.current = onFinished;
  const isDrivingRef = React.useRef(false);

  React.useEffect(() => {
    if (status === "IN_PROGRESS") {
      isDrivingRef.current = true;
      const timer = window.setInterval(() => pollRef.current(), intervalMs);
      return () => clearInterval(timer);
    }

    if (isDrivingRef.current) {
      isDrivingRef.current = false;
      if (status === "FINISHED") {
        onFinishedRef.current();
      }
    }
  }, [status, intervalMs]);
}

const Capacity: React.FC<object> = () => {
  const PENDING_POLL_INTERVAL = 5 * 1000;
  const DN_CSV_DOWNLOAD_URL = '/api/v1/storageDistribution/download';
  const DN_STATUS_URL = '/api/v1/pendingDeletion?component=dn';
  const DOWNLOAD_POLL_TIMEOUT_MS = 10 * 60 * 1000;

  const [state, setState] = React.useState<CapacityState>({
    lastUpdated: 0
  });

  const storageDistribution = useApiData<UtilizationResponse>(
    '/api/v1/storageDistribution',
    CONSTANTS.DEFAULT_CAPACITY_UTILIZATION,
    {
      retryAttempts: 2,
      onError: (error) => showDataFetchError(error)
    }
  );
  
  const scmPendingDeletes = useApiData<SCMPendingDeletion>(
    '/api/v1/pendingDeletion?component=scm',
    CONSTANTS.DEFAULT_SCM_PENDING_DELETION,
    {
      retryAttempts: 2,
      onError: (error) => showDataFetchError(error)
    }
  );

  const omPendingDeletes = useApiData<OMPendingDeletion>(
    '/api/v1/pendingDeletion?component=om',
    CONSTANTS.DEFAULT_OM_PENDING_DELETION,
    {
      retryAttempts: 2,
      onError: (error) => showDataFetchError(error)
    }
  );

  const dnPendingDeletes = useApiData<DNPendingDeletion>(
    '/api/v1/pendingDeletion?component=dn&limit=15',
    CONSTANTS.DEFAULT_DN_PENDING_DELETION,
    {
      retryAttempts: 2,
      onError: (error) => showDataFetchError(error)
    }
  );

  const [selectedDatanode, setSelectedDatanode] = React.useState<string>("");

  // Only show DNs that appear in the pending deletion response (max 15), so the
  // dropdown and the pending-block-size values are always in sync.
  const filteredDNs = React.useMemo(() => {
    const pendingHostNames = new Set(
      (dnPendingDeletes.data.pendingDeletionPerDataNode ?? []).map(dn => dn.hostName)
    );
    return storageDistribution.data.dataNodeUsage.filter(dn => pendingHostNames.has(dn.hostName));
  }, [storageDistribution.data.dataNodeUsage, dnPendingDeletes.data.pendingDeletionPerDataNode]);

  // Seed selected datanode once data loads so dependent calculations work.
  // Prefer the first available DN so the user does not land on the error card
  // while a healthy DN exists; fall back to the first DN (surfacing the error
  // card) only when every DN is unavailable.
  React.useEffect(() => {
    const hostNames = filteredDNs.map(dn => dn.hostName);
    if (!hostNames.includes(selectedDatanode)) {
      const unavailableHosts = new Set(
        (dnPendingDeletes.data.pendingDeletionPerDataNode ?? [])
          .filter(dn => dn.pendingBlockSize === -1)
          .map(dn => dn.hostName)
      );
      const firstAvailable = hostNames.find(hostName => !unavailableHosts.has(hostName));
      setSelectedDatanode(firstAvailable ?? hostNames[0] ?? "");
    }
  }, [filteredDNs]);

  const loadData = () => {
    storageDistribution.refetch();
    scmPendingDeletes.refetch();
    omPendingDeletes.refetch();
    dnPendingDeletes.refetch();
    setState({
      lastUpdated: Number(moment())
    })
  }

  const loadDataIfIdle = () => {
    if (
      storageDistribution.loading ||
      scmPendingDeletes.loading ||
      omPendingDeletes.loading ||
      dnPendingDeletes.loading
    ) {
      return;
    }
    loadData();
  };

  const autoReload = useAutoReload(loadDataIfIdle);

  const selectedDNDetails: DataNodeUsage & {
    pendingBlockSize: number;
    unavailable: boolean;
  } = React.useMemo(() => {
    const selected = storageDistribution.data.dataNodeUsage.find(datanode => datanode.hostName === selectedDatanode)
      ?? storageDistribution.data.dataNodeUsage[0];
    const dnPendingEntry = dnPendingDeletes.data.pendingDeletionPerDataNode?.find(
      dn => dn.hostName === (selected?.hostName ?? selectedDatanode)
    ) ?? { hostName: "unknown-host", datanodeUuid: "unknown-uuid", pendingBlockSize: 0 };
    return {
      ...(selected ?? {
        datanodeUuid: "unknown-uuid",
        hostName: "unknown-host",
        capacity: 0,
        used: 0,
        remaining: 0,
        committed: 0,
        minimumFreeSpace: 0,
        reserved: 0
      }),
      ...dnPendingEntry,
      pendingBlockSize: Math.max(0, dnPendingEntry.pendingBlockSize),
      // -1 is the sentinel for a DN whose pending deletion query failed (offline/unreachable).
      // Its capacity data from the storage report may be outdated, so surface an error instead.
      unavailable: dnPendingEntry.pendingBlockSize === -1
    }
  }, [selectedDatanode, storageDistribution.data.dataNodeUsage, dnPendingDeletes.data.pendingDeletionPerDataNode]);

  const waitForDnFinished = async () => {
    const startTime = Date.now();
    while (Date.now() - startTime < DOWNLOAD_POLL_TIMEOUT_MS) {
      const response = await fetch(DN_STATUS_URL);
      if (!response.ok) {
        throw new Error(`Status check failed: ${response.statusText}`);
      }
      const data = await response.json() as DNPendingDeletion;
      if (data.status === "FINISHED") {
        return;
      }
      await new Promise((resolve) => setTimeout(resolve, PENDING_POLL_INTERVAL));
    }
    throw new Error('CSV download not ready. Please try again later.');
  };

  const downloadCsv = async (url: string) => {
    try {
      await waitForDnFinished();
      const response = await fetch(url);
      if (!response.ok) {
        showDataFetchError(`CSV download failed: ${response.statusText}`);
        return;
      }
      const contentType = response.headers.get('content-type') ?? '';
      if (!contentType.includes('text/csv')) {
        showDataFetchError('CSV download not ready. Please try again later.');
        return;
      }
      const contentDisposition = response.headers.get('content-disposition');
      const filenameMatch = contentDisposition?.match(/filename="([^"]+)"/);
      if (!filenameMatch) {
        showDataFetchError('CSV download not ready. Please try again later.');
        return;
      }
      const blob = await response.blob();
      const filename = filenameMatch?.[1] ?? 'pending_deletion_stats.csv';
      const link = document.createElement('a');
      link.href = window.URL.createObjectURL(blob);
      link.download = filename;
      link.click();
      window.URL.revokeObjectURL(link.href);
    } catch (error) {
      showDataFetchError((error as Error).message);
    }
  };

  // --- DN / OM pending-deletion scan polling ----------------------------------
  // The periodic full refresh (all four endpoints) is driven by useAutoReload
  // when Auto Refresh is enabled, and by the manual reload button otherwise.
  // On top of that, whenever a DN or OM scan is reported IN_PROGRESS we poll
  // ONLY that endpoint at a fast cadence until it finishes, then refetch the
  // other endpoints once so the aggregate pending-deletion numbers reflect the
  // final data. This runs regardless of the Auto Refresh toggle, because a
  // running scan must be driven to completion either way.
  usePendingScanPoll(
    dnPendingDeletes.data.status,
    () => { if (!dnPendingDeletes.loading) dnPendingDeletes.refetch(); },
    () => {
      storageDistribution.refetch();
      scmPendingDeletes.refetch();
      omPendingDeletes.refetch();
      setState({ lastUpdated: Number(moment()) });
    },
    PENDING_POLL_INTERVAL
  );

  usePendingScanPoll(
    omPendingDeletes.data.status,
    () => { if (!omPendingDeletes.loading) omPendingDeletes.refetch(); },
    () => {
      storageDistribution.refetch();
      scmPendingDeletes.refetch();
      dnPendingDeletes.refetch();
      setState({ lastUpdated: Number(moment()) });
    },
    PENDING_POLL_INTERVAL
  );

  const dnReportStatus = (
    (dnPendingDeletes.data.totalNodeQueriesFailed ?? 0) > 0
    ? <Popover content={<>
      { (dnPendingDeletes.data.totalNodesQueried ?? 0)
        - (dnPendingDeletes.data.totalNodeQueriesFailed ?? 0)
      } / { (dnPendingDeletes.data.totalNodesQueried ?? 0) } DNs
      </>
    }>
      <WarningFilled style={{ color: '#f6a62eff', marginRight: 8, fontSize: 14 }} />
      Datanodes
    </Popover>
    : <Popover content={
      <>
        {dnPendingDeletes.data.totalNodesQueried ?? 0} / {dnPendingDeletes.data.totalNodesQueried ?? 0} DNs
      </>
    }>
      <CheckCircleFilled style={{ color: '#1ea57a', marginRight: 8, fontSize: 14 }} />
        Datanodes
    </Popover>
  );

  const unusedSpaceBreakdown = (
    <span>
      UNUSED
      <Popover
        title="Unused Space Breakdown"
        placement='topLeft'
        content={
          <div className='unused-space-breakdown'>
            Minimum Free Space
            <Tag color='red'>{filesize(selectedDNDetails.minimumFreeSpace, {round: 1})}</Tag>
            Remaining
            <Tag color='green'>{filesize(selectedDNDetails.remaining, { round: 1})}</Tag>
          </div>
        }
      >
        <InfoCircleOutlined style={{ color: '#2f84d8', fontSize: 12, marginLeft: 4 }} />
      </Popover>
    </span>
  );

  const totalCapacityDesc = (
    <span>
      TOTAL CAPACITY
      <Popover
        title="Capacity Breakdown"
        placement="topLeft"
        content={
            <>
              <div className='unused-space-breakdown'>
                File System Capacity
                <Tag color='blue'>{filesize(storageDistribution.data.globalStorage.totalFileSystemCapacity, { round: 1 })}</Tag>
                Reserved Space
                <Tag color='orange'>{filesize(storageDistribution.data.globalStorage.totalReservedSpace, { round: 1 })}</Tag>
              </div>
              <div className="capacity-info">
                Ozone Capacity shows how much usable space is available after subtracting the reserved space from the file system capacity.
              </div>
            </>
        }
      >
        <InfoCircleOutlined style={{ color: '#2f84d8', fontSize: 12, marginLeft: 4 }} />
      </Popover>
    </span>
  );

  const dnSelectorTitle = (
    <span>
      Node Selector <WrappedInfoIcon title={nodeSelectorMessage} />
    </span>
  );

  const openKeyUsageBreakdown = (
    <span>
      OPEN KEYS
      <Popover
        title="Open Key Breakdown"
        placement='topLeft'
        content={
          <div className='openkeys-space-breakdown'>
            Open Key/File Bytes
            <Tag color='blue'>{filesize(storageDistribution.data.usedSpaceBreakdown.openKeyBytes?.openKeyAndFileBytes ?? 0, {round: 1})}</Tag>
            Multipart Key Bytes
            <Tag color='orange'>{filesize(storageDistribution.data.usedSpaceBreakdown.openKeyBytes?.multipartOpenKeyBytes ?? 0, {round: 1})}</Tag>
          </div>
        }
      >
        <InfoCircleOutlined style={{ color: '#2f84d8', fontSize: 12, marginLeft: 4 }} />
      </Popover>
    </span>
  );

  const hasSCMPendingDeletionError = (
    scmPendingDeletes.data.totalBlocksize < 0
    || scmPendingDeletes.data.totalReplicatedBlockSize < 0
    || scmPendingDeletes.data.totalBlocksCount < 0
  );

  const scmReplicatedPendingDeletionSize = hasSCMPendingDeletionError
    ? 0
    : scmPendingDeletes.data.totalReplicatedBlockSize;

  // The in-progress (202) response has no totals, so treat missing values as unavailable instead of
  // letting them turn the aggregate PENDING DELETION value into NaN.
  const omPurgeableSize = omPendingDeletes.data.totalPurgeableSize ?? -1;
  const omSnapshotHeldSize = omPendingDeletes.data.totalSnapshotTrappedSize ?? -1;
  const hasOMPendingDeletionError = (
    omPendingDeletes.data.status === "FAILED"
    || (omPendingDeletes.data.status === "FINISHED" && (omPurgeableSize < 0 || omSnapshotHeldSize < 0))
  );

  const omPendingDeletionSize = (omPurgeableSize < 0 || omSnapshotHeldSize < 0)
    ? 0
    : omPurgeableSize + omSnapshotHeldSize;

  return (
    <>
      <div className='page-header-v2'>
        Cluster Capacity
        <AutoReloadPanel
          isLoading={dnPendingDeletes.loading}
          lastRefreshed={state.lastUpdated}
          togglePolling={autoReload.handleAutoReloadToggle}
          onReload={loadData} />
      </div>
      <div className='data-container'>
        <Typography.Title level={4} className='section-title'>Cluster</Typography.Title>
        <CapacityBreakdown
          title='Ozone Capacity'
          loading={storageDistribution.loading}
          items={[{
            title: totalCapacityDesc,
            value: storageDistribution.data.globalStorage.totalOzoneCapacity,
          }, {
            title: 'USED SPACE',
            value: storageDistribution.data.globalStorage.totalOzoneUsedSpace,
            color: '#f4a233'
          }, {
            title: (
              <span>
                OTHER USED SPACE
                <WrappedInfoIcon title={otherUsedSpaceDesc} />
              </span>
            ),
            value: (
              storageDistribution.data.globalStorage.totalOzoneCapacity
              - storageDistribution.data.globalStorage.totalOzoneFreeSpace
              - storageDistribution.data.globalStorage.totalOzoneUsedSpace
            ),
            color: '#11073a'
          }, {
            title: 'CONTAINER PRE-ALLOCATED',
            value: storageDistribution.data.globalStorage.totalOzoneCommittedSpace,
            color: '#f47b2d'
          }, {
            title: 'REMAINING SPACE',
            value: storageDistribution.data.globalStorage.totalOzoneFreeSpace,
            color: '#4553ee'
          }]}
        />
        <Typography.Title level={4} className='section-title'>Service</Typography.Title>
        <CapacityBreakdown
          title={(
            <span>
              Ozone Used Space
              <WrappedInfoIcon title={ozoneUsedSpaceDesc} />
            </span>
          )}
          loading={storageDistribution.loading}
          items={[{
            title: 'TOTAL',
            value: storageDistribution.data.globalStorage.totalOzoneUsedSpace
          }, {
            title: openKeyUsageBreakdown,
            value: storageDistribution.data.usedSpaceBreakdown.openKeyBytes?.totalOpenKeyBytes ?? 0,
            color: '#f47c2d'
          }, {
            title: 'COMMITTED KEYS',
            value: storageDistribution.data.usedSpaceBreakdown.finalizedKeyBytes,
            color: '#f4a233'
          }, {
            title: (
              dnPendingDeletes.data.status !== "FINISHED" || dnPendingDeletes.loading
              || omPendingDeletes.data.status !== "FINISHED" || omPendingDeletes.loading
              ? (
                <span>
                  PENDING DELETION
                  <WrappedInfoIcon title="OM/DN pending deletion data is not yet available. It will be fetched once the pending deletion scan is finished." />
                </span>
              )
              : 'PENDING DELETION'
            ),
            value: (
              omPendingDeletionSize
              + scmReplicatedPendingDeletionSize
              + (dnPendingDeletes.data.totalPendingDeletionSize ?? 0)
            ),
            color: "#10073b"
          }]}
        />
        <div className='data-breakdown-section'>
          <CapacityDetail
            title='Pending Deletion'
            loading={omPendingDeletes.loading || scmPendingDeletes.loading}
            showDropdown={false}
            dataDetails={[{
              title: 'OZONE MANAGER',
              loading: omPendingDeletes.loading
                || (omPendingDeletes.data.status !== "FINISHED" && omPendingDeletes.data.status !== "FAILED"),
              size: omPendingDeletionSize,
              hasError: hasOMPendingDeletionError,
              errorMessage: omPendingDeletes.data.message ?? 'OM pending deletion details are currently unavailable.',
              errorTestId: 'pending-deletion-om-error',
              breakdown: [{
                label: 'PURGEABLE',
                value: omPurgeableSize,
                color: '#f4a233'
              }, {
                label: 'SNAPSHOT HELD',
                value: omSnapshotHeldSize,
                color: '#10073b'
              }],
              sizes: [{
                label: 'PURGEABLE DIRECTORIES',
                value: omPendingDeletes.data.totalPurgeableDirSize ?? -1
              }, {
                label: 'PURGEABLE KEYS',
                value: omPendingDeletes.data.totalPurgeableKeySize ?? -1
              }]
            }, {
              title: 'STORAGE CONTAINER MANAGER',
              size: hasSCMPendingDeletionError ? 0 : scmPendingDeletes.data.totalReplicatedBlockSize,
              hasError: hasSCMPendingDeletionError,
              errorMessage: 'SCM pending deletion details are currently unavailable.',
              errorTestId: 'pending-deletion-scm-error',
              breakdown: [{
                label: 'BLOCKS',
                value: scmPendingDeletes.data.totalReplicatedBlockSize,
                color: '#f4a233'
              }]
            }, {
              title: (
                <span>
                  DATANODES
                  <WrappedInfoIcon title={datanodesPendingDeletionDesc} />
                </span>
              ),
              loading: dnPendingDeletes.loading || dnPendingDeletes.data.status !== "FINISHED",
              size: dnPendingDeletes.data.totalPendingDeletionSize ?? 0,
              breakdown: [{
                label: 'BLOCKS',
                value: dnPendingDeletes.data.totalPendingDeletionSize ?? 0,
                color: '#f4a233'
              }]
            }]} />
          <CapacityDetail
            title={dnReportStatus}
            loading={dnPendingDeletes.loading || dnPendingDeletes.data.status !== "FINISHED"}
            showDropdown={true}
            selectorTitle={dnSelectorTitle}
            downloadUrl={DN_CSV_DOWNLOAD_URL}
            onDownloadClick={() => downloadCsv(DN_CSV_DOWNLOAD_URL)}
            handleSelect={setSelectedDatanode}
            selectedValue={selectedDatanode}
            dropdownItems={filteredDNs.map(datanode => ({
              label: (
                <>
                  <span>{datanode.hostName}</span>
                  <span className="dn-select-option-uuid">{datanode.datanodeUuid}</span>
                </>
              ),
              value: datanode.hostName,
              key: datanode.datanodeUuid
            }))}
            disabledOpts={
              (dnPendingDeletes.data.pendingDeletionPerDataNode ?? [])
                .filter(dn => dn.pendingBlockSize === -1)
                .map(dn => dn.hostName)
            }
            optsClass={'dn-select-option'}
            dataDetails={[{
              title: 'USED SPACE',
              size: (selectedDNDetails.used ?? 0) + (selectedDNDetails.pendingBlockSize ?? 0),
              hasError: selectedDNDetails.unavailable,
              errorMessage: 'Datanode is unavailable; capacity data may be outdated.',
              errorTestId: 'dn-used-space-error',
              breakdown: [{
                label: 'PENDING DELETION',
                value: selectedDNDetails.pendingBlockSize ?? 0,
                color: '#f4a233'
              }, {
                label: 'OZONE USED',
                value: selectedDNDetails.used ?? 0,
                color: '#10073b'
              }]
            }, {
              title: 'FREE SPACE',
              size: (selectedDNDetails.remaining ?? 0) + (selectedDNDetails.committed ?? 0),
              hasError: selectedDNDetails.unavailable,
              errorMessage: 'Datanode is unavailable; capacity data may be outdated.',
              errorTestId: 'dn-free-space-error',
              breakdown: [{
                label: unusedSpaceBreakdown,
                value: selectedDNDetails.remaining ?? 0,
                color: '#f4a233'
              }, {
                label: 'OZONE PRE-ALLOCATED',
                value: selectedDNDetails.committed ?? 0,
                color: '#10073b'
              }]
            }]} />
        </div>
      </div>
    </>

  )

};

export default Capacity;
