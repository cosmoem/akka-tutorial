package de.hpi.ddm.actors;

import java.io.Serializable;
import java.sql.Struct;
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
		this.workers = new ArrayList<>();
		this.permutationWorkers = new ArrayList<>();
		this.largeMessageProxy = this.context().actorOf(LargeMessageProxy.props(), LargeMessageProxy.DEFAULT_NAME);
		this.welcomeData = welcomeData;
		this.passwordWorkpackageList = new ArrayList<>();
		this.permutations = new HashMap<>();
		this.permutationWorkPackages = new ArrayList<>();
		this.numberOfAwaitedPermutationResults = 0;
		this.numberOfHintsPerPassword = 0;
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
		private Map<String, String> permutationsPart;
	}

	/////////////////
	// Actor State //
	/////////////////

	private final ActorRef reader;
	private final ActorRef collector;
	private final List<ActorRef> workers;
	private final List<ActorRef> permutationWorkers;
	private final ActorRef largeMessageProxy;
	private final BloomFilter welcomeData;
	private final List<PasswordWorkpackage> passwordWorkpackageList;
	private final HashMap<String, String> permutations;
	private int numberOfAwaitedPermutationResults;
	private final List<PermutationWorkPackage> permutationWorkPackages;
	private long startTime;
	private int numberOfHintsPerPassword;
	private static PermutationSingleton permutationSingleton;
	
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
				// TODO: Add further messages here to share work between Master and Worker actors
				.matchAny(object -> this.log().info("Received unknown message: \"{}\"", object.toString()))
				.build();
	}



	// TODO: Terminated-Message & PoisonPill Impl



	protected void handle(StartMessage message) {
		this.startTime = System.currentTimeMillis();
		
		this.reader.tell(new Reader.ReadMessage(), this.self());
	}
	
	protected void handle(BatchMessage message) {
		// TODO: Stop fetching lines from the Reader once an empty BatchMessage was received; we have seen all data then
		if (message.getLines().isEmpty()) {
			this.reader.tell(new Reader.StopReadMessage(), this.self());
		}
		else{
			for (String[] line : message.getLines()) {
				int anzHints=line.length-5;
				String[] hints= new String[anzHints];
				if (anzHints - 5 >= 0) System.arraycopy(line, 5, hints, 0, anzHints);
				PasswordWorkpackage passwordWorkpackage= new PasswordWorkpackage(Integer.parseInt(line[0]),line[1],line[2], Integer.parseInt(line[3]),line[4],hints);
				passwordWorkpackageList.add(passwordWorkpackage);
			}

			if(permutations.isEmpty() && this.numberOfAwaitedPermutationResults==0) {
				this.log().info("Creating Permutation Workers...");
				PasswordWorkpackage workpackage = passwordWorkpackageList.get(0);
				this.numberOfHintsPerPassword = workpackage.getHints().length;
				String passwordCharacters = workpackage.getPasswordCharacters();
				for(char character: passwordCharacters.toCharArray()) {
					PermutationWorkPackage permutationWorkPackage = new PermutationWorkPackage(character, passwordCharacters);
					this.permutationWorkPackages.add(permutationWorkPackage);
				}
				// TODO wrong config leads to system crash
				for (ActorRef permutationWorker: this.permutationWorkers) {
					PermutationWorker.WorkMessage workMessage = new PermutationWorker.WorkMessage(this.permutationWorkPackages.remove(0));
					permutationWorker.tell(workMessage, this.self());
					this.numberOfAwaitedPermutationResults++;
				}
			}

			// TODO: Fetch further lines from the Reader
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

	private void handle(WorkerWorkRequestMessage message) {
		if (!passwordWorkpackageList.isEmpty()) {
			PasswordWorkpackage workpackage = passwordWorkpackageList.remove(0);
			Worker.WorkpackageMessage workpackageMessage = new Worker.WorkpackageMessage(workpackage);
			this.sender().tell(workpackageMessage, this.self());
		}
		// TODO: handle Empty List
	}

	private void handle(PermutationWorkerWorkRequestMessage permutationWorkerWorkRequestMessage) {
		PermutationWorkPackage permutationWorkPackage = this.permutationWorkPackages.remove(0);
		this.sender().tell(new PermutationWorker.WorkMessage(permutationWorkPackage), this.self());
	}

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

	protected void handle(RegistrationMessage message) {
		this.context().watch(this.sender());
		String name = this.sender().path().name();
		String type = name.substring(0, name.length() - 1);
		if(type.equals(Worker.DEFAULT_NAME)) {
			this.workers.add(this.sender());
		} else if(type.equals(PermutationWorker.DEFAULT_NAME)) {
			this.permutationWorkers.add(this.sender());
		}

		this.log().info("Registered {}", this.sender());
		
		this.largeMessageProxy.tell(new LargeMessageProxy.LargeMessage<>(new Worker.WelcomeMessage(this.welcomeData), this.sender()), this.self());
		
		// TODO: Assign some work to registering workers. Note that the processing of the global task might have already started.
	}
	
	protected void handle(Terminated message) {
		this.context().unwatch(message.getActor());
		this.workers.remove(message.getActor());
		this.log().info("Unregistered {}", message.getActor());
	}

	private void handle(PermutationResultMessage message) {
		this.log().info("Received Permutation Result.");
		this.permutations.putAll(message.permutationsPart);
		this.numberOfAwaitedPermutationResults--;
		if(this.numberOfAwaitedPermutationResults == 0) {
			HashMap<String, String> mapClone = (HashMap<String, String>) this.permutations.clone();
			PermutationSingleton.setPermutations(permutations);
			//his.sender().tell(new Worker.PermutationMessage(), this.self());
			if (!passwordWorkpackageList.isEmpty()) {
				PasswordWorkpackage workpackage = passwordWorkpackageList.remove(0);
				Worker.WorkpackageMessage workpackageMessage = new Worker.WorkpackageMessage(workpackage);
				for(ActorRef worker : workers) {
					worker.tell(workpackageMessage, this.self());
				}
			}
			/*
			boolean bruteforceWorkerAlreadySpawned = false;
			scala.collection.Iterator<ActorRef> iterator = context().children().iterator();
			while(iterator.hasNext()) {
				ActorRef child = iterator.next();
				String name = child.path().name();
				if(name.substring(0, name.length() - 1).equals(BruteForceWorker.DEFAULT_NAME)) {
					bruteforceWorkerAlreadySpawned = true;
					break;
				}
			}

			if(!bruteforceWorkerAlreadySpawned) {
				int numberOfWorkers = this.numberOfHintsPerPassword/2;
				for (int i = 0; i < numberOfWorkers; i++) {
					this.context().actorOf(BruteForceWorker.props(), BruteForceWorker.DEFAULT_NAME + i);
				}
			}*/
		}
	}
}
