package de.hpi.ddm.actors;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import akka.actor.ActorRef;
import com.opencsv.CSVReader;

import akka.actor.AbstractLoggingActor;
import akka.actor.Props;
import de.hpi.ddm.singletons.ConfigurationSingleton;
import de.hpi.ddm.singletons.DatasetDescriptorSingleton;
import lombok.Data;

import static de.hpi.ddm.actors.LargeMessageProxy.*;
import static de.hpi.ddm.actors.Master.*;

public class Reader extends AbstractLoggingActor {

	////////////////////////
	// Actor Construction //
	////////////////////////
	
	public static final String DEFAULT_NAME = "reader";

	public Reader() {
		largeMessageProxy = this.context().actorOf(LargeMessageProxy.props(), LargeMessageProxy.DEFAULT_NAME);;
	}

	public static Props props() {
		return Props.create(Reader.class);
	}

	////////////////////
	// Actor Messages //
	////////////////////

	@Data
	public static class ReadMessage implements Serializable {
		private static final long serialVersionUID = -3254147511955012292L;
	}

	@Data
	public static class StopReadMessage implements Serializable {
		private static final long serialVersionUID = -1254147518255012831L;
	}
	
	/////////////////
	// Actor State //
	/////////////////

	private final ActorRef largeMessageProxy;
	private CSVReader reader;
	private int bufferSize;
	private List<String[]> buffer;
	
	/////////////////////
	// Actor Lifecycle //
	/////////////////////

	@Override
	public void preStart() throws Exception {
		Reaper.watchWithDefaultReaper(this);
		
		this.reader = DatasetDescriptorSingleton.get().createCSVReader();
		this.bufferSize = ConfigurationSingleton.get().getBufferSize();
		this.buffer = new ArrayList<>(this.bufferSize);
		
		this.read();
		this.log().info("Started Reading...");
	}

	@Override
	public void postStop() throws Exception {
		this.reader.close();
	}

	////////////////////
	// Actor Behavior //
	////////////////////

	@Override
	public Receive createReceive() {
		return receiveBuilder()
				.match(ReadMessage.class, this::handle)
				.match(StopReadMessage.class, this::handle)
				.matchAny(object -> this.log().info("Received unknown message: \"{}\"", object.toString()))
				.build();
	}

	private void handle(ReadMessage message) throws Exception {
		BatchMessage batchMessage = new BatchMessage(new ArrayList<>(this.buffer));
		LargeMessage<BatchMessage> largeMessage = new LargeMessage<>(batchMessage, this.sender());
		this.largeMessageProxy.tell(largeMessage, this.self());
		this.read();
	}


	private void handle(StopReadMessage message) {
		this.buffer.clear();
		this.log().info("Reached end of File stopped reading");
	}
	
	private void read() throws Exception {
		this.buffer.clear();
		
		String[] line;
		while ((this.buffer.size() < this.bufferSize) && ((line = this.reader.readNext()) != null))
			this.buffer.add(line);
	}
}
