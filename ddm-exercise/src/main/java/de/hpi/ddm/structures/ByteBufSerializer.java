package de.hpi.ddm.structures;

import akka.serialization.SerializerWithStringManifest;
import com.twitter.chill.KryoPool;
import de.hpi.ddm.singletons.KryoPoolSingleton;

import java.io.NotSerializableException;
import java.nio.ByteBuffer;

public class ByteBufSerializer extends SerializerWithStringManifest
        implements ByteBufferSerializer {


    /**
     *  UNUSED
     */


    @Override
    public int identifier() {
        return 1337;
    }

    @Override
    public String manifest(Object o) {
        return "serialized-" + o.getClass().getSimpleName();
    }

    @Override
    public byte[] toBinary(Object o) {
        final ByteBuffer buf = ByteBuffer.allocate(256);

        toBinary(o, buf);
        buf.flip();
        final byte[] bytes = new byte[buf.remaining()];
        buf.get(bytes);
        return bytes;




//       return KryoPoolSingleton.get().toBytesWithClass(o);


    }

    @Override
    public Object fromBinary(byte[] bytes, String manifest) throws NotSerializableException {
        return fromBinary(ByteBuffer.wrap(bytes), manifest);
    }

    @Override
    public void toBinary(Object o, ByteBuffer buf) {
        // Implement actual serialization here
    }

    @Override
    public Object fromBinary(ByteBuffer buf, String manifest) {
        // Implement actual deserialization here
        return null;
    }
}
