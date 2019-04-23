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

import java.io.Externalizable;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;

import bftsmart.reconfiguration.util.TOMConfiguration;

/**
 *
 * @author Marcel Santos
 *
 */
public class YCSBTable extends TreeMap<String, HashMap<String, byte[]>> implements Externalizable {
    private static final long serialVersionUID = 3786544460082473686L;

	@Override
	public void writeExternal(ObjectOutput out) throws IOException {
		try {
    	
    	int written = this.getClass().getName().length();
    	Set<String> keys = this.keySet();
    	out.writeInt(keys.size());
    	written += 4;
    	
    	fillToShardSize(written, out);
    	written = 0;
    	
    	for(String key : keys) {
    		byte[] key_bytes = key.getBytes();
    		out.writeInt(key_bytes.length);
    		written = 4;
    		out.write(key_bytes);
    		written +=key_bytes.length; 
    				
    		HashMap<String, byte[]> crt = get(key);
	    	List<String> sorted_attrs = Arrays.asList(crt.keySet().toArray(new String[0]));
	    	Collections.sort(sorted_attrs);

    		out.writeInt(sorted_attrs.size());
    		written += 4;

    		for(String attr : sorted_attrs) {
	    		byte[] attr_byte = attr.getBytes();
	    		out.writeInt(attr_byte.length);
	    		written +=4;
	    		out.write(attr_byte);
	    		written += attr_byte.length;
	    		byte[] val = crt.get(attr);
	    		out.writeInt(val.length);
	    		written += 4;
	    		out.write(val);
        		written += val.length;
        		
	    	}
	    	fillToShardSize(written, out);
	    	written = 0;
    	}
//    	out.flush();
//    	out.close();
		}catch(Exception e) {
			e.printStackTrace();
			throw e;
		}
	}
	int shardSize = TOMConfiguration.staticLoad().getShardSize();
	
	private void fillToShardSize(int c, ObjectOutput out) throws IOException {
		System.out.println("FILLING : " + (shardSize - c));
		
		for(int i = c; i < shardSize; i++)
			out.write('\0');
	}

	private void readToShardSize(int c, ObjectInput in) throws IOException {
		System.out.println("DISCARDING : " + (shardSize - c));
		for(int i = c; i < shardSize; i++) {
			int x = in.read();
		}
	}

	@Override
	public void readExternal(ObjectInput in) throws IOException, ClassNotFoundException {
		try {
    	int read = this.getClass().getName().length();
    	int keys = in.readInt();
    	read += 4;
    	
    	readToShardSize(read, in);
    	read = 0;
    	
		for(int i = 0; i < keys; i++) {
			int key_size = in.readInt();
			read = 4;
			byte[] key_byte = new byte[key_size];
			int r = 0;
			while (r < key_size) {
				r += in.read(key_byte, r, key_size-r);
			}
			read += r;

			String key = new String(key_byte);
    		HashMap<String, byte[]> value = new HashMap<>();
    		
    		int attr_len = in.readInt();
    		read += 4;
    		for(int j = 0;j < attr_len; j++) {
    			int attr_size = in.readInt();
        		read += 4;
        		
    			byte[] attr_byte = new byte[attr_size];
    			r = 0;
    			while (r < attr_size) {
    				r += in.read(attr_byte, r, attr_size-r);
    			}
				read += r;
    			
				int size = in.readInt();
				read += 4;
				byte[] val = new byte[size];
				
				r = 0;
				while (r < size) {
					r += in.read(val, r, (size-r));
				}
				read += r;
				value.put(new String(attr_byte), val);
			}
    		this.put(key, value);
    		
    		readToShardSize(read, in);
    		read = 0;
 		}
		}catch(Exception e) {
			e.printStackTrace();
			throw e;
		}
	}
}
