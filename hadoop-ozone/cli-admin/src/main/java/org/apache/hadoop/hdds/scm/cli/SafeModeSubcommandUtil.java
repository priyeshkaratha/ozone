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

package org.apache.hadoop.hdds.scm.cli;

import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.commons.lang3.StringUtils;
import org.apache.hadoop.hdds.conf.OzoneConfiguration;
import org.apache.hadoop.hdds.scm.ha.SCMNodeInfo;
import org.apache.hadoop.hdds.scm.protocolPB.StorageContainerLocationProtocolClientSideTranslatorPB.ScmNodeTarget;

/**
 * Helpers shared by the {@code safemode} subcommands for resolving an optional
 * {@code --scm host:port} option to a single SCM node.
 */
final class SafeModeSubcommandUtil {

  private SafeModeSubcommandUtil() {
  }

  /**
   * Resolve an optional {@code --scm host:port} address to a
   * {@link ScmNodeTarget}. A blank address yields a target with no node id,
   * which makes the request fan out to all SCMs.
   *
   * @param conf the ozone configuration
   * @param scmAddress the {@code --scm} option value, may be blank
   * @return a node target, node id set only when an address is matched
   * @throws IOException if the address matches no SCM node in the service
   */
  static ScmNodeTarget resolveTarget(OzoneConfiguration conf, String scmAddress)
      throws IOException {
    ScmNodeTarget target = new ScmNodeTarget();
    if (StringUtils.isBlank(scmAddress)) {
      return target;
    }
    List<SCMNodeInfo> nodes = SCMNodeInfo.buildNodeInfo(conf);
    for (SCMNodeInfo node : nodes) {
      if (matchesAddress(node.getScmClientAddress(), scmAddress)) {
        target.setNodeId(node.getNodeId());
        return target;
      }
    }
    throw new IOException("--scm address " + scmAddress
        + " does not match any SCM node. Nodes: " + nodes.stream()
            .map(n -> n.getScmClientAddress() + " [" + n.getNodeId() + "]")
            .collect(Collectors.joining(", ")));
  }

  /**
   * Check if two SCM addresses refer to the same node. Matches on the full
   * {@code host:port} string, or on host name when a port is not specified on
   * one side.
   */
  static boolean matchesAddress(String address1, String address2) {
    if (address1.equalsIgnoreCase(address2)) {
      return true;
    }

    try {
      String[] parts1 = address1.split(":", 2);
      String[] parts2 = address2.split(":", 2);

      String host1 = parts1[0];
      String host2 = parts2[0];

      // Hostnames must match.
      if (!host1.equalsIgnoreCase(host2)) {
        return false;
      }

      // If both have ports specified, they must match.
      if (parts1.length > 1 && parts2.length > 1) {
        return parts1[1].equals(parts2[1]);
      }

      return true;
    } catch (Exception e) {
      // If address resolution fails, no match.
      return false;
    }
  }
}
