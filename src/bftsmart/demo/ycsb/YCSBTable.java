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

/**
 *
 * @author Marcel Santos
 *
 */
public class YCSBTable extends TreeMap<String, HashMap<String, byte[]>> implements Externalizable {
    private static final long serialVersionUID = 3786544460082473686L;

	@Override
	public void writeExternal(ObjectOutput out) throws IOException {
    	System.out.println("############################################");
    	System.out.println("############## write YCSBTable #############");
    	System.out.println("############################################");

    	System.out.println(this.getClass().getName());
    	System.out.println(this.getClass().getTypeName());
    	System.out.println(this.getClass().getCanonicalName());
    	
    	int written = this.getClass().getName().length();
    	Set<String> keys = this.keySet();
    	out.writeInt(keys.size());
    	written += 4;
    	fillTo1k(written, out);
    	    	
    	for(String key : keys) {
    		written = 0;
        	try {
        		written += key.length();
	    		out.writeUTF(key);

	    		HashMap<String, byte[]> crt = get(key);
		    	List<String> sorted_attrs = Arrays.asList(crt.keySet().toArray(new String[0]));
		    	Collections.sort(sorted_attrs);

        		written += 4;
        		out.writeInt(sorted_attrs.size());
		    	for(String attr : sorted_attrs) {
	        		written += attr.length();
		    		out.writeUTF(attr);
		    		byte[] val = crt.get(attr);
	        		
		    		written += 4;
	        		written += val.length;
	        		
		    		out.writeInt(val.length);
		    		out.write(val);
		    	}
		    	fillTo1k(written, out);
        	} catch (Exception e) {
        		e.printStackTrace();
            	break;
        	}
    	}
	}
	
	private void fillTo1k(int c, ObjectOutput out) throws IOException {
		for(int i = c; i < 1024; i++)
			out.write('\0');
	}

	private void readTo1k(int c, ObjectInput in) throws IOException {
		for(int i = c; i < 1024; i++)
			in.read();
	}

	@Override
	public void readExternal(ObjectInput in) throws IOException, ClassNotFoundException {
    	System.out.println("############################################");
    	System.out.println("############### read YCSBTable #############");
    	System.out.println("############################################");
    	
    	int read = this.getClass().getName().length();
    	int keys = in.readInt();
    	read += 4;
    	readTo1k(read, in);

    	System.out.println("Reading " + keys);
		while(keys > 0) {
			read = 0;
	    	try {
    			String key = in.readUTF();
    			read += key.length();
    			HashMap<String, byte[]> value = new HashMap<>();
    			int attr_len = in.readInt();
    			read += 4;
    			for(int i = 0;i < attr_len; i++) {
    				String attr = in.readUTF();
    				read += attr.length();
    				int size = in.readInt();
    				read += 4;
    				byte[] val = new byte[size];
    				int r = 0;
    				while (r < size) {
    					r += in.read(val, r, (size-r));
    				}
    				read += r;
    				value.put(attr, val);
    			}
        		this.put(key, value);
//        		System.out.println("Read : " + read);
        		readTo1k(read, in);
	    	} catch (Exception e) {
	    		e.printStackTrace();
	    		break;
	    	}
	    	keys --;
 		}
	}
}
