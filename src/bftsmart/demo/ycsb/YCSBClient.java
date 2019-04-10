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
    public ServiceProxy proxy = null;
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

    	System.out.println("UPDATE : table : " + table + "; key : " + key);
    	
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
    
    // added by JSoares
    public YCSBClient(int myId) {
    	this(new ServiceProxy(myId), myId);
    }

    public YCSBClient(ServiceProxy proxy, int myId) {
        System.out.println("Initializind client (" + this.myId + ")");
    	this.proxy = proxy;
    	this.myId = myId;
    }
    
	private static final int OP_COUNT = 15555; 

	public static void main(String[] args) {
		
		if(args.length < 2) {
			System.out.println("Usage: java YCSBClient <# of clients> <1st client ID> [# of KV operations]");
			System.exit(-1);
		}
		
		int clients = Integer.parseInt(args[0]);
		int client_ID = Integer.parseInt(args[1]);
		int opCount = OP_COUNT;
		if(args.length == 3) {
			opCount = Integer.parseInt(args[2]);
		}
		
		Thread[] ths = new Thread[clients];
		for(int i = 0; i < clients; i++) {
			ths[i] = new YCSBClientThread(client_ID + i, opCount);
			ths[i].start();
		}
		
		try {
			for(int i = 0; i < clients; i++) {
				ths[i].join();
			}
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
	}
}

class YCSBClientThread extends Thread {
	
	private static final String AB = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
	private static final String TABLE_NAME = "table0";
	private static final String ATTR_NAME = "field";
	
	private static final int ATTR_COUNT = 10;
	private static final int ATTR_LENGTH = 100;
	
	//static SecureRandom rnd = new SecureRandom("hello".getBytes());
	private Random rnd;

	private YCSBClient client;
	private int opCount;
	
	public YCSBClientThread(int clientId, int opCount){
		this.client = new YCSBClient(clientId);
		rnd = new Random(clientId);
		this.opCount = opCount;
	}
	
	private String randomString( int len ){
		   StringBuilder sb = new StringBuilder( len );
		   for( int i = 0; i < len; i++ ) 
		      sb.append( AB.charAt( rnd.nextInt(AB.length()) ) );
		   return sb.toString();
	}
		
	public void run() {
		for(int i = 0; i < opCount; i++) {
			System.out.println("#### STARTING : " + i);
			String key = "" + System.currentTimeMillis();
			HashMap<String, ByteIterator> value = new HashMap<>();
			for(int j = 0; j < ATTR_COUNT; j++) {
				String attr = ATTR_NAME+j;
				byte[] data = randomString(ATTR_LENGTH).getBytes();
				value.put(attr, new ByteArrayByteIterator(data));
			}
			client.update(TABLE_NAME, key, value);
		}
		System.out.println("## Stopping Client");
		client.proxy.close();
	}
}

