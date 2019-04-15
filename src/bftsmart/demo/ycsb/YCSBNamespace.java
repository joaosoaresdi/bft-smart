package bftsmart.demo.ycsb;

import java.io.Externalizable;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.util.Set;
import java.util.TreeMap;

public class YCSBNamespace extends TreeMap<String, YCSBTable> implements Externalizable {

	private static final long serialVersionUID = -180120948539141818L;

	@Override
	public void writeExternal(ObjectOutput out) throws IOException {
		try {
    	int written = this.getClass().getName().length();
    	Set<String> keys = this.keySet();
    	
    	out.writeInt(keys.size());
    	written += 4;
    	fillTo1k(written, out);
    	
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
		try {
    	int read = this.getClass().getName().length();
    	int keys = in.readInt();
    	read += 4;
    	readTo1k(read, in);
    	
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
