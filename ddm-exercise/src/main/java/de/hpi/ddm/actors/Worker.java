package de.hpi.ddm.actors;

import java.io.Serializable;
import java.util.*;

import akka.actor.*;
import akka.cluster.Cluster;
import akka.cluster.ClusterEvent.CurrentClusterState;
import akka.cluster.ClusterEvent.MemberRemoved;
import akka.cluster.ClusterEvent.MemberUp;
import de.hpi.ddm.configuration.Configuration;
import de.hpi.ddm.singletons.ConfigurationSingleton;
import de.hpi.ddm.structures.*;
import de.hpi.ddm.systems.MasterSystem;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import akka.cluster.Member;
import akka.cluster.MemberStatus;

import static de.hpi.ddm.actors.BruteForceWorker.*;
import static de.hpi.ddm.actors.Master.*;
import static de.hpi.ddm.actors.PasswordCrackerWorker.*;
import static de.hpi.ddm.actors.PermutationHandler.*;

public class Worker extends AbstractLoggingActor {

	////////////////////////
	// Actor Construction //
	////////////////////////
	
	public static final String DEFAULT_NAME = "worker";

	public static Props props(BloomFilter welcomeData) {
		return Props.create(Worker.class, () -> new Worker(welcomeData));
	}

	public Worker(BloomFilter welcomeData) {
		this.cluster = Cluster.get(this.context().system());
		this.largeMessageProxy = this.context().actorOf(LargeMessageProxy.props(), LargeMessageProxy.DEFAULT_NAME);
		this.bruteforceWorkers = new ArrayList<>();
		this.bruteForceWorkPackages = new ArrayList<>();
		this.hintResults = new HashMap<>();
		this.welcomeData = welcomeData;
		this.numberOfHints = 0;
		this.passwordWorkPackages = new HashMap<>();
		this.workPackagesReadyForPasswordCracker = new ArrayList<>();
		this.passwordCrackerWorkers = new ArrayList<>();
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
	public static class PasswordWorkPackageMessage implements Serializable {
		private static final long serialVersionUID = -1237147518255012838L;
		private PasswordWorkPackage passwordWorkpackage;
	}

	@Data
	public static class BruteForceWorkerWorkRequestMessage implements Serializable {
		private static final long serialVersionUID = -82654819868676347L;
	}

	@Data
	public static class PasswordCrackerWorkRequestMessage implements Serializable {
		private static final long serialVersionUID = 83744819809806322L;
	}

	@Data @NoArgsConstructor @AllArgsConstructor
	public static class BruteForceResultMessage implements Serializable {
		private static final long serialVersionUID = -83744659694042645L;
		private HintResult hintResult;
	}

	@Data @NoArgsConstructor @AllArgsConstructor
	public static class PasswordCrackerResultMessage implements Serializable {
		private static final long serialVersionUID = 34564659090942333L;
		private int passwordId;
		private String crackedPassword;
	}


	/////////////////
	// Actor State //
	/////////////////

	private Member masterSystem;
	private final Cluster cluster;
	private final ActorRef largeMessageProxy;
	private final List<ActorRef> bruteforceWorkers;
	private final List<ActorRef> passwordCrackerWorkers;
	private final List<BruteForceWorkPackage> bruteForceWorkPackages;
	private final Map<Integer, List<HintResult>> hintResults;
	private final Map<Integer, PasswordWorkPackage> passwordWorkPackages;
	private final List<Integer> workPackagesReadyForPasswordCracker;
	private long registrationTime;
	private final Configuration c = ConfigurationSingleton.get();
	private final BloomFilter welcomeData;
	private int numberOfHints;

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
				.match(WelcomeMessage.class, this::handle) // Welcome from Master
				.match(PasswordWorkPackageMessage.class, this::handle) // Gets Password that should be worked on from Master
				.match(BruteForceWorkerWorkRequestMessage.class, this::handle) // BruteForceWorkers asks for Hint to crack
				.match(BruteForceResultMessage.class, this::handle) // Receives Result from BruteForceWorker
				.match(PasswordCrackerWorkRequestMessage.class, this::handle) // PasswordCracker asks for Password to crack
				.match(PasswordCrackerResultMessage.class, this::handle) // Cracked password result from password cracker
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
	
	private void handle(MemberRemoved message) {
		if (this.masterSystem.equals(message.member()))
			this.self().tell(PoisonPill.getInstance(), ActorRef.noSender());
	}
	
	private void handle(WelcomeMessage message) {
		final long transmissionTime = System.currentTimeMillis() - this.registrationTime;
		int sizeInMB = message.getWelcomeData().getSizeInMB();
		this.log().info("WelcomeMessage with " + sizeInMB + " MB data received in " + transmissionTime + " ms. Waiting for work.");
	}

	private void handle(PasswordWorkPackageMessage message) {
		this.log().info("Received Password Work Package from Master.");
		PasswordWorkPackage passwordWorkpackage = message.getPasswordWorkpackage();
		String[] hints = passwordWorkpackage.getHints();
		this.numberOfHints = hints.length;
		int passwordId = passwordWorkpackage.getId();
		this.passwordWorkPackages.put(passwordId, passwordWorkpackage);
		for (String hint: hints) {
			BruteForceWorkPackage bruteForceWorkPackage = new BruteForceWorkPackage(
					passwordId,
					passwordWorkpackage.getPasswordCharacters(),
					hint
			);
			this.bruteForceWorkPackages.add(bruteForceWorkPackage);
		}
		if (this.bruteforceWorkers.isEmpty()) {
			for (int i = 0; i < c.getNumBruteForceWorkers(); i++) {
				ActorRef actor = this.context().actorOf(
						BruteForceWorker.props(),
						BruteForceWorker.DEFAULT_NAME + "-" + this.self().path().name() + "-" +  i
				);
				this.bruteforceWorkers.add(actor);
			}
		}
		else {
			for (ActorRef bruteforceWorker : this.bruteforceWorkers) {
				giveBruteForceWorkersWork(bruteforceWorker);
			}
		}
	}

	private void handle(BruteForceWorkerWorkRequestMessage message) {
		giveBruteForceWorkersWork(this.sender());
	}

	private void handle(BruteForceResultMessage message) {
		this.log().info("Received Hint Result from {}.", this.sender().path().name());
		HintResult hintResult = message.hintResult;
		int passwordId = hintResult.getPasswordId();
		this.hintResults.putIfAbsent(passwordId, new ArrayList<>());
		this.hintResults.get(passwordId).add(hintResult);
		boolean allDone = this.hintResults.get(passwordId).size() == this.numberOfHints;
		this.log().info("{} for password {}", String.valueOf(allDone), passwordId);
		if(allDone) {
			this.workPackagesReadyForPasswordCracker.add(passwordId);
			this.log().info("Collected all Hint Results.");
			if (this.passwordCrackerWorkers.isEmpty()) {
				for (int i = 0; i < c.getNumPasswordCrackerWorkers(); i++) {
					ActorRef actor = this.context().actorOf(
							PasswordCrackerWorker.props(),
							PasswordCrackerWorker.DEFAULT_NAME + "-" + this.self().path().name() + "-" + i
					);
					this.passwordCrackerWorkers.add(actor);
				}
			}
			else {
				for (ActorRef passwordCrackerWorker : this.passwordCrackerWorkers) {
					givePasswordCrackerWork(passwordCrackerWorker);
				}
			}
			ActorSelection master = this.getContext()
					.actorSelection(this.masterSystem.address() + "/user/" + Master.DEFAULT_NAME);
			master.tell(new WorkerWorkRequestMessage(), this.self());
		}
		else {
			giveBruteForceWorkersWork(this.sender());
		}
	}

	private void handle(PasswordCrackerWorkRequestMessage message) {
		givePasswordCrackerWork(this.sender());
	}

	private void handle(PasswordCrackerResultMessage message) {
		this.getContext()
				.actorSelection(this.masterSystem.address() + "/user/" + Master.DEFAULT_NAME)
				.tell(message, this.self());
		givePasswordCrackerWork(this.sender());
	}

	////////////////////
	// Helper Methods //
	////////////////////

	private void register(Member member) {
		if ((this.masterSystem == null) && member.hasRole(MasterSystem.MASTER_ROLE)) {
			this.masterSystem = member;
			this.getContext()
					.actorSelection(member.address() + "/user/" + Master.DEFAULT_NAME)
					.tell(new RegistrationMessage(), this.self());
			this.registrationTime = System.currentTimeMillis();
		}
	}

	private void givePasswordCrackerWork(ActorRef receiver) {
		if (!this.workPackagesReadyForPasswordCracker.isEmpty()) {
			Integer passwordId = this.workPackagesReadyForPasswordCracker.remove(0);
			PasswordWorkPackage passwordWorkpackage = this.passwordWorkPackages.get(passwordId);
			List<HintResult> hintResults = this.hintResults.get(passwordId);
			PasswordAndSolvedHintsMessage passwordAndSolvedHintsMessage = new PasswordAndSolvedHintsMessage(passwordWorkpackage, hintResults);
			receiver.tell(passwordAndSolvedHintsMessage, this.self());
		}
	}

	private void giveBruteForceWorkersWork(ActorRef receiver) {
		if (!this.bruteForceWorkPackages.isEmpty()) {
			BruteForceWorkPackage bruteForceWorkPackage = this.bruteForceWorkPackages.remove(0);
			HintMessage hintMessage = new HintMessage(bruteForceWorkPackage);
			receiver.tell(hintMessage, this.self());
		}
	}
}