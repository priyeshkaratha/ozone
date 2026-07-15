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

import java.util.concurrent.Callable;
import org.apache.hadoop.hdds.cli.AbstractSubcommand;
import org.apache.hadoop.hdds.cli.HddsVersionProvider;
import org.apache.hadoop.hdds.conf.OzoneConfiguration;
import org.apache.hadoop.hdds.scm.client.ScmClient;
import org.apache.hadoop.hdds.scm.protocolPB.StorageContainerLocationProtocolClientSideTranslatorPB.ScmNodeTarget;
import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * Handler for the safe mode enter command. Puts SCM into manual safe mode,
 * which never auto-exits and must be cleared with
 * {@code ozone admin safemode exit}. With no {@code --scm} option it applies to
 * all SCMs in the service; with {@code --scm host:port} it applies to that
 * single SCM only.
 */
@Command(
    name = "enter",
    description = "Put SCM into manual safe mode. Applies to all SCMs, or to a "
        + "single SCM when --scm is given. Manual safe mode never auto-exits "
        + "and must be cleared with 'ozone admin safemode exit'.",
    mixinStandardHelpOptions = true,
    versionProvider = HddsVersionProvider.class)
public class SafeModeEnterSubcommand extends AbstractSubcommand
    implements Callable<Void> {

  @CommandLine.Mixin
  private ScmOption scmOption;

  @Override
  public Void call() throws Exception {
    OzoneConfiguration conf = getOzoneConf();
    ScmNodeTarget target =
        SafeModeSubcommandUtil.resolveTarget(conf, scmOption.getScm());
    try (ScmClient scmClient = scmOption.createScmClient(conf, target)) {
      boolean inSafeMode = scmClient.enterSafeMode();
      if (target.hasNodeId()) {
        System.out.printf("SCM [%s] entered manual safe mode (inSafeMode=%s).%n",
            target.getNodeId(), inSafeMode);
      } else {
        System.out.println("All SCMs entered manual safe mode.");
      }
    }
    return null;
  }
}
