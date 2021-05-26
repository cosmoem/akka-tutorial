package de.hpi.ddm.singletons;

import com.twitter.chill.KryoInstantiator;
import com.twitter.chill.KryoPool;

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
