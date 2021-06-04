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
                            try {
                                BytesMessage<?> message = (BytesMessage<?>) msg.message();
                                message.getReceiver().tell(message, message.getSender());
                                this.log().info("Rerouting dead letter from {} to {}.", message.getSender(), message.getReceiver());
                            } catch (ClassCastException e) {
                                this.log().info("Received unknown dead letter: \"{}\"", e.toString());
                            }

                        })
                .build();
    }
}
