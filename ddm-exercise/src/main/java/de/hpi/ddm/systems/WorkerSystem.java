package de.hpi.ddm.systems;

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

public class WorkerSystem {

	public static final String WORKER_ROLE = "worker";
	
	public static void start() {
		final Configuration c = ConfigurationSingleton.get();
		
		final Config config = ConfigFactory.parseString(
				"akka.remote.artery.canonical.hostname = \"" + c.getHost() + "\"\n" +
				"akka.remote.artery.canonical.port = " + c.getPort() + "\n" +
				"akka.cluster.roles = [" + WORKER_ROLE + "]\n" +
				"akka.cluster.seed-nodes = [\"akka://" + c.getActorSystemName() + "@" + c.getMasterHost() + ":" + c.getMasterPort() + "\"]")
			.withFallback(ConfigFactory.load("application"));
		
		final ActorSystem system = ActorSystem.create(c.getActorSystemName(), config);
		
		ActorRef clusterListener = system.actorOf(ClusterListener.props(), ClusterListener.DEFAULT_NAME);
		ActorRef metricsListener = system.actorOf(MetricsListener.props(), MetricsListener.DEFAULT_NAME);
		BloomFilter welcomeData = c.generateWelcomeData();

		ActorRef reaper = system.actorOf(Reaper.props(), Reaper.DEFAULT_NAME);
		ActorRef permutationHandler = system.actorOf(PermutationHandler.props(welcomeData), PermutationHandler.DEFAULT_NAME);

		Cluster.get(system).registerOnMemberUp(() -> {
			for (int i = 0; i < c.getNumWorkers(); i++)
				system.actorOf(Worker.props(welcomeData), Worker.DEFAULT_NAME + i);
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
	}
}
