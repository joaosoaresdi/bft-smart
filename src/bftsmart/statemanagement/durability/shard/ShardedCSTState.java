package bftsmart.statemanagement.durability.shard;

import java.io.Serializable;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import bftsmart.statemanagement.ApplicationState;
import bftsmart.statemanagement.durability.CSTState;
import bftsmart.tom.server.defaultservices.CommandsInfo;
import merkletree.MerkleTree;

public class ShardedCSTState extends CSTState implements ApplicationState, Serializable {

	private static final long serialVersionUID = 4497568920284517001L;
	
	private MerkleTree chkPntMrklTree;
	private final String hashAlgo;
	private final int shardSize;
	
	public ShardedCSTState(byte[] state, byte[] hashCheckpoint, CommandsInfo[] logLower, byte[] hashLogLower,
			CommandsInfo[] logUpper, byte[] hashLogUpper, int checkpointCID, int currentCID, int pid, String hashAlgo, int shardSize, boolean buildMT) {
		
		super(state, hashCheckpoint, logLower, hashLogLower, logUpper, hashLogUpper, checkpointCID, currentCID, pid);
		this.hashAlgo = hashAlgo;
		this.shardSize = shardSize;
		// only build when necessary
		if(buildMT) { 
			try {
				chkPntMrklTree = MerkleTree.createTree(MessageDigest.getInstance(hashAlgo), shardSize, state);
			} catch (NoSuchAlgorithmException e) {
				e.printStackTrace();
			}
		}
	}

	@Override
	public String toString() {
		return "ShardedCSTState [logUpperhHash=" + logUpperhHash + ", logLowerHash="
				+ logLowerHash + ", checkpointHash=" + checkpointHash
				+ ", checkpointCID=" + checkpointCID + ", lastCID=" + lastCID + ", logUpper="
				+ logUpper + ", logLower=" + logLower + ", state="
				+ state + ", pid=" + pid + ", hashAlgo="
				+ hashAlgo + ", shardSize=" + shardSize + "]";
	}

	public int getReplicaID() {
		return pid;
	}
	
	public MerkleTree getMerkleTree() {
		return chkPntMrklTree;
	}

	public String getHashAlgo() {
		return hashAlgo;
	}

	public int getShardSize() {
		return shardSize;
	}
	
	public void setStateHash(byte[] hash) {
		this.checkpointHash = hash;
	}

}
