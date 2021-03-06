package ut.distcomp.playlist;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Enumeration;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.FileHandler;
import java.util.logging.Handler;
import java.util.logging.Level;

import ut.distcomp.framework.Config;
import ut.distcomp.framework.NetController;
import ut.distcomp.playlist.Transaction;
import ut.distcomp.playlist.TransactionState.*;

public class Process {
	// Heartbeat pumping time gap in milli seconds. 
	public static final int HEARTBEAT_PUMP_TIME = 500;
	
	// Maintain your own playlist.
	Hashtable<String, String> playList = new Hashtable<String, String>();
	
	// Current process Id.
	int processId;
	
	// Manage your connections.
	NetController controller;
	
	// Location of the config File.
	String configName;
	
	// Instance that would read the configuration file.
	static Config config;

	// Event queue for storing all the messages from the wire.
	final ConcurrentLinkedQueue<String> queue;
	
	// Map of the UP Processes. ProcessId to time last updated.
	Hashtable<Integer, Long> upProcess;
	
	// Current transaction if any-running currently. At one time, we would 
	// only serve one single transaction like (Add, Delete or Update).
	Transaction activeTransaction;
	
	// This variable contains the process number of the coordinator.
	int coordinatorProcessNumber;
	
	public DTLog dtLogger;
	
	// State of my previous(mine state) before dying last time.
	public STATE prevTransactionState;
	
	//// VARIABLES FOR INTERACTION WITH THE SYSTEM ////
	static int delay;
	
	boolean isAnyWhereCoordinatorSelectedPostRecoveryOfPRocess_0;
	
	// We have to die after getting n messages from P.
	// P=key, and n=messages.
	Hashtable<Integer,Integer> deathAfter = new Hashtable<Integer, Integer>();
	
	public Process(int processId) {
		this.processId = processId;
		this.configName = System.getProperty("CONFIG_NAME");
		this.upProcess = new Hashtable<Integer, Long>();
		this.coordinatorProcessNumber = 0;
		
		delay = Integer.parseInt(System.getProperty("DELAY"));
	
		try {
			Handler fh = new FileHandler(System.getProperty("LOG_FOLDER") + "/" + processId + ".log");
			fh.setLevel(Level.FINEST);
			
			config = new Config(this.configName, fh);
			
		} catch (FileNotFoundException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
		this.playList = StateRecovery.readStateFile(this);
		this.queue = new Queue<String>();
		this.controller = new NetController(this.processId, this.config, this.queue);
		
		this.dtLogger = new DTLog(this);
		
		//  XXX JUST FOR THE DEMO.
		// This is happening for the first time.
		String deathAfterString = System.getProperty("DeathAfter");
		Integer message_count = Integer.parseInt(deathAfterString.split("=")[0]);
		Integer process_number = Integer.parseInt(deathAfterString.split("=")[1]);
		deathAfter.put(process_number, message_count);		
	}
	
	public static void main(String[] args) {
		
		// First argument would be the process number.
		Process me = new Process(Integer.parseInt(args[0]));
		
		// Start sending HeartBeats.
		me.pumpHeartBeat();
		
		// Check for recovery.
		me.checkForRecovery();
		
		// Start receiving messages from other process.
		me.startReceivingMessages();
		
		// Start clearing Up Processes from which a hearBeat 
		// was not received in a delta amount of time.
		me.startClearingDeadProcess();
			
	}
	
	// Returns a flag telling whether in process of recovering or not.
	private boolean checkForRecovery() {
		STATE state = this.dtLogger.getLoggedState(this.processId);
				
		if (state == null) {
			config.logger.info("Got nothing from the DT log file.");
			prevTransactionState = null;
		} else if (state == STATE.COMMIT || state == STATE.ABORT) {
			config.logger.info("Transaction completed properly last time.");
			prevTransactionState = state;
		} else if (state == STATE.RESTING) {
			String command = this.dtLogger.getLoggedCommand(this.processId);
			config.logger.info("Aborting last transaction as I did not take part in it..");
			this.dtLogger.write(STATE.ABORT, command);
			prevTransactionState = STATE.ABORT;
		} else { // When this process is in the uncertain stage.
			String command = this.dtLogger.getLoggedCommand(this.processId);
			
			MessageType type;
			if (command.contains("=")) {
				type = MessageType.EDIT;
			} else {
				type  = MessageType.DELETE;
			}
			
			Message msg = new Message(-1, type, command);
			config.logger.info("DTlog: UNCERTAIN. Going to start recovery transaction.");
			this.coordinatorProcessNumber = -1;
			activeTransaction = new RecoveryTransaction(this, msg);
			
			Thread thread = new Thread(activeTransaction);
			thread.start();
			
			return true;
		}
		
		return false;
	}

	public void pumpHeartBeat() {
		final Message heartBeat = new Message(this.processId, MessageType.HEARTBEAT, " ");
		 
        Thread th = new Thread() {
        	public void run() {
        		// Wait for 2 seconds initially to let everyone come up.
        		try {
					Thread.sleep(2000);
				} catch (InterruptedException e1) {
					// TODO Auto-generated catch block
					e1.printStackTrace();
				}
        		while(true) {
        			controller.broadCastMsgs(heartBeat.toString());
        			try {
						Thread.sleep(HEARTBEAT_PUMP_TIME);
					} catch (InterruptedException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
        		}
        	}
        };
        
	    th.start();
	}
	
	public void startReceivingMessages() {
			Thread th = new Thread() {
	        	public void run() {
	        		while(true) {
		        		String msg = queue.poll();
		    			Message message = Message.parseMsg(msg);
		    			
		    			switch(message.type) {
		    				case HEARTBEAT: {
		    					updateProcessList(message);
		    					break;
		    				} // End of Heartbeat case.
		    				case ADD:
		    				case DELETE:
		    				case EDIT: {
		    					if (coordinatorProcessNumber == processId) {
		    						if (activeTransaction != null) {
		    							System.out.println("A transaction is already running. Ignoring this request.");
			    						config.logger.warning("A transaction is already running. Ignoring this request.");
			    						break;
		    						}
		    						if (message.type == MessageType.EDIT 
		    								&& !playList.containsKey(message.payLoad.split("=")[0])) {
		    							System.out.println("No entry found to EDIT.");
		    							config.logger.warning("No entry found to EDIT.");
		    							break;
		    						}
		    						if (message.type == MessageType.DELETE 
		    								&& !playList.containsKey(message.payLoad.trim())) {
		    							System.out.println("No entry found to DELETE.");
		    							config.logger.warning("No entry found to DELETE.");
		    							break;
		    						}
		    						if (upProcess.size() != config.numProcesses - 1) {
		    							System.out.println("Not starting a transaction as all the process are not up.");
		    							config.logger.warning("Not starting a transaction as all the process are not up.");
		    							
		    							break;
		    						}
		    						
		    						startNewTransaction(message);
		    					} else {
		    						config.logger.warning("I am not the coordiantor. Don't send me: " + message.type);
		    						System.out.println("I am not the coordiantor. Don't send me: " + message.type);
		    					}
		    					break;
		    				} // End of ADD/DELTE/UPDATE case.
		    				case VOTE_REQ: {
		    					if (coordinatorProcessNumber == processId) {
		    						config.logger.warning(message.type + " sent by: " + message.process_id);
		    						config.logger.warning("There is something wrong. Coordiantor is not supposed to get " + message.type);
		    					} else {
		    						if (activeTransaction == null) {
		    							coordinatorProcessNumber = message.process_id;
		    							startNewTransaction(message);
		    						} else {
		    							config.logger.warning(message.type + " sent by: " + message.process_id);
			    						config.logger.warning("I should not get a VOTE-REQ if transaction is already running.");
		    						}
		    					}
		    					break;
		    				}
		    				case PRE_COMMIT:
		    				case COMMIT:
		    				case ABORT: 
		    				case STATE_REQ: {
		    					if (coordinatorProcessNumber == processId) {
		    						config.logger.warning(message.type + " sent by: " + message.process_id);
		    						config.logger.warning("There is something wrong. Coordiantor is not supposed to get " + message.type);
		    						break;
		    					} else {
		    						if (activeTransaction != null) {
		    							activeTransaction.update(message);
		    						} else {
		    							config.logger.warning(message.type + " sent by: " + message.process_id);
			    						config.logger.warning("I should not get a " +  message.type + " if transaction is not running.");
		    						}
		    						break;
		    					}
		    				} // End of messages received by the normal process.
		    				case YES:
		    				case NO:
		    				case ACK: 
		    				case STATE_VALUE: {
		    					if (coordinatorProcessNumber != processId) {
		    						config.logger.warning(message.type + " sent by: " + message.process_id);
		    						config.logger.warning("There is something wrong. I am not coorindator. Coordinator should get " + message.type);
		    						break;
		    					} else {
		    						if (activeTransaction != null) {
		    							activeTransaction.update(message);
		    						} else {
		    							config.logger.warning(message.type + " sent by: " + message.process_id);
			    						config.logger.warning("I should not get a " +  message.type + " if transaction is not running.");
		    						}
		    					}
		    					break;
		    				}
		    				case UR_SELECTED: {
		    					if (coordinatorProcessNumber == processId) {
		    						config.logger.info("Ignoring: " + message.toString());
		    						// Safely ignore this message. I am aware that I am the new coordinator.
		    						break;
		    					}
		    					config.logger.info("Received: " + message.toString());
		    					startCoordinatorRecoveryTransaction(message);
		    					break;
		    				}
		    				case STATE_ENQUIRY: {
		    					config.logger.info("Received: " + message.toString());
		    					MessageType type;
		    					if (activeTransaction == null) {
		    						if (prevTransactionState != null) {
		    							if (prevTransactionState == STATE.COMMIT) {
				    						type = MessageType.STATE_COMMIT;
				    					} else if (prevTransactionState == STATE.ABORT) {
				    						type = MessageType.STATE_ABORT;
				    					} else {
				    						// Not reachable.
				    						break;
				    					}
		    							Message stateReply = new Message(processId, type, " ");
				    					config.logger.info("Sending: " + stateReply.toString() + " to: " + message.process_id);
				    					controller.sendMsg(message.process_id, stateReply.toString());
		    						}
		    						break;
		    					} 
		    					String command = activeTransaction.command;
		    					if (activeTransaction.state == STATE.COMMIT) {
		    						type = MessageType.STATE_COMMIT;
		    					} else if (activeTransaction.state == STATE.ABORT) {
		    						type = MessageType.STATE_ABORT;
		    					} else if (activeTransaction.state == STATE.RECOVERING) {
		    						type = MessageType.STATE_RECOVERING;
		    						command = activeTransaction.getUpStates();
		    					} else {
		    						type = MessageType.STATE_UNDECIDED;
		    					}

		    					Message stateReply = new Message(processId, type, command);
		    					config.logger.info("Sending: " + stateReply.toString() + " to: " + message.process_id);
		    					controller.sendMsg(message.process_id, stateReply.toString());
		    					break;
		    				}
		    				case STATE_COMMIT:
		    				case STATE_ABORT:
		    				case STATE_RECOVERING:
		    				case STATE_UNDECIDED: {
		    					//config.logger.info("Received: " + message.toString());
		    					if (activeTransaction != null) {
	    							activeTransaction.update(message);
	    						} else {
	    							config.logger.warning(message.type + " sent by: " + message.process_id);
		    						config.logger.warning("I should not get a " +  message.type + " if transaction is not running.");
	    						}
		    					break;
		    				}
		    				case PRINT_STATE: {
		    					if(activeTransaction == null)
		    					{
		    						System.out.println("No transaction is going on.");
		    					} else {
		    						System.out.println("STATE is "+ activeTransaction.state);
		    					}

		    					System.out.println("Current Playlist contains: \n");
		    					Enumeration<String> songs = playList.keys();
		    					if(songs.hasMoreElements()) {
			    					while(songs.hasMoreElements())
			    					{
				    					String str = (String)songs.nextElement();
				    					System.out.println(str + " : " + playList.get(str));
			    					} 
		    					} else{
		    						System.out.println("No Songs");
		    					}
		    					break;
		    				}
		    				case DIE: {
		    					// Also remove the coordinator from upProcess map and then die. 
		    					config.logger.warning("Received: " + message.toString());
		    					upProcess.remove(message.process_id);
		    					dtLogger.write(activeTransaction.getState(), activeTransaction.command);
		    					System.exit(1);
		    				}
		    				case GIVE_COORDINATOR: {
		    					config.logger.info("Received: " + message.toString());
		    					String to_return = "false";
		    					if (coordinatorProcessNumber == processId) {
		    						to_return = "true";
		    					}
		    					Message co_msg = new Message(processId, MessageType.COORDINATOR_NO, to_return);
		    					config.logger.info("Sending: " + co_msg);
		    					controller.sendMsg(message.process_id, co_msg.toString());
		    					break;
		    				}
		    				case COORDINATOR_NO: {
		    					//isAnyWhereCoordinatorSelectedPostRecoveryOfPRocess_0
		    					if (message.payLoad.trim().equals("true")) {
		    						isAnyWhereCoordinatorSelectedPostRecoveryOfPRocess_0 = true;
		    					}
		    				}
		    			}
	        		}
	        	}
	        };
	        
	        th.start();
	}
	
	protected void startCoordinatorRecoveryTransaction(Message message) {
		coordinatorProcessNumber = processId;
		
		STATE state = prevTransactionState;
		if (activeTransaction != null) {
			state = activeTransaction.state;
			activeTransaction.enforceStop();
		}
		RecoveryCoordinatorTransaction newTransaction = 
				new RecoveryCoordinatorTransaction(this, message, state);
		
		activeTransaction = newTransaction;
		
		config.logger.info("Yeay !! I am the new coordinator");
		Thread thread = new Thread(activeTransaction);
		thread.start();
	}

	// Update the list processes.
	public void updateProcessList(Message message) {
		if (!upProcess.containsKey(message.process_id)) {
			// We would not log upState list untill a recovery is going on. Otherwise, we would screw the UP list
			// required for the total failure case.
			if (activeTransaction != null && !(activeTransaction instanceof RecoveryTransaction)) {
				dtLogger.write(activeTransaction.getState(), activeTransaction.command);
			}
			//config.logger.info(String.format("Adding %d to the upProcess list", message.process_id));
		}
		upProcess.put(message.process_id, System.currentTimeMillis());
	}
	
	// Clears the processes which are dead/non-responsive.
	public void startClearingDeadProcess() {
        Thread th = new Thread() {
        	public void run() {		
        		while(true) {
	        		try {
						Thread.sleep(HEARTBEAT_PUMP_TIME);
					} catch (InterruptedException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
	        		for (Iterator<Map.Entry<Integer, Long>> i = upProcess.entrySet().iterator(); i.hasNext(); ) {
	        	        Map.Entry<Integer, Long> entry = i.next();
	        	        
	        	        if (System.currentTimeMillis() - entry.getValue() > (HEARTBEAT_PUMP_TIME + 200)) {
	        	            i.remove();
	        	            if (activeTransaction != null && !(activeTransaction instanceof RecoveryTransaction)) {
	        					dtLogger.write(activeTransaction.getState(), activeTransaction.command);
	        				}
	        	            //config.logger.warning(String.format("Process %d seems to dead. Clearing from up list.", entry.getKey()));
	        	        }
	        	    }
        		}
        	}
        };
        
        th.start(); // Start the thread.
	}
	
	public void startNewTransaction(Message message) {
		if (coordinatorProcessNumber == processId) {
			activeTransaction = new CoordinatorTransaction(this, message);
		} else {
			activeTransaction = new Transaction(this, message);
		}
		
		Thread thread = new Thread(activeTransaction);
		thread.start();
	}
	
	public void notifyTransactionComplete() {
		if (activeTransaction.state == STATE.COMMIT) {
			if (!activeTransaction.command.contains("=")) {
				if (playList.containsKey(activeTransaction.command.trim())) {
					playList.remove(activeTransaction.command.trim());
				} else {
					System.out.println("ProcessId: " + processId + ": This song was not found in the playlist.");
				}
			} else {
				String[] str = activeTransaction.command.split("=");
				playList.put(str[0], str[1]);
			}
			
			StateRecovery.updateStateFile(this);
		}
		
		if ((activeTransaction instanceof RecoveryTransaction) && processId == 0) {
			//isAnyWhereCoordinatorSelectedPostRecoveryOfPRocess_0
			Message msg = new Message(processId, MessageType.GIVE_COORDINATOR, "-");
			controller.sendMsgs(upProcess.keySet(), msg.toString(), -1);
			
			Thread th = new Thread() {
				public void run() {
					try {
						Thread.sleep(2000);
					} catch (InterruptedException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
					
					if (!isAnyWhereCoordinatorSelectedPostRecoveryOfPRocess_0) {
						coordinatorProcessNumber = 0;
					}
				}
			};
			
			th.start();
		}
		
		// Store your state in the prevTransactionState variable and get ready for the new transaction.
		prevTransactionState = activeTransaction.state;
		config.logger.info("Transaction is complete. State: " + activeTransaction.state);
		System.out.println("Process: " + processId + "'s transaction is complete. State: " + activeTransaction.state);
		activeTransaction = null;
	}
	
	public static void waitTillDelay() {
		try {
			config.logger.info("Waiting for " + Process.delay / 1000 + " secs.");
			Thread.sleep(Process.delay);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
	}
}

