package de.hpi.ddm.actors;

import java.io.Serializable;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import akka.actor.AbstractLoggingActor;
import akka.actor.ActorRef;
import akka.actor.ActorSelection;
import akka.actor.Props;
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
		private long messageId;
	}

	/////////////////
	// Actor State //
	/////////////////

	private int chunkedMessageSize;
	private AtomicLong idCounter;
	private ByteBuffer byteBuffer;

	/////////////////////
	// Actor Lifecycle //
	/////////////////////

	@Override
	public void preStart() {
		Reaper.watchWithDefaultReaper(this);
		
		chunkedMessageSize = ConfigurationSingleton.get().getLargeMessageChunkSize();
		idCounter = new AtomicLong();
		byteBuffer = new ByteBuffer();
	}

	////////////////////
	// Actor Behavior //
	////////////////////

	@Override
	public Receive createReceive() {
		return receiveBuilder()
				.match(LargeMessage.class, this::handle) // Sender Proxy
				.match(BytesMessage.class, this::handle) // Receiver Proxy
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
		final long messageId = createID();

		// Send bytes chunk-wise to receiver proxy
		for(int index = 0; index < messageAsBytes.length; index += chunkedMessageSize) {
			byte[] bytesChunk = Arrays.copyOfRange(
					messageAsBytes, index, Math.min(index + chunkedMessageSize, messageAsBytes.length)
			);
			BytesMessage<byte[]> messageChunk = chunkedBytesMessageCreator(
					receiver, bytesChunk, index, messageId, messageAsBytes.length
			);
			receiverProxy.tell(messageChunk, sender);
		}
	}

	// Receiver proxy
	private void handle(BytesMessage<?> message) {
		// Store Message as Chunks
		long messageId = message.getMessageId();
		byteBuffer.saveChunksToMap(messageId, message.getChunkOffset(), (byte[]) message.getBytes());
		byteBuffer.getMap(messageId).put(message.getChunkOffset(), (byte[]) message.getBytes());

		// Check if all chunks present
		int partitionSize = (int) Math.ceil(message.getMessageLength() * 1.0 / chunkedMessageSize);
		if (partitionSize == byteBuffer.getMap(messageId).size()) {
			byte[] destinationMessage = new byte[message.messageLength];

			// Copying message chunks into destinationMessage byte array in correct order
			List<Integer> sortedChunkOffsets = byteBuffer.getMap(messageId).keySet().stream().sorted().collect(Collectors.toList());
			for (Integer chunkOffset : sortedChunkOffsets) {
				byte[] sourceMessageChunk = byteBuffer.getMap(messageId).get(chunkOffset);
				System.arraycopy(sourceMessageChunk, 0, destinationMessage, chunkOffset, sourceMessageChunk.length);
			}

			// Deserialize; Decoded Message = Original Message
			KryoPool kryoPool = KryoPoolSingleton.get();
			Object decodedMessage = kryoPool.fromBytes(destinationMessage);
			message.getReceiver().tell(decodedMessage, message.getSender());
			this.byteBuffer.deleteMapForMessageId(messageId);
		}
	}

	////////////////////
	// Helper Methods //
	////////////////////

	private long createID() {
		return this.idCounter.getAndIncrement();
	}

	// Receiver expects BytesMessage therefore we need to return a BytesMessage object in our chunkCreator
	private BytesMessage<byte[]> chunkedBytesMessageCreator(
			ActorRef receiver,
			byte[] messageBytes,
			int chunkOffset,
			long messageId,
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
