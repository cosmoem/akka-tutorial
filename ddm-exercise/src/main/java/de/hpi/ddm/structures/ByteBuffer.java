package de.hpi.ddm.structures;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class ByteBuffer {
    // message ID to (chunk offset to chunk message bytes)
    private final Map<String, Map<Integer, byte[]>> messageMap;

    public ByteBuffer() {
        this.messageMap = new ConcurrentHashMap<>();
    }

    public void saveChunksToMap(String messageId, int chunkOffset, byte[] bytes) {
        messageMap.computeIfAbsent(messageId, id -> new ConcurrentHashMap<>());
        messageMap.get(messageId).putIfAbsent(chunkOffset, bytes);
    }

    public void deleteMapForMessageId(String messageId) {
        this.messageMap.remove(messageId);
    }

    public Map<Integer, byte[]> getMap(String messageId) {
        return messageMap.get(messageId);
    }
}
