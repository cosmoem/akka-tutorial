package de.hpi.ddm.actors;

import akka.actor.AbstractLoggingActor;
import akka.actor.ActorRef;
import akka.actor.PoisonPill;
import akka.actor.Props;
import akka.cluster.Cluster;
import akka.cluster.ClusterEvent;
import akka.cluster.Member;
import akka.cluster.MemberStatus;
import de.hpi.ddm.structures.PasswordWorkpackage;
import de.hpi.ddm.systems.MasterSystem;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.*;

public class BruteForceWorker extends AbstractLoggingActor {

    ////////////////////////
    // Actor Construction //
    ////////////////////////

    public static final String DEFAULT_NAME = "brute-force-worker";

    public static Props props() {
        return Props.create(BruteForceWorker.class);
    }

    public BruteForceWorker() {
        this.cluster = Cluster.get(this.context().system());
        this.largeMessageProxy = this.context().actorOf(LargeMessageProxy.props(), LargeMessageProxy.DEFAULT_NAME);
    }

    ////////////////////
    // Actor Messages //
    ////////////////////

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class HintMessage implements Serializable {
        private static final long serialVersionUID = 7356980942734604738L;
        private PasswordWorkpackage workpackage;
        private Map<String, String> permutations;
        private int hintNumber;
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
                // TODO: Add further messages here to share work between Master and Worker actors
                .match(HintMessage.class, this::handle)
                .matchAny(object -> this.log().info("Received unknown message: \"{}\"", object.toString()))
                .build();
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

    private void register(Member member) {
        if ((this.masterSystem == null) && member.hasRole(MasterSystem.MASTER_ROLE)) {
            this.masterSystem = member;

            this.getContext()
                    .actorSelection(member.address() + "/user/" + Master.DEFAULT_NAME)
                    .tell(new Master.RegistrationMessage(), this.self());

            this.registrationTime = System.currentTimeMillis();
        }
    }

    private void handle(ClusterEvent.MemberRemoved message) {
        if (this.masterSystem.equals(message.member()))
            this.self().tell(PoisonPill.getInstance(), ActorRef.noSender());
    }

    private void handle(Worker.WelcomeMessage message) {
        final long transmissionTime = System.currentTimeMillis() - this.registrationTime;
        this.log().info("WelcomeMessage with " + message.getWelcomeData().getSizeInMB() + " MB data received in " + transmissionTime + " ms.");
    }

    private void handle(HintMessage message) {
        int hintNumber = message.getHintNumber();
        PasswordWorkpackage workpackage = message.getWorkpackage();
        char[] passwordChars = message.getWorkpackage().getPasswordCharacters().toCharArray();
        String hint = workpackage.getHints()[hintNumber];

        this.log().info("Received Hint {}, {}", hintNumber, workpackage.getName());

        //String encoded = "f45de51cdef30991551e41e882dd7b5404799648a0a00753f44fc966e6153fc1";

        String bruteforcedHint = bruteforceHint(message.getPermutations(), hint);
        char solvedHint = solveHint(passwordChars, bruteforcedHint);
        int a = 5;
    }

    private String bruteforceHint(Map<String, String> permutations, String encodedHint) {
        for (String key : permutations.keySet()) {
            if(permutations.get(key).equals(encodedHint)) {
                return key;
            }
        }
        return "";
    }

    private char solveHint(char[] passwordChars, String decodedHint) {
        for (char passwordChar: passwordChars) {
            boolean contains = false;
            for (char decodedCharacter: decodedHint.toCharArray()) {
                if(decodedCharacter == passwordChar) {
                    contains = true;
                    break;
                }
            }
            if(!contains) {
                return passwordChar;
            }
        }
        return '0';
    }
}