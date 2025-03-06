package com.p2p.torrent.model;

import java.util.BitSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import lombok.Data;

@Data
public class Peer {
    private String peerId;
    private String hostname;
    private int port;
    private boolean hasFile;
    private BitSet bitfield;
    private boolean interested;
    private boolean choked;
    private Set<String> connectedPeers = ConcurrentHashMap.newKeySet();
    private long downloadRate;
    private boolean optimisticallyUnchoked;
    
    public Peer(String peerId, String hostname, int port, boolean hasFile) {
        this.peerId = peerId;
        this.hostname = hostname;
        this.port = port;
        this.hasFile = hasFile;
        this.choked = true;
        this.interested = false;
    }
    
    public void initializeBitfield(int totalPieces) {
        this.bitfield = new BitSet(totalPieces);
        if (hasFile) {
            this.bitfield.set(0, totalPieces);
        }
    }
}