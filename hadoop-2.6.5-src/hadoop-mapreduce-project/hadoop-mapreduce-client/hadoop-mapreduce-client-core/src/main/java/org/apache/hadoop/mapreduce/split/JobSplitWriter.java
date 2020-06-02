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

package org.apache.hadoop.mapreduce.split;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.Arrays;
import java.util.List;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.permission.FsPermission;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.io.WritableUtils;
import org.apache.hadoop.io.serializer.SerializationFactory;
import org.apache.hadoop.io.serializer.Serializer;
import org.apache.hadoop.mapreduce.InputSplit;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.JobSubmissionFiles;
import org.apache.hadoop.mapreduce.MRConfig;
import org.apache.hadoop.mapreduce.split.JobSplit.SplitMetaInfo;
import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.classification.InterfaceStability;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * The class that is used by the Job clients to write splits (both the meta
 * and the raw bytes parts)
 */
@InterfaceAudience.Private
@InterfaceStability.Unstable
public class JobSplitWriter {

  private static final Log LOG = LogFactory.getLog(JobSplitWriter.class);
  private static final int splitVersion = JobSplit.META_SPLIT_VERSION;
  private static final byte[] SPLIT_FILE_HEADER;

  static {
    try {
      SPLIT_FILE_HEADER = "SPL".getBytes("UTF-8");
    } catch (UnsupportedEncodingException u) {
      throw new RuntimeException(u);
    }
  }
  
  @SuppressWarnings("unchecked")
  public static <T extends InputSplit> void createSplitFiles(Path jobSubmitDir, 
      Configuration conf, FileSystem fs, List<InputSplit> splits) 
  throws IOException, InterruptedException {
    T[] array = (T[]) splits.toArray(new InputSplit[splits.size()]);
    createSplitFiles(jobSubmitDir, conf, fs, array);
  }
  
  public static <T extends InputSplit> void createSplitFiles(Path jobSubmitDir, 
      Configuration conf, FileSystem fs, T[] splits) 
  throws IOException, InterruptedException {
    FSDataOutputStream out = createFile(fs, 
        JobSubmissionFiles.getJobSplitFile(jobSubmitDir), conf); // 创建job.split文件
    
    /**
     * SplitMetaInfo ：
     *  [data-size : 66
	 *	start-offset : 7
	 *	locations : localhost]
     */
    SplitMetaInfo[] info = writeNewSplits(conf, splits, out); // 创建切片的Meta信息并将split数据写入job.split文件
    out.close();
    writeJobSplitMetaInfo(fs,JobSubmissionFiles.getJobSplitMetaFile(jobSubmitDir), 
        new FsPermission(JobSubmissionFiles.JOB_FILE_PERMISSION), splitVersion,
        info); // 将split的Meta信息写入文件
  }
  
  public static void createSplitFiles(Path jobSubmitDir, 
      Configuration conf, FileSystem fs, 
      org.apache.hadoop.mapred.InputSplit[] splits) 
  throws IOException {
    FSDataOutputStream out = createFile(fs, 
        JobSubmissionFiles.getJobSplitFile(jobSubmitDir), conf);
    SplitMetaInfo[] info = writeOldSplits(splits, out, conf);
    out.close();
    writeJobSplitMetaInfo(fs,JobSubmissionFiles.getJobSplitMetaFile(jobSubmitDir), 
        new FsPermission(JobSubmissionFiles.JOB_FILE_PERMISSION), splitVersion,
        info);
  }
  
  private static FSDataOutputStream createFile(FileSystem fs, Path splitFile, 
      Configuration job)  throws IOException {
    FSDataOutputStream out = FileSystem.create(fs, splitFile, // 创建文件splitFile：./.staging/job_local722021009_0001/job.split
        new FsPermission(JobSubmissionFiles.JOB_FILE_PERMISSION)); // 文件权限
    int replication = job.getInt(Job.SUBMIT_REPLICATION, 10);
    fs.setReplication(splitFile, (short)replication); // 设置副本数
    writeSplitHeader(out);
    return out;
  }
  private static void writeSplitHeader(FSDataOutputStream out) 
  throws IOException {
    out.write(SPLIT_FILE_HEADER);
    out.writeInt(splitVersion);
  }
  
  @SuppressWarnings("unchecked")
  private static <T extends InputSplit> 
  SplitMetaInfo[] writeNewSplits(Configuration conf, 
      T[] array, FSDataOutputStream out)
  throws IOException, InterruptedException {

    SplitMetaInfo[] info = new SplitMetaInfo[array.length]; // 创建与切片数量相同的SplitMetaInfo数组
    if (array.length != 0) { // 如果切片的数量不为0
      SerializationFactory factory = new SerializationFactory(conf); // 序列化工场
      int i = 0;
      int maxBlockLocations = conf.getInt(MRConfig.MAX_BLOCK_LOCATIONS_KEY,
          MRConfig.MAX_BLOCK_LOCATIONS_DEFAULT); // 此切片的block地址最多是多少；也就是说，当前切片包含多少block，这个是最大数量，默认是10
      long offset = out.getPos(); // 获取数据流写入的当前位置
      for(T split: array) { // 迭代所有切片
        long prevCount = out.getPos(); // 获取数据流写入的当前最新位置
        Text.writeString(out, split.getClass().getName());
        Serializer<T> serializer = 
          factory.getSerializer((Class<T>) split.getClass());
        serializer.open(out);
        serializer.serialize(split); // 将切片信息序列化
        long currCount = out.getPos(); // 拿到数据流写入的位置
        String[] locations = split.getLocations(); // split的所在机器
        if (locations.length > maxBlockLocations) { // 如果当前切片的block数量大于了，设置的maxBlockLocations
          LOG.warn("Max block location exceeded for split: "
              + split + " splitsize: " + locations.length +
              " maxsize: " + maxBlockLocations);
          locations = Arrays.copyOf(locations, maxBlockLocations); // 拷贝，猜测是不让split的block数量大于10
        }
        /**
         *  [data-size : 66
		 *	start-offset : 7
		 *	locations : localhost]
         */
        info[i++] = 
          new JobSplit.SplitMetaInfo( 
              locations, offset,
              split.getLength()); // 创建SplitMetaInfo，并加入SplitMetaInfo数组
        offset += currCount - prevCount; // 更改数据流的写入位置
      }
    }
    return info; // 返回SplitMetaInfo数组
  }
  
  private static SplitMetaInfo[] writeOldSplits(
      org.apache.hadoop.mapred.InputSplit[] splits,
      FSDataOutputStream out, Configuration conf) throws IOException {
    SplitMetaInfo[] info = new SplitMetaInfo[splits.length];
    if (splits.length != 0) {
      int i = 0;
      long offset = out.getPos();
      int maxBlockLocations = conf.getInt(MRConfig.MAX_BLOCK_LOCATIONS_KEY,
          MRConfig.MAX_BLOCK_LOCATIONS_DEFAULT);
      for(org.apache.hadoop.mapred.InputSplit split: splits) {
        long prevLen = out.getPos();
        Text.writeString(out, split.getClass().getName());
        split.write(out);
        long currLen = out.getPos();
        String[] locations = split.getLocations();
        if (locations.length > maxBlockLocations) {
          LOG.warn("Max block location exceeded for split: "
              + split + " splitsize: " + locations.length +
              " maxsize: " + maxBlockLocations);
          locations = Arrays.copyOf(locations,maxBlockLocations);
        }
        info[i++] = new JobSplit.SplitMetaInfo( 
            locations, offset,
            split.getLength());
        offset += currLen - prevLen;
      }
    }
    return info;
  }

  private static void writeJobSplitMetaInfo(FileSystem fs, Path filename, 
      FsPermission p, int splitMetaInfoVersion, 
      JobSplit.SplitMetaInfo[] allSplitMetaInfo) 
  throws IOException {
    // write the splits meta-info to a file for the job tracker
    FSDataOutputStream out = 
      FileSystem.create(fs, filename, p); // 创建写文件流：./.staging/job_local1424033684_0001/job.splitmetainfo
    out.write(JobSplit.META_SPLIT_FILE_HEADER);
    WritableUtils.writeVInt(out, splitMetaInfoVersion);
    WritableUtils.writeVInt(out, allSplitMetaInfo.length);
    for (JobSplit.SplitMetaInfo splitMetaInfo : allSplitMetaInfo) { // 将splitMetaInfo一个一个定入job.splitmetainfo文件
      splitMetaInfo.write(out);
    }
    out.close(); // 关闭数据流
  }
}

