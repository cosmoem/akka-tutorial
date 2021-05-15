package de.hpi.ddm.structures;

import java.util.HashMap;
import java.util.Map;

public class ByteBuffer {

    private static ByteBuffer byteBuffer;
    private Map<Long, Map<Integer, byte[]>> map;

    private ByteBuffer() {
        this.map = new HashMap<>();
    }


    public static ByteBuffer getBuffer() {

        if(byteBuffer==null)
        {
            byteBuffer = new ByteBuffer();
        }

        return byteBuffer;
    }

    public void safeChunksToMap(Long key, int offset, byte[] bytes) {

        if(map.get(key)==null){
            map.put(key,new HashMap<>());
        }
        map.get(key).put(offset, bytes);
    }


    public Map<Integer, byte[]> getMap(Long key) {
        return map.get(key);
    }


}
