/**
Copyright (c) 2007-2013 Alysson Bessani, Eduardo Alchieri, Paulo Sousa, and the authors indicated in the @author tags

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/
package bftsmart.demo.mycounter;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectInputStream;
import java.io.ObjectOutput;
import java.io.ObjectOutputStream;
import java.util.Arrays;

import bftsmart.tom.MessageContext;
import bftsmart.tom.ServiceReplica;
import bftsmart.tom.server.durability.DurabilityCoordinator;

/**
 * Example replica that implements a BFT replicated service (a counter).
 * If the increment > 0 the counter is incremented, otherwise, the counter
 * value is read.
 * 
 * @author alysson
 */

public final class MyCounterServer extends DurabilityCoordinator  {
    
    private long counter = 0;
    private int iterations = 0;
    
    public MyCounterServer(int id) {
    	new ServiceReplica(id, this, this);
    }
            
    @Override
    public byte[] appExecuteUnordered(byte[] command, MessageContext msgCtx) {         
        iterations++;
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(4);
            new DataOutputStream(out).writeLong(counter);
            return out.toByteArray();
        } catch (IOException ex) {
        	logger.error("Invalid request received!");
            return new byte[0];
        }
    }
/*  
    @Override
    public byte[] appExecuteOrdered(byte[] command, MessageContext msgCtx) {
        iterations++;
        try {
            int increment = new DataInputStream(new ByteArrayInputStream(command)).readInt();
            counter += increment;
            
            System.out.println("(" + iterations + ") Counter was incremented. Current value = " + counter);
            
            ByteArrayOutputStream out = new ByteArrayOutputStream(4);
            new DataOutputStream(out).writeInt(counter);
            return out.toByteArray();
        } catch (IOException ex) {
            System.err.println("Invalid request received!");
            return new byte[0];
        }
    }
*/
    public static void main(String[] args){
        if(args.length < 1) {
            System.out.println("Use: java CounterServer <processId>");
            System.exit(-1);
        }      
        new MyCounterServer(Integer.parseInt(args[0]));
    }

    
    @SuppressWarnings("unchecked")
    @Override
    public void installSnapshot(byte[] state) {
    	logger.info(Arrays.toString(state));
        try {
            ByteArrayInputStream bis = new ByteArrayInputStream(state);
            ObjectInput in = new ObjectInputStream(bis);
            counter = in.readLong();
            in.close();
            bis.close();
        } catch (IOException e) {
        	logger.error("Error deserializing state: "
                    + e);
            e.printStackTrace();
        }
    }

    @Override
    public byte[] getSnapshot() {
    	logger.info("");
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ObjectOutput out = new ObjectOutputStream(bos);
            out.writeLong(counter);
            out.flush();
            bos.flush();
            out.close();
            bos.close();
            return bos.toByteArray();
        } catch (IOException ioe) {
        	logger.error("Error serializing state: "
                    + ioe.getMessage());
            return "ERROR".getBytes();
        }
    }

	@Override
	public byte[][] appExecuteBatch(byte[][] commands, MessageContext[] msgCtxs) {
//		logger.info("Command length: " + commands.length);

		byte[][] ret = new byte[commands.length][];
		for(int i = 0 ;i < commands.length; i ++) {
			byte[] command = commands[i];
	        iterations++;
	        try {
	            long increment = new DataInputStream(new ByteArrayInputStream(command)).readLong();
	            counter += increment;
	            System.out.println("(" + iterations + ") Counter was incremented. Current value = " + counter);	            
	            ByteArrayOutputStream out = new ByteArrayOutputStream(4);
	            new DataOutputStream(out).writeLong(counter);
	            ret[i] = out.toByteArray();
	        } catch (IOException ex) {
	        	logger.error("Invalid request received!");
	            ret[i] = new byte[0];
	        }
		}
		return ret;
	}
}
