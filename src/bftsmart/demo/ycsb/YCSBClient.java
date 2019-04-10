/**
 * Copyright (c) 2007-2013 Alysson Bessani, Eduardo Alchieri, Paulo Sousa, and the authors indicated in the @author tags
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package bftsmart.demo.ycsb;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Properties;
import java.util.Random;
import java.util.Set;
import java.util.Vector;
import java.util.concurrent.atomic.AtomicInteger;

import com.yahoo.ycsb.ByteArrayByteIterator;
import com.yahoo.ycsb.ByteIterator;
import com.yahoo.ycsb.DB;

import bftsmart.tom.ServiceProxy;

/**
 *
 * @author Marcel Santos
 *
 */
public class YCSBClient extends DB {

    private static AtomicInteger counter = new AtomicInteger();
    private ServiceProxy proxy = null;
    private int myId;

    public YCSBClient() {
    }

    @Override
    public void init() {
        Properties props = getProperties();
        int initId = Integer.valueOf((String) props.get("smart-initkey"));
        myId = initId + counter.addAndGet(1);
        proxy = new ServiceProxy(myId);
        System.out.println("YCSBKVClient. Initiated client id: " + myId);
    }

    @Override
    public int delete(String arg0, String arg1) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int insert(String table, String key,
            HashMap<String, ByteIterator> values) {
    	System.out.println("INSERT : " + values.size());

        Iterator<String> keys = values.keySet().iterator();
        HashMap<String, byte[]> map = new HashMap<>();
        while (keys.hasNext()) {
            String field = keys.next();
            map.put(field, values.get(field).toArray());
        }
        YCSBMessage msg = YCSBMessage.newInsertRequest(table, key, map);
        byte[] reply = proxy.invokeOrdered(msg.getBytes());
        YCSBMessage replyMsg = YCSBMessage.getObject(reply);
        return replyMsg.getResult();
    }

    @Override
    public int read(String table, String key,
            Set<String> fields, HashMap<String, ByteIterator> result) {
        HashMap<String, byte[]> results = new HashMap<>();
        YCSBMessage request = YCSBMessage.newReadRequest(table, key, fields, results);
        byte[] reply = proxy.invokeUnordered(request.getBytes());
        YCSBMessage replyMsg = YCSBMessage.getObject(reply);
        return replyMsg.getResult();
    }

    @Override
    public int scan(String arg0, String arg1, int arg2, Set<String> arg3,
            Vector<HashMap<String, ByteIterator>> arg4) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int update(String table, String key,
            HashMap<String, ByteIterator> values) {

    	System.out.println("UPDATE : table : " + table + "; key : " + key + "\n attribute.length : " + values.keySet().toArray(new String[0]).length +  "\n attribute.value.length: "+ values.values().toArray(new ByteIterator[0])[0].toString().length());
    	
        Iterator<String> keys = values.keySet().iterator();
        HashMap<String, byte[]> map = new HashMap<>();
        while (keys.hasNext()) {
            String field = keys.next();
            map.put(field, values.get(field).toArray());
        }
        YCSBMessage msg = YCSBMessage.newUpdateRequest(table, key, map);
        byte[] reply = proxy.invokeOrdered(msg.getBytes());
        YCSBMessage replyMsg = YCSBMessage.getObject(reply);
        return replyMsg.getResult();
    }
    
    
    
	private static final String ATTR_NAME = "field";
	
	private static final int RECORD_COUNT = 15222; 
	private static final int KEY_SIZE = 10;

	private static final int ATTR_COUNT = 10;
	private static final int ATTR_LENGTH = 100;
	
	
	static final String AB = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
	//static SecureRandom rnd = new SecureRandom("hello".getBytes());
	static Random rnd = new Random(15);

	static String randomString( int len ){
	   StringBuilder sb = new StringBuilder( len );
	   for( int i = 0; i < len; i++ ) 
	      sb.append( AB.charAt( rnd.nextInt(AB.length()) ) );
	   return sb.toString();
	}
	
	public static void main(String[] args) {
		String table = "table0";
		
		YCSBClient client = new YCSBClient();
		
        client.myId = 1111;
        client.proxy = new ServiceProxy(client.myId);
        System.out.println("YCSBKVClient. Initiated client id: " + client.myId);

		
		for(int i = 0; i < RECORD_COUNT; i++) {
			System.out.println("###### STARTING : " + i);
			//String key = randomString(KEY_SIZE);
			String key = "" + System.currentTimeMillis();
			HashMap<String, ByteIterator> value = new HashMap<>();
			for(int j = 0; j < ATTR_COUNT; j++) {
				String attr = ATTR_NAME+j;
				byte[] data = randomString(ATTR_LENGTH).getBytes();
				value.put(attr, new ByteArrayByteIterator(data));
			}
			client.update(table, key, value);
			System.out.println("###### ENDED : " + i);
		}

	}

}
