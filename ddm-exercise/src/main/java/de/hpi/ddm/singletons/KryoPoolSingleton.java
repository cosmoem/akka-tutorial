package de.hpi.ddm.singletons;

import akka.serialization.JavaSerializer;
import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.pool.KryoFactory;
import com.twitter.chill.IKryoRegistrar;
import com.twitter.chill.KryoInstantiator;
import com.twitter.chill.KryoPool;
import de.hpi.ddm.actors.LargeMessageProxy;
import de.hpi.ddm.actors.PermutationWorker;
import de.hpi.ddm.actors.Worker;
import de.hpi.ddm.structures.PasswordWorkpackage;
import de.hpi.ddm.structures.PermutationWorkPackage;
import org.objenesis.strategy.StdInstantiatorStrategy;

public class KryoPoolSingleton {

	private static final int POOL_SIZE = 10;
	private static final KryoPool kryo;

	static {
		KryoInstantiator kryoInstantiator = new KryoInstantiator();
		kryo = KryoPool.withByteArrayOutputStream(POOL_SIZE, kryoInstantiator);
	}

	public static KryoPool get() {
		return kryo;
	}
}
