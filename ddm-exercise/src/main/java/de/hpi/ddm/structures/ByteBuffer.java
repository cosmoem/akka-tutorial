package de.hpi.ddm.structures;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ByteBuffer {
    // message ID to (chunk offset to chunk message bytes)
    private final Map<Long, Map<Integer, byte[]>> messageMap;

    public ByteBuffer() {
        this.messageMap = new ConcurrentHashMap<>();
    }

    public void saveChunksToMap(Long messageId, int chunkOffset, byte[] bytes) {
        messageMap.computeIfAbsent(messageId, id -> new ConcurrentHashMap<>());
        messageMap.get(messageId).put(chunkOffset, bytes);
    }

    public void deleteMapForMessageId(long messageId) {
        this.messageMap.remove(messageId);
    }

    public Map<Integer, byte[]> getMap(Long messageId) {
        return messageMap.get(messageId);
    }
}
