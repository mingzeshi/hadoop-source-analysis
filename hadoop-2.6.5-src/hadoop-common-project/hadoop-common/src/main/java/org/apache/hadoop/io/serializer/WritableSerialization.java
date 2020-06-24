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

package org.apache.hadoop.io.serializer;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.classification.InterfaceStability;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.util.ReflectionUtils;

/**
 * A {@link Serialization} for {@link Writable}s that delegates to
 * {@link Writable#write(java.io.DataOutput)} and
 * {@link Writable#readFields(java.io.DataInput)}.
 */
@InterfaceAudience.Public
@InterfaceStability.Evolving
public class WritableSerialization extends Configured
	implements Serialization<Writable> {
  static class WritableDeserializer extends Configured
  	implements Deserializer<Writable> {

    private Class<?> writableClass;
    private DataInputStream dataIn;
    
    public WritableDeserializer(Configuration conf, Class<?> c) {
      setConf(conf);
      this.writableClass = c;
    }
    
    @Override
    public void open(InputStream in) {
      if (in instanceof DataInputStream) {
        dataIn = (DataInputStream) in;
      } else {
        dataIn = new DataInputStream(in);
      }
    }
    
    @Override
    public Writable deserialize(Writable w) throws IOException {
      Writable writable;
      if (w == null) {
        writable 
          = (Writable) ReflectionUtils.newInstance(writableClass, getConf());
      } else {
        writable = w;
      }
      writable.readFields(dataIn);
      return writable;
    }

    @Override
    public void close() throws IOException {
      dataIn.close();
    }
    
  }
  
  static class WritableSerializer extends Configured implements
  	Serializer<Writable> {
    
    private DataOutputStream dataOut;
    
    @Override
    public void open(OutputStream out) {
      if (out instanceof DataOutputStream) {
        dataOut = (DataOutputStream) out;
      } else {
        dataOut = new DataOutputStream(out);
      }
    }

    /**
     * Writable w: 这个 writable是我们在传输key、value时要实现的接口 -> public interface WritableComparable<T> extends Writable, Comparable<T>
     */
    @Override
    public void serialize(Writable w) throws IOException {
      /**
       * 调用了，org.apache.hadoop.io.Writable 的 write() 方法，这是个接口，具体实现在我们的包装对象中，比如String的包装对象是Text
       * Text在实现write()方法的时候，会传输一个DataOutputStream dataOut；write()方法实现中会写dataOut.write(...)将数据写出去
       * 
       * 在此传入的dataOut，通过以上的 public void open(OutputStream out) 方法来实现的，这个open方法在哪里调用过？
       * MapoutputBuffer的init()方法调用过 -> keySerializer.open(bb)，传入的DataoutputStream，是 protected class BlockingBuffer extends DataOutputStream
       * 
       * 实质是调用BlockingBuffer的write方法，但BlockingBuffer是包装了Buffer：
       * 	public BlockingBuffer() {
       * 		super(new Buffer());
       * 	}
       * 实质上是调用的Buffer的write()方法，向缓冲区写数据都是通过这个方法来写的
       */
      w.write(dataOut);
    }

    @Override
    public void close() throws IOException {
      dataOut.close();
    }

  }

  @InterfaceAudience.Private
  @Override
  public boolean accept(Class<?> c) {
    return Writable.class.isAssignableFrom(c);
  }

  @InterfaceAudience.Private
  @Override
  public Serializer<Writable> getSerializer(Class<Writable> c) {
    return new WritableSerializer();
  }
  
  @InterfaceAudience.Private
  @Override
  public Deserializer<Writable> getDeserializer(Class<Writable> c) {
    return new WritableDeserializer(getConf(), c);
  }

}
