package bftsmart.statemanagement.durability.shard;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

import bftsmart.statemanagement.durability.CSTRequestF1;
import ch.qos.logback.classic.Logger;
import merkletree.MerkleTree;

public class ShardedCSTRequest extends CSTRequestF1 {

	private static final long serialVersionUID = -5984506128894126706L;
	
	protected Integer[] commonShards;
	protected Integer[] nonCommonShards;
	
	protected int shardCount; // total number of chkpnt shards
	protected String hashAlgo;
	protected int shardSize;

	public ShardedCSTRequest(int cid, String hashAlgo, int shardSize) {
		super(cid);
		this.hashAlgo = hashAlgo;
		this.shardSize = shardSize;
	}

	@Override
	public String toString() {
		return "ShardedCSTRequest [logUpper=" + logUpper + ", logLower=" + logLower + ", ckpPeriod=" + ckpPeriod
				+ ", logUpperSize=" + logUpperSize + ", logLowerSize=" + logLowerSize + ", address=" + address
				+ ", cid=" + cid + ", checkpointReplica=" + checkpointReplica + ",\n commonShards="
				+ commonShards.length + ",\n nonCommonShards.length=" + nonCommonShards.length
				+ ",\n shardCount=" + shardCount + ", hashAlgo=" + hashAlgo + ", shardSize=" + shardSize + "]";
	}


	public Integer[] getCommonShards() {
		return commonShards;
	}

	public Integer[] getNonCommonShards() {
		return nonCommonShards;
	}

	public int getShardCount() {
		return shardCount;
	}

	public void setCommonShards(Integer[] commonShards) {
		this.commonShards = commonShards;
	}

	public void setNonCommonShards(Integer[] nonCommonShards) {
		this.nonCommonShards = nonCommonShards;
	}

	public void setShardCount(int shardCount) {
		this.shardCount = shardCount;
	}

	public void setHashAlgo(String hashAlgo) {
		this.hashAlgo = hashAlgo;
	}

	public void setShardSize(int shardSize) {
		this.shardSize = shardSize;
	}

	public String getHashAlgo() {
		return hashAlgo;
	}
	
	public int getShardSize() {
		return shardSize;
	}
	
	/*
	 * logLower sends first half of common shards/chunks/pages
	 * logUpper sends second half of common shards/chunks/pages
	 * checkpointReplica sends non-common shards/chunks/pages
	 */
	@Override
	public void defineReplicas(int[] otherReplicas, int globalCkpPeriod, int me) {    	
    	int N = otherReplicas.length + 1; // The total number of replicas is the others plus me 
    	ckpPeriod = globalCkpPeriod / N;
    	// case of recovering from a crash before the occurrence of a checkpoint
    	if (cid < globalCkpPeriod) {
    		logUpper = otherReplicas[0];
    		logLower = otherReplicas[1];
    		checkpointReplica = otherReplicas[2];
    		logUpperSize = cid + 1;
    	} else {
    		logLowerSize = ckpPeriod;
    		logUpperSize = (cid + 1) % ckpPeriod;
    		// position of the replica with the oldest checkpoint in the others array
    		int oldestReplicaPosition = getOldest(otherReplicas, cid, globalCkpPeriod, me);
    		logUpper = otherReplicas[(oldestReplicaPosition + 2) % otherReplicas.length];
    		logLower = otherReplicas[oldestReplicaPosition];
    		checkpointReplica = otherReplicas[(oldestReplicaPosition + 1) % otherReplicas.length];
    	}
    }
	
	//defines the set of common shards between all replicas and defines which are assigned to each replica
	public void assignShards(HashMap<Integer, ShardedCSTState> firstReceivedStates, byte[] localState) throws Exception {
		MessageDigest md = null;
		try {
			md = MessageDigest.getInstance(hashAlgo);
		} catch (NoSuchAlgorithmException e) {
			e.printStackTrace();
		}
		MerkleTree localStateMT = MerkleTree.createTree(md, shardSize, localState);
		
		ShardedCSTState chkpntState = firstReceivedStates.get(checkpointReplica);
		ShardedCSTState upperLogState = firstReceivedStates.get(logUpper);
		ShardedCSTState lowerLogState = firstReceivedStates.get(logLower);
		if(chkpntState == null || upperLogState == null || lowerLogState == null) {
			System.out.println(this.getClass().getName() + ".assignShards: PANIC!!!!");
			System.out.println(this.getClass().getName() + ".assignShards: PANIC!!!!");
			System.out.println(this.getClass().getName() + ".assignShards: PANIC!!!!");
			System.out.println(this.getClass().getName() + ".assignShards: PANIC!!!!");
			System.out.println(this.getClass().getName() + ".assignShards: PANIC!!!!");
			throw new Exception("chkpntState == null || upperLogState == null || lowerLogState == null");
		}
	
		MerkleTree chkpntMT = chkpntState.getMerkleTree();
		MerkleTree upperLogMT = upperLogState.getMerkleTree();
		MerkleTree lowerLogtMT = lowerLogState.getMerkleTree();

		this.shardCount = chkpntMT.getLeafCount();		

		//Common shards between other replicas
		HashSet<Integer> commonShards = new HashSet<>();
		commonShards.addAll(chkpntMT.getEqualPageIndexs(upperLogMT));
		commonShards.retainAll(chkpntMT.getEqualPageIndexs(lowerLogtMT));
		
		Integer[] shards = new Integer[this.shardCount];
		for(int i = 0;i < shardCount; i++)
			shards[i] = i;
		HashSet<Integer> nonCommonShards = new HashSet<Integer>(Arrays.asList(shards));
		nonCommonShards.removeAll(commonShards);
		this.nonCommonShards = nonCommonShards.toArray(new Integer[0]);

		commonShards.removeAll(localStateMT.getEqualPageIndexs(upperLogMT));
		commonShards.removeAll(localStateMT.getEqualPageIndexs(lowerLogtMT));
		this.commonShards = commonShards.toArray(new Integer[0]);

	}

	public void reAssignShards(Integer[] faultyShards) {
		if(Arrays.asList(commonShards).containsAll(Arrays.asList(faultyShards))) {
			nonCommonShards = faultyShards;
			commonShards = new Integer[0];
		}
		else if(Arrays.asList(nonCommonShards).containsAll(Arrays.asList(faultyShards))) {
			commonShards = faultyShards;
			nonCommonShards = new Integer[0];
		} else {
			List<Integer> new_common = new LinkedList<>();
			List<Integer> new_non_common = new LinkedList<>();
			List<Integer> commonShards = Arrays.asList(this.commonShards);
			List<Integer> nonCommonShards = Arrays.asList(this.nonCommonShards);
			for(Integer shard: faultyShards) {
				if(commonShards.contains(shard))
					new_non_common.add(shard);
				if(nonCommonShards.contains(shard))
					new_common.add(shard);
			}
			this.commonShards = new_common.toArray(new Integer[0]);
			this.nonCommonShards = new_non_common.toArray(new Integer[0]);
		}
	}

}
