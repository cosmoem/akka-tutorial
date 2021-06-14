package de.hpi.ddm.systems;

import java.util.Scanner;
import java.util.concurrent.TimeUnit;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.cluster.Cluster;
import de.hpi.ddm.actors.*;
import de.hpi.ddm.actors.listeners.ClusterListener;
import de.hpi.ddm.actors.listeners.MetricsListener;
import de.hpi.ddm.configuration.Configuration;
import de.hpi.ddm.singletons.ConfigurationSingleton;
import de.hpi.ddm.structures.BloomFilter;
import scala.concurrent.Await;
import scala.concurrent.duration.Duration;

public class MasterSystem {
	
	public static final String MASTER_ROLE = "master";

	public static void start() {
		final Configuration c = ConfigurationSingleton.get();
		
		final Config config = ConfigFactory.parseString(
				"akka.remote.artery.canonical.hostname = \"" + c.getHost() + "\"\n" +
				"akka.remote.artery.canonical.port = " + c.getPort() + "\n" +
				"akka.cluster.roles = [" + MASTER_ROLE + "]\n" +
				"akka.cluster.seed-nodes = [\"akka://" + c.getActorSystemName() + "@" + c.getHost() + ":" + c.getPort() + "\"]")
			.withFallback(ConfigFactory.load("application"));
		
		final ActorSystem system = ActorSystem.create(c.getActorSystemName(), config);

		//ActorRef clusterListener = system.actorOf(ClusterListener.props(), ClusterListener.DEFAULT_NAME);
		//ActorRef metricsListener = system.actorOf(MetricsListener.props(), MetricsListener.DEFAULT_NAME);
		
		ActorRef reaper = system.actorOf(Reaper.props(), Reaper.DEFAULT_NAME);
		
		ActorRef reader = system.actorOf(Reader.props(), Reader.DEFAULT_NAME);
		
		ActorRef collector = system.actorOf(Collector.props(), Collector.DEFAULT_NAME);

		BloomFilter welcomeData = c.generateWelcomeData();
		ActorRef master = system.actorOf(Master.props(reader, collector, welcomeData), Master.DEFAULT_NAME);

		int numWorkers = c.getNumWorkers();
		if (numWorkers > 0) {
			ActorRef permutationHandler = system.actorOf(PermutationHandler.props(welcomeData), PermutationHandler.DEFAULT_NAME + "-mastersys");
		}

		Cluster.get(system).registerOnMemberUp(() -> {
			// for local non-distributed start-up
			if (numWorkers > 0) {
				for (int i = 0; i < numWorkers; i++) {
					system.actorOf(Worker.props(welcomeData), Worker.DEFAULT_NAME + i);
				}
			}
				if (!c.isStartPaused())
				master.tell(new Master.StartMessage(), ActorRef.noSender());
		});

		Cluster.get(system).registerOnMemberRemoved(() -> {
			system.terminate();
			new Thread(() -> {
				try {
					Await.ready(system.whenTerminated(), Duration.create(10, TimeUnit.SECONDS));
				} catch (Exception e) {
					System.exit(-1);
				}
			}).start();
		});
		
		if (c.isStartPaused()) {
			System.out.println("Press <enter> to start!");
			try (final Scanner scanner = new Scanner(System.in)) {
				scanner.nextLine();
			}
			master.tell(new Master.StartMessage(), ActorRef.noSender());
		}
	}
}
