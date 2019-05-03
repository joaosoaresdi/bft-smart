package bftsmart.statemanagement.durability.shard;

import java.util.Arrays;
import java.util.HashSet;
import java.util.concurrent.ConcurrentHashMap;

import bftsmart.statemanagement.durability.CSTRequestF1;
import merkletree.MerkleTree;

public class ShardedCSTRequest extends CSTRequestF1 {

	private static final long serialVersionUID = -5984506128894126706L;

	protected Integer[] commonShards;
	protected Integer[] nonCommonShards;
	
	private int[] upperReps;
	private int[] lowerReps;
	private int[] checkpointReps;

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
				+ commonShards.length + ",\n nonCommonShards.length=" + nonCommonShards.length + ",\n shardCount="
				+ shardCount + ", hashAlgo=" + hashAlgo + ", shardSize=" + shardSize + "]";
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
	
	public int inCheckpointReplicas(int id) {
		for(int i = 0; i < checkpointReps.length; i++) {
			if(checkpointReps[i] == id)
				return i;
		}
		return -1;
	}

	public int inLowerLogReplicas(int id) {
		for(int i = 0; i < lowerReps.length; i++) {
			if(lowerReps[i] == id)
				return i;
		}
		return -1;
	}

	public int inUpperLogReplicas(int id) {
		for(int i = 0; i < upperReps.length; i++) {
			if(upperReps[i] == id)
				return i;
		}
		return -1;
	}

	public int[] getUpperReplicas() {
		return upperReps;
	}

	public int[] getLowerReplicas() {
		return lowerReps;
	}

	public int[] getCheckpointReplicas() {
		return checkpointReps;
	}

	/*
	 * logLower sends first half of common shards/chunks/pages logUpper sends second
	 * half of common shards/chunks/pages checkpointReplica sends non-common
	 * shards/chunks/pages
	 */
	@Override
	public void defineReplicas(int[] otherReplicas, int globalCkpPeriod, int me) {
		int N = otherReplicas.length + 1; // The total number of replicas is the others plus me
		ckpPeriod = globalCkpPeriod / N;
		// case of recovering from a crash before the occurrence of a checkpoint
		// position of the replica with the oldest checkpoint in the others array
		int oldestReplicaPosition = getOldest(otherReplicas, cid, globalCkpPeriod, me);
		
		int repsPerGroup = otherReplicas.length / 3;
		upperReps = new int[repsPerGroup];
		lowerReps = new int[repsPerGroup];
		checkpointReps = new int[repsPerGroup];
		if (cid < globalCkpPeriod) {
			logUpperSize = cid + 1;
			for(int i = 0; i < repsPerGroup; i++) {
				upperReps[i] = otherReplicas[(2*repsPerGroup+i) % otherReplicas.length];
				lowerReps[i] = otherReplicas[(i) % otherReplicas.length];
				checkpointReps[i] = otherReplicas[(repsPerGroup +i) % otherReplicas.length];
			}
		} else {
			logLowerSize = ckpPeriod;
			logUpperSize = (cid + 1) % ckpPeriod;
			for(int i = 0; i < repsPerGroup; i++) {
				upperReps[i] = otherReplicas[(oldestReplicaPosition + (2 * repsPerGroup) + i) % otherReplicas.length];
				lowerReps[i] = otherReplicas[(oldestReplicaPosition+i) % otherReplicas.length];
				checkpointReps[i] = otherReplicas[(oldestReplicaPosition + repsPerGroup +i) % otherReplicas.length];
			}
		}
	}

	// defines the set of common shards between all replicas and defines which are
	// assigned to each replica
	public void assignShards(ConcurrentHashMap<Integer, ShardedCSTState> firstReceivedStates) {

		ShardedCSTState chkpntState = firstReceivedStates.get(checkpointReplica);
		ShardedCSTState upperLogState = firstReceivedStates.get(logUpper);
		ShardedCSTState lowerLogState = firstReceivedStates.get(logLower);

		if (chkpntState == null || upperLogState == null || lowerLogState == null) {
			System.out.println(this.getClass().getName() + ".assignShards: PANIC!!!!");
			System.out.println(chkpntState);
			System.out.println(upperLogState);
			System.out.println(lowerLogState);

			return;
		}

		MerkleTree chkpntMT = chkpntState.getMerkleTree();
		MerkleTree upperLogMT = upperLogState.getMerkleTree();
		MerkleTree lowerLogtMT = lowerLogState.getMerkleTree();

		this.shardCount = chkpntMT.getLeafCount();

		// Common shards between other replicas
		HashSet<Integer> commonShards = new HashSet<Integer>();
		if (chkpntMT.getHeight() == upperLogMT.getHeight()) {
			commonShards = chkpntMT.getEqualPageIndexs(upperLogMT);
			if (chkpntMT.getHeight() == lowerLogtMT.getHeight())
				commonShards.retainAll(chkpntMT.getEqualPageIndexs(lowerLogtMT));
		} else {
			if (chkpntMT.getHeight() == lowerLogtMT.getHeight())
				commonShards = chkpntMT.getEqualPageIndexs(lowerLogtMT);
		}

		nonCommonShards = new Integer[shardCount - commonShards.size()];
		int count = 0;
		for (int i = 0; i < shardCount; i++) {
			if (!commonShards.contains(i)) {
				nonCommonShards[count] = i;
				count++;
			}
		}

		this.commonShards = commonShards.toArray(new Integer[0]);
		Arrays.sort(this.commonShards);
	}

	//TODO: needs to be redone
	public void reAssignShards(Integer[] faultyShards) {
		nonCommonShards = faultyShards;
		commonShards = new Integer[0];
	}
}
