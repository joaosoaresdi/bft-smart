package bftsmart.demo.ycsb.test;

import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Random;

import org.nustaq.serialization.FSTConfiguration;

import bftsmart.demo.ycsb.YCSBTable;

public class YCSBTableTest {
	
	static FSTConfiguration conf = FSTConfiguration.createJsonConfiguration();


	private static final String ATTR_NAME = "field";
	
	private static final int RECORD_COUNT = 5000; 
	private static final int KEY_SIZE = 10;

	private static final int ATTR_COUNT = 10;
	private static final int ATTR_LENGTH = 100;
	
	
	static final String AB = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
	//static SecureRandom rnd = new SecureRandom("hello".getBytes());
	static Random rnd = new Random(0);

	static String randomString( int len ){
	   StringBuilder sb = new StringBuilder( len );
	   for( int i = 0; i < len; i++ ) 
	      sb.append( AB.charAt( rnd.nextInt(AB.length()) ) );
	   return sb.toString();
	}
	
	public static void main(String[] args) {
		
		YCSBTable table = new YCSBTable();
		
		for(int i = 0; i < RECORD_COUNT; i++) {
			String key = randomString(KEY_SIZE);
			HashMap<String, byte[]> value = new HashMap<>();
			for(int j = 0; j < ATTR_COUNT; j++) {
				String attr = ATTR_NAME+j;
				byte[] data = randomString(ATTR_LENGTH).getBytes();
				value.put(attr, data);
			}
			table.put(key, value);
		}
		
	    FSTConfiguration conf = FSTConfiguration.createDefaultConfiguration();
	    byte[] ser = conf.asByteArray(table);

	    try {
		    FileOutputStream fos = new FileOutputStream("YCSBTableTest_0.ser");
		    fos.write(ser);
		    fos.flush();
		    fos.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
	    
	    YCSBTable copy = (YCSBTable)conf.asObject(ser);
	    

	}

}
