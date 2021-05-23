package de.hpi.ddm.actors;

import java.io.Serializable;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import akka.actor.*;
import akka.cluster.Cluster;
import akka.cluster.ClusterEvent.CurrentClusterState;
import akka.cluster.ClusterEvent.MemberRemoved;
import akka.cluster.ClusterEvent.MemberUp;
import de.hpi.ddm.structures.BloomFilter;
import de.hpi.ddm.structures.PasswordWorkpackage;
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
	/////////////////
	// Actor State //
	/////////////////

	private Member masterSystem;
	private final Cluster cluster;
	private final ActorRef largeMessageProxy;
	private long registrationTime;
	private Map<String, String> permutations;
	
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
		if(permutations.isEmpty()) {
			//char[] test = {'a', 'b', 'c'};
			//heapPermutation(test, test.length, 2, permutations);
			char[] passwordCharacters = workpackage.getPasswordCharacters().toCharArray();
			heapPermutation(passwordCharacters, passwordCharacters.length, workpackage.getPasswordLength(), permutations);
		}
		int numberOfWorkers = workpackage.getHints().length/3;
		this.log().info("Received Work!");
		for (int i = 0; i < numberOfWorkers; i++) {
			ActorRef actor = this.context().actorOf(BruteForceWorker.props(), BruteForceWorker.DEFAULT_NAME + i);
			BruteForceWorker.HintMessage hintMessage = new BruteForceWorker.HintMessage(workpackage, permutations, i);
			actor.tell(hintMessage, this.self());
		}
	}



	private String hash(String characters) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] hashedBytes = digest.digest(String.valueOf(characters).getBytes(StandardCharsets.UTF_8));
			StringBuilder stringBuffer = new StringBuilder();
			for (byte hashedByte : hashedBytes) {
				stringBuffer.append(Integer.toString((hashedByte & 0xff) + 0x100, 16).substring(1));
			}
			return stringBuffer.toString();
		}
		catch (NoSuchAlgorithmException e) {
			throw new RuntimeException(e.getMessage());
		}
	}

	// Generating all permutations of an array using Heap's Algorithm
	// https://en.wikipedia.org/wiki/Heap's_algorithm
	// https://www.geeksforgeeks.org/heaps-algorithm-for-generating-permutations/
	private void heapPermutation(char[] chars, int charLength, int desiredPermutationLength, Map<String, String> outputMap) {
		// If size is 1, store the obtained permutation
		if (charLength == 1) {
			String correctLength = new String(Arrays.copyOf(chars, desiredPermutationLength));
			String hashed = hash(correctLength);
			outputMap.put(correctLength, hashed);
		}

		for (int i = 0; i < charLength; i++) {
			heapPermutation(chars, charLength - 1, desiredPermutationLength, outputMap);
			// If size is odd, swap first and last element
			char temp;
			if (charLength % 2 == 1) {
				temp = chars[0];
				chars[0] = chars[charLength - 1];
			}
			// If size is even, swap i-th and last element
			else {
				temp = chars[i];
				chars[i] = chars[charLength - 1];
			}
			chars[charLength - 1] = temp;
		}
	}

	private void permutations(char[] chars, int charLength, int desiredPermutationLength, Map<String, String> outputMap)
}