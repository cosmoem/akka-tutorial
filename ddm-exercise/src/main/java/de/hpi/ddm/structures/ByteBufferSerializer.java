package de.hpi.ddm.structures;

import java.nio.ByteBuffer;

public interface ByteBufferSerializer {


    /**
     *  UNUSED
     */

    /** Serializes the given object into the `ByteBuffer`. */
    void toBinary(Object o, ByteBuffer buf);

    /**
     * Produces an object from a `ByteBuffer`, with an optional type-hint; the class should be
     * loaded using ActorSystem.dynamicAccess.
     */
    Object fromBinary(ByteBuffer buf, String manifest);

}
