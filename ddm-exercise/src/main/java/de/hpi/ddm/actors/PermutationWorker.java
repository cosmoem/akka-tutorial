package de.hpi.ddm.actors;

import akka.actor.AbstractLoggingActor;
import akka.actor.ActorRef;
import akka.actor.PoisonPill;
import akka.actor.Props;
import akka.cluster.Cluster;
import akka.cluster.ClusterEvent;
import akka.cluster.Member;
import akka.cluster.MemberStatus;
import de.hpi.ddm.structures.PermutationWorkPackage;
import de.hpi.ddm.systems.MasterSystem;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static de.hpi.ddm.actors.Master.*;

public class PermutationWorker extends AbstractLoggingActor {

    ////////////////////////
    // Actor Construction //
    ////////////////////////

    public static final String DEFAULT_NAME = "permutation-worker";

    public static Props props() {
        return Props.create(PermutationWorker.class);
    }

    public PermutationWorker() {
        this.cluster = Cluster.get(this.context().system());
        this.largeMessageProxy = this.context().actorOf(LargeMessageProxy.props(), LargeMessageProxy.DEFAULT_NAME);
    }

    ////////////////////
    // Actor Messages //
    ////////////////////

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class PermutationWorkMessage implements Serializable {
        private static final long serialVersionUID = -6345481666862325L;
        private PermutationWorkPackage permutationWorkPackage;
    }

    /////////////////
    // Actor State //
    /////////////////

    private Member masterSystem;
    private final Cluster cluster;
    private final ActorRef largeMessageProxy;
    private long registrationTime;

    /////////////////////
    // Actor Lifecycle //
    /////////////////////

    @Override
    public void preStart() {
        Reaper.watchWithDefaultReaper(this);
        this.cluster.subscribe(this.self(), ClusterEvent.MemberUp.class, ClusterEvent.MemberRemoved.class);
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
                .match(ClusterEvent.CurrentClusterState.class, this::handle)
                .match(ClusterEvent.MemberUp.class, this::handle)
                .match(ClusterEvent.MemberRemoved.class, this::handle)
                .match(Worker.WelcomeMessage.class, this::handle)
                .match(PermutationWorkMessage.class, this::handle)
                .matchAny(object -> this.log().info("Received unknown message: \"{}\"", object.toString()))
                .build();
    }

    private void handle(Worker.WelcomeMessage message) {
        final long transmissionTime = System.currentTimeMillis() - this.registrationTime;
        int sizeInMB = message.getWelcomeData().getSizeInMB();
        this.log().info("WelcomeMessage with " + sizeInMB + " MB data received in " + transmissionTime + " ms.");
    }

    private void handle(ClusterEvent.CurrentClusterState message) {
        message.getMembers().forEach(member -> {
            if (member.status().equals(MemberStatus.up()))
                this.register(member);
        });
    }

    private void handle(ClusterEvent.MemberUp message) {
        this.register(message.member());
    }

    private void handle(ClusterEvent.MemberRemoved message) {
        if (this.masterSystem.equals(message.member()))
            this.self().tell(PoisonPill.getInstance(), ActorRef.noSender());
    }

    private void handle(PermutationWorkMessage message) {
        this.log().info("Received Permutation Work Package from {}.", this.sender().path().name());
        PermutationWorkPackage permutationWorkPackage = message.permutationWorkPackage;
        char[] passwordChars = permutationWorkPackage.getPasswordChars().toCharArray();
        char head = permutationWorkPackage.getHead();
        char[] charsWithoutHead = new char[passwordChars.length-1];
        int index = 0;
        for (char c: passwordChars) {
            if(!(c == head)) {
                charsWithoutHead[index] = c;
                index++;
            }
        }
        Map<String, String> permutationsWithoutHead = new HashMap<>();
        parallelHeapPermutation(
                charsWithoutHead,
                charsWithoutHead.length,
                charsWithoutHead.length-1,
                permutationsWithoutHead,
                head
        );
        //this.largeMessageProxy.tell(new LargeMessageProxy.LargeMessage<>(new Master.PermutationResultMessage(permutationsWithoutHead), this.sender()), this.self()); TODO
        this.sender().tell(new PermutationResultMessage(permutationsWithoutHead), this.self());
    }

    ////////////////////
    // Helper Methods //
    ////////////////////

    private void parallelHeapPermutation(
            char[] passwordChars,
            int charLength,
            int desiredPermutationLength,
            Map<String, String> outputMap,
            char head
    ) {
        if (charLength == 1) {
            String correctLengthString = new String(Arrays.copyOf(passwordChars, desiredPermutationLength));
            String hashed = hash(head + correctLengthString);
            outputMap.put(correctLengthString, hashed);
        }

        for (int i = 0; i < charLength; i++) {
            parallelHeapPermutation(passwordChars, charLength - 1, desiredPermutationLength, outputMap, head);
            // If size is odd, swap first and last element
            char temp;
            if (charLength % 2 == 1) {
                temp = passwordChars[0];
                passwordChars[0] = passwordChars[charLength - 1];
            }
            // If size is even, swap i-th and last element
            else {
                temp = passwordChars[i];
                passwordChars[i] = passwordChars[charLength - 1];
            }
            passwordChars[charLength - 1] = temp;
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
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e.getMessage());
        }
    }

    private void register(Member member) {
        if ((this.masterSystem == null) && member.hasRole(MasterSystem.MASTER_ROLE)) {
            this.masterSystem = member;

            this.getContext()
                    .actorSelection(member.address() + "/user/" + Master.DEFAULT_NAME)
                    .tell(new RegistrationMessage(), this.self());

            this.registrationTime = System.currentTimeMillis();
        }
    }
}
