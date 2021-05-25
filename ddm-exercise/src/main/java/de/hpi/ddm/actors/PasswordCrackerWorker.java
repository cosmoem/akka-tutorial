package de.hpi.ddm.actors;

import akka.actor.AbstractLoggingActor;
import akka.actor.ActorRef;
import akka.actor.PoisonPill;
import akka.actor.Props;
import akka.cluster.Cluster;
import akka.cluster.ClusterEvent;
import akka.cluster.Member;
import akka.cluster.MemberStatus;
import de.hpi.ddm.structures.BloomFilter;
import de.hpi.ddm.structures.HintResult;
import de.hpi.ddm.structures.PasswordWorkpackage;
import de.hpi.ddm.systems.WorkerSystem;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

public class PasswordCrackerWorker extends AbstractLoggingActor {

    ////////////////////////
    // Actor Construction //
    ////////////////////////

    public static final String DEFAULT_NAME = "password-cracker-worker";

    public static Props props() {
        return Props.create(PasswordCrackerWorker.class);
    }

    public PasswordCrackerWorker() {
        this.cluster = Cluster.get(this.context().system());
        this.largeMessageProxy = this.context().actorOf(LargeMessageProxy.props(), LargeMessageProxy.DEFAULT_NAME);
    }

    ////////////////////
    // Actor Messages //
    ////////////////////

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class PasswordAndSolvedHintsMessage implements Serializable {
        private static final long serialVersionUID = -1111040922228609111L;
        private PasswordWorkpackage passwordWorkpackage;
        private List<HintResult> hintResults;
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
                .match(Worker.WelcomeMessage.class, this::handle) // Welcome message from Worker (parent)
                .match(PasswordAndSolvedHintsMessage.class, this::handle) // Gets password and hints to solve
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

    private void handle(ClusterEvent.MemberRemoved message) {
        if (this.masterSystem.equals(message.member()))
            this.self().tell(PoisonPill.getInstance(), ActorRef.noSender());
    }

    private void handle(Worker.WelcomeMessage message) {
        final long transmissionTime = System.currentTimeMillis() - this.registrationTime;
        int sizeInMB = message.getWelcomeData().getSizeInMB();
        this.log().info("WelcomeMessage with " + sizeInMB + " MB data received in " + transmissionTime + " ms.");
        this.sender().tell(new Worker.PasswordCrackerWorkRequestMessage(), this.self());
    }

    private void handle(PasswordAndSolvedHintsMessage message) {
        this.log().info("Hallo wir sind hier angekommen :)");
        //this.sender().tell(); TODO return result + pull? retries ????
    }

    ////////////////////
    // Helper Methods //
    ////////////////////

    private void register(Member member) {
        if (member.hasRole(WorkerSystem.WORKER_ROLE)) {
            this.getContext().parent()
                    .tell(new PermutationHandler.WorkerSystemRegistrationMessage(), this.self());
            this.registrationTime = System.currentTimeMillis();
        }
    }
}
