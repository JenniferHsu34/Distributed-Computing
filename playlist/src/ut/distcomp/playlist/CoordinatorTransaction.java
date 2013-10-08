package ut.distcomp.playlist;

import java.util.HashSet;
import java.util.Set;

import javax.annotation.processing.Processor;

import ut.distcomp.playlist.TransactionState.STATE;

public class CoordinatorTransaction extends Transaction {
	// Set of processes from where I am expecting an response.
	Set<Integer> processWaitSet;
	
	// Set of processes which sent me a positive response.
	Set<Integer> positiveResponseSet;
	
	private boolean abortFlag = false;
	private String reasonToAbort;
	
	public CoordinatorTransaction(Process process, Message message) {
		super(process, message);
		processWaitSet = new HashSet<Integer>();
		positiveResponseSet = new HashSet<Integer>();
		
		this.BUFFER_TIMEOUT = 2000;
		this.DECISION_TIMEOUT = process.delay + this.BUFFER_TIMEOUT;
	}
	
	public STATE getState()
	{
		if (state != STATE.COMMIT && state != STATE.ABORT) {
			return STATE.UNCERTAIN;
		} else {
			return state;
		}
	}
	
	@Override
	public void run() {
		lock.lock();
		// WaitSize > 0 is being checked because we have to collect all the responses because aborting/commiting.
		while((state != STATE.COMMIT && state != STATE.ABORT) || processWaitSet.size() > 0) {
			
			if(state == STATE.RESTING) {
				process.dtLogger.write(STATE.UNCERTAIN, command);
				// Update your state to waiting for all the decisions to arrive.
				state = STATE.WAIT_DECISION;
				
				// If we have come here, it means that we just received a new Transaction request.
				Message msg = new Message(process.processId, MessageType.VOTE_REQ, command);
				processWaitSet.addAll(process.upProcess.keySet());
				process.config.logger.info("Received: " + message.toString());
				Process.waitTillDelay();
				process.config.logger.info("Sending VOTE_REQs.");
				process.controller.sendMsgs(processWaitSet, msg.toString(), -1);
				
				// Timeout if all the process don't reply back with a Yes or No.
				Thread th = new Thread() {
					public void run(){
						try {
							Thread.sleep(DECISION_TIMEOUT);
						} catch (InterruptedException e) {
							// TODO Auto-generated catch block
							e.printStackTrace();
						}
						lock.lock();
						state = STATE.DECISION_RECEIVED;
						if (processWaitSet.size() > 0 ) {
							reasonToAbort = "Did not get a reply from some processes.";
							abortFlag = true;
						}
						nextMessageArrived.signal();
						lock.unlock();
					}
				};
				th.start();
			} // End of STATE.RESTING
			else if (state == STATE.WAIT_DECISION) {
				if (message.type == MessageType.YES) {
					process.config.logger.info("Received: " + message.toString());
					processWaitSet.remove(message.process_id);
					positiveResponseSet.add(message.process_id);
					if (processWaitSet.size() == 0) {
						process.config.logger.info("Successfully got all the YES replies.");
					}
				} else if (message.type == MessageType.NO) {
					process.config.logger.info("Received: " + message.toString());
					abortFlag = true;
					processWaitSet.remove(message.process_id);
					process.config.logger.info("Got a no from " + message.process_id);
					reasonToAbort = "Process " + message.process_id + " sent a NO !!";
				} else {
					process.config.logger.warning("Co-ordinator was waiting for YES/NO." + 
							" However got a " + message.type + ".");
					break;
				}
				
			} // End of STATE.WAIT_DECISION.
			else if (state == STATE.DECISION_RECEIVED) {
				if (abortFlag) {
					abortTransaction();
				} else {
					// Send PRE_COMMIT message to all of them.
					Message msg = new Message(process.processId, MessageType.PRE_COMMIT, command);
					processWaitSet = positiveResponseSet;
					positiveResponseSet = new HashSet<Integer>();

					process.config.logger.info("Received Yes from all the processes");
					Process.waitTillDelay();
					process.config.logger.info("Sending PRE_COMMIT to all the processes.");
					
					int partial_count = -1;
					if (!System.getProperty("PartialPreCommit").equals("-1")) {
						partial_count = Integer.parseInt(System.getProperty("PartialPreCommit"));
					}
					process.controller.sendMsgs(processWaitSet, msg.toString(), partial_count);
					
					// Update your state to waiting for all the decisions to arrive.
					state = STATE.WAIT_ACK;
					
					// Timeout if all the process don't reply back with a Yes or No.
					Thread th = new Thread() {
						public void run(){
							try {
								Thread.sleep(DECISION_TIMEOUT);
							} catch (InterruptedException e) {
								// TODO Auto-generated catch block
								e.printStackTrace();
							}
							lock.lock();
							state = STATE.ACK_RECEIVED;
							nextMessageArrived.signal();
							lock.unlock();
						}
					};
					th.start();
				}
			} // End of STATE.DECISION_RECEIVED.
			else if (state == STATE.WAIT_ACK) {
				if (message.type != MessageType.ACK) {
					process.config.logger.warning("Co-ordinator was waiting for Acknowledgement." + 
							" However got a " + message.type + ".");
					break;
				}
				process.config.logger.info("Received: " + message.toString());
				processWaitSet.remove(message.process_id);
				positiveResponseSet.add(message.process_id);
				if (processWaitSet.size() == 0) {
					process.config.logger.info("Successfully got all the acknowledgements.");
				}
			} // End of STATE.WAIT_ACK.
			else if (state == STATE.ACK_RECEIVED) {
				Message msg = new Message(process.processId, MessageType.COMMIT, command);
				process.dtLogger.write(STATE.COMMIT, command);
				state = STATE.COMMIT;
				process.config.logger.info("Acknowledgments have been received.");
				process.notifyTransactionComplete();
				Process.waitTillDelay();
				process.config.logger.info("Sending COMMIT message to processes from which received ACK.");
				
				int partial_count = -1;
				int extra_credit = -1;
				if(!System.getProperty("ExtraCredit").equals("-1")) {
					System.out.println("Extra credit executing.");
					extra_credit = Integer.parseInt(System.getProperty("ExtraCredit"));
					Message dieMessage = new Message(process.processId, MessageType.DIE, " ");
					process.controller.sendMsgs(process.upProcess.keySet(), dieMessage.toString(), partial_count);
					System.exit(1);
				}else{
				
					if (!System.getProperty("PartialCommit").equals("-1")) {
						partial_count = Integer.parseInt(System.getProperty("PartialCommit"));
					}
					//process.controller.sendMsgs(positiveResponseSet, msg.toString(), partial_count);
					process.controller.sendMsgs(process.upProcess.keySet(), msg.toString(), partial_count);
				}
				positiveResponseSet.clear();
				processWaitSet.clear();
			}
			
			try {
				// Wait until some other message is arrived.
				nextMessageArrived.await();
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
		lock.unlock();
	}
	
	private void dieIfNMessagesReceived() {
		if (process.deathAfter.containsKey(message.process_id)) {
			int n = process.deathAfter.get(message.process_id);
			n = n - 1;
			if (n == 0) {
				process.config.logger.info("Received n messages from" + message.process_id);
				process.config.logger.warning("Killing  myself"); 
				System.exit(1);
			}
			process.deathAfter.put(message.process_id, n);
		}
	}
		
	public void update(Message message) {
		lock.lock();
		
		dieIfNMessagesReceived();

		this.message = message;
		nextMessageArrived.signal();
		
		lock.unlock();
	}
	
	public void abortTransaction() {
		process.dtLogger.write(STATE.ABORT, command);
		state = STATE.ABORT;
		process.config.logger.warning("Transaction aborted: " + reasonToAbort);
		process.notifyTransactionComplete();
		Message msg = new Message(process.processId, MessageType.ABORT, command);
		Process.waitTillDelay();
		process.config.logger.info("Sending Abort messages to processes which voted Yes.");
		//process.controller.sendMsgs(positiveResponseSet, msg.toString(), -1);
		process.controller.sendMsgs(process.upProcess.keySet(), msg.toString(), -1);
		
		processWaitSet.clear();
		positiveResponseSet.clear();
	}
}
