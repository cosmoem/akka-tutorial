package de.hpi.ddm.actors;

import akka.actor.AbstractLoggingActor;
import akka.actor.ActorRef;
import akka.actor.DeadLetter;
import akka.actor.Props;

public class DeadLetterActor extends AbstractLoggingActor {

    public static final String DEFAULT_NAME = "dead-letter";

    public DeadLetterActor() {
        this.largeMessageProxy = this.context().actorOf(LargeMessageProxy.props(), LargeMessageProxy.DEFAULT_NAME);
    }

    public static Props props() {
        return Props.create(DeadLetterActor.class);
    }


    private final ActorRef largeMessageProxy;

    @Override
    public Receive createReceive() {
        return receiveBuilder()
                .match(
                        DeadLetter.class,
                        msg -> {
                                this.largeMessageProxy.tell(msg, msg.sender());
                        })
                .build();
    }
}
