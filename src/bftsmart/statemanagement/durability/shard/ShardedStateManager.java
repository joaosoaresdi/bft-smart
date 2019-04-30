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
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

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
	
	private ConcurrentHashMap<Integer, ShardedCSTState> firstReceivedStates = new ConcurrentHashMap<>();

	// state transfer configuration used for synchronisation phase
	private ShardedCSTRequest shardedCSTConfig;	

	// previously reconstructed state 
	// used to recover from faulty shards
	private ShardedCSTState statePlusLower;

	@Override
	public void currentConsensusIdAsked(int sender, int id) {
		logger.trace("");
		logger.debug("Received ConsensusID query from {} with QueryID {}", sender, id);
		
		int me = SVController.getStaticConf().getProcessId();
		DurableStateLog log = ((DurableStateLog)dt.getRecoverer().getLog());

		ShardedCSTState state;
		if(log == null)
			state = new ShardedCSTState(null, null, null, null, null, null, -1, tomLayer.getLastExec(), -1, SVController.getStaticConf().getMrklTreeHashAlgo(), SVController.getStaticConf().getShardSize(), true);
		else
			state = log.buildCurrentState(tomLayer.getLastExec(), SVController.getStaticConf().getMrklTreeHashAlgo(), SVController.getStaticConf().getShardSize());
		
		state.setSerializedState(null);
		
		SMMessage currentCIDReply = new StandardSMMessage(me, id, TOMUtil.SM_REPLY_INITIAL, 0, state, null, 0, 0);
		logger.debug("Sending reply {}", currentCIDReply);
		tomLayer.getCommunication().send(new int[] { sender }, currentCIDReply);
	}

	private static AtomicBoolean fence = new AtomicBoolean(false);
	
	@Override
	public void currentConsensusIdReceived(SMMessage smsg) {
		logger.trace("");
		if (!isInitializing || waitingCID > -1 || queryID != smsg.getCID()) {
			logger.debug("Ignoring ConsensusID request {} (expecting ID {})", smsg.toString(), queryID);
			return;
		}
		logger.debug("Received ConsensusID request {} (expecting queryID {})", smsg.toString(), queryID);

		firstReceivedStates.put(smsg.getSender(), (ShardedCSTState)smsg.getState());
		
		synchronized (queries) {
			Map<Integer, Integer> replies = queries.get(queryID);
			if (replies == null) {
				replies = new ConcurrentHashMap<Integer,Integer> ();
				queries.put(queryID, replies);
			}
		}
		
		Map<Integer, Integer> replies = queries.get(queryID);
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
					if(fence.compareAndSet(false, true)) { // only one can enter per queryID 
						logger.debug("There is a quorum for CID {}", cid);
						queries.clear();
	
						if (cid == lastCID) {
							logger.debug("Replica state is up to date");
	
							firstReceivedStates.clear();
	
							dt.deliverLock();
							isInitializing = false;
							tomLayer.setLastExec(cid);
							dt.canDeliver();
							dt.deliverUnlock();
							break;
						} else {
							// ask for state
							logger.debug("Replica state is outdated...");
							System.out.println("Replica State is outdated...");
							lastCID = cid + 1;
							if (waitingCID == -1) {
								waitingCID = cid;
								requestState();
							}
						}
					}
					fence.set(false);
				}
			}
		}
	}

	private long stateTransferStartTime;
	private long stateTransferEndTime;
	
	private int retries = 0;

	@Override
	protected void requestState() {
		
		logger.trace("");
		if (tomLayer.requestsTimer != null) {
			tomLayer.requestsTimer.clearAll();
		}

		int me = SVController.getStaticConf().getProcessId();
		int[] otherReplicas = SVController.getCurrentViewOtherAcceptors();
		int globalCkpPeriod = SVController.getStaticConf().getGlobalCheckpointPeriod();

		try {
//			if(firstReceivedStates.isEmpty()) {
//				CSTRequestF1 cst = new CSTRequestF1(waitingCID);
//				cst.defineReplicas(otherReplicas, globalCkpPeriod, me);
//				cstRequest = cst;
//				CSTSMMessage cstMsg = new CSTSMMessage(me, waitingCID, TOMUtil.SM_REQUEST, cst, null, null, -1, -1);
//
//				logger.info("Sending state request to the other replicas {} ", cstMsg);
//				tomLayer.getCommunication().send(SVController.getCurrentViewOtherAcceptors(), cstMsg);
//			}
//			else {
				ShardedCSTRequest cst = new ShardedCSTRequest(waitingCID, SVController.getStaticConf().getMrklTreeHashAlgo(), SVController.getStaticConf().getShardSize());
				cst.defineReplicas(otherReplicas, globalCkpPeriod, me);
				cst.assignShards(firstReceivedStates);
				
				logger.debug("\n\t Starting State Transfer: \n" + cst);
	
				this.shardedCSTConfig = cst;
				this.retries = 0;
				this.statePlusLower = null;
	
				stateTransferStartTime = System.currentTimeMillis();

				ShardedCSTSMMessage cstMsg = new ShardedCSTSMMessage(me, waitingCID,TOMUtil.SM_REQUEST, cst, null, null, -1, -1);
				tomLayer.getCommunication().send(SVController.getCurrentViewOtherAcceptors(), cstMsg);

				//			}
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
		long t0 = System.currentTimeMillis();
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

			long t1 = System.currentTimeMillis();
			System.out.println("############# Time to send State : \t" + (t1-t0));
			System.out.println("############# Time to send State : \t" + (t1-t0));
			System.out.println("############# Time to send State : \t" + (t1-t0));
			System.out.println("############# Time to send State : \t" + (t1-t0));
			logger.info("Sending reply {}", reply);
			tomLayer.getCommunication().send(new int[]{msg.getSender()}, reply);
		}
	}

	private boolean validatePreCSTState(CSTState lowerState, CommandsInfo[] upperLog, byte[] upperLogHash) {
		byte[] logHashFromCkpSender = ((ShardedCSTState)chkpntState).getLogUpperHash();
		byte[] logHashFromLowerSender = lowerState.getLogUpperHash();

		return (Arrays.equals(upperLogHash, logHashFromCkpSender) && Arrays.equals(upperLogHash, logHashFromLowerSender));
	}

	private Integer[] detectFaultyShards(CSTState lowerState, CSTState upperState, CSTState chkpntState) {
		logger.debug("detecting faulty shards");
		stateTransferEndTime = System.currentTimeMillis();								
		System.out.println("State Transfer process BEFORE DETECT FAULTY SHARDS!");
		System.out.println("Time: \t" + (stateTransferEndTime - stateTransferStartTime));
		
		List<Integer> faultyPages = new LinkedList<Integer>();
		int shardSize = this.shardedCSTConfig.getShardSize();
		
		Integer[] noncommonShards = shardedCSTConfig.getNonCommonShards();
		Integer[] commonShards = shardedCSTConfig.getCommonShards();
		
        int nonCommon_size = noncommonShards.length;
        int common_size = commonShards.length;
        
        int third = (nonCommon_size+common_size)/3;

        System.out.println("detecting faulty shards");
		System.out.println("noncommonShards : " + noncommonShards.length);
		System.out.println("commonShards : " + commonShards.length);
		System.out.println("Third : " + third);
		
    	if(nonCommon_size < third) {
    		Future<List<Integer>>[] waitingTasks = new Future[3];
    		waitingTasks[0] = executorService.submit(new Callable<List<Integer>>() {
    			@Override
    			public List<Integer> call() throws Exception {
    				MessageDigest md = null;
    				try {
    					md = MessageDigest.getInstance(shardedCSTConfig.hashAlgo);
    				} catch (NoSuchAlgorithmException e) {
    					e.printStackTrace();
    				}

    				List<Integer> faultyPages = new LinkedList<>();

    				ShardedCSTState state = firstReceivedStates.get(((ShardedCSTState)chkpntState).getReplicaID());
    				MerkleTree mt = state.getMerkleTree();
    				List<TreeNode> nodes = mt.getLeafs();
    				
    				byte[] data = chkpntState.getSerializedState();
    				logger.debug("DATA : " + data);
    				logger.debug("DATA : " + data);
    				logger.debug("DATA : " + data);
    				logger.debug("DATA : " + data.length);
    				logger.debug("DATA : " + data.length);
    				logger.debug("DATA : " + data.length);


    				Integer[] shards = shardedCSTConfig.getCommonShards();
    	    		int comm_count = third - nonCommon_size;
    	    		for(int i = 0;i < comm_count; i++) {
		    			try {
		    				md.reset();
	    	    			md.update(data, i*shardSize, shardSize);
		    			} catch (Exception e) {
		    				e.printStackTrace();
		    			}
    					if(!Arrays.equals(md.digest(), nodes.get(shards[i]).digest())) {
    						logger.debug("Faulty shard detected {} from Replica {}", shards[i], state.getReplicaID());
    						faultyPages.add(shards[i]);
    						break;
    					}
    	    		}
    	    		
    				shards = shardedCSTConfig.getNonCommonShards();
    				int len = shardSize;
    	    		for(int i = 0;i < noncommonShards.length; i++) {
		    			try {
		    				if(((comm_count+i+1)*shardSize) > data.length)
		    					len = data.length - ((comm_count+i) * shardSize);
		    				md.reset();
		    				md.update(data, (comm_count+i) * shardSize, len);
		    			} catch (Exception e) {
		    				e.printStackTrace();
		    			}

    					if(!Arrays.equals(md.digest(), nodes.get(shards[i]).digest())) {
    						logger.debug("Faulty shard detected {} from Replica {}", shards[i], state.getReplicaID());
    						faultyPages.add(shards[i]);
    						break;
    					}
    	    		}

    				return faultyPages;
    			}
    			
    		});

    		waitingTasks[1] = executorService.submit(new Callable<List<Integer>>() {
    			@Override
    			public List<Integer> call() throws Exception {
    				MessageDigest md = null;
    				try {
    					md = MessageDigest.getInstance(shardedCSTConfig.hashAlgo);
    				} catch (NoSuchAlgorithmException e) {
    					e.printStackTrace();
    				}

    				List<Integer> faultyPages = new LinkedList<>();

    				ShardedCSTState state = firstReceivedStates.get(((ShardedCSTState)lowerState).getReplicaID());
    				MerkleTree mt = state.getMerkleTree();
    				List<TreeNode> nodes = mt.getLeafs();
    				
    				byte[] data = lowerState.getSerializedState();
    				
    				logger.debug("DATA : " + data);
    				logger.debug("DATA : " + data);
    				logger.debug("DATA : " + data);
    				logger.debug("DATA : " + data.length);
    				logger.debug("DATA : " + data.length);
    				logger.debug("DATA : " + data.length);

    				Integer[] shards = shardedCSTConfig.getCommonShards();
    	    		int comm_count = third - nonCommon_size;
    	    		//lowerLog

    				int count = 0;
    	    		for(int i = comm_count; i < (comm_count+third) ; i++, count++) {
		    			try {
		    				md.reset();
	    	    			md.update(data, count * shardSize, shardSize);
		    			} catch (Exception e) {
		    				e.printStackTrace();
		    			}

    					if(!Arrays.equals(md.digest(), nodes.get(count).digest())) {
    						logger.debug("Faulty shard detected {} from Replica {}", shards[i], state.getReplicaID());
    						faultyPages.add(shards[i]);
    						break;
    					}
    	    		}
    	    		return faultyPages;

    			}
    		});
    		
    		waitingTasks[2] = executorService.submit(new Callable<List<Integer>>() {
    			@Override
    			public List<Integer> call() throws Exception {
    				MessageDigest md = null;
    				try {
    					md = MessageDigest.getInstance(shardedCSTConfig.hashAlgo);
    				} catch (NoSuchAlgorithmException e) {
    					e.printStackTrace();
    				}

    	    		//upperLog
    				List<Integer> faultyPages = new LinkedList<>();

    				ShardedCSTState state = firstReceivedStates.get(((ShardedCSTState)upperState).getReplicaID());
    				MerkleTree mt = state.getMerkleTree();
    				List<TreeNode> nodes = mt.getLeafs();
    				
    				byte[] data = upperState.getSerializedState();

    				logger.debug("DATA : " + data);
    				logger.debug("DATA : " + data);
    				logger.debug("DATA : " + data);
    				logger.debug("DATA : " + data.length);
    				logger.debug("DATA : " + data.length);
    				logger.debug("DATA : " + data.length);

    				Integer[] shards = shardedCSTConfig.getCommonShards();
    	    		int comm_count = third - nonCommon_size;

    				int size = (common_size) - (comm_count+third);
    	    		int count = 0;
    	    		for(int i = (comm_count+third) ; i < (comm_count+third+size) ; i++, count++) {
		    			try {
		    				md.reset();
	    	    			md.update(data, count * shardSize, shardSize);
		    			} catch (Exception e) {
		    				e.printStackTrace();
		    			}
    					if(!Arrays.equals(md.digest(), nodes.get(count).digest())) {
    						logger.debug("Faulty shard detected {} from Replica {}", shards[i], state.getReplicaID());
    						faultyPages.add(shards[i]);
    						break;
    					}
    	    		}

    	    		return faultyPages;

    			}
    		});
    		
        	try {
				faultyPages.addAll(waitingTasks[0].get());
				faultyPages.addAll(waitingTasks[1].get());
				faultyPages.addAll(waitingTasks[2].get());
			} catch (InterruptedException e) {
				e.printStackTrace();
			} catch (ExecutionException e) {
				e.printStackTrace();
			}
    	}
    	else {
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
    			try {
	    			md.update(data, i * shardSize, shardSize);
    			} catch (Exception e) {
//	    				e.printStackTrace();
    			}

				if(!Arrays.equals(md.digest(), nodes.get(shards[i]).digest())) {
//					logger.info("Faulty shard detected {} from Replica {}", shards[i], state.getReplicaID());
					faultyPages.add(shards[i]);
				}
			}
	
			shards = this.shardedCSTConfig.getCommonShards();

			state = firstReceivedStates.get(((ShardedCSTState)lowerState).getReplicaID());
			mt = state.getMerkleTree();
			nodes = mt.getLeafs();
			data = lowerState.getSerializedState();
			
	        int half;
			if(common_size%2 == 1)
				half = ((common_size+1)/2);
			else 
				half = (common_size/2);
	        

			for(int i = 0; i < half; i++) {
    			try {
	    			md.update(data, i * shardSize, shardSize);
    			} catch (Exception e) {
//	    				e.printStackTrace();
    			}

				if(!Arrays.equals(md.digest(), nodes.get(i).digest())) {
//					logger.info("Faulty shard detected {} from Replica {}", shards[i], state.getReplicaID());
					faultyPages.add(shards[i]);
				}
			}
	
			
			state = firstReceivedStates.get(((ShardedCSTState)upperState).getReplicaID());
			mt = state.getMerkleTree();
			nodes = mt.getLeafs();
			data = upperState.getSerializedState();
	
			for(int i = 0; i < half; i++) {
    			try {
	    			md.update(data, i * shardSize, shardSize);
    			} catch (Exception e) {
//	    				e.printStackTrace();
    			}

				if(!Arrays.equals(md.digest(), nodes.get(half+i).digest())) {
//					logger.info("Faulty shard detected {} from Replica {}", shards[half+i], state.getReplicaID());
					faultyPages.add(shards[half+i]);
				}
			}

    	}
		Integer[] ret = faultyPages.toArray(new Integer[0]);
		stateTransferEndTime = System.currentTimeMillis();								
		System.out.println("State Transfer process AFTER DETECT FAULTY SHARDS!");
		System.out.println("Time: \t" + (stateTransferEndTime - stateTransferStartTime));
		return ret;
	}

	
	//this can be paralellized easily since there are no races
	ExecutorService executorService = Executors.newFixedThreadPool(3);
	Future<Boolean>[] waitingTasks = new Future[3];
	
	private ShardedCSTState rebuildCSTState(CSTState logLowerState, CSTState logUpperState, CSTState chkPntState) {
		logger.debug("rebuilding state");

		
		//TODO: current state should be copied directly into chkpntData
		// REMOVED SINCE IM NOT TREATING PREVIOUS EXISTING STATE
		// unecessary 2 arraycopies
//		byte[] currState = dt.getRecoverer().getState(this.lastCID, true).getSerializedState();
//		if(currState != null) {
//			int length = currState.length > rebuiltData.length ? rebuiltData.length : currState.length;
//			System.arraycopy(currState, 0, rebuiltData, 0, length);
//		}
		
		if(statePlusLower == null) {
				statePlusLower =  new ShardedCSTState(new byte[shardedCSTConfig.getShardCount() * shardedCSTConfig.getShardSize()],
						null,
						logLowerState.getLogLower(), ((ShardedCSTState)chkpntState).getLogLowerHash(), null, null,
						((ShardedCSTState)chkpntState).getCheckpointCID(), logUpperState.getCheckpointCID(), 
						SVController.getStaticConf().getProcessId(), ((ShardedCSTState)chkpntState).getHashAlgo(), ((ShardedCSTState)chkpntState).getShardSize(), false);
		}

		Integer[] noncommonShards = shardedCSTConfig.getNonCommonShards();
		Integer[] commonShards = shardedCSTConfig.getCommonShards();
		
		int shardSize = shardedCSTConfig.getShardSize();
        int nonCommon_size = noncommonShards.length;
        int common_size = commonShards.length;
        
        int third = (nonCommon_size+common_size)/3;
        
		byte[] logLowerSer = logLowerState.getSerializedState();
		byte[] logUpperSer = logUpperState.getSerializedState();
		byte[] chkpntSer = chkPntState.getSerializedState();

		if(nonCommon_size < third) {
    		int comm_count = third - nonCommon_size;
    		
    		//when sorted shards
    		//common chkpnt
    		waitingTasks[0] = executorService.submit(new Callable<Boolean>() {
    			@Override
    			public Boolean call() throws Exception {
    				System.arraycopy(chkpntSer, 0, statePlusLower.state, commonShards[0]*shardSize, shardSize);
    				System.arraycopy(chkpntSer, shardSize, statePlusLower.state, commonShards[1]*shardSize, (comm_count-1)*shardSize);
    				return true;
    			}
    			
    		});

    		//common lowerlog
    		waitingTasks[1] = executorService.submit(new Callable<Boolean>() {
    			@Override
    			public Boolean call() throws Exception {
    	    		//common lowerlog
    				System.arraycopy(logLowerSer, 0, statePlusLower.state, commonShards[comm_count]*shardSize, (third)*shardSize);
    				return true;
    			}
    			
    		});
    		//common upperlog
    		waitingTasks[2] = executorService.submit(new Callable<Boolean>() {
    			@Override
    			public Boolean call() throws Exception {
    	    		//common upperlog
    				System.arraycopy(logUpperSer, 0, statePlusLower.state, commonShards[comm_count+third]*shardSize, logUpperSer.length);
    				return true;
    			}
    			
    		});

    		//non common
    		for(int i = 0;i < noncommonShards.length; i++) {
    			try {
    				System.arraycopy(chkpntSer, (comm_count+i)*shardSize, statePlusLower.state, noncommonShards[i]*shardSize, shardSize);
    			} catch (Exception e) {
    				e.printStackTrace();
    				logger.error("Error copying received shard during state rebuild. IGNORING IT FOR NOW");
    			}
    		}

    		//when not sorted
    		/*
    		//non common
    		for(int i = 0;i < noncommonShards.length; i++) {
    			try {
    				System.arraycopy(chkpntSer, (comm_count+i)*shardSize, rebuiltData, noncommonShards[i]*shardSize, shardSize);
    			} catch (Exception e) {
    				e.printStackTrace();
    				logger.error("Error copying received shard during state rebuild. IGNORING IT FOR NOW");
    			}
    		}
    		
    		//common chkpnt
    		int start = 0;
    		int count = 1;
    		for(int i = 1; i < comm_count; i++) {
    			if(commonShards[i] == (commonShards[i-1]+1)) {
    				count ++;
    			}
    			else {
    				System.out.println("COPYING 0 : " + count + " shards");
    				System.out.println(i + " FROM : " + commonShards[start] + " TO : " + commonShards[i-1]);
    				System.arraycopy(chkpntSer, start*shardSize, rebuiltData, commonShards[start]*shardSize, count*shardSize);
    				start = i;
    				count = 1;
    			}
    		}
			System.out.println("COPYING 1 : " + count + " shards");
			System.out.println("FROM : " + commonShards[start] + " TO : " + commonShards[start+count-1]);
			System.arraycopy(chkpntSer, start*shardSize, rebuiltData, commonShards[start]*shardSize, count*shardSize);

    		//lowerLog
    		start = comm_count;
    		count = 1;
    		for(int i = 1; i < (third); i++) {
    			if(commonShards[i+comm_count] == (commonShards[comm_count+i-1]+1)) {
    				count ++;
    			}
    			else {
    				System.out.println("COPYING 2 : " + count + " shards");
    				System.out.println(i + " FROM : " + commonShards[start] + " TO : " + commonShards[start+count-1]);
    				System.arraycopy(logLowerSer, (start-comm_count)*shardSize, rebuiltData, commonShards[start]*shardSize, count*shardSize);
    				start = comm_count+i;
    				count = 1;
    			}
    		}

			System.out.println("COPYING 3 : " + count + " shards");
			System.out.println("FROM : " + commonShards[start] + " TO : " + commonShards[start+count-1]);
			System.arraycopy(logLowerSer, (start-comm_count)*shardSize, rebuiltData, commonShards[start]*shardSize, (count)*shardSize);
			
    		//upperLog
       		int size = (common_size) - (comm_count+third);
    		start = comm_count+third;
    		count = 1;
    		for(int i = 1; i < (size); i++) {
    			if(commonShards[i+comm_count+third] == (commonShards[comm_count+third+i-1]+1)) {
    				count ++;
    			}
    			else {
    				System.out.println("COPYING 4 : " + count + " shards");
    				System.out.println(i + " FROM : " + commonShards[start] + " TO : " + commonShards[start+count-1]);
    				System.arraycopy(logUpperSer, (start-(comm_count+third))*shardSize, rebuiltData, commonShards[start]*shardSize, count*shardSize);
    				start = comm_count+third+i;
    				count = 1;
    			}
    		}

			System.out.println("COPYING 5 : " + count + " shards");
			System.out.println("FROM : " + commonShards[start] + " TO : " + commonShards[start+count-1]);
			System.arraycopy(logUpperSer, (start-(comm_count+third))*shardSize, rebuiltData, commonShards[start]*shardSize, (count)*shardSize);
			*/
    	}
    	else {
            int half;
    		if(common_size%2 == 1)
    			half = ((common_size+1)/2);
    		else 
    			half = (common_size/2);

    		for(int i = 0;i < commonShards.length; i++) {
    			try {
    				if(i < half) {
    					System.arraycopy(logLowerSer, i*shardSize, statePlusLower.state, commonShards[i]*shardSize, shardSize);
    				}else {
    					System.arraycopy(logUpperSer, (i-half)*shardSize, statePlusLower.state, commonShards[i]*shardSize, shardSize);
    				}
    			} catch (Exception e) {
    				e.printStackTrace();
    				logger.error("Error copying shard during state rebuild. IGNORING IT FOR NOW");
    			}
    		}
    		
    		for(int i = 0;i < noncommonShards.length; i++) {
    			try {
    				System.arraycopy(chkpntSer, i*shardSize, statePlusLower.state, noncommonShards[i]*shardSize, shardSize);
    			} catch (Exception e) {
    				e.printStackTrace();
    				logger.error("Error copying received shard during state rebuild. IGNORING IT FOR NOW");
    			}
    		}
    	}
		
//		int i =rebuiltData.length-1;
//		for(; i > 0; i--) {
//			if(rebuiltData[i] != '\0')
//				break;
//		}
//		byte[] trimedData = new byte[i+1];
//		System.arraycopy(rebuiltData, 0, trimedData, 0, i+1);
		
//		if( i != chkpntData.length-1) {
//			byte[] trimedData = new byte[i+1];
//			System.arraycopy(chkpntData, 0, trimedData, 0, i+1);
//			if(statePlusLower == null)
//				return new ShardedCSTState(trimedData,
//						TOMUtil.getBytes(((ShardedCSTState)chkpntState).getSerializedState()),
//						stateLower.getLogLower(), ((ShardedCSTState)chkpntState).getLogLowerHash(), null, null,
//						((ShardedCSTState)chkpntState).getCheckpointCID(), stateUpper.getCheckpointCID(), SVController.getStaticConf().getProcessId(), ((ShardedCSTState)chkpntState).getHashAlgo(), ((ShardedCSTState)chkpntState).getShardSize(), false);
//			else {
//				statePlusLower.setSerializedState(trimedData);
//				return statePlusLower;
//			}
//		}
//		else {
		statePlusLower.setStateHash(TOMUtil.computeShardedHash(statePlusLower.state));
			return statePlusLower;
//		}	
	}
	
	private static AtomicBoolean CSTfence = new AtomicBoolean(false);
	
	@Override
	public void SMReplyDeliver(SMMessage msg, boolean isBFT) {
		logger.trace("");

		ShardedCSTSMMessage reply = (ShardedCSTSMMessage)msg;
		if (SVController.getStaticConf().isStateTransferEnabled()) {
			logger.debug("The state transfer protocol is enabled");
			logger.debug("Received a CSTMessage from {} ", reply.getSender());

			if (waitingCID != -1 && reply.getCID() == waitingCID) {
				receivedRegencies.put(reply.getSender(), reply.getRegency());
				receivedLeaders.put(reply.getSender(), reply.getLeader());
				receivedViews.put(reply.getSender(), reply.getView());

				InetSocketAddress address = reply.getCstConfig().getAddress();
				Socket clientSocket;
				ShardedCSTState stateReceived = null; //state transfer
				try {
					logger.debug("Opening connection to peer {} for requesting its Replica State", address);
					clientSocket = new Socket(address.getHostName(), address.getPort());
					ObjectInputStream in = new ObjectInputStream(clientSocket.getInputStream());
					stateReceived = (ShardedCSTState) in.readObject();
					in.close();
					clientSocket.close();
				} catch (Exception e) {
					e.printStackTrace();
					logger.error("Failed to transfer state", e);
					// TODO: flag that the transfer failed for repeating the transfer process
					return;
				}

				receivedStates.put(reply.getSender(), stateReceived);
				if (reply.getSender() == shardedCSTConfig.getCheckpointReplica()) {
					logger.debug("Received State from Checkpoint Replica\n");
					this.chkpntState = stateReceived;
				}
				if (reply.getSender() == shardedCSTConfig.getLogLower()) {
					logger.debug("Received State from Lower Log Replica\n");
					stateLower.set(stateReceived);
				}
				if (reply.getSender() == shardedCSTConfig.getLogUpper()) {
					logger.debug("Received State from Upper Log Replica\n");
					stateUpper.set(stateReceived);
				}

				if (receivedStates.size() == 3) {
					if(CSTfence.compareAndSet(false, true)) { // only one enters here
						lockTimer.lock();
						// wait for every response of every replica						
						// should use monitors
						
						logger.debug("Validating Received State\n");
						while(stateUpper.get() == null);
						CSTState upperState = stateUpper.get();
						
						CommandsInfo[] upperLog = upperState.getLogUpper();
						byte[] upperLogHash = CommandsInfo.computeHash(upperLog);

						while(stateLower.get() == null);
						CSTState lowerState = stateLower.get();

						boolean validState = false;
						if (reply.getCID() < SVController.getStaticConf().getGlobalCheckpointPeriod()) {
							validState = validatePreCSTState(lowerState, upperLog, upperLogHash);
						}
						else {

							CommandsInfo[] lowerLog = lowerState.getLogLower();
							byte[] lowerLogHash = CommandsInfo.computeHash(lowerLog);
	
							// validate lower log -> hash(lowerLog) == lowerLogHash
							if (Arrays.equals(((CSTState)chkpntState).getLogLowerHash(), lowerLogHash)) {
								validState = true;
								logger.debug("VALID Lower Log hash");
							} else {
								logger.debug("INVALID Lower Log hash");
							}
							// validate upper log -> hash(upperLog) == upperLogHash
							if (!Arrays.equals(((CSTState)chkpntState).getLogUpperHash(), upperLogHash) ) {
								validState = false;
								logger.debug("INVALID Upper Log hash");
							} else {
								logger.debug("VALID Upper Log hash");
							}
	
							if (validState) { // validate checkpoint

								stateTransferEndTime = System.currentTimeMillis();
								System.out.println("State Transfer process BEFORE statePlusLower/REBUILD!");
								System.out.println("Time: \t" + (stateTransferEndTime - stateTransferStartTime));
								
								statePlusLower = rebuildCSTState(lowerState, upperState, (CSTState)chkpntState);
								try {
									while(!(waitingTasks[0].get()&&waitingTasks[1].get()&&waitingTasks[2].get()));
								} catch (InterruptedException e) {
									e.printStackTrace();
								} catch (ExecutionException e) {
									e.printStackTrace();
								}
								stateTransferEndTime = System.currentTimeMillis();
								System.out.println("State Transfer process AFTER statePlusLower/REBUILD!");
								System.out.println("Time: \t" + (stateTransferEndTime - stateTransferStartTime));

								logger.debug("Intalling Checkpoint and replying Lower Log");
								logger.debug("Installing state plus lower \n" + statePlusLower);
								
								stateTransferEndTime = System.currentTimeMillis();
								System.out.println("State Transfer process BEFORE setState!");
								System.out.println("Time: \t" + (stateTransferEndTime - stateTransferStartTime));

								dt.getRecoverer().setState(statePlusLower);

								stateTransferEndTime = System.currentTimeMillis();								
								System.out.println("State Transfer process AFTER SET STATE!");
								System.out.println("Time: \t" + (stateTransferEndTime - stateTransferStartTime));

								//byte[] currentStateHash = ((DurabilityCoordinator) dt.getRecoverer()).getCurrentStateHash();
								byte[] currentStateHash = ((DurabilityCoordinator) dt.getRecoverer()).getCurrentShardedStateHash();
								if (!Arrays.equals(currentStateHash, upperState.getCheckpointHash())) {
									logger.debug("INVALID Checkpoint + Lower Log hash"); 
									validState = false;
								} else {
									logger.debug("VALID Checkpoint + Lower Log  hash");
								}
								stateTransferEndTime = System.currentTimeMillis();								
								System.out.println("State Transfer process AFTER VALIDATING STATE!");
								System.out.println("Time: \t" + (stateTransferEndTime - stateTransferStartTime));
							}
							else {
								logger.debug("Terminating transfer process due to faulty Lower and Upper Logs");
							}
						}
						
						int currentRegency = tomLayer.execManager.getCurrentLeader();;
						int currentLeader = tomLayer.getSynchronizer().getLCManager().getLastReg();
						View currentView = SVController.getCurrentView();
						CertifiedDecision currentProof = upperState.getCertifiedDecision(SVController);
						
						if (!appStateOnly) {
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
						}
														
						if (/*currentRegency > -1 &&*/ currentLeader > -1
								&& currentView != null && validState && (!isBFT || currentProof != null || appStateOnly)) {
							logger.debug("---- RECEIVED VALID STATE ----");

							stateTransferEndTime = System.currentTimeMillis();								
							System.out.println("State Transfer process BEFORE GET SYNCHRONIZER!");
							System.out.println("Time: \t" + (stateTransferEndTime - stateTransferStartTime));

							tomLayer.getSynchronizer().getLCManager().setLastReg(currentRegency);
							tomLayer.getSynchronizer().getLCManager().setNextReg(currentRegency);
							tomLayer.getSynchronizer().getLCManager().setNewLeader(currentLeader);
	
							stateTransferEndTime = System.currentTimeMillis();								
							System.out.println("State Transfer process AFTER GET SYNCHRONIZER!");
							System.out.println("Time: \t" + (stateTransferEndTime - stateTransferStartTime));

							tomLayer.execManager.setNewLeader(currentLeader);
	
							if (currentProof != null && !appStateOnly) {
								logger.debug("Trying to install proof for consensus " + waitingCID);
	
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
									//NOTE [JSoares]: if this happens shouldn't the transfer process stop????
									logger.debug("Failed to install proof for consensus " + waitingCID);
								}
							}
							
							stateTransferEndTime = System.currentTimeMillis();								
							System.out.println("State Transfer process AFTER PROOF!");
							System.out.println("Time: \t" + (stateTransferEndTime - stateTransferStartTime));

	
							// I might have timed out before invoking the state transfer, so
							// stop my re-transmission of STOP messages for all regencies up to the current one
							if (currentRegency > 0) {
								tomLayer.getSynchronizer().removeSTOPretransmissions(currentRegency - 1);
							}
	
							stateTransferEndTime = System.currentTimeMillis();
							System.out.println("State Transfer process before deliver lock acquire!");
							System.out.println("Time: \t" + (stateTransferEndTime - stateTransferStartTime));
							
							logger.debug("Trying to acquire deliverlock");
							dt.deliverLock();
							logger.debug("Successfuly acquired deliverlock");
							
							stateTransferEndTime = System.currentTimeMillis();
							System.out.println("State Transfer process after deliver lock acquire!");
							System.out.println("Time: \t" + (stateTransferEndTime - stateTransferStartTime));
	
							// this makes the isRetrievingState() evaluates to false
							waitingCID = -1;
							
							// JSoares Modified, since the state sent by the UpperLog replica contains checkpoint data 
							// and the original transfer process is not expecting it
							upperState.setSerializedState(null);
							
							logger.debug("Updating state with Upper Log operations");
							dt.update(upperState);
							
							stateTransferEndTime = System.currentTimeMillis();
							System.out.println("State Transfer process upperLog installed!");
							System.out.println("Time: \t" + (stateTransferEndTime - stateTransferStartTime));
							
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
	
							logger.debug("Processing out of context messages");
							tomLayer.processOutOfContext();
	
							if (SVController.getCurrentViewId() != currentView.getId()) {
								logger.info("Installing current view!");
								SVController.reconfigureTo(currentView);
							}
	
							isInitializing = false;
	
							dt.canDeliver();
							dt.deliverUnlock();
	
							logger.info("State Transfer process completed successfuly!");
							
							stateTransferEndTime = System.currentTimeMillis();
							System.out.println("State Transfer process completed successfuly!");
							System.out.println("Time: \t" + (stateTransferEndTime - stateTransferStartTime));
			
							reset(true);
							
							tomLayer.requestsTimer.Enabled(true);
							tomLayer.requestsTimer.startTimer();
	
							if (stateTimer != null) {
								stateTimer.cancel();
							}
	
							if (appStateOnly) {
								appStateOnly = false;
								tomLayer.getSynchronizer().resumeLC();
							}
							
							System.exit(1);
							
						} else if (chkpntState == null
								&& (SVController.getCurrentViewN() / 2) < getReplies()) {
							logger.debug("---- DIDNT RECEIVE STATE ----");
	
							waitingCID = -1;
							reset(false);
							if (appStateOnly) {
								requestState();
							}
							if (stateTimer != null) {
								stateTimer.cancel();
							}
						} else if (!validState) {
							logger.debug("---- RECEIVED INVALID STATE  ----");
	
							retries ++;
							if(retries < 3) {							
								Integer[] faultyShards = detectFaultyShards(lowerState, upperState, (CSTState) chkpntState);
								if(faultyShards.length == 0) { 
									logger.debug("Cannot detect faulty shards. Will restart protocol");
									reset(true);
	//								firstReceivedStates.clear();
	//								statePlusLower = null;
									requestState();
									if (stateTimer != null) {
										stateTimer.cancel();
									}
								}
								else {
									logger.debug("Retrying State Transfer for the {} time", retries);
									
	                                reset(false);
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
						lockTimer.unlock();
						CSTfence.set(false);
					}
				}
				else {
					// waiting for replies
				}
			}
			else {
				logger.info("Received unexpected state reply (discarding)");
			}
		}
	}
	
	public void reset(boolean full) {
		super.reset();
		if(full) {
			firstReceivedStates.clear();
			statePlusLower = null;
			this.chkpntState = null;
			stateLower.set(null);
			stateUpper.set(null);
		}
	}
}
