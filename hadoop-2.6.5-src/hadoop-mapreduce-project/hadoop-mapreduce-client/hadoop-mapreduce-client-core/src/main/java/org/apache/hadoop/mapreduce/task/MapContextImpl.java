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

package org.apache.hadoop.mapreduce.task;

import java.io.IOException;

import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.classification.InterfaceStability;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.mapreduce.InputSplit;
import org.apache.hadoop.mapreduce.MapContext;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.OutputCommitter;
import org.apache.hadoop.mapreduce.RecordReader;
import org.apache.hadoop.mapreduce.RecordWriter;
import org.apache.hadoop.mapreduce.StatusReporter;
import org.apache.hadoop.mapreduce.TaskAttemptID;

/**
 * The context that is given to the {@link Mapper}.
 * @param <KEYIN> the key input type to the Mapper
 * @param <VALUEIN> the value input type to the Mapper
 * @param <KEYOUT> the key output type from the Mapper
 * @param <VALUEOUT> the value output type from the Mapper
 */
@InterfaceAudience.Private
@InterfaceStability.Unstable
public class MapContextImpl<KEYIN,VALUEIN,KEYOUT,VALUEOUT> 
    extends TaskInputOutputContextImpl<KEYIN,VALUEIN,KEYOUT,VALUEOUT> 
    implements MapContext<KEYIN, VALUEIN, KEYOUT, VALUEOUT> {
  private RecordReader<KEYIN,VALUEIN> reader;
  private InputSplit split;

  public MapContextImpl(Configuration conf, TaskAttemptID taskid,
                        RecordReader<KEYIN,VALUEIN> reader,
                        RecordWriter<KEYOUT,VALUEOUT> writer,
                        OutputCommitter committer,
                        StatusReporter reporter,
                        InputSplit split) {
	/**
	 * conf: Configuration: core-default.xml, core-site.xml, mapred-default.xml, mapred-site.xml, yarn-default.xml, yarn-site.xml, hdfs-default.xml, hdfs-site.xml, file:/tmp/hadoop-Administrator/mapred/local/localRunner/hdfs/job_local515674942_0001/job_local515674942_0001.xml
	 * taskid: attempt_local515674942_0001_m_000000_0 
	 * writer: org.apache.hadoop.mapred.MapTask$NewOutputCollector，默认子类是MapOutputBuffer
	 * committer: org.apache.hadoop.mapreduce.lib.output.FileOutputCommitter 
	 * reporter: org.apache.hadoop.mapred.Task$TaskReporter
	 */
    super(conf, taskid, writer, committer, reporter);
    this.reader = reader; // org.apache.hadoop.mapred.MapTask$NewTrackingRecordReader，实际创建的是LineRecordReader按行读数据组件
    this.split = split; // file:/D:/mr_file/20190130/wc_data/input/a.txt:0+66，就是一个或多个文件的路径与此切片需要处理的此文件偏移量
  }

  /**
   * Get the input split for this map.
   */
  public InputSplit getInputSplit() {
    return split;
  }

  @Override
  public KEYIN getCurrentKey() throws IOException, InterruptedException {
    return reader.getCurrentKey();
  }

  @Override
  public VALUEIN getCurrentValue() throws IOException, InterruptedException {
    return reader.getCurrentValue();
  }

  @Override
  public boolean nextKeyValue() throws IOException, InterruptedException {
    return reader.nextKeyValue();
  }

}
     