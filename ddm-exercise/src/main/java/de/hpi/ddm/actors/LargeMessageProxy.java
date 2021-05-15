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
import de.hpi.ddm.singletons.KryoPoolSingleton;
import de.hpi.ddm.structures.ByteBuffer;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

public class LargeMessageProxy extends AbstractLoggingActor {

	////////////////////////
	// Actor Construction //
	////////////////////////



	public static final int CHUNKED_MESSAGE_SIZE =8192;

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

		private int length;
		private int offset;
		private long id;

	}


	private static AtomicLong idCounter = new AtomicLong();

	public static long createID()
	{
		return idCounter.getAndIncrement();
	}

	/////////////////
	// Actor State //
	/////////////////

	/////////////////////
	// Actor Lifecycle //
	/////////////////////

	////////////////////
	// Actor Behavior //
	////////////////////

	// receiver expects Bytemessage therefore we need to return a Bytemessage object in our chunkCreator
	@Override
	public Receive createReceive() {
		return receiveBuilder()
				.match(LargeMessage.class, this::handle)
				.match(BytesMessage.class, this::handle)
				.matchAny(object -> this.log().info("Received unknown message: \"{}\"", object.toString()))
				.build();
	}


	private BytesMessage<byte[]> chunkCreator(ActorRef receiver, byte [] bytes, int offset, long id, int length){
		BytesMessage <byte[]> bytesMessage= new BytesMessage<>();
		bytesMessage.receiver= receiver;
		bytesMessage.bytes=bytes;
		bytesMessage.offset=offset;
		bytesMessage.id=id;
		bytesMessage.length=length;
		bytesMessage.sender=this.sender();
		return bytesMessage;
	}


	private void handle(LargeMessage<?> largeMessage) {
		Object message = largeMessage.getMessage();
		ActorRef sender = this.sender();
		ActorRef receiver = largeMessage.getReceiver();
		ActorSelection receiverProxy = this.context().actorSelection(receiver.path().child(DEFAULT_NAME));

		// TODO: Implement a protocol that transmits the potentially very large message object.
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


		// serialize to byte array
		KryoPool kryoPool= KryoPoolSingleton.get();
		byte[] bytes = kryoPool.toBytesWithClass(message);
		long messageID= createID();

		// send bytes chunk-wise to receiver proxy
		for(int index=0; index < bytes.length; index += CHUNKED_MESSAGE_SIZE){
			receiverProxy.tell(chunkCreator(receiver,
					Arrays.copyOfRange(bytes,index,Math.min(index+ CHUNKED_MESSAGE_SIZE , bytes.length)),index, messageID, bytes.length),sender);
		}
	}





	private void handle(BytesMessage<?> message) {
        // TODO: With option a): Store the message, ask for the next chunk and, if all chunks are present, reassemble the message's content, deserialize it and pass it to the receiver.
		// The following code assumes that the transmitted bytes are the original message, which they shouldn't be in your proper implementation ;-)



		// Store Message as Chunks
		ByteBuffer.getBuffer().safeChunksToMap(message.getId(), message.getOffset(), (byte[]) message.getBytes());

		Map<Integer, byte[]> map = ByteBuffer.getBuffer().getMap(message.getId());

		map.put(message.getOffset(), (byte[]) message.getBytes());

		// Check if all chunks present
		int partitionSize= (int) Math.ceil(message.getLength() * 1.0 / CHUNKED_MESSAGE_SIZE);
		if(partitionSize== map.size()){

			// Decode
			byte[] destinationMessage= new byte[message.length];

			//sort keys of map, sort and reduct it to list
			List<Integer> collect = map.keySet().stream().sorted().collect(Collectors.toList());
			for (Integer offset : collect) {
				byte[] sourceMessage = map.get(offset);
				int destPos=offset;
				int totalLength= map.get(offset).length;
				System.arraycopy(sourceMessage, 0, destinationMessage, destPos,
						totalLength);
			}

			// Deserialize; Decoded Message = Orginal Message
			Object decodedMessage = KryoPoolSingleton.get().fromBytes(destinationMessage);

			// Transmit message and trigger forwarding to parent
			message.getReceiver().tell(decodedMessage, message.getSender());
		}
	}

}
