package com.p2p.torrent.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class Handshake {
    private static final String HEADER = "P2PFILESHARINGPROJ";
    private String peerId;
    
    public String toString() {
        return HEADER + peerId;
    }
}