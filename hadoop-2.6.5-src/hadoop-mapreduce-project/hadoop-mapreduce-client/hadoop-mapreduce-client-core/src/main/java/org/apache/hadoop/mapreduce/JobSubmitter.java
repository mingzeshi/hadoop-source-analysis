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
package org.apache.hadoop.mapreduce;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.classification.InterfaceStability;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileContext;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.FileUtil;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.permission.FsPermission;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.QueueACL;

import static org.apache.hadoop.mapred.QueueManager.toFullPropertyName;

import org.apache.hadoop.mapreduce.filecache.ClientDistributedCacheManager;
import org.apache.hadoop.mapreduce.filecache.DistributedCache;
import org.apache.hadoop.mapreduce.protocol.ClientProtocol;
import org.apache.hadoop.mapreduce.security.TokenCache;
import org.apache.hadoop.mapreduce.split.JobSplitWriter;
import org.apache.hadoop.security.Credentials;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.security.authorize.AccessControlList;
import org.apache.hadoop.security.token.Token;
import org.apache.hadoop.security.token.TokenIdentifier;
import org.apache.hadoop.util.ReflectionUtils;
import org.apache.hadoop.yarn.api.records.ReservationId;
import org.codehaus.jackson.JsonParseException;
import org.codehaus.jackson.map.JsonMappingException;
import org.codehaus.jackson.map.ObjectMapper;

import com.google.common.base.Charsets;

@InterfaceAudience.Private
@InterfaceStability.Unstable
class JobSubmitter {
  protected static final Log LOG = LogFactory.getLog(JobSubmitter.class);
  private static final String SHUFFLE_KEYGEN_ALGORITHM = "HmacSHA1";
  private static final int SHUFFLE_KEY_LENGTH = 64;
  private FileSystem jtFs;
  private ClientProtocol submitClient;
  private String submitHostName;
  private String submitHostAddress;
  
  JobSubmitter(FileSystem submitFs, ClientProtocol submitClient) 
  throws IOException {
    this.submitClient = submitClient;
    this.jtFs = submitFs;
  }
  
  /**
   * configure the jobconf of the user with the command line options of 
   * -libjars, -files, -archives.
   * @param job
   * @throws IOException
   */
  /**
   * 拷贝配置文件、jar、其它文件等，到任务运行的临时工作目录：.staging/job_local118346634_0001
   */
  private void copyAndConfigureFiles(Job job, Path jobSubmitDir) 
  throws IOException {
    JobResourceUploader rUploader = new JobResourceUploader(jtFs); // 构建一个任务资源上传器
    rUploader.uploadFiles(job, jobSubmitDir);

    // Set the working directory
    // 设置工作目录
    if (job.getWorkingDirectory() == null) {
      job.setWorkingDirectory(jtFs.getWorkingDirectory());
    }
  }

  /**
   * Internal method for submitting jobs to the system.
   * 
   * <p>The job submission process involves:
   * <ol>
   *   <li>
   *   Checking the input and output specifications of the job.
   *   </li>
   *   <li>
   *   Computing the {@link InputSplit}s for the job.
   *   </li>
   *   <li>
   *   Setup the requisite accounting information for the 
   *   {@link DistributedCache} of the job, if necessary.
   *   </li>
   *   <li>
   *   Copying the job's jar and configuration to the map-reduce system
   *   directory on the distributed file-system. 
   *   </li>
   *   <li>
   *   Submitting the job to the <code>JobTracker</code> and optionally
   *   monitoring it's status.
   *   </li>
   * </ol></p>
   * @param job the configuration to submit
   * @param cluster the handle to the Cluster
   * @throws ClassNotFoundException
   * @throws InterruptedException
   * @throws IOException
   */
  /**
   * 提交任务的方法 
   */
  JobStatus submitJobInternal(Job job, Cluster cluster) 
  throws ClassNotFoundException, InterruptedException, IOException {

    //validate the jobs output specs 
    checkSpecs(job); // 检查job输出规范

    Configuration conf = job.getConfiguration(); // 此job的configuration
    addMRFrameworkToDistributedCache(conf); // 字面上的意思是，添加mr框架到文件系统的缓存

    Path jobStagingArea = JobSubmissionFiles.getStagingDir(cluster, conf); // 生成与创建任务运行临时文件夹
    //configure the command line options correctly on the submitting dfs
    InetAddress ip = InetAddress.getLocalHost();
    if (ip != null) { // 设置一些提交信息
      submitHostAddress = ip.getHostAddress();
      submitHostName = ip.getHostName();
      conf.set(MRJobConfig.JOB_SUBMITHOST,submitHostName);
      conf.set(MRJobConfig.JOB_SUBMITHOSTADDR,submitHostAddress);
    }
    JobID jobId = submitClient.getNewJobID(); // 任务的ID，如果是本地的任务：job_local118346634_0001；如果是集群任务job_1579598562033_45633，这里的submitClient，是LocalJobRunner或YarnJobRunner
    job.setJobID(jobId); // 设置jobid
    Path submitJobDir = new Path(jobStagingArea, jobId.toString()); // job存放临时文件的文件夹，就是jar、xml配置文件等，.staging/job_local118346634_0001
    JobStatus status = null;
    try { 
      // 一些设置信息
      conf.set(MRJobConfig.USER_NAME,
          UserGroupInformation.getCurrentUser().getShortUserName());
      conf.set("hadoop.http.filter.initializers", 
          "org.apache.hadoop.yarn.server.webproxy.amfilter.AmFilterInitializer");
      conf.set(MRJobConfig.MAPREDUCE_JOB_DIR, submitJobDir.toString()); 
      LOG.debug("Configuring job " + jobId + " with " + submitJobDir
          + " as the submit dir");
      // get delegation token for the dir
      TokenCache.obtainTokensForNamenodes(job.getCredentials(),
          new Path[] { submitJobDir }, conf);
      
      populateTokenCache(conf, job.getCredentials());

      // generate a secret to authenticate shuffle transfers
      // 这里应该是做一些验证的
      if (TokenCache.getShuffleSecretKey(job.getCredentials()) == null) {
        KeyGenerator keyGen;
        try {
         
          int keyLen = CryptoUtils.isShuffleEncrypted(conf) 
              ? conf.getInt(MRJobConfig.MR_ENCRYPTED_INTERMEDIATE_DATA_KEY_SIZE_BITS, 
                  MRJobConfig.DEFAULT_MR_ENCRYPTED_INTERMEDIATE_DATA_KEY_SIZE_BITS)
              : SHUFFLE_KEY_LENGTH;
          keyGen = KeyGenerator.getInstance(SHUFFLE_KEYGEN_ALGORITHM);
          keyGen.init(keyLen);
        } catch (NoSuchAlgorithmException e) {
          throw new IOException("Error generating shuffle secret key", e);
        }
        SecretKey shuffleKey = keyGen.generateKey();
        TokenCache.setShuffleSecretKey(shuffleKey.getEncoded(),
            job.getCredentials());
      }

      copyAndConfigureFiles(job, submitJobDir); // 拷贝所有的配置文件、jar等，到任务运行的临时文件夹

      Path submitJobFile = JobSubmissionFiles.getJobConfPath(submitJobDir); // 获取提交任务的文件，job.xml : ./.staging/job_local1074886335_0001/job.xml
      
      // Create the splits for the job
      LOG.debug("Creating splits at " + jtFs.makeQualified(submitJobDir)); // 检查文件
      
      /**
       * 这个地是重点，划分map任务切片
       * 	并将切片信息写入job.xml
       * 	并将SplitMetaInfo信息写入job.splitmetainfo
       * 	
       */
      int maps = writeSplits(job, submitJobDir); // 返回切片的数量
      conf.setInt(MRJobConfig.NUM_MAPS, maps); // 切片的数量也就是map-task的数量，并写入configuration中
      LOG.info("number of splits:" + maps); // 切片数量日志

      // write "queue admins of the queue to which job is being submitted"
      // to job file.
      String queue = conf.get(MRJobConfig.QUEUE_NAME,
          JobConf.DEFAULT_QUEUE_NAME); // 任务提交到哪个队列，默认default
      AccessControlList acl = submitClient.getQueueAdmins(queue);
      conf.set(toFullPropertyName(queue,
          QueueACL.ADMINISTER_JOBS.getAclName()), acl.getAclString());

      // removing jobtoken referrals before copying the jobconf to HDFS
      // as the tasks don't need this setting, actually they may break
      // because of it if present as the referral will point to a
      // different job.
      TokenCache.cleanUpTokenReferral(conf);

      if (conf.getBoolean(
          MRJobConfig.JOB_TOKEN_TRACKING_IDS_ENABLED,
          MRJobConfig.DEFAULT_JOB_TOKEN_TRACKING_IDS_ENABLED)) {
        // Add HDFS tracking ids
        ArrayList<String> trackingIds = new ArrayList<String>();
        for (Token<? extends TokenIdentifier> t :
            job.getCredentials().getAllTokens()) {
          trackingIds.add(t.decodeIdentifier().getTrackingId());
        }
        conf.setStrings(MRJobConfig.JOB_TOKEN_TRACKING_IDS,
            trackingIds.toArray(new String[trackingIds.size()]));
      }

      // Set reservation info if it exists
      ReservationId reservationId = job.getReservationId();
      if (reservationId != null) {
        conf.set(MRJobConfig.RESERVATION_ID, reservationId.toString());
      }

      // Write job file to submit dir
      writeConf(conf, submitJobFile); // 将所有的配置项写入./.staging/job_local1045495247_0001/job.xml，就是这些如下：包括自己配置的与众多xxxx-default.xml
      // <property><name>dfs.journalnode.rpc-address</name><value>0.0.0.0:8485</value><source>hdfs-default.xml</source></property>
      // <property><name>yarn.ipc.rpc.class</name><value>org.apache.hadoop.yarn.ipc.HadoopYarnProtoRPC</value><source>yarn-default.xml</source></property>

      
      //
      // Now, actually submit the job (using the submit name)
      //
      // 这里开始提交任务
      printTokens(jobId, job.getCredentials());
      /**
       * jobId : job_local1045495247_0001
       * submitJobDir.toString() : ./.staging/job_local1045495247_0001
       * job.getCredentials() : 类似验证的东西
       */
      status = submitClient.submitJob( // 这里如果是本地运行就是LocalJobRunner，如果提交到集群运行，就是YARNRunner
          jobId, submitJobDir.toString(), job.getCredentials()); // 提交任务，并返回状态
      if (status != null) { // 返回状态
        return status;
      } else {
        throw new IOException("Could not launch job");
      }
    } finally {
      if (status == null) { // 清除目录
        LOG.info("Cleaning up the staging area " + submitJobDir);
        if (jtFs != null && submitJobDir != null)
          jtFs.delete(submitJobDir, true);

      }
    }
  }
  
  private void checkSpecs(Job job) throws ClassNotFoundException, 
      InterruptedException, IOException {
	/**
	 * Configuration: 
	 * core-default.xml, core-site.xml, mapred-default.xml, mapred-site.xml, 
	 * yarn-default.xml, yarn-site.xml, hdfs-default.xml, hdfs-site.xml 
	 */
    JobConf jConf = (JobConf)job.getConfiguration();
    // Check the output specification
    if (jConf.getNumReduceTasks() == 0 ? // 如果reduce task的数量是0，就判断mapper、否则判断reducer
        jConf.getUseNewMapper() : jConf.getUseNewReducer()) {
      org.apache.hadoop.mapreduce.OutputFormat<?, ?> output = // 通过反射的方式创建output
        ReflectionUtils.newInstance(job.getOutputFormatClass(), // 通过我们传入的outputformat：job.getOutputFormatClass()，来创建OutputFormat实现子类，比如：org.apache.hadoop.mapreduce.lib.output.TextOutputFormat
          job.getConfiguration());
      output.checkOutputSpecs(job); // 如果是：TextOutputFormat，那就调用TextOutputFormat的checkOutputSpecs(job)方法；但TextOutputFormat没有checkOutputSpecs(job)方法，在父类FileOutputFormat中
    } else {
      jConf.getOutputFormat().checkOutputSpecs(jtFs, jConf);
    }
  }
  
  private void writeConf(Configuration conf, Path jobFile) 
      throws IOException {
    // Write job file to JobTracker's fs        
    FSDataOutputStream out = 
      FileSystem.create(jtFs, jobFile, 
                        new FsPermission(JobSubmissionFiles.JOB_FILE_PERMISSION));
    try {
      conf.writeXml(out);
    } finally {
      out.close();
    }
  }
  
  private void printTokens(JobID jobId,
      Credentials credentials) throws IOException {
    LOG.info("Submitting tokens for job: " + jobId);
    for (Token<?> token: credentials.getAllTokens()) {
      LOG.info(token);
    }
  }

  @SuppressWarnings("unchecked")
  private <T extends InputSplit>
  int writeNewSplits(JobContext job, Path jobSubmitDir) throws IOException,
      InterruptedException, ClassNotFoundException {
    Configuration conf = job.getConfiguration(); // 拿到全局配置
    
    /**
     * 获取InputFormatClass，这里是我们在代码中自己配置的
     * 如果要处理文本就是：TextInputFormat
     * 如果是序列化的文件就是：SequenceFileInputFormat
     * 如果是DB数据库就是：DBIntputFormat或DataDrivenDBInputFormat
     * 
     * 也可以自定义InputFormat处理自己特定的数据
     */
    InputFormat<?, ?> input =
      ReflectionUtils.newInstance(job.getInputFormatClass(), conf);

    List<InputSplit> splits = input.getSplits(job); // 重点：规划切片，这个要找InputFormat的子类；例如TextInputFormat的getSplits方法就在FileInputFormat中
    T[] array = (T[]) splits.toArray(new InputSplit[splits.size()]); // 转换数组

    // sort the splits into order based on size, so that the biggest
    // go first
    Arrays.sort(array, new SplitComparator()); // 对数据切片集合按照大小排序，以便大的先提交计算
    JobSplitWriter.createSplitFiles(jobSubmitDir, conf, 
        jobSubmitDir.getFileSystem(conf), array); // 将split信息写入文件
    return array.length;
  }
  
  private int writeSplits(org.apache.hadoop.mapreduce.JobContext job,
      Path jobSubmitDir) throws IOException,
      InterruptedException, ClassNotFoundException {
    JobConf jConf = (JobConf)job.getConfiguration();
    int maps; // map任务的数量
    if (jConf.getUseNewMapper()) { // 使用新旧API，2.x应该都是新的API
      maps = writeNewSplits(job, jobSubmitDir); // 规划map任务切片，一个任务切片就是一个map-task，返回map-task数量
    } else {
      maps = writeOldSplits(jConf, jobSubmitDir);
    }
    return maps; // 返回map-task数量
  }
  
  //method to write splits for old api mapper.
  private int writeOldSplits(JobConf job, Path jobSubmitDir) 
  throws IOException {
    org.apache.hadoop.mapred.InputSplit[] splits =
    job.getInputFormat().getSplits(job, job.getNumMapTasks());
    // sort the splits into order based on size, so that the biggest
    // go first
    Arrays.sort(splits, new Comparator<org.apache.hadoop.mapred.InputSplit>() {
      public int compare(org.apache.hadoop.mapred.InputSplit a,
                         org.apache.hadoop.mapred.InputSplit b) {
        try {
          long left = a.getLength();
          long right = b.getLength();
          if (left == right) {
            return 0;
          } else if (left < right) {
            return 1;
          } else {
            return -1;
          }
        } catch (IOException ie) {
          throw new RuntimeException("Problem getting input split size", ie);
        }
      }
    });
    JobSplitWriter.createSplitFiles(jobSubmitDir, job, 
        jobSubmitDir.getFileSystem(job), splits);
    return splits.length;
  }
  
  private static class SplitComparator implements Comparator<InputSplit> {
    @Override
    public int compare(InputSplit o1, InputSplit o2) {
      try {
        long len1 = o1.getLength();
        long len2 = o2.getLength();
        if (len1 < len2) {
          return 1;
        } else if (len1 == len2) {
          return 0;
        } else {
          return -1;
        }
      } catch (IOException ie) {
        throw new RuntimeException("exception in compare", ie);
      } catch (InterruptedException ie) {
        throw new RuntimeException("exception in compare", ie);
      }
    }
  }
  
  @SuppressWarnings("unchecked")
  private void readTokensFromFiles(Configuration conf, Credentials credentials)
  throws IOException {
    // add tokens and secrets coming from a token storage file
    String binaryTokenFilename =
      conf.get("mapreduce.job.credentials.binary");
    if (binaryTokenFilename != null) {
      Credentials binary = Credentials.readTokenStorageFile(
          FileSystem.getLocal(conf).makeQualified(
              new Path(binaryTokenFilename)),
          conf);
      credentials.addAll(binary);
    }
    // add secret keys coming from a json file
    String tokensFileName = conf.get("mapreduce.job.credentials.json");
    if(tokensFileName != null) {
      LOG.info("loading user's secret keys from " + tokensFileName);
      String localFileName = new Path(tokensFileName).toUri().getPath();

      boolean json_error = false;
      try {
        // read JSON
        ObjectMapper mapper = new ObjectMapper();
        Map<String, String> nm = 
          mapper.readValue(new File(localFileName), Map.class);

        for(Map.Entry<String, String> ent: nm.entrySet()) {
          credentials.addSecretKey(new Text(ent.getKey()), ent.getValue()
              .getBytes(Charsets.UTF_8));
        }
      } catch (JsonMappingException e) {
        json_error = true;
      } catch (JsonParseException e) {
        json_error = true;
      }
      if(json_error)
        LOG.warn("couldn't parse Token Cache JSON file with user secret keys");
    }
  }

  //get secret keys and tokens and store them into TokenCache
  private void populateTokenCache(Configuration conf, Credentials credentials) 
  throws IOException{
    readTokensFromFiles(conf, credentials);
    // add the delegation tokens from configuration
    String [] nameNodes = conf.getStrings(MRJobConfig.JOB_NAMENODES); // 拿到所有的namenode，应该是：hdfs://service1:8020，本地的是file:///
    LOG.debug("adding the following namenodes' delegation tokens:" + 
        Arrays.toString(nameNodes));
    if(nameNodes != null) {
      Path [] ps = new Path[nameNodes.length];
      for(int i=0; i< nameNodes.length; i++) {
        ps[i] = new Path(nameNodes[i]);
      }
      TokenCache.obtainTokensForNamenodes(credentials, ps, conf);
    }
  }

  @SuppressWarnings("deprecation")
  private static void addMRFrameworkToDistributedCache(Configuration conf)
      throws IOException {
    String framework =
        conf.get(MRJobConfig.MAPREDUCE_APPLICATION_FRAMEWORK_PATH, "");
    if (!framework.isEmpty()) {
      URI uri;
      try {
        uri = new URI(framework);
      } catch (URISyntaxException e) {
        throw new IllegalArgumentException("Unable to parse '" + framework
            + "' as a URI, check the setting for "
            + MRJobConfig.MAPREDUCE_APPLICATION_FRAMEWORK_PATH, e);
      }

      String linkedName = uri.getFragment();

      // resolve any symlinks in the URI path so using a "current" symlink
      // to point to a specific version shows the specific version
      // in the distributed cache configuration
      FileSystem fs = FileSystem.get(conf);
      Path frameworkPath = fs.makeQualified(
          new Path(uri.getScheme(), uri.getAuthority(), uri.getPath()));
      FileContext fc = FileContext.getFileContext(frameworkPath.toUri(), conf);
      frameworkPath = fc.resolvePath(frameworkPath);
      uri = frameworkPath.toUri();
      try {
        uri = new URI(uri.getScheme(), uri.getAuthority(), uri.getPath(),
            null, linkedName);
      } catch (URISyntaxException e) {
        throw new IllegalArgumentException(e);
      }

      DistributedCache.addCacheArchive(uri, conf);
    }
  }
}