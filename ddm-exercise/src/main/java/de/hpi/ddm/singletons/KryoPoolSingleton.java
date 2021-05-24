package de.hpi.ddm.singletons;

import com.esotericsoftware.kryo.Kryo;
import com.twitter.chill.IKryoRegistrar;
import com.twitter.chill.KryoInstantiator;
import com.twitter.chill.KryoPool;
import de.hpi.ddm.actors.PermutationWorker;
import org.objenesis.strategy.StdInstantiatorStrategy;

public class KryoPoolSingleton {

	private static final int POOL_SIZE = 10;
	private static final KryoPool kryo;

	static {
		KryoInstantiator kryoInstantiator = new KryoInstantiator();
		kryoInstantiator.withRegistrar(
				(IKryoRegistrar) kryo -> kryo.register(PermutationWorker.PermutationWorkMessage.class, 1)
		);
		kryo = KryoPool.withByteArrayOutputStream(POOL_SIZE, kryoInstantiator);
	}

	public static KryoPool get() {
		return kryo;
	}
}
