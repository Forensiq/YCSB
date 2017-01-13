/**
 * Copyright (c) 2015 YCSB contributors. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you
 * may not use this file except in compliance with the License. You
 * may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License. See accompanying
 * LICENSE file.
 */

package com.yahoo.ycsb.db;

import com.aerospike.client.AerospikeException;
import com.aerospike.client.Bin;
import com.aerospike.client.Key;
import com.aerospike.client.Record;
import com.aerospike.client.policy.ClientPolicy;
import com.aerospike.client.policy.Policy;
import com.aerospike.client.policy.RecordExistsAction;
import com.aerospike.client.policy.WritePolicy;
import com.yahoo.ycsb.ByteArrayByteIterator;
import com.yahoo.ycsb.ByteIterator;
import com.yahoo.ycsb.DBException;
import com.yahoo.ycsb.Status;

import java.util.*;

/**
 * YCSB binding for <a href="http://www.aerospike.com/">Areospike</a>.
 */
public class AerospikeIpClient extends com.yahoo.ycsb.DB {
  private static final String DEFAULT_HOST = "localhost";
  private static final String DEFAULT_PORT = "3000";
  private static final String DEFAULT_TIMEOUT = "10000";
  private static final String DEFAULT_NAMESPACE = "ycsb";

  private String namespace = null;

  private com.aerospike.client.AerospikeClient client = null;

  private Policy readPolicy = new Policy();
  private WritePolicy insertPolicy = new WritePolicy();
  private WritePolicy updatePolicy = new WritePolicy();
  private WritePolicy deletePolicy = new WritePolicy();

  @Override
  public void init() throws DBException {
    insertPolicy.recordExistsAction = RecordExistsAction.CREATE_ONLY;
    updatePolicy.recordExistsAction = RecordExistsAction.UPDATE_ONLY;

    Properties props = getProperties();

    namespace = props.getProperty("as.namespace", DEFAULT_NAMESPACE);

    String host = props.getProperty("as.host", DEFAULT_HOST);
    String user = props.getProperty("as.user");
    String password = props.getProperty("as.password");
    int port = Integer.parseInt(props.getProperty("as.port", DEFAULT_PORT));
    int timeout = Integer.parseInt(props.getProperty("as.timeout",
        DEFAULT_TIMEOUT));

    readPolicy.timeout = timeout;
    insertPolicy.timeout = timeout;
    updatePolicy.timeout = timeout;
    deletePolicy.timeout = timeout;

    ClientPolicy clientPolicy = new ClientPolicy();

    if (user != null && password != null) {
      clientPolicy.user = user;
      clientPolicy.password = password;
    }

    try {
      client =
          new com.aerospike.client.AerospikeClient(clientPolicy, host, port);
    } catch (AerospikeException e) {
      throw new DBException(String.format("Error while creating Aerospike " +
          "client for %s:%d.", host, port), e);
    }
  }

  @Override
  public void cleanup() throws DBException {
    client.close();
  }

  @Override
  public Status read(String table, String key, Set<String> fields,
      HashMap<String, ByteIterator> result) {
    
    // remove string components from key (see buildKeyName() in CoreWorkload) and convert to byte array    
    String keyValue = key.substring(4);
    long keyLong = Long.valueOf(keyValue);
    // int to byte array conversion http://stackoverflow.com/a/2183259
    // we're only interested in the last for bytes of the long (i.e. the signed int value)
    byte[] ipBytes = new byte[] {(byte)(keyLong >>> 24), (byte)(keyLong >>> 16), (byte)(keyLong >>> 8), (byte)keyLong};
        
    try {
      Record record;

      if (fields != null) {
        record = client.get(readPolicy, new Key(namespace, table, ipBytes),
            fields.toArray(new String[fields.size()]));
      } else {
        record = client.get(readPolicy, new Key(namespace, table, ipBytes));
      }

      if (record == null) {
        // expecting 90% of key's not to be found
//        System.err.println("Record key " + keyLong + " not found (read)");
        return Status.ERROR;
      }

      for (Map.Entry<String, Object> entry: record.bins.entrySet()) {
        result.put(entry.getKey(),
            new ByteArrayByteIterator((byte[])entry.getValue()));
      }

      return Status.OK;
    } catch (AerospikeException e) {
      System.err.println("Error while reading key " + keyLong + ": " + e);
      return Status.ERROR;
    }
  }

  @Override
  public Status scan(String table, String start, int count, Set<String> fields,
      Vector<HashMap<String, ByteIterator>> result) {
    System.err.println("Scan not implemented");
    return Status.ERROR;
  }

  private Status write(String table, String key, WritePolicy writePolicy,
      HashMap<String, ByteIterator> values) {
    Bin[] bins = new Bin[values.size()];
    int index = 0;

    for (Map.Entry<String, ByteIterator> entry: values.entrySet()) {
      bins[index] = new Bin(entry.getKey(), entry.getValue().toArray());
      ++index;
    }

    Key keyObj = new Key(namespace, table, key);

    try {
      client.put(writePolicy, keyObj, bins);
      return Status.OK;
    } catch (AerospikeException e) {
      System.err.println("Error while writing key " + key + ": " + e);
      return Status.ERROR;
    }
  }

  @Override
  public Status update(String table, String key,
      HashMap<String, ByteIterator> values) {
    return write(table, key, updatePolicy, values);
  }

  @Override
  public Status insert(String table, String key,
      HashMap<String, ByteIterator> values) {
    return write(table, key, insertPolicy, values);
  }

  @Override
  public Status delete(String table, String key) {
    try {
      if (!client.delete(deletePolicy, new Key(namespace, table, key))) {
        System.err.println("Record key " + key + " not found (delete)");
        return Status.ERROR;
      }

      return Status.OK;
    } catch (AerospikeException e) {
      System.err.println("Error while deleting key " + key + ": " + e);
      return Status.ERROR;
    }
  }
}
