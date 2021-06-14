package de.hpi.ddm.actors;

import java.io.Serializable;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import akka.actor.*;
import com.twitter.chill.KryoPool;
import de.hpi.ddm.singletons.ConfigurationSingleton;
import de.hpi.ddm.singletons.KryoPoolSingleton;
import de.hpi.ddm.structures.ByteBuffer;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

public class LargeMessageProxy extends AbstractLoggingActor {

	////////////////////////
	// Actor Construction //
	////////////////////////

	public static final String DEFAULT_NAME = "largeMessageProxy";

	public static Props props() {
		return Props.create(LargeMessageProxy.class);
	}

	////////////////////
	// Actor Messages //
	////////////////////

	@Data @NoArgsConstructor @AllArgsConstructor
	public static class LargeMessage<T> implements Serializable {
		private static final long serialVersionUID = 2940665245810221108L;
		private T message;
		private ActorRef receiver;
	}

	@Data @NoArgsConstructor @AllArgsConstructor
	public static class BytesMessage<T> implements Serializable {
		private static final long serialVersionUID = 4057807743872319842L;
		private T bytes;
		private ActorRef sender;
		private ActorRef receiver;

		private int messageLength;
		private int chunkOffset;
		private String messageId;
	}

	@Data @NoArgsConstructor @AllArgsConstructor
	public static class AckMessage implements Serializable {
		private static final long serialVersionUID = 6453807787692319993L;
		private String messageId;
		private int chunkOffset;
	}

	/////////////////
	// Actor State //
	/////////////////

	private int chunkedMessageSize;
	private ByteBuffer receiverByteBuffer;
	private ByteBuffer senderByteBuffer;
	private Map<String, Map<Integer, Cancellable>> sendAttempts;

	/////////////////////
	// Actor Lifecycle //
	/////////////////////

	@Override
	public void preStart() {
		Reaper.watchWithDefaultReaper(this);
		
		chunkedMessageSize = ConfigurationSingleton.get().getLargeMessageChunkSize();
		receiverByteBuffer = new ByteBuffer();
		senderByteBuffer = new ByteBuffer();
		sendAttempts = new HashMap<>();
	}

	////////////////////
	// Actor Behavior //
	////////////////////

	@Override
	public Receive createReceive() {
		return receiveBuilder()
				.match(LargeMessage.class, this::handle) // Sender Proxy
				.match(BytesMessage.class, this::handle) // Receiver Proxy
				.match(AckMessage.class, this::handle) // ACK from Receiver to Sender for received Chunk
				.matchAny(object -> this.log().info("Received unknown message: \"{}\"", object.toString()))
				.build();
	}

	// Sender proxy
	private void handle(LargeMessage<?> largeMessage) {
		Object message = largeMessage.getMessage();
		ActorRef sender = this.sender();
		ActorRef receiver = largeMessage.getReceiver();
		ActorSelection receiverProxy = this.context().actorSelection(receiver.path().child(DEFAULT_NAME));

		// Serialize to byte array

		KryoPool kryoPool = KryoPoolSingleton.get();
		byte[] messageAsBytes = kryoPool.toBytesWithClass(message);
		final String messageId = createID();

		// Send bytes chunk-wise to receiver proxy
		for(int index = 0; index < messageAsBytes.length; index += chunkedMessageSize) {
			byte[] bytesChunk = Arrays.copyOfRange(
					messageAsBytes, index, Math.min(index + chunkedMessageSize, messageAsBytes.length)
			);
			BytesMessage<byte[]> messageChunk = chunkedBytesMessageCreator(
					receiver, bytesChunk, index, messageId, messageAsBytes.length
			);

			senderByteBuffer.saveChunksToMap(messageId, index, bytesChunk);

			Cancellable sendAttempt = this.getContext().system().scheduler()
				.scheduleAtFixedRate(
					Duration.ZERO,
					Duration.ofSeconds(5),
					() -> receiverProxy.tell(messageChunk, this.self()),
					this.context().dispatcher()
				);

			sendAttempts.computeIfAbsent(messageId, id -> new ConcurrentHashMap<>());
			sendAttempts.get(messageId).put(index, sendAttempt);
		}
	}

	// Receiver proxy
	private void handle(BytesMessage<?> message) {
		// Store Message as Chunks

		String messageId = message.getMessageId().toString();
		int chunkOffset = message.getChunkOffset();
		this.sender().tell(new AckMessage(messageId, chunkOffset), this.self()); // TODO what if ACK is never received????

		receiverByteBuffer.saveChunksToMap(messageId, chunkOffset, (byte[]) message.getBytes());
		receiverByteBuffer.getMap(messageId).put(chunkOffset, (byte[]) message.getBytes());

		// Check if all chunks present
		int partitionSize = (int) Math.ceil(message.getMessageLength() * 1.0 / chunkedMessageSize);
		if (partitionSize == receiverByteBuffer.getMap(messageId).size()) {
			byte[] destinationMessage = new byte[message.messageLength];

			// Copying message chunks into destinationMessage byte array in correct order
			List<Integer> sortedChunkOffsets = receiverByteBuffer.getMap(messageId).keySet().stream().sorted().collect(Collectors.toList());
			for (Integer offset : sortedChunkOffsets) {
				byte[] sourceMessageChunk = receiverByteBuffer.getMap(messageId).get(offset);
				System.arraycopy(sourceMessageChunk, 0, destinationMessage, offset, sourceMessageChunk.length);
			}

			// Deserialize; Decoded Message = Original Message
			KryoPool kryoPool = KryoPoolSingleton.get();
			Object decodedMessage = kryoPool.fromBytes(destinationMessage);
			message.getReceiver().tell(decodedMessage, message.getSender());
			this.receiverByteBuffer.deleteMapForMessageId(messageId);
		}
	}

	private void handle(AckMessage message) {
		String messageId = message.getMessageId();
		int chunkOffset = message.getChunkOffset();
		// cancel cancellable
		Map<Integer, Cancellable> cancellableMap = sendAttempts.get(messageId);
		if (cancellableMap != null) {
			cancellableMap.get(chunkOffset).cancel();
			cancellableMap.remove(chunkOffset);
			if (cancellableMap.isEmpty()) {
				sendAttempts.remove(messageId);
			}
		}

		// delete cancellable and message
		senderByteBuffer.getMap(messageId).remove(chunkOffset);
		if (senderByteBuffer.getMap(messageId).isEmpty()) {
			senderByteBuffer.deleteMapForMessageId(messageId);
		}
	}

	////////////////////
	// Helper Methods //
	////////////////////

	private String createID() {
		return UUID.randomUUID().toString();
	}

	// Receiver expects BytesMessage therefore we need to return a BytesMessage object in our chunkCreator
	private BytesMessage<byte[]> chunkedBytesMessageCreator(
			ActorRef receiver,
			byte[] messageBytes,
			int chunkOffset,
			String messageId,
			int messageLength
	) {
		BytesMessage<byte[]> bytesMessage = new BytesMessage<>();
		bytesMessage.receiver = receiver;
		bytesMessage.bytes = messageBytes;
		bytesMessage.chunkOffset = chunkOffset;
		bytesMessage.messageId = messageId;
		bytesMessage.messageLength = messageLength;
		bytesMessage.sender = this.sender();
		return bytesMessage;
	}
}
