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

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.mapreduce.MRConfig;
import org.apache.hadoop.mapreduce.protocol.ClientProtocol;
import org.apache.hadoop.mapreduce.protocol.ClientProtocolProvider;

public class YarnClientProtocolProvider extends ClientProtocolProvider {

  @Override
  public ClientProtocol create(Configuration conf) throws IOException { // 创建与yarn集群通信的任务运行组件
	  
	/**
	 * 根据配置文件判断，如果向集群提交任务会加载yanr-site.xml文件，那里会有集群的uri
	 * 
	 * MRConfig.YARN_FRAMEWORK_NAME：yarn
	 * conf.get(MRConfig.FRAMEWORK_NAME)，如果是本地提交是local，集群提交是yarn
	 */
    if (MRConfig.YARN_FRAMEWORK_NAME.equals(conf.get(MRConfig.FRAMEWORK_NAME))) {
      return new YARNRunner(conf); // 创建一个YARNRunner
    }
    return null;
  }

  @Override
  public ClientProtocol create(InetSocketAddress addr, Configuration conf)
      throws IOException {
    return create(conf);
  }

  @Override
  public void close(ClientProtocol clientProtocol) throws IOException {
    if (clientProtocol instanceof YARNRunner) {
      ((YARNRunner)clientProtocol).close();
    }
  }
}
