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
package bftsmart.statemanagement.durability;

import java.io.IOException;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.net.Socket;

import bftsmart.statemanagement.ApplicationState;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class StateSenderRunnable implements Runnable {

    private Logger logger = LoggerFactory.getLogger(this.getClass());
    
	private Socket socket;
	private ApplicationState state;
	
	public StateSenderRunnable(Socket socket, ApplicationState state) {
		this.socket = socket;
		this.state = state;
	}
	
	@Override
	public void run() {
		logger.debug("State transfer started (socket: {})", socket);
		try {
			long t0 = System.currentTimeMillis();
			OutputStream os = socket.getOutputStream();
			ObjectOutputStream oos = new ObjectOutputStream(os);
			oos.writeObject(state);
			oos.flush();
			os.flush();
			oos.close();
			os.close();
			System.out.println("network send time : " + (System.currentTimeMillis()-t0));
			if(state.getSerializedState() != null) {
				logger.debug("Replica state transfer successful (bytes: {}, socket: {})", state.getSerializedState().length, socket);
				logger.debug("Replica state transfer successful (bytes: {}, socket: {})", state.getSerializedState().length, socket);
			}
		} catch (Exception e) {
			e.printStackTrace();
			logger.error("Error during state transfer",e);
		} finally {
			try {
				socket.close();
			} catch (IOException e) {
				e.printStackTrace();
				logger.error("Error closing state transfer socket",e);
				socket = null;
			}
		}
	}

}
