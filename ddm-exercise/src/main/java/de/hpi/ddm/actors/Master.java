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
import de.hpi.ddm.singletons.PermutationSingleton;
import de.hpi.ddm.structures.BloomFilter;
import de.hpi.ddm.structures.PasswordWorkpackage;
import de.hpi.ddm.structures.PermutationWorkPackage;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import static de.hpi.ddm.actors.LargeMessageProxy.*;
import static de.hpi.ddm.actors.PermutationWorker.*;
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
		this.permutationWorkers = new ArrayList<>();
		this.welcomeData = welcomeData;
		//this.permutations = new HashMap<>();
		this.permuationsReady = false;
		this.passwordWorkPackages = new ArrayList<>();
		this.permutationWorkPackages = new ArrayList<>();
		this.numberOfAwaitedPermutationResults = 0;
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
	public static class PermutationWorkerWorkRequestMessage implements Serializable {
		private static final long serialVersionUID = -63434816465427697L;
	}

	@Data @NoArgsConstructor @AllArgsConstructor
	public static class PermutationResultMessage implements Serializable {
		private static final long serialVersionUID = -72434659866542342L;
		//private Map<String, String> permutationsPart;
		private char head;
	}

	/////////////////
	// Actor State //
	/////////////////

	private final ActorRef reader;
	private final ActorRef collector;
	private final ActorRef largeMessageProxy;
	private final List<ActorRef> workers;
	private final List<ActorRef> permutationWorkers;
	private final BloomFilter welcomeData;
	//private final HashMap<String, String> permutations;
	private boolean permuationsReady;
	private final List<PasswordWorkpackage> passwordWorkPackages;
	private final List<PermutationWorkPackage> permutationWorkPackages;
	private int numberOfAwaitedPermutationResults;
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
				.match(RegistrationMessage.class, this::handle)
				.match(WorkerWorkRequestMessage.class, this::handle)
				.match(PermutationWorkerWorkRequestMessage.class, this::handle)
				.match(PermutationResultMessage.class, this::handle)
				.matchAny(object -> this.log().info("Received unknown message: \"{}\"", object.toString()))
				.build();
	}

	// TODO: Terminated-Message & PoisonPill Impl ?

	protected void handle(StartMessage message) {
		this.startTime = System.currentTimeMillis();
		this.reader.tell(new Reader.ReadMessage(), this.self());
	}
	
	protected void handle(BatchMessage message) {
		// Stop fetching lines from the Reader once an empty BatchMessage was received; we have seen all data then
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
				PasswordWorkpackage passwordWorkpackage = new PasswordWorkpackage(
						Integer.parseInt(line[0]),
						line[1],
						line[2],
						Integer.parseInt(line[3]),
						line[4],
						hints
				);
				this.passwordWorkPackages.add(passwordWorkpackage);
			}

			if (!this.permuationsReady) {
				PasswordWorkpackage passwordWorkpackage = this.passwordWorkPackages.get(0);
				String passwordCharacters = passwordWorkpackage.getPasswordCharacters();
				for (char character: passwordCharacters.toCharArray()) {
					PermutationWorkPackage permutationWorkPackage = new PermutationWorkPackage(character, passwordCharacters);
					this.permutationWorkPackages.add(permutationWorkPackage);
				}
				this.permuationsReady = true;
			}

			// Fetch further lines from the Reader
			this.reader.tell(new Reader.ReadMessage(), this.self());
		}

		// TODO: This is where the task begins:
		// - The Master received the first batch of input records.
		// - To receive the next batch, we need to send another ReadMessage to the reader.
		// - If the received BatchMessage is empty, we have seen all data for this task.
		// - We need a clever protocol that forms sub-tasks from the seen records, distributes the tasks to the known workers and manages the results.
		//   -> Additional messages, maybe additional actors, code that solves the subtasks, ...
		//   -> The code in this handle function needs to be re-written.
		// - Once the entire processing is done, this.terminate() needs to be called.
		
		// Info: Why is the input file read in batches?
		// a) Latency hiding: The Reader is implemented such that it reads the next batch of data from disk while at the same time the requester of the current batch processes this batch.
		// b) Memory reduction: If the batches are processed sequentially, the memory consumption can be kept constant; if the entire input is read into main memory, the memory consumption scales at least linearly with the input size.
		// - It is your choice, how and if you want to make use of the batched inputs. Simply aggregate all batches in the Master and start the processing afterwards, if you wish.

		// TODO: Send (partial) results to the Collector
		this.collector.tell(new Collector.CollectMessage("If I had results, this would be one."), this.self());
	}

	protected void handle(Terminated message) {
		this.context().unwatch(message.getActor());
		this.workers.remove(message.getActor());
		this.permutationWorkers.remove(message.getActor());
		this.log().info("Unregistered {}", message.getActor());
	}

	protected void handle(RegistrationMessage message) {
		this.context().watch(this.sender());
		String name = this.sender().path().name();
		String type = name.substring(0, name.length() - 1);
		if (type.equals(Worker.DEFAULT_NAME)) {
			this.workers.add(this.sender());
		} else if (type.equals(PermutationWorker.DEFAULT_NAME)) {
			this.permutationWorkers.add(this.sender());
		}

		this.log().info("Registered {}", this.sender());

		WelcomeMessage welcomeMessage = new WelcomeMessage(this.welcomeData);
		LargeMessage<WelcomeMessage> largeMessage = new LargeMessage<>(welcomeMessage, this.sender());
		this.largeMessageProxy.tell(largeMessage, this.self());
	}

	private void handle(WorkerWorkRequestMessage message) {
		// TODO: handle empty work package list
		if (!this.passwordWorkPackages.isEmpty()) {
			PasswordWorkpackage passwordWorkpackage = this.passwordWorkPackages.remove(0);
			this.sender().tell(new PasswordWorkPackageMessage(passwordWorkpackage), this.self());
		}
	}

	private void handle(PermutationWorkerWorkRequestMessage permutationWorkerWorkRequestMessage) {
		if(!this.permutationWorkPackages.isEmpty()) {
			PermutationWorkPackage permutationWorkPackage = this.permutationWorkPackages.remove(0);
			PermutationWorkMessage permutationWorkMessage = new PermutationWorkMessage(permutationWorkPackage);
			LargeMessage<PermutationWorkMessage> largeMessage = new LargeMessage<>(permutationWorkMessage, this.sender());
			this.largeMessageProxy.tell(largeMessage, this.self());
			this.numberOfAwaitedPermutationResults++;
		}
		// TODO empty list handling
	}

	private void handle(PermutationResultMessage message) {
		this.log().info("Received Permutation Result from {} for letter {}.", this.sender().path().name(), message.head);
		//this.permutations.putAll(message.permutationsPart);
		this.numberOfAwaitedPermutationResults--;
		if (this.numberOfAwaitedPermutationResults == 0) {
			//PermutationSingleton.setPermutations(permutations);
			// TODO handle empty list
			for(ActorRef worker : this.workers) {
				if (!this.passwordWorkPackages.isEmpty()) {
					PasswordWorkpackage passwordWorkpackage = this.passwordWorkPackages.remove(0);
					PasswordWorkPackageMessage workPackageMessage = new PasswordWorkPackageMessage(passwordWorkpackage);
					worker.tell(workPackageMessage, this.self());
				}
			}
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
