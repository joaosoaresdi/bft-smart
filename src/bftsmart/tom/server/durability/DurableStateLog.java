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
package bftsmart.tom.server.durability;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

import bftsmart.statemanagement.durability.CSTRequest;
import bftsmart.statemanagement.durability.CSTRequestF1;
import bftsmart.statemanagement.durability.CSTState;
import bftsmart.statemanagement.durability.shard.ShardedCSTRequest;
import bftsmart.statemanagement.durability.shard.ShardedCSTState;
import bftsmart.tom.MessageContext;
import bftsmart.tom.server.defaultservices.CommandsInfo;
import bftsmart.tom.server.defaultservices.FileRecoverer;
import bftsmart.tom.server.defaultservices.StateLog;
import bftsmart.tom.util.TOMUtil;

public class DurableStateLog extends StateLog {

    private int id;
    public final static String DEFAULT_DIR = "files".concat(System.getProperty("file.separator"));
    private static final int INT_BYTE_SIZE = 4;
    private static final int EOF = 0;

    private RandomAccessFile log;
    // Temporary log file to log all commands until the the number of consensus executed reaches
    // the global checkpoint period
    private RandomAccessFile logBeforeGlobal;
    private boolean syncLog;
    private String logPath;
    private String logBeforeGlobalPath;
    private String lastCkpPath;
    private boolean syncCkp;
    private boolean isToLog;
    private ReentrantLock checkpointLock = new ReentrantLock();
    private Map<Integer, Long> logPointers;
    private FileRecoverer fr;
    
    public DurableStateLog(int id, byte[] initialState, byte[] initialHash,
            boolean isToLog, boolean syncLog, boolean syncCkp) {
        super(id, initialState, initialHash);
        this.id = id;
        this.isToLog = isToLog;
        this.syncLog = syncLog;
        this.syncCkp = syncCkp;
        this.logPointers = new HashMap<Integer, Long>();
        this.fr = new FileRecoverer(id, DEFAULT_DIR);
    }

    private void createLogFile() {
        logPath = DEFAULT_DIR + String.valueOf(id) + "." + System.currentTimeMillis() + ".log";
        try {
            log = new RandomAccessFile(logPath, (syncLog ? "rwd" : "rw"));
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        }
    }

    private void createBeforeGlobalLogFile() {
        logBeforeGlobalPath = DEFAULT_DIR + String.valueOf(id) + "." + System.currentTimeMillis() + "1st.log";
        try {
            logBeforeGlobal = new RandomAccessFile(logBeforeGlobalPath, (syncLog ? "rwd" : "rw"));
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        }
    }
    
    /**
     * Adds a message batch to the log. This batches should be added to the log
     * in the same order in which they are delivered to the application. Only
     * the 'k' batches received after the last checkpoint are supposed to be
     * kept
     * @param commands The batch of messages to be kept.
     * @param msgCtx
     * @param consensusId the consensus id added to the batch
     */
    @Override
    public void addMessageBatch(byte[][] commands, MessageContext[] msgCtx, int consensusId) {
        CommandsInfo command = new CommandsInfo(commands, msgCtx);
        if (isToLog) {
            if(log == null) {
                createLogFile();
            }
            writeCommandToDisk(command, consensusId);
        }
    }
    
    private void writeCommandToDisk(CommandsInfo commandsInfo, int consensusId) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try {
            ObjectOutputStream oos = new ObjectOutputStream(bos);
            oos.writeObject(commandsInfo);
            oos.flush();

            byte[] batchBytes = bos.toByteArray();

            ByteBuffer bf = ByteBuffer.allocate(3 * INT_BYTE_SIZE
                    + batchBytes.length);
            bf.putInt(batchBytes.length);
            bf.put(batchBytes);
            bf.putInt(EOF);
            bf.putInt(consensusId);
            
            log.write(bf.array());
            log.seek(log.length() - 2 * INT_BYTE_SIZE);// Next write will overwrite the EOF mark
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    
    public void addMessageBatchBeforeGlobal(byte[][] commands, MessageContext[] msgCtx, int consensusId) {
        CommandsInfo command = new CommandsInfo(commands, msgCtx);
        if (isToLog) {
            if (logBeforeGlobal == null) {
                createBeforeGlobalLogFile();
            }
            writeCommandToDiskBeforeGlobal(command, consensusId);
        }
    }
    
    private void writeCommandToDiskBeforeGlobal(CommandsInfo commandsInfo, int consensusId) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try {
            ObjectOutputStream oos = new ObjectOutputStream(bos);
            oos.writeObject(commandsInfo);
            oos.flush();

            byte[] batchBytes = bos.toByteArray();

            ByteBuffer bf = ByteBuffer.allocate(3 * INT_BYTE_SIZE + batchBytes.length);
            bf.putInt(batchBytes.length);
            bf.put(batchBytes);
            bf.putInt(EOF);
            bf.putInt(consensusId);
            
            // writes the commands to the initial log before global
            logBeforeGlobal.write(bf.array());
            logBeforeGlobal.seek(logBeforeGlobal.length() - 2 * INT_BYTE_SIZE);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    
    @Override
    public void newCheckpoint(byte[] state, byte[] stateHash, int consensusId) {
        String ckpPath = DEFAULT_DIR + String.valueOf(id) + "."    + System.currentTimeMillis() + ".tmp";
        try {
            checkpointLock.lock();
            RandomAccessFile ckp = new RandomAccessFile(ckpPath, (syncCkp ? "rwd" : "rw"));
             
            System.out.println("ALOCCATING " + (state.length + stateHash.length + 4 * INT_BYTE_SIZE) + " BYTES FOR CHKPNT BUFFER");
            System.out.println("STATE.length " + (state.length) + " BYTES");
            System.out.println("stateHash.length " + (stateHash.length) + " BYTES");
            System.out.println("stateHash.length " + ((long)stateHash.length) + " BYTES");
            System.out.println("STATE.length +  stateHash.length" + (state.length + stateHash.length) + " BYTES");
            System.out.println("STATE.length +  stateHash.length" + (long)((long)state.length + (long)stateHash.length) + " BYTES");
            System.out.println("STATE.length +  stateHash.length + 4 * INT_BYTE_SIZE" + (state.length + stateHash.length + (4 * INT_BYTE_SIZE)) + " BYTES");
            ByteBuffer bf = ByteBuffer.allocate((state.length + stateHash.length + (4 * INT_BYTE_SIZE)));
            bf.putInt(state.length);
            bf.put(state);
            bf.putInt(stateHash.length);
            bf.put(stateHash);
            bf.putInt(EOF);
            bf.putInt(consensusId);

            byte[] ckpState = bf.array();
            
            ckp.write(ckpState);
            ckp.close();

            if (isToLog) {
                deleteLogFile();
            }
            deleteLastCkp();
            renameCkp(ckpPath);
            if (isToLog) {
                createLogFile();
            }
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            checkpointLock.unlock();
        }
    }

    private void renameCkp(String ckpPath) {
        String finalCkpPath = ckpPath.replace(".tmp", ".ckp");
        new File(ckpPath).renameTo(new File(finalCkpPath));
        lastCkpPath = finalCkpPath;
    }

    private void deleteLastCkp() {
        if (lastCkpPath != null)
            new File(lastCkpPath).delete();
    }

    private void deleteLogFile() {
        try {
            if(log != null) {
                log.close();
            }
            if (logPath != null) {
                File logFile = new File(logPath);
                if (logFile.exists()) {
                    logFile.delete();
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    
    //Added by JSoares
    public CSTState getState(CSTRequest cstRequest) {
    	if(cstRequest instanceof ShardedCSTRequest)
    		return getShardedState((ShardedCSTRequest)cstRequest);
    	else
    		return getOriginalState(cstRequest);
    }
    
    //Added by JSoares
    // TODO: increase efficiency (do not use mem copy use start end indexes)
    private CSTState getShardedState(ShardedCSTRequest cstRequest) {
        int lastCheckpointCID = getLastCheckpointCID();
        int lastCID = getLastCID(); 
        
		Integer[] noncommonShards = cstRequest.getNonCommonShards();
		Integer[] commonShards = cstRequest.getCommonShards();

		int shardSize = cstRequest.getShardSize();
		int nonCommon_size = cstRequest.getNonCommonShards().length;
        int common_size = cstRequest.getCommonShards().length;
        
        int third = (nonCommon_size+common_size)/3;
        int half;
		if(common_size%2 == 1)
			half = ((common_size+1)/2);
		else 
			half = (common_size/2);
        
		int comm_count = third - nonCommon_size;
		System.out.println("THIRD : " + third);
		System.out.println("HALF : " + half);
		System.out.println("comm_count : " + comm_count);

		if(id == cstRequest.getCheckpointReplica()) {
            // This replica is expected to send the checkpoint plus the hashes of lower and upper log portions
    		checkpointLock.lock();
            byte[] ckpState = fr.getCkpState(lastCkpPath);
            checkpointLock.unlock(); 

            byte[] data;
        	if(nonCommon_size < third) {
        		data = new byte[third*shardSize];

    			System.arraycopy(ckpState, commonShards[0]*shardSize, data, 0 , shardSize);
    			System.arraycopy(ckpState, commonShards[1]*shardSize, data, shardSize, (comm_count-1)*shardSize);

				int length = shardSize;
        		for(int i = 0;i < noncommonShards.length; i++) {
        			System.out.println(noncommonShards[i]);
        			try {
        				if(ckpState.length < ((noncommonShards[i]+1)*shardSize))
        					length = ckpState.length - ((noncommonShards[i])*shardSize);
        				System.arraycopy(ckpState, noncommonShards[i]*shardSize, data, (comm_count+i)*shardSize, length);
        			} catch (Exception e) {
        				e.printStackTrace();
        				System.out.println(i);
        				System.out.println(ckpState.length);
        				System.out.println(noncommonShards[i]);
        				System.out.println(noncommonShards[i]*shardSize);
        				System.out.println(data.length);
        				System.out.println((comm_count+i));
        				System.out.println((comm_count+i)*shardSize);
        				System.out.println(length);
        			}
        		}
        	}
        	else {
        		data = new byte[nonCommon_size*shardSize];
        		for(int i = 0;i < noncommonShards.length; i++) {
    				System.arraycopy(ckpState, noncommonShards[i]*shardSize, data, i*shardSize, shardSize);
        		}
        	}
        	
            CommandsInfo[] logLower = fr.getLogState(cstRequest.getLogLowerSize(), logPath);
            CommandsInfo[] logUpper = fr.getLogState(logPointers.get(cstRequest.getLogUpper()), 0, cstRequest.getLogUpperSize(), logPath);

            byte[] logLowerHash = CommandsInfo.computeHash(logLower);
            byte[] logUpperHash = CommandsInfo.computeHash(logUpper);
            
            ShardedCSTState cstState = new ShardedCSTState(data, null, null, logLowerHash, null, logUpperHash, lastCheckpointCID, lastCID, this.id, cstRequest.getHashAlgo(), cstRequest.getShardSize(), false);
            return cstState;
        } else if(id == cstRequest.getLogLower()) {
            // This replica is expected to send the lower part of the log and a subset of common shards; [0, (length/2)]
    		checkpointLock.lock();
            byte[] ckpState = fr.getCkpState(lastCkpPath);
            checkpointLock.unlock();
            byte[] data;
        	if(nonCommon_size < third) {
                data = new byte[third*shardSize];
    			System.arraycopy(ckpState, commonShards[comm_count]*shardSize, data, 0, third*shardSize);
        	}
        	else {
                data = new byte[half*shardSize];
        		for(int i = 0;i < half; i++) {
					System.arraycopy(ckpState, commonShards[i]*shardSize, data, i*shardSize, shardSize);
        		}
        	}

            CommandsInfo[] logLower = fr.getLogState(logPointers.get(cstRequest.getCheckpointReplica()), 0, cstRequest.getLogLowerSize(), logPath);
            ShardedCSTState cstState = new ShardedCSTState(data, null, logLower, null, null, null, lastCheckpointCID, lastCID, this.id, cstRequest.getHashAlgo(), cstRequest.getShardSize(), false);
            return cstState;
        } else { //upperLog
            // This replica is expected to send the upper part of the log; the hash for its checkpoint; and the second half of shared shards; [length/2, length]
        	checkpointLock.lock();
            fr.recoverCkpHash(lastCkpPath);
            byte[] ckpHash = fr.getCkpStateHash();
            byte[] ckpState = fr.getCkpState(lastCkpPath);
            checkpointLock.unlock();
        	
            byte[] data;
        	if(nonCommon_size < third) {
        		int start = (comm_count + third);
        		int size = common_size-start;
                data = new byte[size*shardSize];
    			System.arraycopy(ckpState, commonShards[start]*shardSize, data, 0 ,size*shardSize);
        	}
        	else {
                data = new byte[half*shardSize];
        		for(int i = 0;i < half; i++) {
					System.arraycopy(ckpState, commonShards[i]*shardSize, data, i*shardSize, shardSize);
        		}
        	}
            CommandsInfo[] logUpper = fr.getLogState(cstRequest.getLogUpperSize(), logPath);

            int lastCIDInState = lastCheckpointCID + cstRequest.getLogUpperSize();
//            ShardedCSTState cstState = new ShardedCSTState(data, ckpHash, null, null, logUpper, null, lastCheckpointCID, lastCIDInState, this.id, cstRequest.getHashAlgo(), cstRequest.getShardSize(), false);
            ShardedCSTState cstState = new ShardedCSTState(null, ckpHash, null, null, logUpper, null, lastCheckpointCID, lastCIDInState, this.id, cstRequest.getHashAlgo(), cstRequest.getShardSize(), false);
            return cstState;
        }
    }


    private CSTState getOriginalState(CSTRequest cstRequest) {
        
    	int cid = cstRequest.getCID();
        int lastCheckpointCID = getLastCheckpointCID();
        int lastCID = getLastCID();
        System.out.println("LAST CKP CID = " + lastCheckpointCID);
        System.out.println("CID = " + cid);
        System.out.println("LAST CID = " + lastCID);
        System.out.println(((CSTRequestF1)cstRequest).getLogLowerSize());
        System.out.println(((CSTRequestF1)cstRequest).getLogUpperSize());
        
        if(cstRequest instanceof CSTRequestF1) {
            CSTRequestF1 requestF1 = (CSTRequestF1)cstRequest;
            if(id == requestF1.getCheckpointReplica()) {
                // This replica is expected to send the checkpoint plus the hashes of lower and upper log portions
                checkpointLock.lock();
                byte[] ckpState = fr.getCkpState(lastCkpPath);
                checkpointLock.unlock();
                CommandsInfo[] logLower = fr.getLogState(requestF1.getLogLowerSize(), logPath);
                CommandsInfo[] logUpper = fr.getLogState(logPointers.get(requestF1.getLogUpper()), 0, requestF1.getLogUpperSize(), logPath);
                byte[] logLowerHash = CommandsInfo.computeHash(logLower);
                byte[] logUpperHash = CommandsInfo.computeHash(logUpper);
                CSTState cstState = new CSTState(ckpState, null, null, logLowerHash, null, logUpperHash, lastCheckpointCID, lastCID, this.id);
                
                if(this.id == 1)
                    cstState = new CSTState(new byte[ckpState.clone().length], null, null, logLowerHash, null, logUpperHash, lastCheckpointCID, lastCID, this.id);
                System.out.println("--- sending chkpnt: " + id);
                
                return cstState;                
            } else if(id == requestF1.getLogLower()) {
                // This replica is expected to send the lower part of the log
                System.out.println("--- sending lower log: " + id);
                CommandsInfo[] logLower = fr.getLogState(logPointers.get(requestF1.getCheckpointReplica()), 0, requestF1.getLogLowerSize(), logPath);
                CSTState cstState = new CSTState(null, null, logLower, null, null, null, lastCheckpointCID, lastCID, this.id);
                return cstState;
            } else {
                // This replica is expected to send the upper part of the log plus the hash for its checkpoint
                System.out.println("--- sending upper log: " + id);
                checkpointLock.lock();
                fr.recoverCkpHash(lastCkpPath);
                byte[] ckpHash = fr.getCkpStateHash();
                checkpointLock.unlock();
                
                CommandsInfo[] logUpper = fr.getLogState(requestF1.getLogUpperSize(), logPath);
                
                int lastCIDInState = lastCheckpointCID + requestF1.getLogUpperSize();
                CSTState cstState = new CSTState(null, ckpHash, null, null, logUpper, null, lastCheckpointCID, lastCIDInState, this.id);
                return cstState;
            }
        }
        return null;
    }
    
    // modified by JSoares
    public CSTState getStateBeforeGlobal(CSTRequestF1 request) {
    	if(request instanceof ShardedCSTRequest)
    		return getShardedStateBeforeGlobal((ShardedCSTRequest)request);
    	else { // original Implementation
	        int cid = request.getCID();
	        int lastCheckpointCID = getLastCheckpointCID();
	        int lastCID = getLastCID();
	        
	        CommandsInfo[] commands = fr.getLogState(0, 0, request.getLogUpperSize(), logBeforeGlobalPath);
	        CSTState cstState;
	        byte[] logBytes = TOMUtil.getBytes(commands);

	        if(id == request.getLogUpper()) {
	//            cstState = new CSTState(null, null, null, null, commands, null, lastCheckpointCID, lastCID, this.id);
	            cstState = new CSTState(null, null, null, null, commands, null, -1, cid, this.id);
	        }
	        else {
	            byte[] logHash = CommandsInfo.computeHash(commands);
	//            cstState = new CSTState(null, null, null, null, null, logHash, lastCheckpointCID, lastCID, this.id);
	            cstState = new CSTState(null, null, null, null, null, logHash, -1, cid, this.id);
	        }
	        return cstState;
    	}
    }
    
    public void transferApplicationState(SocketChannel sChannel, int cid) {
        fr.transferCkpState(sChannel, lastCkpPath);
        
//        int lastCheckpointCID = getLastCheckpointCID();
//        int lastCID = getLastCID();
//        if (cid >= lastCheckpointCID && cid <= lastCID) {
//            int size = cid - lastCheckpointCID;
//            fr.transferLog(sChannel, size);
//        }
    }

    public void setLastCID(int cid, int checkpointPeriod, int checkpointPortion) {
        super.setLastCID(cid);
        // save the file pointer to retrieve log information later
        if((cid % checkpointPeriod) % checkpointPortion == checkpointPortion -1) {
            int ckpReplicaIndex = (((cid % checkpointPeriod) + 1) / checkpointPortion) -1;
            try {
                logPointers.put(ckpReplicaIndex, log.getFilePointer());
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * Updates this log, according to the information contained in the
     * TransferableState object
     * 
     * @param state
     * @param transState TransferableState object containing the information which is
     * used to updated this log
     */
    public void update(CSTState state) {
        newCheckpoint(state.getSerializedState(), state.getStateHash(), state.getCheckpointCID());
        setLastCheckpointCID(state.getCheckpointCID());
    }

    protected CSTState loadDurableState() {
        FileRecoverer fr = new FileRecoverer(id, DEFAULT_DIR);
        lastCkpPath = fr.getLatestFile(".ckp");
        logPath = fr.getLatestFile(".log");
        byte[] checkpoint = null;
        if(lastCkpPath != null)
            checkpoint = fr.getCkpState(lastCkpPath);
        CommandsInfo[] log = null;
        if(logPath !=null)
            log = fr.getLogState(0, logPath);
        int ckpLastConsensusId = fr.getCkpLastConsensusId();
        int logLastConsensusId = fr.getLogLastConsensusId();
        CSTState cstState = new CSTState(checkpoint, fr.getCkpStateHash(), log, null,
                null, null, ckpLastConsensusId, logLastConsensusId, this.id);
        if(logLastConsensusId > ckpLastConsensusId) {
            super.setLastCID(logLastConsensusId);
        } else
            super.setLastCID(ckpLastConsensusId);
        super.setLastCheckpointCID(ckpLastConsensusId);
        return cstState;
    }

    //Added by JSoares
    
//	public ShardedCSTState(byte[] state, byte[] hashCheckpoint, CommandsInfo[] logLower, byte[] hashLogLower,
//	CommandsInfo[] logUpper, byte[] hashLogUpper, int checkpointCID, int currentCID, int pid, String hashAlgo, int shardSize) {

    public ShardedCSTState buildCurrentState(int lastConsensusID, String mrklTreeHashAlgo, int shardSize) {
        FileRecoverer fr = new FileRecoverer(id, DEFAULT_DIR);
        lastCkpPath = fr.getLatestFile(".ckp");
        byte[] checkpoint = null;
        if(lastCkpPath != null)
            checkpoint = fr.getCkpState(lastCkpPath);
        
        return new ShardedCSTState(checkpoint, null, null, null,
                null, null, getLastCheckpointCID(), lastConsensusID, this.id, mrklTreeHashAlgo, shardSize, true);
	}
    
    private ShardedCSTState getShardedStateBeforeGlobal(ShardedCSTRequest request) {
        int cid = request.getCID();
        
        CommandsInfo[] commands = fr.getLogState(0, 0, request.getLogUpperSize(), logBeforeGlobalPath);
        ShardedCSTState cstState;
        if(id == request.getLogUpper()) {
            cstState = new ShardedCSTState(null, null, null, null, commands, null, -1, cid, this.id, request.getHashAlgo(), request.getShardSize(), false);
        }
        else {
            byte[] logHash = CommandsInfo.computeHash(commands);
            cstState = new ShardedCSTState(null, null, null, null, null, logHash, -1, cid, this.id, request.getHashAlgo(), request.getShardSize(), false);
        }
        return cstState;
    }
}
