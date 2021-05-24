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
		private int offset; // TODO naming: offset? chunk number ? chunk position?? chunk offset?
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

	// TODO define postStop? restart?

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

	// TODO delete comment
	// Implement a protocol that transmits the potentially very large message object.
	// The following code sends the entire message wrapped in a BytesMessage, which will definitely fail in a distributed setting if the message is large!
	// Solution options:
	// a) Split the message into smaller batches of fixed size and send the batches via ...
	//    a.a) self-build send-and-ack protocol (see Master/Worker pull propagation), or
	//    a.b) Akka streaming using the streams build-in backpressure mechanisms.
	// b) Send the entire message via Akka's http client-server component.
	// c) Other ideas ...
	// Hints for splitting:
	// - To split an object, serialize it into a byte array and then send the byte array range-by-range (tip: try "KryoPoolSingleton.get()").
	// - If you serialize a message manually and send it, it will, of course, be serialized again by Akka's message passing subsystem.
	// - But: Good, language-dependent serializers (such as kryo) are aware of byte arrays so that their serialization is very effective w.r.t. serialization time and size of serialized data.


	// Our Solution Concept:
	// Sender Proxy --> gets LargeMessage from Sender
	// 1) Splits the message into smaller batches of fixed size
	//     - size configurable in Configuration
	//     - serialized to byte array with KryoPool
	// 2) Sends the chunks (BytesMessage) with self-build send-and-ack protocol (TODO see Master/Worker pull propagation)
	// Receiver Proxy --> gets BytesMessages from Receiver Proxy
	// 3) Stores the message, asks for the next chunk and checks if all chunks are present
	// 4) If all chunks are present, reassembles the message's content, deserializes it and passes it to the Receiver


	// Sender proxy
	private void handle(LargeMessage<?> largeMessage) {
		Object message = largeMessage.getMessage();
		ActorRef sender = this.sender();
		ActorRef receiver = largeMessage.getReceiver();
		ActorSelection receiverProxy = this.context().actorSelection(receiver.path().child(DEFAULT_NAME));

		// Serialize to byte array
		byte[] messageAsBytes = KryoPoolSingleton.get().toBytesWithClass(message);
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

	// TODO delete comment
	// With option a): Store the message, ask for the next chunk and, if all chunks are present, reassemble the message's content, deserialize it and pass it to the receiver.
	// The following code assumes that the transmitted bytes are the original message, which they shouldn't be in your proper implementation ;-)

	// Receiver proxy
	private void handle(BytesMessage<?> message) {
		// Store Message as Chunks
		byteBuffer.saveChunksToMap(message.getMessageId(), message.getOffset(), (byte[]) message.getBytes());
		final Map<Integer, byte[]> messageChunksMap = byteBuffer.getMap(message.getMessageId());
		messageChunksMap.put(message.getOffset(), (byte[]) message.getBytes());

		// Check if all chunks present
		int partitionSize = (int) Math.ceil(message.getMessageLength() * 1.0 / chunkedMessageSize);
		if (partitionSize == messageChunksMap.size()) {
			byte[] destinationMessage = new byte[message.messageLength];

			// Copying message chunks into destinationMessage byte array in correct order
			List<Integer> sortedChunkNumbers = messageChunksMap.keySet().stream().sorted().collect(Collectors.toList());
			for (Integer offset : sortedChunkNumbers) { // TODO ensure none are missing?
				byte[] sourceMessageChunk = messageChunksMap.get(offset);
				System.arraycopy(sourceMessageChunk, 0, destinationMessage, offset, sourceMessageChunk.length);
			}

			// Deserialize; Decoded Message = Original Message
			Object decodedMessage = KryoPoolSingleton.get().fromBytes(destinationMessage, message.getClass());

			// Send message to receiver
			message.getReceiver().tell(decodedMessage, message.getSender());
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
			int offset,
			long messageId,
			int messageLength
	) {
		BytesMessage<byte[]> bytesMessage = new BytesMessage<>();
		bytesMessage.receiver = receiver;
		bytesMessage.bytes = messageBytes;
		bytesMessage.offset = offset;
		bytesMessage.messageId = messageId;
		bytesMessage.messageLength = messageLength;
		bytesMessage.sender = this.sender();
		return bytesMessage;
	}
}
