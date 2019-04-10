package bftsmart.statemanagement.durability.shard;

import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;

import bftsmart.reconfiguration.views.View;
import bftsmart.statemanagement.ApplicationState;
import bftsmart.statemanagement.SMMessage;

public class ShardedCSTSMMessage extends SMMessage {

	private ShardedCSTRequest cstConfig;
	
    public ShardedCSTSMMessage() {
    	super();
    }

    public ShardedCSTSMMessage(int sender, int cid, int type, ShardedCSTRequest cstConfig, ApplicationState state, View view, int regency, int leader) {
    	super(sender, cid, type, state, view, regency, leader);
    	this.cstConfig = cstConfig;
    }
    
    public ShardedCSTRequest getCstConfig() {
    	return cstConfig;
    }
    
	@Override
    public void writeExternal(ObjectOutput out) throws IOException{
        super.writeExternal(out);
        out.writeObject(cstConfig);
    }

    @Override
    public void readExternal(ObjectInput in) throws IOException, ClassNotFoundException{
        super.readExternal(in);
        cstConfig = (ShardedCSTRequest)in.readObject();
    }

	@Override
	public String toString() {
		return "ShardedCSTSMMessage [cid=" + cid + ", TRIGGER_SM_LOCALLY=" + TRIGGER_SM_LOCALLY + ", sender=" + sender
				+ ", authenticated=" + authenticated + ", cstConfig=" + cstConfig + "]";
	}
}
