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
import java.net.ServerSocket;
import java.net.Socket;

import bftsmart.statemanagement.ApplicationState;
import bftsmart.statemanagement.durability.shard.ShardedCSTRequest;
import bftsmart.tom.server.Recoverable;
import bftsmart.tom.server.durability.DurabilityCoordinator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class StateSenderServer implements Runnable {
	private Logger logger = LoggerFactory.getLogger(this.getClass());

	private ServerSocket server;
	private DurabilityCoordinator coordinator;
	private CSTRequest request;

	public StateSenderServer(int port, Recoverable recoverable, CSTRequest request) {
		this.coordinator = (DurabilityCoordinator) (recoverable);
		this.request = request;
		try {
			server = new ServerSocket(port);
		} catch (IOException e) {
			logger.error("Could not open server socket", e);
		}
	}

	@Override
	public void run() {
		while(true) {
			try {
				Socket socket = server.accept();
				logger.debug("Received connection for state transfer");
				ApplicationState state = coordinator.getState(request);
				StateSender sender = new StateSender(socket, state);
				new Thread(sender).start();
			} catch (Exception e) {
				e.printStackTrace();
				logger.error("Problem executing StateSenderServer thread", e);
			}
		}
	}

	public void updateServer(Recoverable recoverer, CSTRequest cstConfig) {
		this.coordinator = (DurabilityCoordinator) (recoverer);
		this.request = cstConfig;
	}

}
