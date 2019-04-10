package bftsmart.statemanagement.durability.shard;

import java.io.ObjectInputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Timer;
import java.util.TimerTask;

import bftsmart.consensus.Consensus;
import bftsmart.consensus.Epoch;
import bftsmart.consensus.messages.ConsensusMessage;
import bftsmart.consensus.messages.MessageFactory;
import bftsmart.reconfiguration.views.View;
import bftsmart.statemanagement.SMMessage;
import bftsmart.statemanagement.durability.CSTSMMessage;
import bftsmart.statemanagement.durability.CSTState;
import bftsmart.statemanagement.durability.DurableStateManager;
import bftsmart.statemanagement.durability.StateSenderServer;
import bftsmart.statemanagement.standard.StandardSMMessage;
import bftsmart.tom.leaderchange.CertifiedDecision;
import bftsmart.tom.server.defaultservices.CommandsInfo;
import bftsmart.tom.server.durability.DurabilityCoordinator;
import bftsmart.tom.server.durability.DurableStateLog;
import bftsmart.tom.util.TOMUtil;
import merkletree.MerkleTree;
import merkletree.TreeNode;

public class ShardedStateManager extends DurableStateManager {

	// replica states received during the first phase of the CST protocol
	private HashMap<Integer, ShardedCSTState> firstReceivedStates = new HashMap<>();

	// state transfer configuration used for synchronisation phase
	private ShardedCSTRequest shardedCSTConfig;	

	// previously reconstructed state 
	// used to recover from faulty shards
	private ShardedCSTState statePlusLower;

	@Override
	public void currentConsensusIdAsked(int sender, int id) {
		logger.trace("");
		logger.info("Received ConsensusID query from {} with QueryID {}", sender, id);
		int me = SVController.getStaticConf().getProcessId();
		DurableStateLog log = ((DurableStateLog)dt.getRecoverer().getLog());

		//		public ShardedCSTState(byte[] state, byte[] hashCheckpoint, CommandsInfo[] logLower, byte[] hashLogLower,
		//				CommandsInfo[] logUpper, byte[] hashLogUpper, int checkpointCID, int currentCID, int pid, String hashAlgo, int shardSize) {

		ShardedCSTState state;
		if(log == null)
			state = new ShardedCSTState(null, null, null, null, null, null, -1, tomLayer.getLastExec(), -1, null, -1);
		else
			state = log.getState(tomLayer.getLastExec(), SVController.getStaticConf().getMrklTreeHashAlgo(), SVController.getStaticConf().getShardSize());
		logger.debug("LOG {}", state);
		SMMessage currentCIDReply = new StandardSMMessage(me, id, TOMUtil.SM_REPLY_INITIAL, 0, state, null, 0, 0);

		logger.info("Sending reply {}", currentCIDReply);
		tomLayer.getCommunication().send(new int[] { sender }, currentCIDReply);
	}

	@Override
	public synchronized void currentConsensusIdReceived(SMMessage smsg) {
		logger.trace("");
		if (!isInitializing || waitingCID > -1 || queryID != smsg.getCID()) {
			logger.info("Ignoring ConsensusID request {} (expecting ID {})", smsg.toString(), queryID);
			return;
		}
		logger.info("Received ConsensusID request {} (expecting ID {})", smsg.toString(), queryID);

		firstReceivedStates.put(smsg.getSender(), (ShardedCSTState)smsg.getState());

		Map<Integer, Integer> replies = queries.get(queryID);
		if (replies == null) {
			replies = new HashMap<>();
			queries.put(queryID, replies);
		}
		replies.put(smsg.getSender(), smsg.getState().getLastCID());

		if (replies.size() > SVController.getQuorum()) {
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
					logger.info("There is a quorum for CID {}", cid);
					queries.clear();

					if (cid == lastCID) {
						logger.info("Replica state is up to date");

						firstReceivedStates.clear();

						dt.deliverLock();
						isInitializing = false;
						tomLayer.setLastExec(cid);
						dt.canDeliver();
						dt.deliverUnlock();
						break;
					} else {
						// ask for state
						logger.info("Replica state is outdated...");
						System.out.println("Replica State is outdated...");
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

	private long CST_start_time;
	private long CST_end_time;
	
	private int retries = 0;

	@Override
	protected void requestState() {
		CST_start_time = System.currentTimeMillis();
		
		logger.trace("");
		if (tomLayer.requestsTimer != null) {
			tomLayer.requestsTimer.clearAll();
		}

		int me = SVController.getStaticConf().getProcessId();
		int[] otherReplicas = SVController.getCurrentViewOtherAcceptors();
		int globalCkpPeriod = SVController.getStaticConf().getGlobalCheckpointPeriod();

		try {
			ShardedCSTRequest cst = new ShardedCSTRequest(waitingCID, SVController.getStaticConf().getMrklTreeHashAlgo(), SVController.getStaticConf().getShardSize());
			cst.defineReplicas(otherReplicas, globalCkpPeriod, me);
			cst.assignShards(firstReceivedStates, dt.getRecoverer().getState(this.lastCID, true).getSerializedState());
			logger.debug("\n\t Starting State Transfer: \n" + cst);
			System.out.println("Starting State Transfer: \n" + cst);

			this.shardedCSTConfig = cst;
			this.retries = 0;
			this.statePlusLower = null;

			ShardedCSTSMMessage cstMsg = new ShardedCSTSMMessage(me, waitingCID,TOMUtil.SM_REQUEST, cst, null, null, -1, -1);
			tomLayer.getCommunication().send(SVController.getCurrentViewOtherAcceptors(), cstMsg);
		} catch (Exception e) {
			e.printStackTrace();
		}


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
	public void SMRequestDeliver(SMMessage msg, boolean isBFT) {
		logger.trace("");
		if (SVController.getStaticConf().isStateTransferEnabled()
				&& dt.getRecoverer() != null) {
			logger.info("Received State Transfer Request from " + msg.getSender());

			int myId = SVController.getStaticConf().getProcessId();
			InetSocketAddress address = SVController.getCurrentView().getAddress(myId);
			int port = 4444 + myId;
			address = new InetSocketAddress(address.getHostName(), port);

			ShardedCSTRequest cstConfig = (ShardedCSTRequest)((ShardedCSTSMMessage)msg).getCstConfig();
			cstConfig.setAddress(address);

			if (stateServer == null) {
				stateServer = new StateSenderServer(port, dt.getRecoverer(), cstConfig);
				new Thread(stateServer).start();
			}
			else {
				stateServer.updateServer(dt.getRecoverer(), cstConfig);
			}

			ShardedCSTSMMessage reply = new ShardedCSTSMMessage(myId, msg.getCID(),
					TOMUtil.SM_REPLY, cstConfig, null,
					SVController.getCurrentView(), tomLayer.getSynchronizer().getLCManager().getLastReg(),
					tomLayer.execManager.getCurrentLeader());

			logger.info("Sending reply {}", reply);
			tomLayer.getCommunication().send(new int[]{msg.getSender()}, reply);
		}
	}

	private boolean validatePreCSTState(CommandsInfo[] upperLog, byte[] upperLogHash) {
		byte[] logHashFromCkpSender = ((ShardedCSTState)chkpntState).getLogUpperHash();
		byte[] logHashFromLowerSender = stateLower.getLogUpperHash();
		boolean haveState = false;
		haveState = Arrays.equals(upperLogHash, logHashFromCkpSender);
		if (haveState) {
			haveState = Arrays.equals(upperLogHash, logHashFromLowerSender);
		}
		return haveState;
	}

	private Integer[] detectFaultyShards() {
		logger.info("detecting faulty shards");
		List<Integer> faultyPages = new LinkedList<Integer>();
		int shardSize = this.shardedCSTConfig.getShardSize();
		MessageDigest md = null;
		try {
			md = MessageDigest.getInstance(this.shardedCSTConfig.hashAlgo);
		} catch (NoSuchAlgorithmException e) {
			e.printStackTrace();
		}

		ShardedCSTState state = firstReceivedStates.get(((ShardedCSTState)chkpntState).getReplicaID());
		MerkleTree mt = state.getMerkleTree();
		List<TreeNode> nodes = mt.getLeafs();
		byte[] data = ((ShardedCSTState)chkpntState).getSerializedState();
		Integer[] shards = this.shardedCSTConfig.getNonCommonShards();
		for(int i = 0; i < shards.length; i++) {
			byte[] shard = new byte[shardSize];
			System.arraycopy(data, i * shardSize, shard, 0, shardSize);
			if(!Arrays.equals(md.digest(shard), nodes.get(shards[i]).digest())) {
				logger.info("Faulty shard detected {} from Replica {}", shards[i], state.getReplicaID());
				faultyPages.add(shards[i]);
			}
		}

		state = firstReceivedStates.get(((ShardedCSTState)stateLower).getReplicaID());
		mt = state.getMerkleTree();
		nodes = mt.getLeafs();
		data = state.getSerializedState();
		
		shards = this.shardedCSTConfig.getCommonShards();
		int half = (shards.length/2)+1;
		
		for(int i = 0; i < half; i++) {
			try {
				byte[] shard = new byte[shardSize];
				System.arraycopy(data, i * shardSize, shard, 0, shardSize);
				if(!Arrays.equals(md.digest(shard), nodes.get(i).digest())) {
					logger.info("Faulty shard detected {} from Replica {}", shards[i], state.getReplicaID());
					faultyPages.add(shards[i]);
				}
			} catch (Exception e) {
				e.printStackTrace();
			}
		}

		
		state = firstReceivedStates.get(((ShardedCSTState)stateUpper).getReplicaID());
		mt = state.getMerkleTree();
		nodes = mt.getLeafs();
		data = state.getSerializedState();

		for(int i = 0; i < half; i++) {
			try {
				byte[] shard = new byte[shardSize];
				System.arraycopy(data, i * shardSize, shard, 0, shardSize);
				if(!Arrays.equals(md.digest(shard), nodes.get(half+i).digest())) {
					logger.info("Faulty shard detected {} from Replica {}", shards[half+i], state.getReplicaID());
					faultyPages.add(shards[half+i]);
				}
			} catch (Exception e) {
				e.printStackTrace();
			}
		}

		return faultyPages.toArray(new Integer[0]);
	}

	private ShardedCSTState rebuildCSTState() {
		logger.info("rebuilding state");
		ShardedCSTState chkPntState = (ShardedCSTState)chkpntState;
		ShardedCSTState logUpperState = (ShardedCSTState)stateUpper;
		ShardedCSTState logLowerState = (ShardedCSTState)stateLower;

		byte[] chkpntData = new byte[shardedCSTConfig.getShardCount() * shardedCSTConfig.getShardSize()];

		byte[] currState = dt.getRecoverer().getState(this.lastCID, true).getSerializedState();
		System.arraycopy(currState, 0, chkpntData, 0, currState.length);

		if(statePlusLower != null)
			chkpntData = statePlusLower.getSerializedState();
		
		Integer[] commonShards = shardedCSTConfig.getCommonShards();
		int shardSize = shardedCSTConfig.getShardSize();

		int half = (commonShards.length/2);
		if(commonShards.length%2 == 1)
			half = ((commonShards.length+1)/2);
		
//		logger.debug("Current State: \n" + Arrays.toString(chkpntData));
		logger.debug("Common Shards: " + commonShards.length);
		logger.debug("HALF : " + half);
//		logger.debug("LowerChkPnt Shards : " + Arrays.toString(logLowerState.getSerializedState()));
//		logger.debug("UpperChkPnt Shards : " + Arrays.toString(logUpperState.getSerializedState()));
		for(int i = 0;i < commonShards.length; i++) {
//			logger.debug("Copying common shard {} to shard {}", i, commonShards[i]);
			try {
				if(i < half) {
//					logger.debug("Copying Shard : " + commonShards[i] + " from lower");
					System.arraycopy(logLowerState.getSerializedState(), i*shardSize, chkpntData, commonShards[i]*shardSize, shardSize);
				}else {
//					logger.debug("Copying Shard : " + commonShards[i] + " from upper");
					System.arraycopy(logUpperState.getSerializedState(), (i-half)*shardSize, chkpntData, commonShards[i]*shardSize, shardSize);
				}
			} catch (Exception e) {
				logger.error("Error copying shard during state rebuild. IGNORING IT FOR NOW");
			}
		}

		Integer[] noncommonShards = shardedCSTConfig.getNonCommonShards();
		logger.debug("NonCommon Shards : " + noncommonShards.length);
//		logger.debug("ChkPnt Shards : " + Arrays.toString(logLowerState.getSerializedState()));
		for(int i = 0;i < noncommonShards.length; i++) {
			try {
//				logger.debug("Copying Shard : " + noncommonShards[i] + " from chkpnt");
				System.arraycopy(chkPntState.getSerializedState(), i*shardSize, chkpntData, noncommonShards[i]*shardSize, shardSize);
			} catch (Exception e) {
				logger.error("Error copying received shard during state rebuild. IGNORING IT FOR NOW");
			}
		}

		int i =chkpntData.length-1;
		for(; i > 0; i--) {
			if(chkpntData[i] != '\0')
				break;
		}
		
		byte[] trimedData = new byte[i+1];
		System.arraycopy(chkpntData, 0, trimedData, 0, i+1);
//		logger.debug("TRIMMED CHECKPOINT STATE : \n" + Arrays.toString(chkpntData));

		
		if(statePlusLower == null)
			return new ShardedCSTState(trimedData,
					TOMUtil.getBytes(((ShardedCSTState)chkpntState).getSerializedState()),
					stateLower.getLogLower(), ((ShardedCSTState)chkpntState).getLogLowerHash(), null, null,
					((ShardedCSTState)chkpntState).getCheckpointCID(), stateUpper.getCheckpointCID(), SVController.getStaticConf().getProcessId(), ((ShardedCSTState)chkpntState).getHashAlgo(), ((ShardedCSTState)chkpntState).getShardSize());
		else {
			statePlusLower.setSerializedState(trimedData);
			return statePlusLower;
		}
	}
	
	@Override
	public void SMReplyDeliver(SMMessage msg, boolean isBFT) {
		logger.trace("");

		lockTimer.lock();
		ShardedCSTSMMessage reply = (ShardedCSTSMMessage)msg;
		if (SVController.getStaticConf().isStateTransferEnabled()) {
			logger.info("Received State Transfer Response from " + msg.getSender());

			if (waitingCID != -1 && reply.getCID() == waitingCID) {
				int currentRegency = -1;
				int currentLeader = -1;
				View currentView = null;
				CertifiedDecision currentProof = null;

				if (!appStateOnly) {
					receivedRegencies.put(reply.getSender(), reply.getRegency());
					receivedLeaders.put(reply.getSender(), reply.getLeader());
					receivedViews.put(reply.getSender(), reply.getView());

					if (enoughRegencies(reply.getRegency())) {
						currentRegency = reply.getRegency();
					}
					if (enoughLeaders(reply.getLeader())) {
						currentLeader = reply.getLeader();
					}
					if (enoughViews(reply.getView())) {
						currentView = reply.getView();
						if (!currentView.isMember(SVController.getStaticConf().getProcessId())) {
							logger.warn("Not a member!");
						}
					}
				} else {
					currentLeader = tomLayer.execManager.getCurrentLeader();
					currentRegency = tomLayer.getSynchronizer().getLCManager().getLastReg();
					currentView = SVController.getCurrentView();
				}

				InetSocketAddress address = reply.getCstConfig().getAddress();
				Socket clientSocket;
				ShardedCSTState stateReceived = null; //state transfer
				try {
					logger.info("Opening connection to peer {} for requesting its Replica State", address);
					clientSocket = new Socket(address.getHostName(), address.getPort());
					ObjectInputStream in = new ObjectInputStream(clientSocket.getInputStream());
					stateReceived = (ShardedCSTState) in.readObject();
					in.close();
					clientSocket.close();
				} catch (Exception e) {
					logger.error("Failed to transfer state", e);
					// TODO: flag that the transfer failed for repeating the transfer process
					return;
				}

				receivedStates.put(reply.getSender(), stateReceived);
				if (reply.getSender() == shardedCSTConfig.getCheckpointReplica()) {
					logger.info("Received State from Checkpoint Replica\n");
					this.chkpntState = stateReceived;
				}
				if (reply.getSender() == shardedCSTConfig.getLogLower()) {
					logger.info("Received State from Lower Log Replica\n");
					this.stateLower = stateReceived;
				}
				if (reply.getSender() == shardedCSTConfig.getLogUpper()) {
					logger.info("Received State from Upper Log Replica\n");
					this.stateUpper = stateReceived;
				}

				if (receivedStates.size() == 3) {
					logger.info("Validating Received State\n");
					CommandsInfo[] upperLog = stateUpper.getLogUpper();
					byte[] upperLogHash = CommandsInfo.computeHash(upperLog);

					boolean validState = false;
					if (reply.getCID() < SVController.getStaticConf().getGlobalCheckpointPeriod()) {
						validState = validatePreCSTState(upperLog, upperLogHash);
					}
					else {
						CommandsInfo[] lowerLog = stateLower.getLogLower();
						byte[] lowerLogHash = CommandsInfo.computeHash(lowerLog);

						// validate lower log -> hash(lowerLog) == lowerLogHash
						if (Arrays.equals(((CSTState)chkpntState).getLogLowerHash(), lowerLogHash)) {
							validState = true;
							logger.info("VALID Lower Log hash");
						} else {
							logger.info("INVALID Lower Log hash");
						}
						// validate upper log -> hash(upperLog) == upperLogHash
						if (!Arrays.equals(((CSTState)chkpntState).getLogUpperHash(), upperLogHash) ) {
							validState = false;
							logger.info("INVALID Upper Log hash");
						} else {
							logger.info("VALID Upper Log hash");
						}

						if (validState) { // validate checkpoint
							statePlusLower = rebuildCSTState();
							logger.info("Intalling Checkpoint and replying Lower Log");
							logger.info("Installing state plus lower \n" + statePlusLower);
							dt.getRecoverer().setState(statePlusLower);
							byte[] currentStateHash = ((DurabilityCoordinator) dt.getRecoverer()).getCurrentStateHash();
							if (!Arrays.equals(currentStateHash, stateUpper.getCheckpointHash())) {
								logger.info("INVALID Checkpoint + Lower Log hash"); 
								validState = false;
							} else {
								logger.info("VALID Checkpoint + Lower Log  hash");
							}
						}
						else {
							logger.info("Terminating transfer process due to faulty Lower and Upper Logs");
						}
					}
					
					currentProof = this.stateUpper.getCertifiedDecision(SVController);
					
					logger.info("CURRENT Regency = " + currentRegency);
					logger.info("CURRENT Leader = " + currentLeader);
					logger.info("CURRENT View = " + currentView);
					logger.info("CURRENT PROOF = " + currentProof);
					logger.info("validState = " + validState);
					logger.info("appStateOnly = " + appStateOnly);
					
					
					if (/*currentRegency > -1 &&*/ currentLeader > -1
							&& currentView != null && validState && (!isBFT || currentProof != null || appStateOnly)) {
						logger.info("---- RECEIVED VALID STATE ----");

						tomLayer.getSynchronizer().getLCManager().setLastReg(currentRegency);
						tomLayer.getSynchronizer().getLCManager().setNextReg(currentRegency);
						tomLayer.getSynchronizer().getLCManager().setNewLeader(currentLeader);

						tomLayer.execManager.setNewLeader(currentLeader);

						if (currentProof != null && !appStateOnly) {
							logger.info("Trying to install proof for consensus " + waitingCID);

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
								logger.info("Successfully installed proof for consensus " + waitingCID);
							} else {
								//NOTE [JSoares]: if this happens shouldn't the transfer process stop????
								logger.info("Failed to install proof for consensus " + waitingCID);
							}
						}

						// I might have timed out before invoking the state transfer, so
						// stop my re-transmission of STOP messages for all regencies up to the current one
						if (currentRegency > 0) {
							tomLayer.getSynchronizer().removeSTOPretransmissions(currentRegency - 1);
						}

						logger.info("Trying to acquire deliverlock");
						dt.deliverLock();
						logger.info("Successfuly acquired deliverlock");

						// this makes the isRetrievingState() evaluates to false
						waitingCID = -1;
						
						// JSoares Modified, since the state sent by the UpperLog replica contains checkpoint data 
						// and the original transfer process is not expecting it
						stateUpper.setSerializedState(null);
						
						logger.info("Updating state with Upper Log operations");
						dt.update(stateUpper);

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

						logger.info("State Transfer process completed successfuly!");
						
						reset();
						firstReceivedStates.clear();
						statePlusLower = null;
						
						tomLayer.requestsTimer.Enabled(true);
						tomLayer.requestsTimer.startTimer();

						if (stateTimer != null) {
							stateTimer.cancel();
						}

						if (appStateOnly) {
							appStateOnly = false;
							tomLayer.getSynchronizer().resumeLC();
						}
						CST_end_time = System.currentTimeMillis();
						
						System.out.println("State Transfer process completed successfuly!");
						System.out.println("State Transfer duration: " + (CST_end_time - CST_start_time));
						
					} else if (chkpntState == null
							&& (SVController.getCurrentViewN() / 2) < getReplies()) {
						logger.info("---- DIDNT RECEIVE STATE ----");

						waitingCID = -1;
						reset();
						if (appStateOnly) {
							requestState();
						}
						if (stateTimer != null) {
							stateTimer.cancel();
						}
					} else if (!validState) {
						logger.warn("---- RECEIVED INVALID STATE  ----");

						retries ++;
						if(retries < 3) {							
							Integer[] faultyShards = detectFaultyShards();
							if(faultyShards.length == 0) { 
								logger.info("Cannot detect faulty shards. Will restart protocol");
								reset();
//								firstReceivedStates.clear();
//								statePlusLower = null;
								requestState();
								if (stateTimer != null) {
									stateTimer.cancel();
								}
							}
							else {
								logger.info("Retrying State Transfer for the {} time", retries);
								
                                reset();
								if (stateTimer != null) {
									stateTimer.cancel();
								}
								
                                this.shardedCSTConfig.reAssignShards(faultyShards);
                        		logger.debug("Requesting Faulty Shards: \n" + shardedCSTConfig);

                                int me = SVController.getStaticConf().getProcessId();
                                
								ShardedCSTSMMessage cstMsg = new ShardedCSTSMMessage(me, waitingCID,TOMUtil.SM_REQUEST, this.shardedCSTConfig, null, null, -1, -1);
								tomLayer.getCommunication().send(SVController.getCurrentViewOtherAcceptors(), cstMsg);

								TimerTask stateTask = new TimerTask() {
									public void run() {                
										CSTSMMessage msg = new CSTSMMessage(-1, waitingCID,TOMUtil.TRIGGER_SM_LOCALLY, null, null, null, -1, -1);
										triggerTimeout(msg);
									}
								};

								stateTimer = new Timer("state timer");
								timeout = timeout * 2;
								if(timeout < 0)
									timeout = INIT_TIMEOUT;
								stateTimer.schedule(stateTask, timeout);

							}
						}
						else {
							logger.debug("---- exceeded number of retries  ----");
							logger.debug("---- exceeded number of retries  ----");
							// exceeded number of retries
							// have to restart protocol
							// or should wait until timeout???
						}
					}
					else {
						logger.debug("---- NAO BATE EM NADA  ----");
						logger.debug("---- NAO BATE EM NADA  ----");
						logger.debug("---- NAO BATE EM NADA  ----");
						logger.debug("---- NAO BATE EM NADA  ----");
						logger.debug("---- NAO BATE EM NADA  ----");
						logger.debug("---- NAO BATE EM NADA  ----");
					}
				}
			}
			else {
				logger.info("Received unexpected state reply (discarding)");
			}
		}
		lockTimer.unlock();
	}
}
