package de.hpi.ddm.actors;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import akka.actor.AbstractLoggingActor;
import akka.actor.ActorRef;
import akka.actor.PoisonPill;
import akka.actor.Props;
import akka.actor.Terminated;
import de.hpi.ddm.structures.BloomFilter;
import de.hpi.ddm.structures.PasswordWorkPackage;
import de.hpi.ddm.structures.PermutationWorkPackage;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import static de.hpi.ddm.actors.LargeMessageProxy.*;
import static de.hpi.ddm.actors.PermutationHandler.*;
import static de.hpi.ddm.actors.Worker.*;

public class Master extends AbstractLoggingActor {

	////////////////////////
	// Actor Construction //
	////////////////////////
	
	public static final String DEFAULT_NAME = "master";

	public static Props props(final ActorRef reader, final ActorRef collector, final BloomFilter welcomeData) {
		return Props.create(Master.class, () -> new Master(reader, collector, welcomeData));
	}

	public Master(final ActorRef reader, final ActorRef collector, final BloomFilter welcomeData) {
		this.reader = reader;
		this.collector = collector;
		this.largeMessageProxy = this.context().actorOf(LargeMessageProxy.props(), LargeMessageProxy.DEFAULT_NAME);
		this.workers = new ArrayList<>();
		this.welcomeData = welcomeData;
		this.passwordWorkPackages = new ArrayList<>();
		this.permutationWorkPackages = new ArrayList<>();
		this.resultTracker = new HashMap<>();
	}

	////////////////////
	// Actor Messages //
	////////////////////

	@Data
	public static class StartMessage implements Serializable {
		private static final long serialVersionUID = -50374816448627600L;
	}
	
	@Data @NoArgsConstructor @AllArgsConstructor
	public static class BatchMessage implements Serializable {
		private static final long serialVersionUID = 8343040942748609598L;
		private List<String[]> lines;
	}

	@Data
	public static class RegistrationMessage implements Serializable {
		private static final long serialVersionUID = 3303081601659723997L;
	}

	@Data
	public static class WorkerWorkRequestMessage implements Serializable {
		private static final long serialVersionUID = -20374816448627627L;
	}

	@Data
	public static class PermutationWorkPackageRequest implements Serializable {
		private static final long serialVersionUID = -63434816465427697L;
	}

	@Data
	public static class PermutationsReadyMessage implements Serializable {
		private static final long serialVersionUID = 12344816432127698L;
	}

	/////////////////
	// Actor State //
	/////////////////

	private final ActorRef reader;
	private final ActorRef collector;
	private final ActorRef largeMessageProxy;
	private final List<ActorRef> workers;
	private final BloomFilter welcomeData;
	private final List<PasswordWorkPackage> passwordWorkPackages;
	private final List<PermutationWorkPackage> permutationWorkPackages;
	private final Map<Integer, Boolean> resultTracker;
	private long startTime;
	
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
				.match(StartMessage.class, this::handle)
				.match(BatchMessage.class, this::handle)
				.match(Terminated.class, this::handle)
				.match(RegistrationMessage.class, this::handle) // Registration from PermutationHandler & Workers
				.match(PermutationWorkPackageRequest.class, this::handle) // PermutationHandler asks for Work Packages
				.match(PermutationsReadyMessage.class, this::handle) // PermutationHandler signals that Permutation Calculation is done
				.match(WorkerWorkRequestMessage.class, this::handle) // Worker asks for next password to crack
				.match(PasswordCrackerResultMessage.class, this::handle) // Password result from worker
				.matchAny(object -> this.log().info("Received unknown message: \"{}\"", object.toString()))
				.build();
	}

	// TODO: Terminated-Message & PoisonPill Impl ?

	protected void handle(StartMessage message) {
		this.log().info("Received StartMessage from MasterSystem.");
		this.startTime = System.currentTimeMillis();
		this.reader.tell(new Reader.ReadMessage(), this.self());
	}
	
	protected void handle(BatchMessage message) {
		// Stop fetching lines from the Reader once an empty BatchMessage was received; we have seen all data then
		this.log().info("Received BatchMessage from Reader.");
		if (message.getLines().isEmpty()) {
			this.reader.tell(new Reader.StopReadMessage(), this.self());
		}
		else {
			for (String[] line : message.getLines()) {
				int numberOfHints = line.length-5;
				String[] hints = new String[numberOfHints];
				if (numberOfHints - 5 >= 0) {
					System.arraycopy(line, 5, hints, 0, numberOfHints);
				}
				int passwordId = Integer.parseInt(line[0]);
				PasswordWorkPackage passwordWorkpackage = new PasswordWorkPackage(
						passwordId,
						line[1],
						line[2],
						Integer.parseInt(line[3]),
						line[4],
						hints
				);
				this.passwordWorkPackages.add(passwordWorkpackage);
				this.resultTracker.put(passwordId, false);
			}

			// creation of permutation work packages
			if (this.permutationWorkPackages.isEmpty()) {
				PasswordWorkPackage passwordWorkpackage = this.passwordWorkPackages.get(0);
				String passwordCharacters = passwordWorkpackage.getPasswordCharacters();
				char[] charactersArray = passwordCharacters.toCharArray();
				for (char character: charactersArray) {
					for (char character2: charactersArray) {
						if (character2!=character) {
							PermutationWorkPackage permutationWorkPackage = new PermutationWorkPackage(character, character2, passwordCharacters);
							this.permutationWorkPackages.add(permutationWorkPackage);
						}
					}
				}
			}

			// Fetch further lines from the Reader
			this.reader.tell(new Reader.ReadMessage(), this.self());
		}
	}

	protected void handle(Terminated message) {
		this.context().unwatch(message.getActor());
		this.workers.remove(message.getActor());
		this.log().info("Unregistered {}", message.getActor());
	}

	protected void handle(RegistrationMessage message) {
		this.context().watch(this.sender());
		String name = this.sender().path().name();
		String type = name.substring(0, name.length() - 1);
		if (type.equals(Worker.DEFAULT_NAME)) {
			this.workers.add(this.sender());
		}

		this.log().info("Registered {}", this.sender());

		WelcomeMessage welcomeMessage = new WelcomeMessage(this.welcomeData);
		LargeMessage<WelcomeMessage> largeMessage = new LargeMessage<>(welcomeMessage, this.sender());
		this.largeMessageProxy.tell(largeMessage, this.self());
	}

	private void handle(PermutationWorkPackageRequest message) {
		this.log().info("Received Request for Permutation Work Packages from {}", this.sender().path().name());
		if(!this.permutationWorkPackages.isEmpty()) {
			PermutationWorkPackagesMessage workPackagesMessage = new PermutationWorkPackagesMessage(this.permutationWorkPackages);
			LargeMessage<PermutationWorkPackagesMessage> largeMessage = new LargeMessage<>(workPackagesMessage, this.sender());
			this.largeMessageProxy.tell(largeMessage, this.self());
		}
	}

	private void handle(PermutationsReadyMessage message) {
		this.log().info("Received Signal that Permutations are ready for System {}", this.sender().path().parent().name());
		for (ActorRef worker : this.workers) {
			if (!this.passwordWorkPackages.isEmpty()) {
				if (worker.path().parent().equals(sender().path().parent())) {
					PasswordWorkPackage passwordWorkpackage = this.passwordWorkPackages.remove(0);
					worker.tell(new PasswordWorkPackageMessage(passwordWorkpackage), this.self());
				}
			}
		}
	}

	private void handle(WorkerWorkRequestMessage message) {
		// TODO: handle empty work package list
		if (!this.passwordWorkPackages.isEmpty()) {
			PasswordWorkPackage passwordWorkpackage = this.passwordWorkPackages.remove(0);
			this.sender().tell(new PasswordWorkPackageMessage(passwordWorkpackage), this.self());
		}
	}

	private void handle(PasswordCrackerResultMessage message) {
		this.collector.tell(message, this.self());
		this.resultTracker.put(message.getPasswordId(), true);
		boolean allDone = true;
		for (Boolean value : this.resultTracker.values()) {
			if (!value) {
				allDone = false;
				break;
			}
		}
		if (allDone) {
			terminate();
		}
	}

	////////////////////
	// Helper Methods //
	////////////////////

	protected void terminate() {
		this.collector.tell(new Collector.PrintMessage(), this.self());
		this.reader.tell(PoisonPill.getInstance(), ActorRef.noSender());
		this.collector.tell(PoisonPill.getInstance(), ActorRef.noSender());

		for (ActorRef worker : this.workers) {
			this.context().unwatch(worker);
			worker.tell(PoisonPill.getInstance(), ActorRef.noSender());
		}

		this.self().tell(PoisonPill.getInstance(), ActorRef.noSender());

		long executionTime = System.currentTimeMillis() - this.startTime;
		this.log().info("Algorithm finished in {} ms", executionTime);
	}
}
