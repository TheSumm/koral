package de.uni_koblenz.west.cidre.common.executor.messagePassing;

import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.util.logging.Logger;

import de.uni_koblenz.west.cidre.common.messages.MessageType;
import de.uni_koblenz.west.cidre.common.query.Mapping;
import de.uni_koblenz.west.cidre.common.query.MappingRecycleCache;

public class MessageSenderBuffer {

	private final Logger logger;

	private final MessageSender messageSender;

	private final MessageReceiverListener localMessageReceiver;

	private final Mapping[][] mappingBuffer;

	private final int[] nextIndex;

	public MessageSenderBuffer(int numberOfSlaves, int bundleSize,
			MessageSender messageSender,
			MessageReceiverListener localMessageReceiver, Logger logger) {
		this.logger = logger;
		this.messageSender = messageSender;
		this.localMessageReceiver = localMessageReceiver;
		mappingBuffer = new Mapping[numberOfSlaves + 1][bundleSize];
		nextIndex = new int[numberOfSlaves + 1];
	}

	public void sendQueryCreate(int queryId, byte[] queryTree) {
		ByteBuffer message = ByteBuffer
				.allocate(Byte.BYTES + Integer.BYTES + queryTree.length);
		message.put(MessageType.QUERY_CREATE.getValue()).putInt(queryId)
				.put(queryTree);
		messageSender.sendToAllSlaves(message.array());
	}

	public void sendQueryCreated(int receivingComputer, long coordinatorID) {
		ByteBuffer message = ByteBuffer
				.allocate(Byte.BYTES + Short.BYTES + Long.BYTES);
		message.put(MessageType.QUERY_CREATED.getValue())
				.putShort((short) messageSender.getCurrentID())
				.putLong(coordinatorID);
		messageSender.send(receivingComputer, message.array());
	}

	public void sendQueryStart(int queryID) {
		ByteBuffer message = ByteBuffer.allocate(Byte.BYTES + Integer.BYTES);
		message.put(MessageType.QUERY_CREATE.getValue()).putInt(queryID);
		messageSender.sendToAllSlaves(message.array());
	}

	public void sendQueryMapping(Mapping mapping, long senderTaskID,
			long receiverTaskID, MappingRecycleCache mappingCache) {
		mapping.updateReceiver(receiverTaskID);
		mapping.updateSender(senderTaskID);
		int receivingComputer = getComputerID(receiverTaskID);
		if (receivingComputer == messageSender.getCurrentID()) {
			// the receiver is on this computer
			localMessageReceiver.receiveLocalMessage(senderTaskID,
					receiverTaskID, mapping.getByteArray(),
					mapping.getFirstIndexOfMappingInByteArray());
			mappingCache.releaseMapping(mapping);
		} else {
			enqueue(receivingComputer, mapping, receiverTaskID, mappingCache);
		}
	}

	/**
	 * Broadcasts the finish message to all instances of this query task on the
	 * other computers. If it is the root, the coordinator is informed
	 * additionally.
	 * 
	 * @param finishedTaskID
	 * @param isRoot
	 * @param coordinatorID
	 */
	public void sendQueryTaskFinished(long finishedTaskID, boolean isRoot,
			long coordinatorID, MappingRecycleCache mappingCache) {
		sendAllBufferedMessages(mappingCache);
		ByteBuffer message = ByteBuffer
				.allocate(Byte.BYTES + Short.BYTES + Long.BYTES);
		message.put(MessageType.QUERY_TASK_FINISHED.getValue())
				.putShort((short) messageSender.getCurrentID())
				.putLong(finishedTaskID);
		messageSender.sendToAllOtherSlaves(message.array());
		if (isRoot) {
			message = ByteBuffer.allocate(
					Byte.BYTES + Short.BYTES + Long.BYTES + Long.BYTES);
			message.put(MessageType.QUERY_TASK_FINISHED.getValue())
					.putShort((short) messageSender.getCurrentID())
					.putLong(coordinatorID).putLong(finishedTaskID);
			messageSender.send(getComputerID(coordinatorID), message.array());
		}
	}

	private int getComputerID(long taskID) {
		return (int) (taskID >>> 6 * Byte.BYTES);
	}

	public void sendAllBufferedMessages(MappingRecycleCache mappingCache) {
		for (int i = 0; i < mappingBuffer.length; i++) {
			sendBufferedMessages(i, mappingCache);
		}
	}

	private void sendBufferedMessages(int receivingComputer,
			MappingRecycleCache mappingCache) {
		ByteBuffer buffer = null;
		synchronized (mappingBuffer[receivingComputer]) {
			// determine size of message
			int sizeOfMessage = Byte.BYTES + Short.BYTES;
			for (int i = 0; i < nextIndex[receivingComputer]; i++) {
				Mapping mapping = mappingBuffer[receivingComputer][i];
				sizeOfMessage += mapping.getLengthOfMappingInByteArray()
						+ Byte.BYTES;
			}
			// create message
			buffer = ByteBuffer.allocate(sizeOfMessage);
			buffer.put(MessageType.QUERY_MAPPING_BATCH.getValue())
					.putShort((short) messageSender.getCurrentID());
			for (int i = 0; i < nextIndex[receivingComputer]; i++) {
				Mapping mapping = mappingBuffer[receivingComputer][i];
				buffer.put(MessageType.QUERY_MAPPING_BATCH.getValue());
				buffer.put(mapping.getByteArray(),
						mapping.getFirstIndexOfMappingInByteArray(),
						mapping.getLengthOfMappingInByteArray());
				mappingCache.releaseMapping(mapping);
				mappingBuffer[receivingComputer][i] = null;
			}
			nextIndex[receivingComputer] = 0;
		}
		// send message
		if (buffer != null) {
			messageSender.send(receivingComputer, buffer.array());
		}
	}

	private void enqueue(int receivingComputer, Mapping mapping,
			long receiverTaskID, MappingRecycleCache mappingCache) {
		synchronized (mappingBuffer[receivingComputer]) {
			if (isBufferFull(receivingComputer)) {
				sendBufferedMessages(receivingComputer, mappingCache);
			}
			mappingBuffer[receivingComputer][nextIndex[receivingComputer]++] = mapping;
			if (isBufferFull(receivingComputer)) {
				sendBufferedMessages(receivingComputer, mappingCache);
			}
		}
	}

	/**
	 * Only call it within a synchronized block!
	 * 
	 * @param receivingComputer
	 * @return
	 */
	private boolean isBufferFull(int receivingComputer) {
		return nextIndex[receivingComputer] == mappingBuffer[receivingComputer].length;
	}

	public void sendQueryTaskFailed(int receiver, long controllerID,
			String message) {
		byte[] messageBytes = null;
		try {
			messageBytes = message.getBytes("UTF-8");
		} catch (UnsupportedEncodingException e) {
			if (logger != null) {
				logger.finer(
						"Error during conversion of error message during query execution:");
				logger.throwing(e.getStackTrace()[0].getClassName(),
						e.getStackTrace()[0].getMethodName(), e);
			}
			messageBytes = new byte[0];
		}
		ByteBuffer messageBB = ByteBuffer.allocate(
				Byte.BYTES + Short.BYTES + Long.BYTES + messageBytes.length);
		messageBB.put(MessageType.QUERY_TASK_FAILED.getValue())
				.putShort((short) messageSender.getCurrentID())
				.putLong(controllerID).put(messageBytes);
		messageSender.send(receiver, messageBB.array());
	}

	public void sendQueryAbortion(int queryID) {
		ByteBuffer message = ByteBuffer.allocate(Byte.BYTES + Integer.BYTES);
		message.put(MessageType.QUERY_ABORTION.getValue()).putInt(queryID);
		messageSender.sendToAllSlaves(message.array());
	}

	public void clear() {
		int bufferSize = mappingBuffer[0].length;
		for (int i = 0; i < mappingBuffer.length; i++) {
			synchronized (mappingBuffer[i]) {
				mappingBuffer[i] = new Mapping[bufferSize];
				nextIndex[i] = 0;
			}
		}
	}

	public void close(MappingRecycleCache mappingCache) {
		sendAllBufferedMessages(mappingCache);
	}

}
