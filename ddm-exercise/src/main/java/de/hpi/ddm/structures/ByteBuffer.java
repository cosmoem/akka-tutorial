package de.hpi.ddm.structures;

import java.util.HashMap;
import java.util.Map;

public class ByteBuffer {
    // message ID to (chunk number to chunk message bytes)
    private final Map<Long, Map<Integer, byte[]>> messageMap;

    public ByteBuffer() {
        this.messageMap = new HashMap<>();
    }

    public void saveChunksToMap(Long messageId, int offset, byte[] bytes) {
        messageMap.computeIfAbsent(messageId, k -> new HashMap<>());
        messageMap.get(messageId).put(offset, bytes);
    }

    public Map<Integer, byte[]> getMap(Long messageId) {
        return messageMap.get(messageId);
    }
}
