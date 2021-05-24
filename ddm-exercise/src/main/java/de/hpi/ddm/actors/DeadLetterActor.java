package de.hpi.ddm.actors;

import akka.actor.AbstractLoggingActor;
import akka.actor.DeadLetter;
import akka.actor.Props;
import lombok.NoArgsConstructor;

import de.hpi.ddm.actors.LargeMessageProxy.*;

@NoArgsConstructor
public class DeadLetterActor extends AbstractLoggingActor {

    public static final String DEFAULT_NAME = "dead-letter";

    public static Props props() {
        return Props.create(DeadLetterActor.class);
    }

    @Override
    public Receive createReceive() {
        return receiveBuilder()
                .match(
                        DeadLetter.class,
                        msg -> {
                            BytesMessage<?> message = (BytesMessage<?>) msg.message();
                            message.getReceiver().tell(message, message.getSender());
                            this.log().info("Rerouting dead letter from {} to {}.", msg.sender(), msg.sender());
                        })
                .matchAny(object -> this.log().info("Received unknown message: \"{}\"", object.toString()))
                .build();
    }
}
