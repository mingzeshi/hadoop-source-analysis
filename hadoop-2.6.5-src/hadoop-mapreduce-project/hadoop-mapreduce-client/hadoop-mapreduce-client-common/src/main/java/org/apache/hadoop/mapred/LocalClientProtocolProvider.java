/**
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

package org.apache.hadoop.mapred;

import java.io.IOException;
import java.net.InetSocketAddress;

import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.mapreduce.MRConfig;
import org.apache.hadoop.mapreduce.protocol.ClientProtocol;
import org.apache.hadoop.mapreduce.protocol.ClientProtocolProvider;

@InterfaceAudience.Private
public class LocalClientProtocolProvider extends ClientProtocolProvider {

  @Override
  public ClientProtocol create(Configuration conf) throws IOException { // 创建本地的任务运行组件
    String framework =
        conf.get(MRConfig.FRAMEWORK_NAME, MRConfig.LOCAL_FRAMEWORK_NAME); // 默认是MRConfig.LOCAL_FRAMEWORK_NAME：local
    if (!MRConfig.LOCAL_FRAMEWORK_NAME.equals(framework)) { // 如果非local模式
      return null;
    }
    conf.setInt(JobContext.NUM_MAPS, 1); // 如果是本地模式，设置：mapreduce.job.maps，map的job数量是1

    return new LocalJobRunner(conf); // 如果是local模式，创建LocalJobRunner，本地任务运行组件
  }

  @Override
  public ClientProtocol create(InetSocketAddress addr, Configuration conf) {
    return null; // LocalJobRunner doesn't use a socket
  }

  @Override
  public void close(ClientProtocol clientProtocol) {
    // no clean up required
  }

}
