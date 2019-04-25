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
package bftsmart.statemanagement.durability;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Queue;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import bftsmart.consensus.Consensus;
import bftsmart.consensus.Epoch;
import bftsmart.consensus.messages.ConsensusMessage;
import bftsmart.consensus.messages.MessageFactory;
import bftsmart.reconfiguration.views.View;
import bftsmart.statemanagement.ApplicationState;
import bftsmart.statemanagement.SMMessage;
import bftsmart.statemanagement.StateManager;
import bftsmart.tom.leaderchange.CertifiedDecision;
import bftsmart.tom.server.defaultservices.CommandsInfo;
import bftsmart.tom.server.durability.DurabilityCoordinator;
import bftsmart.tom.util.TOMUtil;

public class DurableStateManager extends StateManager {

	protected Logger logger = LoggerFactory.getLogger(this.getClass());

	protected ReentrantLock lockTimer = new ReentrantLock();
	protected Timer stateTimer = null;
	protected final static long INIT_TIMEOUT = 120000;
	protected long timeout = INIT_TIMEOUT;

	private CSTRequestF1 cstConfig;
	
	static final protected AtomicReference<CSTState> stateLower = new AtomicReference<>(null);	
	static final protected AtomicReference<CSTState> stateUpper = new AtomicReference<>(null);

	protected StateSenderServer stateServer= null;

	public void setLastCID(int cid) {

		super.setLastCID(cid);
		tomLayer.setLastExec(cid);
	}

	long stateTransferStartTime;
	long stateTransferEndTime;
	@Override
	protected void requestState() {
		logger.trace("");

		if (tomLayer.requestsTimer != null) {
			tomLayer.requestsTimer.clearAll();
		}

		int me = SVController.getStaticConf().getProcessId();
		int[] otherProcesses = SVController.getCurrentViewOtherAcceptors();
		int globalCkpPeriod = SVController.getStaticConf()
				.getGlobalCheckpointPeriod();

		CSTRequestF1 cst = new CSTRequestF1(waitingCID);
		cst.defineReplicas(otherProcesses, globalCkpPeriod, me);
		this.cstConfig = cst;
		
		//start timer
		stateTransferStartTime = System.currentTimeMillis();

		CSTSMMessage cstMsg = new CSTSMMessage(me, waitingCID,
				TOMUtil.SM_REQUEST, cst, null, null, -1, -1);

		logger.info("Sending state request to the other replicas {} ", cstMsg);

		tomLayer.getCommunication().send(
				SVController.getCurrentViewOtherAcceptors(), cstMsg);

		TimerTask stateTask = new TimerTask() {
			public void run() {                
				CSTSMMessage msg = new CSTSMMessage(-1, waitingCID,TOMUtil.TRIGGER_SM_LOCALLY, null, null, null, -1, -1);

				triggerTimeout(msg);
			}
		};

		stateTimer = new Timer("state timer");
		timeout = timeout * 2;
		stateTimer.schedule(stateTask, timeout);
	}

	@Override
	public void stateTimeout() {
		lockTimer.lock();
		logger.info("(StateManager.stateTimeout) Timeout for the replica that was supposed to send the complete state. Changing desired replica.");
		if (stateTimer != null) {
			stateTimer.cancel();
		}
		reset();
		requestState();
		lockTimer.unlock();
	}

	// called when node receives a state request (resulting from other server calling requestState())
	@Override
	public void SMRequestDeliver(SMMessage msg, boolean isBFT) {
		logger.trace("");
		long t0 = System.currentTimeMillis();
		if (SVController.getStaticConf().isStateTransferEnabled()
				&& dt.getRecoverer() != null) {
			logger.info("The state transfer protocol is enabled");

			int myId = SVController.getStaticConf().getProcessId();
			InetSocketAddress address = SVController.getCurrentView().getAddress(myId);
			int port = 4444 + myId;
			address = new InetSocketAddress(address.getHostName(), port);
			
			CSTRequestF1 cstConfig = ((CSTSMMessage) msg).getCstConfig();
			cstConfig.setAddress(address);

			//modified by JSoares
			if (stateServer == null) {
				stateServer = new StateSenderServer(port, dt.getRecoverer(), cstConfig);
				new Thread(stateServer).start();
			}
			else {
				stateServer.updateServer(dt.getRecoverer(), cstConfig);
			}


			CSTSMMessage reply = new CSTSMMessage(myId, msg.getCID(),
					TOMUtil.SM_REPLY, cstConfig, null,
					SVController.getCurrentView(), tomLayer.getSynchronizer().getLCManager().getLastReg(),
					tomLayer.execManager.getCurrentLeader());

			long t1 = System.currentTimeMillis();
			System.out.println("############# Time to send State : \t" + (t1-t0));
			System.out.println("############# Time to send State : \t" + (t1-t0));
			System.out.println("############# Time to send State : \t" + (t1-t0));
			System.out.println("############# Time to send State : \t" + (t1-t0));
			logger.info("Sending reply {}", reply);
			int[] targets = {msg.getSender()};
			tomLayer.getCommunication().send(targets, reply);

		}
	}

	// called when node receives a reply to its state request (resulting from other server calling SMRequestDelivery())
	@Override
	public void SMReplyDeliver(SMMessage msg, boolean isBFT) {
		logger.trace("");

		lockTimer.lock();
		CSTSMMessage reply = (CSTSMMessage) msg;
		if (SVController.getStaticConf().isStateTransferEnabled()) {
			logger.debug("The state transfer protocol is enabled");
			logger.debug("Received a CSTMessage {} ", reply);

			if (waitingCID != -1 && reply.getCID() == waitingCID) {
				int currentRegency = -1;
				int currentLeader = -1;
				View currentView = null;
				CertifiedDecision currentProof = null;

				if (!appStateOnly) {
					logger.info("!appStateOnly");
					receivedRegencies.put(reply.getSender(), reply.getRegency());
					receivedLeaders.put(reply.getSender(), reply.getLeader());
					receivedViews.put(reply.getSender(), reply.getView());
					//                    senderProofs.put(msg.getSender(), msg.getState().getCertifiedDecision(SVController));
					if (enoughRegencies(reply.getRegency())) {
						currentRegency = reply.getRegency();
					}
					if (enoughLeaders(reply.getLeader())) {
						currentLeader = reply.getLeader();
					}
					if (enoughViews(reply.getView())) {
						currentView = reply.getView();
						if (!currentView.isMember(SVController.getStaticConf()
								.getProcessId())) {
							logger.warn("Not a member!");
						}
					}
					//                    if (enoughProofs(waitingCID, this.tomLayer.getSynchronizer().getLCManager())) currentProof = msg.getState().getCertifiedDecision(SVController);

				} else {
					logger.info("appStateOnly");
					currentLeader = tomLayer.execManager.getCurrentLeader();
					currentRegency = tomLayer.getSynchronizer().getLCManager().getLastReg();
					currentView = SVController.getCurrentView();
				}

				logger.info("The reply is for the CID that I want!");

				InetSocketAddress address = reply.getCstConfig().getAddress();
				Socket clientSocket;
				ApplicationState stateReceived = null; 
				try {
					logger.debug("Opening connection to peer {} to fetch CSTState", address);
					clientSocket = new Socket(address.getHostName(),
							address.getPort());
					ObjectInputStream in = new ObjectInputStream(
							clientSocket.getInputStream());
					stateReceived = (ApplicationState) in.readObject();
					clientSocket.close();
				} catch (UnknownHostException e) {
					logger.error("Failed to connect to address", e);
				} catch (IOException e) {
					logger.error("Failed to connect to address", e);
				} catch (ClassNotFoundException e) {
					logger.error("Failed to deserialize application state object", e);
				}

				if (stateReceived instanceof CSTState) {
					receivedStates.put(reply.getSender(), stateReceived);
					if (reply.getSender() == cstConfig.getCheckpointReplica()) {
						logger.debug("Received State from Checkpoint Replica\n");
						this.chkpntState = (CSTState) stateReceived;
					}
					if (reply.getSender() == cstConfig.getLogLower()) {
						logger.debug("Received State from Lower Log Replica\n");
						stateLower.set((CSTState) stateReceived);
					}
					if (reply.getSender() == cstConfig.getLogUpper()) {
						logger.debug("Received State from Upper Log Replica\n");
						stateUpper.set((CSTState) stateReceived);
					}
				}

				if (receivedStates.size() == 3) {
					boolean validState = false;
					CommandsInfo[] upperLog = stateUpper.get().getLogUpper();
					byte[] upperLogHash = CommandsInfo.computeHash(upperLog);

					if (reply.getCID() < SVController.getStaticConf().getGlobalCheckpointPeriod()) {
						validState = validatePreCSTState(upperLog, upperLogHash);
					}
					else {
						
						CommandsInfo[] lowerLog = stateLower.get().getLogLower();

						byte[] lowerLogHash = CommandsInfo.computeHash(lowerLog);						
						// validate lower log
						if (Arrays.equals(((CSTState)chkpntState).getLogLowerHash(), lowerLogHash)) {
							validState = true;
							logger.debug("VALID Lower Log hash");
						} else {
							logger.debug("INVALID Lower Log hash");
						}
						// validate upper log

						if (!Arrays.equals(((CSTState)chkpntState).getLogUpperHash(), upperLogHash) ) {
							validState = false;
							logger.debug("INVALID Upper Log hash");
						} else {
							logger.debug("VALID Upper Log hash");
						}
						stateTransferEndTime = System.currentTimeMillis();
						System.out.println("State Transfer process BEFORE statePlusLower/REBUILD!");
						System.out.println("Time: \t" + (stateTransferEndTime - stateTransferStartTime));

						CSTState statePlusLower = new CSTState(((CSTState)chkpntState).getSerializedState(),
								TOMUtil.computeHash(((CSTState)chkpntState).getSerializedState()),
								stateLower.get().getLogLower(), ((CSTState)chkpntState).getLogLowerHash(), null, null,
								((CSTState)chkpntState).getCheckpointCID(), stateUpper.get().getCheckpointCID(), SVController.getStaticConf().getProcessId());

						stateTransferEndTime = System.currentTimeMillis();
						System.out.println("State Transfer process AFTER statePlusLower/REBUILD!");
						System.out.println("Time: \t" + (stateTransferEndTime - stateTransferStartTime));

						if (validState) { // validate checkpoint
							logger.debug("Intalling Checkpoint and replying Lower Log");
							logger.debug("Installing state plus lower \n" + statePlusLower);
							
							stateTransferEndTime = System.currentTimeMillis();
							System.out.println("State Transfer process BEFORE setState!");
							System.out.println("Time: \t" + (stateTransferEndTime - stateTransferStartTime));

							dt.getRecoverer().setState(statePlusLower);

							stateTransferEndTime = System.currentTimeMillis();								
							System.out.println("State Transfer process AFTER SET STATE!");
							System.out.println("Time: \t" + (stateTransferEndTime - stateTransferStartTime));
							
							byte[] currentStateHash = ((DurabilityCoordinator) dt.getRecoverer()).getCurrentStateHash();
							if (!Arrays.equals(currentStateHash, stateUpper.get().getCheckpointHash())) {
								logger.warn("checkpoint hash don't match");
								validState = false;
							}
						}
					}

					logger.debug("get certifiedDecision for index " + stateUpper.get().getLastCID() + ". log upper size(): " + stateUpper.get().getLogUpper().length);
					currentProof = stateUpper.get().getCertifiedDecision(SVController);

					logger.debug("-- current regency: " + currentRegency);
					logger.debug("-- current leader: " + currentLeader);
					logger.debug("-- current view: " + currentView);
					logger.debug("-- haveState: " + validState);
					logger.debug("-- currentProof: " + currentProof);
					logger.debug("-- isBFT: " + isBFT);

					if ( /*currentRegency > -1 &&*/ currentLeader > -1
							&& currentView != null && validState && (!isBFT || currentProof != null || appStateOnly)) {
						logger.debug("---- RECEIVED VALID STATE ----");

						logger.debug("The state of those replies is good!");
						logger.debug("CID State requested: " + reply.getCID());
						logger.debug("CID State received: " + stateUpper.get().getLastCID());

						tomLayer.getSynchronizer().getLCManager().setLastReg(currentRegency);
						tomLayer.getSynchronizer().getLCManager().setNextReg(currentRegency);
						tomLayer.getSynchronizer().getLCManager().setNewLeader(currentLeader);

						tomLayer.execManager.setNewLeader(currentLeader);

						if (currentProof != null && !appStateOnly) {

							logger.debug("Installing proof for consensus " + waitingCID);

							Consensus cons = execManager.getConsensus(waitingCID);
							Epoch e = null;

							for (ConsensusMessage cm : currentProof.getConsMessages()) {

								e = cons.getEpoch(cm.getEpoch(), true, SVController);
								if (e.getTimestamp() != cm.getEpoch()) {

									logger.debug("Strange... proof contains messages from more than just one epoch");
									e = cons.getEpoch(cm.getEpoch(), true, SVController);
								}
								e.addToProof(cm);

								if (cm.getType() == MessageFactory.ACCEPT) {
									e.setAccept(cm.getSender(), cm.getValue());
								}
								else if (cm.getType() == MessageFactory.WRITE) {
									e.setWrite(cm.getSender(), cm.getValue());
								}
							}


							if (e != null) {

								byte[] hash = tomLayer.computeHash(currentProof.getDecision());
								e.propValueHash = hash;
								e.propValue = currentProof.getDecision();
								e.deserializedPropValue = tomLayer.checkProposedValue(currentProof.getDecision(), false);
								cons.decided(e, false);

								logger.debug("Successfully installed proof for consensus " + waitingCID);

							} else {
								logger.debug("Failed to install proof for consensus " + waitingCID);

							}
						}
						// I might have timed out before invoking the state transfer, so
						// stop my re-transmission of STOP messages for all regencies up to the current one
						if (currentRegency > 0) {
							tomLayer.getSynchronizer().removeSTOPretransmissions(currentRegency - 1);
						}

						logger.debug("trying to acquire deliverlock");
						dt.deliverLock();
						logger.debug("acquired");

						// this makes the isRetrievingState() evaluates to false
						waitingCID = -1;
						dt.update(stateUpper.get());

						// Deal with stopped messages that may come from
						// synchronization phase
						if (!appStateOnly && execManager.stopped()) {
							Queue<ConsensusMessage> stoppedMsgs = execManager.getStoppedMsgs();
							for (ConsensusMessage stopped : stoppedMsgs) {
								if (stopped.getNumber() > chkpntState.getLastCID()) {
									execManager.addOutOfContextMessage(stopped);
								}
							}
							execManager.clearStopped();
							execManager.restart();
						}

						logger.info("Processing out of context messages");
						tomLayer.processOutOfContext();

						if (SVController.getCurrentViewId() != currentView.getId()) {
							logger.info("Installing current view!");
							SVController.reconfigureTo(currentView);
						}

						isInitializing = false;

						dt.canDeliver();
						dt.deliverUnlock();

						reset();

						logger.info("I updated the state!");

						tomLayer.requestsTimer.Enabled(true);
						tomLayer.requestsTimer.startTimer();
						if (stateTimer != null) {
							stateTimer.cancel();
						}

						if (appStateOnly) {
							appStateOnly = false;
							tomLayer.getSynchronizer().resumeLC();
						}
						stateTransferEndTime = System.currentTimeMillis();
						System.out.println("State Transfer process completed successfuly!");
						System.out.println("Time: \t" + (stateTransferEndTime - stateTransferStartTime));

					} else if (chkpntState == null && (SVController.getCurrentViewN() / 2) < getReplies()) {
						logger.warn("---- DIDNT RECEIVE STATE ----");

						logger.debug("I have more than "
								+ (SVController.getCurrentViewN() / 2)
								+ " messages that are no good!");

						waitingCID = -1;
						reset();

						if (stateTimer != null) {
							stateTimer.cancel();
						}

						if (appStateOnly) {
							requestState();
						}
					} else if (!validState) {
						logger.warn("---- RECEIVED INVALID STATE  ----");

						logger.debug("The replica from which I expected the state, sent one which doesn't match the hash of the others, or it never sent it at all");

						reset();
						requestState();

						if (stateTimer != null) {
							stateTimer.cancel();
						}
					}
				}
			}
		}
		else {
			logger.info("Received unexpected state reply (discarding)");
		}
		lockTimer.unlock();
	}

	private boolean validatePreCSTState(CommandsInfo[] upperLog, byte[] upperLogHash) {
		byte[] logHashFromCkpSender = ((CSTState)chkpntState).getLogUpperHash();
		byte[] logHashFromLowerSender = stateLower.get().getLogUpperHash();

		boolean haveState = false;
		haveState = Arrays.equals(upperLogHash, logHashFromCkpSender);
		if (haveState) {
			haveState = Arrays.equals(upperLogHash, logHashFromLowerSender);
		}
		return haveState;
	}

}
