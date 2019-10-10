/**
 * Copyright (c) 2007-2013 Alysson Bessani, Eduardo Alchieri, Paulo Sousa, and
 * the authors indicated in the @author tags
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package bftsmart.statemanagement;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import bftsmart.consensus.messages.ConsensusMessage;
import bftsmart.reconfiguration.ServerViewController;
import bftsmart.reconfiguration.views.View;
import bftsmart.statemanagement.standard.StandardSMMessage;
import bftsmart.tom.core.DeliveryThread;
import bftsmart.tom.core.ExecutionManager;
import bftsmart.tom.core.TOMLayer;
import bftsmart.tom.leaderchange.CertifiedDecision;
import bftsmart.tom.leaderchange.LCManager;
import bftsmart.tom.server.defaultservices.DefaultApplicationState;
import bftsmart.tom.util.TOMUtil;

/**
 *
 * @author Marcel Santos
 *
 */
public abstract class StateManager {

	private Logger logger = LoggerFactory.getLogger(this.getClass());

	protected TOMLayer tomLayer; //total order multicast
	protected ServerViewController SVController;
	protected DeliveryThread dt;
	protected ExecutionManager execManager;

	protected ConcurrentHashMap<Integer, ApplicationState> receivedStates = null; //received application state
	protected ConcurrentHashMap<Integer, View> receivedViews = null;
	protected ConcurrentHashMap<Integer, Integer> receivedRegencies = null;
	protected ConcurrentHashMap<Integer, Integer> receivedLeaders = null;
	protected ConcurrentHashMap<Integer, CertifiedDecision> receivedProofs = null;

	protected boolean appStateOnly;
	protected int waitingCID = -1;
	protected int queryID = -1;
	protected int lastCID;
	protected ApplicationState chkpntState;

	protected boolean isInitializing = true;
	protected Map<Integer, Map<Integer, Integer>> queries = new ConcurrentHashMap<>();

	public StateManager() {
		receivedStates = new ConcurrentHashMap<>();
		receivedViews = new ConcurrentHashMap<>();
		receivedRegencies = new ConcurrentHashMap<>();
		receivedLeaders = new ConcurrentHashMap<>();
		receivedProofs = new ConcurrentHashMap<>();
	}

	protected int getReplies() {
		return receivedStates.size();
	}

	protected boolean enoughReplies() {
		return receivedStates.size() > SVController.getCurrentViewF();
	}

	protected boolean enoughRegencies(int regency) {
		if(receivedRegencies.size() < SVController.getQuorum())
			return false;

		Collection<Integer> regencies = receivedRegencies.values();
		int counter = 0;
		for (int r : regencies) {
			if (regency == r) {
				counter++;
			}
		}
		return counter > SVController.getQuorum();
	}

	protected boolean enoughLeaders(int leader) {
		if(receivedLeaders.size() < SVController.getQuorum())
			return false;
		
		Collection<Integer> leaders = receivedLeaders.values();
		int counter = 0;
		for (int l : leaders) {
			if (leader == l) {
				counter++;
			}
		}
		return counter > SVController.getQuorum();
	}

	protected boolean enoughViews(View view) {
		if(receivedViews.size() < SVController.getQuorum())
			return false;
		
		Collection<View> views = receivedViews.values();
		int counter = 0;
		for (View v : views) {
			if (view.equals(v)) {
				counter++;
			}
		}
		return counter > SVController.getQuorum();
	}

	// check if the consensus messages are consistent without checking the
	// mac/signatures
	// if it is consistent, it returns the respective consensus ID; otherwise,
	// returns -1
	private int proofIsConsistent(Set<ConsensusMessage> proof) {

		int id = -1;
		byte[] value = null;

		for (ConsensusMessage cm : proof) {

			if (id == -1)
				id = cm.getNumber();
			if (value == null)
				value = cm.getValue();

			if (id != cm.getNumber() || !Arrays.equals(value, cm.getValue())) {
				return -1; // they are not consistent, so the proof is invalid
			}

		}

		// if the values are still these, this means the proof is empty, thus is invalid
		if (id == -1 || value == null)
			return -1;

		return id;
	}

	protected boolean enoughProofs(int cid, LCManager lc) {

		int counter = 0;
		for (CertifiedDecision cDec : receivedProofs.values()) {

			if (cDec != null && cid == proofIsConsistent(cDec.getConsMessages()) && lc.hasValidProof(cDec)) {
				counter++;
			}

		}
		boolean result = counter > SVController.getQuorum();
		return result;
	}

	/**
	 * Clear the collections and state hold by this object. Calls clear() in the
	 * States, Leaders, regencies and Views collections. Sets the state to null;
	 */
	protected void reset() {
		receivedStates.clear();
		receivedLeaders.clear();
		receivedRegencies.clear();
		receivedViews.clear();
		receivedProofs.clear();
		chkpntState = null;
	}

	public Collection<ApplicationState> receivedStates() {
		return receivedStates.values();
	}

	public void setLastCID(int cid) {
		lastCID = cid;
	}

	public int getLastCID() {
		return lastCID;
	}

	public void requestAppState(int cid) {
		lastCID = cid + 1;
		waitingCID = cid;
		logger.debug("Updated waitingcid to " + cid);
		appStateOnly = true;
		requestState();
	}

	public void analyzeState(int cid) {
		logger.info("The state transfer protocol is enabled");
		if (waitingCID == -1) {
			logger.info("I'm not waiting for any state, so I will keep record of this message");
			if (tomLayer.execManager.isDecidable(cid)) {
				logger.info("I have now more than " + SVController.getCurrentViewF() + " messages for CID " + cid
						+ " which are beyond CID " + lastCID);
				lastCID = cid;
				waitingCID = cid - 1;
				logger.info("I will be waiting for state messages associated to consensus " + waitingCID);
				requestState();
			}
		}
	}

	public boolean isRetrievingState() {
		if (isInitializing) {
			return true;
		}
		return waitingCID > -1;
	}

	public void askCurrentConsensusId() {
		logger.trace("");

		if (SVController.getCurrentViewN() == 1) {
			logger.info("Replica state is up to date");
			dt.deliverLock();
			isInitializing = false;
			tomLayer.setLastExec(-1);
			dt.canDeliver();
			dt.deliverUnlock();
			return;
		}

		int me = SVController.getStaticConf().getProcessId();
		int[] target = SVController.getCurrentViewOtherAcceptors();
		SMMessage currentCID;

		while (isInitializing) {
			logger.debug("Sending ConsensusID query with QueryID {} to replicas {}", queryID, target);
			queryID++;
			currentCID = new StandardSMMessage(me, queryID, TOMUtil.SM_ASK_INITIAL, 0, null, null, 0, 0);
			logger.info("Sending ConsensusID query {}", currentCID);
			tomLayer.getCommunication().send(target, currentCID);
			try {
				// TODO: shouldn't this be parameterised???? (value modified by JSoares)
				Thread.sleep(25000);
			} catch (InterruptedException e) {
				logger.error("Interruption during sleep", e);
			}
		}
	}

	public void currentConsensusIdAsked(int sender, int id) {
		logger.trace("");
		int me = SVController.getStaticConf().getProcessId();
		int lastConsensusId = tomLayer.getLastExec();
	    
		//public DefaultApplicationState(CommandsInfo[] messageBatches, int lastCheckpointCID, int lastCID, byte[] state, byte[] stateHash, int pid) {

		DefaultApplicationState state = new DefaultApplicationState(null, -1, lastConsensusId, null, null, -1);

		SMMessage currentCIDReply = new StandardSMMessage(me, id, TOMUtil.SM_REPLY_INITIAL, 0, state, null, 0, 0);

		logger.info("Received ConsensusID query from {} with QueryID {} and replied with \n {}", sender, id, state);
		tomLayer.getCommunication().send(new int[] { sender }, currentCIDReply);

	}

	public synchronized void currentConsensusIdReceived(SMMessage smsg) {
		logger.trace("");

		if (!isInitializing || waitingCID > -1 || queryID != smsg.getCID()) {
			logger.info("Ignored reply to ConsensusID (expecting ID {})", smsg.toString(), queryID);
			return;
		}
		logger.info("Received reply to ConsensusID (expecting ID {})", smsg.toString(), queryID);

		Map<Integer, Integer> replies = queries.get(queryID);

		if (replies == null) {

			replies = new HashMap<>();
			queries.put(queryID, replies);
		}

		replies.put(smsg.getSender(), smsg.getState().getLastCID());

		if (replies.size() > SVController.getQuorum()) {

			// logger.info("Received quorum of replies for query ID {}", queryID);

			HashMap<Integer, Integer> cids = new HashMap<>();
			for (int id : replies.keySet()) {

				int value = replies.get(id);

				Integer count = cids.get(value);
				if (count == null) {
					cids.put(value, 1);
				} else {
					cids.put(value, count + 1);
				}
			}
			for (int cid : cids.keySet()) {
				if (cids.get(cid) > SVController.getQuorum()) {

					queries.clear();

					if (cid == lastCID) {
						logger.info("Replica state is up to date (my CID {} : CID quorum {})", lastCID, cid);
						dt.deliverLock();
						isInitializing = false;
						tomLayer.setLastExec(cid);
						dt.canDeliver();
						dt.deliverUnlock();
						break;
					} else {
						// ask for state
						logger.info("Replica state is outdated (my CID is {} and a quorum exists for CID {}) (Requesting STATE)", lastCID, cid);
						lastCID = cid + 1;
						if (waitingCID == -1) {
							waitingCID = cid;
							requestState();
						}
					}
				}
			}
		}
	}

	public void init(TOMLayer tomLayer, DeliveryThread dt) {
		SVController = tomLayer.controller;

		this.tomLayer = tomLayer;
		this.dt = dt;
		this.execManager = tomLayer.execManager;

		chkpntState = null;
		lastCID = -1;
		waitingCID = -1;

		appStateOnly = false;
	}

	public void triggerTimeout(SMMessage msg) {
		logger.info("Triggering timeout for state request {}", msg);

		int[] myself = new int[1];
		myself[0] = SVController.getStaticConf().getProcessId();
		tomLayer.getCommunication().send(myself, msg);
	}

	/**
	 * Request the state to the other replicas. It should ask for the state defined
	 * in the 'waitingCID' variable.
	 */
	protected abstract void requestState();

	/**
	 * To use if the state manager needs to use timeout for liveness and when such
	 * timeout expires. To trigger the invocation, the method 'triggerTimeout'
	 * should be invoked by the class extending StateManager supplying an SMMessage
	 * of type 'TRIGGER_SM_LOCALLY'
	 */
	public abstract void stateTimeout();

	/**
	 * Invoked when a replica is asking to be sent the application state.
	 * 
	 * @param msg   The message sent by the replica, of type 'SM_REQUEST'.
	 * @param isBFT true if the library is set for BFT, false if CFT
	 */
	public abstract void SMRequestDeliver(SMMessage msg, boolean isBFT);

	/**
	 * Invoked when a replica receives a reply to its request to be sent the
	 * application state.
	 * 
	 * @param msg   The message sent by the replica, of type 'SM_REPLY'.
	 * @param isBFT true if the library is set for BFT, false if CFT
	 */
	public abstract void SMReplyDeliver(SMMessage msg, boolean isBFT);

}
