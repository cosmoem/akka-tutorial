package de.hpi.ddm.actors;

import java.io.Serializable;
import java.util.*;

import akka.actor.*;
import akka.cluster.Cluster;
import akka.cluster.ClusterEvent.CurrentClusterState;
import akka.cluster.ClusterEvent.MemberRemoved;
import akka.cluster.ClusterEvent.MemberUp;
import de.hpi.ddm.structures.*;
import de.hpi.ddm.systems.MasterSystem;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import akka.cluster.Member;
import akka.cluster.MemberStatus;

public class Worker extends AbstractLoggingActor {

	////////////////////////
	// Actor Construction //
	////////////////////////
	
	public static final String DEFAULT_NAME = "worker";

	public static Props props() {
		return Props.create(Worker.class);
	}

	public Worker() {
		this.cluster = Cluster.get(this.context().system());
		this.largeMessageProxy = this.context().actorOf(LargeMessageProxy.props(), LargeMessageProxy.DEFAULT_NAME);
		this.permutations = new HashMap<>();
		this.permutationWorkPackages = new ArrayList<>();
		this.bruteForceWorkPackages = new ArrayList<>();
		this.numberOfAwaitedPermutationResults = 0;
		this.numberOfHintsPerPassword = 0;
		this.hintResults = new HashMap<>();
	}
	
	////////////////////
	// Actor Messages //
	////////////////////

	@Data @NoArgsConstructor @AllArgsConstructor
	public static class WelcomeMessage implements Serializable {
		private static final long serialVersionUID = 8343040942748609598L;
		private BloomFilter welcomeData;
	}
	@Data @NoArgsConstructor @AllArgsConstructor
	public static class WorkpackageMessage implements Serializable {
		private static final long serialVersionUID = -1237147518255012838L;
		private PasswordWorkpackage passwordWorkpackage;
	}

	@Data
	public static class NextMessage implements Serializable {
		private static final long serialVersionUID = -82654819868676347L;
	}

	@Data @NoArgsConstructor @AllArgsConstructor
	public static class PermutationResultMessage implements Serializable {
		private static final long serialVersionUID = -72434659866542342L;
		private Map<String, String> permutationsPart;
	}

	@Data @NoArgsConstructor @AllArgsConstructor
	public static class BruteForceResultMessage implements Serializable {
		private static final long serialVersionUID = -83744659694042645L;
		private HintResult hintResult;
	}


	/////////////////
	// Actor State //
	/////////////////

	private Member masterSystem;
	private final Cluster cluster;
	private final ActorRef largeMessageProxy;
	private long registrationTime;
	private Map<String, String> permutations;
	private int numberOfAwaitedPermutationResults;
	private List<PermutationWorkPackage> permutationWorkPackages;
	private List<BruteForceWorkPackage> bruteForceWorkPackages;
	private int numberOfHintsPerPassword;
	private Map<Integer, List<HintResult>> hintResults;
	
	/////////////////////
	// Actor Lifecycle //
	/////////////////////

	@Override
	public void preStart() {
		Reaper.watchWithDefaultReaper(this);
		
		this.cluster.subscribe(this.self(), MemberUp.class, MemberRemoved.class);
	}

	@Override
	public void postStop() {
		this.cluster.unsubscribe(this.self());
	}

	////////////////////
	// Actor Behavior //
	////////////////////

	@Override
	public Receive createReceive() {
		return receiveBuilder()
				.match(CurrentClusterState.class, this::handle)
				.match(MemberUp.class, this::handle)
				.match(MemberRemoved.class, this::handle)
				.match(WelcomeMessage.class, this::handle)
				// TODO: Add further messages here to share work between Master and Worker actors
				.match(WorkpackageMessage.class, this::handle)
				.match(NextMessage.class, this::handle)
				.match(PermutationResultMessage.class, this::handle)
				.match(BruteForceResultMessage.class, this::handle)
				.matchAny(object -> this.log().info("Received unknown message: \"{}\"", object.toString()))
				.build();
	}

	private void handle(CurrentClusterState message) {
		message.getMembers().forEach(member -> {
			if (member.status().equals(MemberStatus.up()))
				this.register(member);
		});
	}

	private void handle(MemberUp message) {
		this.register(message.member());
	}

	private void register(Member member) {
		if ((this.masterSystem == null) && member.hasRole(MasterSystem.MASTER_ROLE)) {
			this.masterSystem = member;
			
			this.getContext()
				.actorSelection(member.address() + "/user/" + Master.DEFAULT_NAME)
				.tell(new Master.RegistrationMessage(), this.self());
			
			this.registrationTime = System.currentTimeMillis();
		}
	}
	
	private void handle(MemberRemoved message) {
		if (this.masterSystem.equals(message.member()))
			this.self().tell(PoisonPill.getInstance(), ActorRef.noSender());
	}
	
	private void handle(WelcomeMessage message) {
		final long transmissionTime = System.currentTimeMillis() - this.registrationTime;
		this.log().info("WelcomeMessage with " + message.getWelcomeData().getSizeInMB() + " MB data received in " + transmissionTime + " ms.");
		this.getContext()
				.actorSelection(this.masterSystem.address() + "/user/" + Master.DEFAULT_NAME)
				.tell(new Master.NextMessage(), this.self());
	}

	private void handle(WorkpackageMessage message) {
		PasswordWorkpackage workpackage = message.getPasswordWorkpackage();
		this.log().info("Received Work!");
		String[] hints = workpackage.getHints();
		for(String hint: hints) {
			BruteForceWorkPackage bruteForceWorkPackage = new BruteForceWorkPackage(workpackage.getId(), workpackage.getPasswordCharacters(), hint);
			bruteForceWorkPackages.add(bruteForceWorkPackage);
		}

		if(permutations.isEmpty() && this.numberOfAwaitedPermutationResults==0) {
			this.log().info("Creating Permutation Workers...");
			this.numberOfHintsPerPassword = hints.length;
			String passwordCharacters = workpackage.getPasswordCharacters();
			for(char character: passwordCharacters.toCharArray()) {
				PermutationWorkPackage permutationWorkPackage = new PermutationWorkPackage(character, passwordCharacters);
				this.permutationWorkPackages.add(permutationWorkPackage);
			}
			int numberOfPermutationWorkers = passwordCharacters.length();
			this.numberOfAwaitedPermutationResults = numberOfPermutationWorkers;
			for (int i = 0; i < numberOfPermutationWorkers; i++) {
				this.context().actorOf(PermutationWorker.props(), PermutationWorker.DEFAULT_NAME + i);
			}
		}
	}

	private void handle(NextMessage message) {
		String name = sender().path().name();
		String type = name.substring(0, name.length() - 1);
		this.log().info("Received Work Request from {}", sender().path().name());
		if(type.equals(BruteForceWorker.DEFAULT_NAME)) {
			BruteForceWorkPackage workpackage = bruteForceWorkPackages.remove(0);
			BruteForceWorker.HintMessage hintMessage = new BruteForceWorker.HintMessage(workpackage, permutations);
			this.sender().tell(hintMessage, this.self());
		}
		else if(type.equals(PermutationWorker.DEFAULT_NAME)) {
			PermutationWorkPackage permutationWorkPackage = this.permutationWorkPackages.remove(0);
			//this.sender().tell(new PermutationWorker.WorkMessage(permutationWorkPackage), this.self());
		}
	}


	private void handle(PermutationResultMessage message) {
		this.log().info("Received Permutation Result.");
		this.permutations.putAll(message.permutationsPart);
		this.numberOfAwaitedPermutationResults--;
		if(this.numberOfAwaitedPermutationResults==0) {
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
			}
		}
	}


	private void handle(BruteForceResultMessage message) {
		this.log().info("Received Hint Result.");
		HintResult hintResult = message.hintResult;
		this.hintResults.putIfAbsent(hintResult.getPasswordId(), new ArrayList<>());
		this.hintResults.get(hintResult.getPasswordId()).add(hintResult);
	}

}