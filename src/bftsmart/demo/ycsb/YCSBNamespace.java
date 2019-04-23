package bftsmart.demo.ycsb;

import java.io.Externalizable;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.util.Set;
import java.util.TreeMap;

import bftsmart.reconfiguration.util.TOMConfiguration;

public class YCSBNamespace extends TreeMap<String, YCSBTable> implements Externalizable {

	private static final long serialVersionUID = -180120948539141818L;

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
	    		out.writeUTF(key);
	    		YCSBTable crt = get(key);
	    		out.writeObject(crt);
    	}
		} catch(Exception e) {
			e.printStackTrace();
			throw e;
		}
	}

	private int shardSize = TOMConfiguration.staticLoad().getShardSize();
	
	private void fillToShardSize(int c, ObjectOutput out) throws IOException {
		for(int i = c; i < shardSize; i++)
			out.write('\0');
	}

	private void readToShardSize(int c, ObjectInput in) throws IOException {
		for(int i = c; i < shardSize; i++)
			in.read();
	}

	@Override
	public void readExternal(ObjectInput in) throws IOException, ClassNotFoundException {
		try {
    	int read = this.getClass().getName().length();
    	int keys = in.readInt();
    	read += 4;
    	readToShardSize(read, in);
    	read = 0;
		while(keys > 0) {
			String key = in.readUTF();
			YCSBTable value = (YCSBTable) in.readObject();
    		this.put(key, value);
	    	keys --;
 		}
		}catch (Exception e) {
			e.printStackTrace();
			throw e;
		}
	}
}
