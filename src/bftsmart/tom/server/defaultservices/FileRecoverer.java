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
package bftsmart.tom.server.defaultservices;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.Arrays;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FileRecoverer {
	private Logger logger = LoggerFactory.getLogger(this.getClass());

    private byte[] ckpHash;
    private int ckpLastConsensusId;
    private int logLastConsensusId;

    private int replicaId;
    private String defaultDir;

    public FileRecoverer(int replicaId, String defaultDir) {
        this.replicaId = replicaId;
        this.defaultDir = defaultDir;
        ckpLastConsensusId = -1;
        logLastConsensusId = -1;
    }

    /**
     * Reads all log messages from the last log file created
     * 
     * @return an array with batches of messages executed for each consensus
     */
    // public CommandsInfo[] getLogState() {
    // String lastLogFilename = getLatestFile(".log");
    // if(lastLogFilename != null)
    // return getLogState(0, lastLogFilename);
    // return null;
    // }

    public CommandsInfo[] getLogState(int index, String logPath) {
        RandomAccessFile log = null;

        System.out.println("GETTING LOG FROM " + logPath);
        if ((log = openLogFile(logPath)) != null) {

            CommandsInfo[] logState = recoverLogState(log, index);

            try {
                log.close();
            } catch (IOException e) {
                e.printStackTrace();
            }

            return logState;
        }

        return null;
    }

    /**
     * Recover portions of the log for collaborative state transfer.
     * 
     * @param start
     *            the index for which the commands start to be collected
     * @param number
     *            the number of commands retrieved
     * @return The commands for the period selected
     */
    public CommandsInfo[] getLogState(long pointer, int startOffset, int number, String logPath) {
        RandomAccessFile log = null;

        System.out.println("GETTING LOG FROM " + logPath);
        if ((log = openLogFile(logPath)) != null) {

            CommandsInfo[] logState = recoverLogState(log, pointer, startOffset, number);

            try {
                log.close();
            } catch (IOException e) {
                e.printStackTrace();
            }

            return logState;
        }

        return null;
    }
/*
    // modified by JSoares
    // TODO: [JSoares] - modify this to only read the required bytes from the file, instead of the entire checkpoint
    public byte[] getCkpState(String ckpPath, Integer[] shards, int shardSize) {
        RandomAccessFile ckp = null;
        byte[] toReturn = new byte[shards.length*shardSize];
        logger.debug("GETTING CHECKPOINT FROM " + ckpPath);
        if ((ckp = openLogFile(ckpPath)) != null) {
        	
            byte[] ckpState = recoverCkpState(ckp);
//            try {
//	            FileOutputStream fos = new FileOutputStream(ckpPath+"state");
//	            fos.write(ckpState);
//	            fos.flush();
//	            fos.close();
//            } catch (Exception e) {
//            	e.printStackTrace();
//            }
            		
//            logger.debug(" CHECKPOINT : " + Arrays.toString(ckpState));
            
            for(int i = 0;i < shards.length; i++) {
            	int orig_offset = shards[i]*shardSize;
            	int dest_offset = i*shardSize;
            	int length = (orig_offset + shardSize) > ckpState.length ? (ckpState.length-orig_offset) : shardSize;
            	if(length != shardSize) {
            		byte[] tmp = new byte[shardSize];
//            		System.out.println("################# length != shardSize #################");
            		System.arraycopy(ckpState, orig_offset, tmp, 0, length);
                    System.arraycopy(tmp, 0, toReturn, dest_offset, shardSize);
            	}
            	else {
//                logger.debug("Reading State : state shard [{}] to CST shard [{}]", shards[i], i);                            	
            		System.arraycopy(ckpState, orig_offset, toReturn, dest_offset, length);
            	}
            }
//            logger.debug(" TO SEND : " + Arrays.toString(toReturn));
            try {
                ckp.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
            return toReturn;
        }
        return null;
    }
*/
    public byte[] getCkpState(String ckpPath) {
        RandomAccessFile ckp = null;

        logger.debug("GETTING CHECKPOINT FROM " + ckpPath);
        if ((ckp = openLogFile(ckpPath)) != null) {

            byte[] ckpState = recoverCkpState(ckp);

            try {
                ckp.close();
            } catch (IOException e) {
                e.printStackTrace();
            }

            return ckpState;
        }

        return null;
    }

    public void recoverCkpHash(String ckpPath) {
        RandomAccessFile ckp = null;

//        System.out.println("GETTING HASH FROM CHECKPOINT" + ckpPath);
        if ((ckp = openLogFile(ckpPath)) != null) {
            byte[] ckpHash = null;
            try {
                int ckpSize = ckp.readInt();
                ckp.skipBytes(ckpSize);
                int hashLength = ckp.readInt();
                ckpHash = new byte[hashLength];
                ckp.read(ckpHash);
//                System.out.println("--- Last ckp size: " + ckpSize + " Last ckp hash: "
//                        + Arrays.toString(ckpHash));
            } catch (Exception e) {
                e.printStackTrace();
                System.err.println("State recover was aborted due to an unexpected exception");
            }
            this.ckpHash = ckpHash;
        }
    }

    private byte[] recoverCkpState(RandomAccessFile ckp) {
        byte[] ckpState = null;
        try {
            long ckpLength = ckp.length();
            boolean mayRead = true;
            while (mayRead) {
                try {
                    if (ckp.getFilePointer() < ckpLength) {
                        int size = ckp.readInt();
                        logger.debug("Read chkPnt State : {} bytes", size);
                        if (size > 0) {
                            ckpState = new byte[size];// ckp state
                            int read = ckp.read(ckpState);
//                            logger.debug("Read chkPnt State : \n \t data : {}", Arrays.toString(ckpState));
                            if (read == size) {
                                int hashSize = ckp.readInt();
                                if (hashSize > 0) {
                                    ckpHash = new byte[hashSize];// ckp hash
                                    read = ckp.read(ckpHash);
                                    if (read == hashSize) {
                                        mayRead = false;
                                    } else {
                                        ckpHash = null;
                                        ckpState = null;
                                    }
                                }
                            } else {
                                mayRead = false;
                                ckp = null;
                            }
                        } else {
                            mayRead = false;
                        }
                    } else {
                        mayRead = false;
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                    ckp = null;
                    mayRead = false;
                }
            }
            if (ckp.readInt() == 0) {
                ckpLastConsensusId = ckp.readInt();
                System.out.println("LAST CKP read from file: " + ckpLastConsensusId);
            }
        } catch (Exception e) {
            e.printStackTrace();
            System.err.println("State recover was aborted due to an unexpected exception");
        }

        return ckpState;
    }

    public void transferLog(SocketChannel sChannel, int index, String logPath) {
        RandomAccessFile log = null;

        System.out.println("GETTING STATE FROM LOG " + logPath);
        if ((log = openLogFile(logPath)) != null) {
            transferLog(log, sChannel, index);
        }
    }

    private void transferLog(RandomAccessFile logFile, SocketChannel sChannel, int index) {
        try {
            long totalBytes = logFile.length();
            System.out.println("---Called transferLog." + totalBytes + " " + (sChannel == null));
            FileChannel fileChannel = logFile.getChannel();
            long bytesTransfered = 0;
            while (bytesTransfered < totalBytes) {
                long bufferSize = 65536;
                if (totalBytes - bytesTransfered < bufferSize) {
                    bufferSize = (int) (totalBytes - bytesTransfered);
                    if (bufferSize <= 0)
                        bufferSize = (int) totalBytes;
                }
                long bytesSent = fileChannel.transferTo(bytesTransfered, bufferSize, sChannel);
                if (bytesSent > 0) {
                    bytesTransfered += bytesSent;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            System.err.println("State recover was aborted due to an unexpected exception");
        }
    }

    public void transferCkpState(SocketChannel sChannel, String ckpPath) {
        RandomAccessFile ckp = null;

        System.out.println("GETTING CHECKPOINT FROM " + ckpPath);
        if ((ckp = openLogFile(ckpPath)) != null) {

            transferCkpState(ckp, sChannel);

            try {
                ckp.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private void transferCkpState(RandomAccessFile ckp, SocketChannel sChannel) {
        try {
            long milliInit = System.currentTimeMillis();
            System.out.println("--- Sending checkpoint." + ckp.length() + " " + (sChannel == null));
            FileChannel fileChannel = ckp.getChannel();
            long totalBytes = ckp.length();
            long bytesTransfered = 0;
            while (bytesTransfered < totalBytes) {
                long bufferSize = 65536;
                if (totalBytes - bytesTransfered < bufferSize) {
                    bufferSize = (int) (totalBytes - bytesTransfered);
                    if (bufferSize <= 0)
                        bufferSize = (int) totalBytes;
                }
                long bytesRead = fileChannel.transferTo(bytesTransfered, bufferSize, sChannel);
                if (bytesRead > 0) {
                    bytesTransfered += bytesRead;
                }
            }
            System.out.println("---Took " + (System.currentTimeMillis() - milliInit)
                    + " milliseconds to transfer the checkpoint");
            fileChannel.close();
        } catch (Exception e) {
            e.printStackTrace();
            System.err.println("State recover was aborted due to an unexpected exception");
        }
    }

    public byte[] getCkpStateHash() {
        return ckpHash;
    }

    public int getCkpLastConsensusId() {
        return ckpLastConsensusId;
    }

    public int getLogLastConsensusId() {
        return logLastConsensusId;
    }

    private RandomAccessFile openLogFile(String file) {
        try {
            return new RandomAccessFile(file, "r");
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    private CommandsInfo[] recoverLogState(RandomAccessFile log, int endOffset) {
        try {
            long logLength = log.length();
            ArrayList<CommandsInfo> state = new ArrayList<CommandsInfo>();
            int recoveredBatches = 0;
            boolean mayRead = true;
            System.out.println("filepointer: " + log.getFilePointer() + " loglength " + logLength
                    + " endoffset " + endOffset);
            while (mayRead) {
                try {
                    if (log.getFilePointer() < logLength) {
                        int size = log.readInt();
                        if (size > 0) {
                            byte[] bytes = new byte[size];
                            int read = log.read(bytes);
                            if (read == size) {
                                ByteArrayInputStream bis = new ByteArrayInputStream(bytes);
                                ObjectInputStream ois = new ObjectInputStream(bis);
                                state.add((CommandsInfo) ois.readObject());
                                if (++recoveredBatches == endOffset) {
                                    System.out.println("read all " + endOffset + " log messages");
                                    return state.toArray(new CommandsInfo[state.size()]);
                                }
                            } else {
                                mayRead = false;
                                System.out.println("STATE CLEAR");
                                state.clear();
                            }
                        } else {
                            logLastConsensusId = log.readInt();
                            System.out.print("ELSE 1. Recovered batches: " + recoveredBatches);
                            System.out.println(", logLastConsensusId: " + logLastConsensusId);
                            return state.toArray(new CommandsInfo[state.size()]);
                        }
                    } else {
                        System.out.println("ELSE 2 " + recoveredBatches);
                        mayRead = false;
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                    state.clear();
                    mayRead = false;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            System.err.println("State recover was aborted due to an unexpected exception");
        }

        return null;
    }

    /**
     * Searches the log file and retrieves the portion selected.
     * 
     * @param log
     *            The log file
     * @param start
     *            The offset to start retrieving commands
     * @param number
     *            The number of commands retrieved
     * @return The commands for the period selected
     */
    private CommandsInfo[] recoverLogState(RandomAccessFile log, long pointer, int startOffset,
            int number) {
        try {
            long logLength = log.length();
            ArrayList<CommandsInfo> state = new ArrayList<CommandsInfo>();
            int recoveredBatches = 0;
            boolean mayRead = true;

            log.seek(pointer);

            int index = 0;
            while (index < startOffset) {
                int size = log.readInt();
                byte[] bytes = new byte[size];
                log.read(bytes);
                index++;
            }

            while (mayRead) {

                try {
                    if (log.getFilePointer() < logLength) {
                        int size = log.readInt();

                        if (size > 0) {
                            byte[] bytes = new byte[size];
                            int read = log.read(bytes);
                            if (read == size) {
                                ByteArrayInputStream bis = new ByteArrayInputStream(bytes);
                                ObjectInputStream ois = new ObjectInputStream(bis);

                                state.add((CommandsInfo) ois.readObject());

                                if (++recoveredBatches == number) {
                                    return state.toArray(new CommandsInfo[state.size()]);
                                }
                            } else {
                                logger.debug("recoverLogState ({}, {} , {}) STATE CLEAR", pointer, startOffset, number);
                                mayRead = false;
                                state.clear();
                            }
                        } else {
                            logger.debug("recoverLogState ({}, {} , {}) ELSE 1", pointer, startOffset, number);
                            System.out.println("recoverLogState (pointer,offset,number) ELSE 1");
                            mayRead = false;
                        }
                    } else {
                        logger.debug("recoverLogState ({}, {} , {}) ELSE 2", pointer, startOffset, number);
                        System.out.println("recoverLogState (pointer,offset,number) ELSE 2 "
                                + recoveredBatches);
                        mayRead = false;
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                    state.clear();
                    mayRead = false;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            System.err.println("State recover was aborted due to an unexpected exception");
        }

        return null;
    }

    public String getLatestFile(String extention) {
        File directory = new File(defaultDir);
        String latestFile = null;
        if (directory.isDirectory()) {
            File[] serverLogs = directory.listFiles(new FileListFilter(replicaId, extention));
            long timestamp = 0;
            for (File f : serverLogs) {
                String[] nameItems = f.getName().split("\\.");
                if (nameItems[1].contains("1st")) {
                    nameItems[1] = nameItems[1].substring(0, nameItems[1].indexOf("1st"));
                }
                long filets = new Long(nameItems[1]).longValue();
                if (filets > timestamp) {
                    timestamp = filets;
                    latestFile = f.getAbsolutePath();
                }
            }
        }
        return latestFile;
    }

    private class FileListFilter implements FilenameFilter {

        private int id;
        private String extention;

        public FileListFilter(int id, String extention) {
            this.id = id;
            this.extention = extention;
        }

        public boolean accept(File directory, String filename) {
            boolean fileOK = false;

            if (id >= 0) {
                if (filename.startsWith(id + ".") && filename.endsWith(extention)) {
                    fileOK = true;
                }
            }

            return fileOK;
        }
    }

}
