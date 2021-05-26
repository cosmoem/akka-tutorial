package de.hpi.ddm.actors;

import akka.actor.*;
import akka.cluster.Cluster;
import akka.cluster.ClusterEvent;
import akka.cluster.Member;
import akka.cluster.MemberStatus;
import de.hpi.ddm.configuration.Configuration;
import de.hpi.ddm.singletons.ConfigurationSingleton;
import de.hpi.ddm.structures.BloomFilter;
import de.hpi.ddm.structures.PermutationWorkPackage;
import de.hpi.ddm.systems.MasterSystem;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static de.hpi.ddm.actors.Master.*;
import static de.hpi.ddm.actors.PermutationWorker.*;

public class PermutationHandler extends AbstractLoggingActor {

    ////////////////////////
    // Actor Construction //
    ////////////////////////

    public static final String DEFAULT_NAME = "permutation-handler";

    public static Props props(final BloomFilter welcomeData) {
        return Props.create(PermutationHandler.class, () -> new PermutationHandler(welcomeData));
    }

    public PermutationHandler(final BloomFilter welcomeData) {
        this.cluster = Cluster.get(this.context().system());
        this.permutationWorkers = new ArrayList<>();
        this.permutationWorkPackages = new ArrayList<>();
        this.resultTracker = new HashMap<>();
        this.welcomeData = welcomeData;
        this.largeMessageProxy = this.context().actorOf(LargeMessageProxy.props(), LargeMessageProxy.DEFAULT_NAME);
    }

    ////////////////////
    // Actor Messages //
    ////////////////////


    @Data
    public static class WorkerSystemRegistrationMessage implements Serializable {
        private static final long serialVersionUID = -5533081653659775497L;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class PermutationWorkPackagesMessage implements Serializable {
        private static final long serialVersionUID = 12344816443217600L;
        private List<PermutationWorkPackage> permutationWorkPackages;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class PermutationResultMessage implements Serializable {
        private static final long serialVersionUID = -72434659866542342L;
        private char head;
        private char head2;
    }

    @Data
    public static class PermutationWorkRequest implements Serializable {
        private static final long serialVersionUID = 53134659986442334L;
    }

    /////////////////
    // Actor State //
    /////////////////

    private Member masterSystem;
    private final Cluster cluster;
    private final List<ActorRef> permutationWorkers;
    private long registrationTime;
    private Cancellable workRequest;
    private final List<PermutationWorkPackage> permutationWorkPackages;
    private final Map<String, Boolean> resultTracker;
    private final Configuration c = ConfigurationSingleton.get();
    private final BloomFilter welcomeData;
    private final ActorRef largeMessageProxy;

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
                .match(Worker.WelcomeMessage.class, this::handle) // Welcome from Master
                .match(PermutationWorkPackagesMessage.class, this::handle) // PermutationWorkPackages List from Master
                .match(WorkerSystemRegistrationMessage.class, this::handle) // Registration from PermutationWorker
                .match(PermutationWorkRequest.class, this::handle) // WorkRequest from PermutationWorker
                .match(PermutationResultMessage.class, this::handle) // Message that job is finished from PermutationWorker
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
        PermutationWorkPackageRequest workRequestMessage = new PermutationWorkPackageRequest();
        this.workRequest = this.getContext().system().scheduler().schedule(
                Duration.ZERO,
                Duration.ofSeconds(3),
                this.sender(),
                workRequestMessage,
                this.getContext().dispatcher(),
                this.self()
        );
    }


    private void handle(PermutationWorkPackagesMessage message) {
        this.log().info("Received Permutation Work Packages from master.");
        this.workRequest.cancel();
        this.permutationWorkPackages.addAll(message.getPermutationWorkPackages());
        for (PermutationWorkPackage workPackage : this.permutationWorkPackages) {
            String key = String.valueOf(workPackage.getHead()) + String.valueOf(workPackage.getHead2());
            this.resultTracker.put(key, false);
        }
        if (this.permutationWorkers.isEmpty()) {
            for (int i = 0; i < c.getNumPermutationWorkers(); i++) {
                ActorRef actor = this.context()
                        .actorOf(PermutationWorker.props().withDispatcher("akka.actor.my-dispatcher"), PermutationWorker.DEFAULT_NAME + i);
                this.log().info("Created actor {}", actor.path().name());
            }
        }
    }

    protected void handle(WorkerSystemRegistrationMessage message) {
        this.context().watch(this.sender());
        String name = this.sender().path().name();
        this.permutationWorkers.add(this.sender());
        this.log().info("Registered {}", this.sender());
        Worker.WelcomeMessage welcomeMessage = new Worker.WelcomeMessage(this.welcomeData);
        this.sender().tell(welcomeMessage, this.self());
    }

    protected void handle(PermutationWorkRequest message) {
        this.log().info("Received Permutation Work Request from {}", this.sender().path().name());
        if (!this.permutationWorkPackages.isEmpty()) {
            PermutationWorkPackage workPackage = this.permutationWorkPackages.remove(0);
            this.sender().tell(new PermutationWorkMessage(workPackage), this.self());
        }
    }

    private void handle(PermutationResultMessage message) {
        this.log().info("Received Signal that Computation for letter combination {}-{} is done from {}", message.head, message.head2, this.sender().path().name());
        if (!this.permutationWorkPackages.isEmpty()) {
            PermutationWorkPackage workPackage = this.permutationWorkPackages.remove(0);
            this.sender().tell(new PermutationWorkMessage(workPackage), this.self());
        }
        String key = String.valueOf(message.head) + String.valueOf(message.head2);
        this.resultTracker.replace(key, true);
        boolean allDone = true;
        for (Boolean done : resultTracker.values()) {
            if (!done) {
                allDone = false;
                break;
            }
        }
        if (allDone) {
            this.getContext()
                    .actorSelection(this.masterSystem.address() + "/user/" + Master.DEFAULT_NAME)
                    .tell(new PermutationsReadyMessage(), this.self());
        }
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
}
