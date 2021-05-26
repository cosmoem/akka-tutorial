package de.hpi.ddm.actors;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import akka.actor.AbstractLoggingActor;
import akka.actor.Props;
import lombok.Data;

import static de.hpi.ddm.actors.Worker.*;

public class Collector extends AbstractLoggingActor {

	////////////////////////
	// Actor Construction //
	////////////////////////
	
	public static final String DEFAULT_NAME = "collector";

	public static Props props() {
		return Props.create(Collector.class);
	}

	////////////////////
	// Actor Messages //
	////////////////////

	@Data
	public static class PrintMessage implements Serializable {
		private static final long serialVersionUID = -267778464637901383L;
	}
	
	/////////////////
	// Actor State //
	/////////////////
	
	private List<String> results = new ArrayList<>();
	
	/////////////////////
	// Actor Lifecycle //
	/////////////////////

	@Override
	public void preStart() {
		Reaper.watchWithDefaultReaper(this);
	}

	////////////////////
	// Actor Behavior //
	////////////////////

	@Override
	public Receive createReceive() {
		return receiveBuilder()
				.match(PrintMessage.class, this::handle)
				.match(PasswordCrackerResultMessage.class, this::handle) // Password result from master
				.matchAny(object -> this.log().info("Received unknown message: \"{}\"", object.toString()))
				.build();
	}
	
	protected void handle(PrintMessage message) {
		this.results.forEach(result -> this.log().info("{}", result));
	}

	private void handle(PasswordCrackerResultMessage message) {
		String crackedPassword = message.getCrackedPassword();
		int passwordId = message.getPasswordId();
		this.results.add(crackedPassword);
		this.log().info("Added Cracked password with ID {}: {}", passwordId, crackedPassword);
	}
}
