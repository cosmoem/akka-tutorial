package de.hpi.ddm.actors;

import akka.actor.*;
import akka.cluster.Cluster;
import akka.cluster.Member;
import akka.cluster.MemberStatus;
import de.hpi.ddm.singletons.PermutationSingleton;
import de.hpi.ddm.structures.BruteForceWorkPackage;
import de.hpi.ddm.structures.HintResult;

import de.hpi.ddm.systems.WorkerSystem;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.*;

import static akka.cluster.ClusterEvent.*;
import static de.hpi.ddm.actors.Worker.*;

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
        private BruteForceWorkPackage bruteForceWorkPackage;
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
                .match(WelcomeMessage.class, this::handle) // Welcome message from Worker (parent)
                .match(HintMessage.class, this::handle) // Receives hint to work on from Worker
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
        this.log().info("WelcomeMessage with " + sizeInMB + " MB data received in " + transmissionTime + " ms.");
        this.context().parent().tell(new BruteForceWorkerWorkRequestMessage(), this.self());
    }

    private void handle(HintMessage message) {
        final BruteForceWorkPackage bruteForceWorkPackage = message.getBruteForceWorkPackage();
        final char[] passwordChars = bruteForceWorkPackage.getPasswordChars().toCharArray();
        final String hint = bruteForceWorkPackage.getHint();
        final int passwordId = bruteForceWorkPackage.getPasswordId();

        this.log().info("Received Hint {} for Password {}", hint, passwordId);

        String decodedHint = bruteforceHint(hint);
        char letter = solveHint(passwordChars, decodedHint);
        HintResult hintResult = new HintResult(passwordId, letter, hint);
        this.sender().tell(new BruteForceResultMessage(hintResult), this.self());
    }

    ////////////////////
    // Helper Methods //
    ////////////////////

    private String bruteforceHint(String encodedHint) {
        List<String> permutations = PermutationSingleton.getPermutations();
        for (String string : permutations) {
            String decodedPermutation = string.substring(0, 10);
            String encodedPermutation = string.substring(11);
            if(encodedPermutation.equals(encodedHint)) {
                return decodedPermutation;
            }
        }
        return "";
    }

    private char solveHint(char[] passwordChars, String decodedHint) {
        for (char passwordChar: passwordChars) {
            boolean contains = false;
            for (char decodedCharacter: decodedHint.toCharArray()) {
                if (decodedCharacter == passwordChar) {
                    contains = true;
                    break;
                }
            }
            if (!contains) {
                return passwordChar;
            }
        }
        return '0';
    }

    private void register(Member member) {
        if (member.hasRole(WorkerSystem.WORKER_ROLE)) {
            this.getContext().parent()
                    .tell(new PermutationHandler.WorkerSystemRegistrationMessage(), this.self());
            this.registrationTime = System.currentTimeMillis();
        }
    }
}