package com.p2p.torrent.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class FilePiece {
    private int pieceIndex;
    private byte[] data;
}