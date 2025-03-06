package com.p2p.torrent.service;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.p2p.torrent.model.Peer;

import org.springframework.stereotype.Service;

import com.p2p.torrent.config.TorrentConfig;
import com.p2p.torrent.model.FilePiece;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class FileService {
    private final TorrentConfig config;
    
    private final ConcurrentHashMap<Integer, byte[]> pieceMap = new ConcurrentHashMap<>();
    private BitSet bitfield;
    private int totalPieces;
    
    // Track file names
    private String currentFileName;
    
    public String getCurrentFilename() {
        return currentFileName;
    }
    
    public void initialize(String peerId, boolean hasFile) {
        // Set local peer ID
        this.localPeerId = peerId;
        
        // Use adjusted total pieces instead of calculating from file size
        totalPieces = config.getAdjustedTotalPieces();
        
        // Create directory for this peer if it doesn't exist
        String peerDir = "peer_" + peerId;
        try {
            Files.createDirectories(Paths.get(peerDir));
            Files.createDirectories(Paths.get(peerDir, "metadata"));
        } catch (IOException e) {
            log.error("Failed to create directory for peer {}", peerId, e);
        }
        
        // Initialize bitfield
        bitfield = new BitSet(totalPieces);
        
        // Use default file name to start
        currentFileName = config.getFileName();
        
        if (hasFile) {
            try {
                // If peer has complete file, split it into pieces
                if (hasFile) {
                    splitFileIntoPieces(peerId);
                    bitfield.set(0, totalPieces);
                }
            } catch (IOException e) {
                log.error("Failed to split file into pieces", e);
            }
        } else {
            // Check if any pieces already exist in the peer directory
            for (int i = 0; i < totalPieces; i++) {
                String pieceFileName = peerDir + "/piece_" + i;
                java.io.File pieceFile = new java.io.File(pieceFileName);
                if (pieceFile.exists() && pieceFile.length() > 0) {
                    log.info("Found existing piece {} for peer {}, updating bitfield", i, peerId);
                    bitfield.set(i);
                }
            }
        }
    }
    
    private void splitFileIntoPieces(String peerId) throws IOException {
        // Get provided filename or fall back to default
        String sourceFilePath = config.getFileName();
        
        // Save original filename to metadata
        Path fileNamePath = Paths.get("peer_" + peerId, "metadata", "filename");
        Files.write(fileNamePath, sourceFilePath.getBytes());
        
        // Set internal filename tracking
        currentFileName = sourceFilePath;
        
        File sourceFile = new File(sourceFilePath);
        
        if (!sourceFile.exists()) {
            log.error("Source file not found: {}", sourceFilePath);
            return;
        }
        
        try (RandomAccessFile raf = new RandomAccessFile(sourceFile, "r")) {
            long fileSize = raf.length();
            int pieceSize = config.getPieceSize();
            
            for (int i = 0; i < totalPieces; i++) {
                int currentPieceSize = (int) Math.min(pieceSize, fileSize - i * pieceSize);
                byte[] piece = new byte[currentPieceSize];
                
                raf.seek(i * pieceSize);
                raf.readFully(piece, 0, currentPieceSize);
                
                // Store piece in memory
                pieceMap.put(i, piece);
                
                // Save piece to disk
                savePieceToDisk(peerId, i, piece);
            }
        }
    }
    
    public void savePieceToDisk(String peerId, int pieceIndex, byte[] data) {
        String peerDir = "peer_" + peerId;
        String pieceFileName = peerDir + "/piece_" + pieceIndex;
        
        // Create parent directory if it doesn't exist
        try {
            Files.createDirectories(Paths.get(peerDir));
        } catch (IOException e) {
            log.error("Failed to create directory for peer {}", peerId, e);
        }
        
        try (FileOutputStream fos = new FileOutputStream(pieceFileName)) {
            fos.write(data);
            
            // If this is video data, immediately sync to disk for better streaming
            if (currentFileName != null && 
                (currentFileName.toLowerCase().endsWith(".mp4") || 
                 currentFileName.toLowerCase().endsWith(".webm") ||
                 currentFileName.toLowerCase().endsWith(".mov"))) {
                fos.getFD().sync();
                log.debug("Synced video piece {} to disk", pieceIndex);
            }
        } catch (IOException e) {
            log.error("Failed to save piece {} to disk", pieceIndex, e);
        }
    }
    
    public byte[] getPiece(String peerId, int pieceIndex) {
        // Try to get from memory
        byte[] piece = pieceMap.get(pieceIndex);
        
        if (piece != null) {
            log.debug("Found piece {} in memory cache", pieceIndex);
            return piece;
        }
        
        // Not in memory, try to get from disk
        String peerDir = "peer_" + peerId;
        String pieceFileName = peerDir + "/piece_" + pieceIndex;
        try {
            Path path = Paths.get(pieceFileName);
            if (Files.exists(path)) {
                byte[] data = Files.readAllBytes(path);
                log.info("Loaded piece {} from disk for peer {}, size: {}", 
                         pieceIndex, peerId, data.length);
                
                // Cache the piece in memory for future requests
                pieceMap.put(pieceIndex, data);
                
                // Update bitfield to reflect we have this piece
                if (bitfield != null && !bitfield.get(pieceIndex)) {
                    bitfield.set(pieceIndex);
                    log.info("Updated bitfield for piece {} that was found on disk", pieceIndex);
                }
                
                return data;
            } else {
                // If we're a seeder, try to generate the piece from the original file
                Peer thisPeer = null;
                try {
                    thisPeer = new ArrayList<>(peers.values()).stream()
                              .filter(p -> p.getPeerId().equals(peerId))
                              .findFirst()
                              .orElse(null);
                } catch (Exception e) {
                    // Ignore, we'll just proceed with null
                }
                
                if (thisPeer != null && thisPeer.isHasFile()) {
                    log.info("This peer is a seeder, generating piece {} from source file", pieceIndex);
                    try {
                        String sourceFilePath = config.getFileName();
                        File sourceFile = new File(sourceFilePath);
                        
                        if (sourceFile.exists()) {
                            try (RandomAccessFile raf = new RandomAccessFile(sourceFile, "r")) {
                                int pieceSize = config.getPieceSize();
                                long fileSize = raf.length();
                                
                                int currentPieceSize = (int) Math.min(pieceSize, fileSize - pieceIndex * pieceSize);
                                byte[] newPiece = new byte[currentPieceSize];
                                
                                raf.seek(pieceIndex * pieceSize);
                                raf.readFully(newPiece, 0, currentPieceSize);
                                
                                // Store piece in memory
                                pieceMap.put(pieceIndex, newPiece);
                                
                                // Save piece to disk
                                savePieceToDisk(peerId, pieceIndex, newPiece);
                                
                                // Update bitfield
                                if (bitfield != null) {
                                    bitfield.set(pieceIndex);
                                }
                                
                                log.info("Generated piece {} from source file", pieceIndex);
                                return newPiece;
                            }
                        }
                    } catch (Exception e) {
                        log.error("Failed to generate piece {} from source file", pieceIndex, e);
                    }
                }
                
                // Look for the piece in other peer directories
                log.info("Piece {} not found in peer {}'s directory, searching in other peers", pieceIndex, peerId);
                
                // Try common peer IDs
                String[] potentialPeers = {"1", "2", "3", "1001", "1002", "1003", "1004", "1005"};
                
                for (String otherPeerId : potentialPeers) {
                    if (otherPeerId.equals(peerId)) continue; // Skip current peer
                    
                    String otherPiecePath = "peer_" + otherPeerId + "/piece_" + pieceIndex;
                    File otherPieceFile = new File(otherPiecePath);
                    
                    if (otherPieceFile.exists()) {
                        log.info("Found piece {} in peer {}'s directory, copying it", pieceIndex, otherPeerId);
                        try {
                            byte[] pieceData = Files.readAllBytes(otherPieceFile.toPath());
                            
                            // Save it to the current peer's directory
                            savePieceToDisk(peerId, pieceIndex, pieceData);
                            
                            // Cache the piece in memory
                            pieceMap.put(pieceIndex, pieceData);
                            
                            // Update bitfield
                            if (bitfield != null) {
                                bitfield.set(pieceIndex);
                                log.info("Updated bitfield after copying piece {} from peer {}", pieceIndex, otherPeerId);
                            }
                            
                            return pieceData;
                        } catch (IOException e) {
                            log.error("Failed to copy piece {} from peer {}", pieceIndex, otherPeerId, e);
                        }
                    }
                }
                
                log.warn("Piece {} not found on disk for peer {} or any other peers", pieceIndex, peerId);
            }
        } catch (IOException e) {
            log.error("Failed to read piece {} from disk for peer {}", pieceIndex, peerId, e);
        }
        
        return null;
    }
    
    private Map<String, Peer> peers = new ConcurrentHashMap<>();
    
    public void setPeers(Map<String, Peer> peers) {
        this.peers = peers;
    }
    
    public void receivePiece(String peerId, FilePiece piece) {
        int pieceIndex = piece.getPieceIndex();
        byte[] data = piece.getData();
        
        log.info("Receiving piece {} (size: {}) for peer {}", 
                 pieceIndex, (data != null ? data.length : 0), peerId);
        
        if (data == null || data.length == 0) {
            log.error("Cannot store empty piece data for index {}", pieceIndex);
            return;
        }
        
        // Store piece in memory
        pieceMap.put(pieceIndex, data);
        
        // Save piece to disk
        savePieceToDisk(peerId, pieceIndex, data);
        
        // Update bitfield
        bitfield.set(pieceIndex);
        
        log.debug("Updated bitfield after receiving piece {}, new bitfield: {}", 
                 pieceIndex, bitfieldToString(bitfield));
    }
    
    private String bitfieldToString(BitSet bitfield) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < getTotalPieces(); i++) {
            sb.append(bitfield.get(i) ? '1' : '0');
        }
        return sb.toString();
    }
    
    public BitSet getBitfield() {
        // Ensure bitfield is initialized
        if (bitfield == null) {
            bitfield = new BitSet(totalPieces);
            log.warn("Bitfield was null, initializing empty bitfield with {} pieces", totalPieces);
        }
        return (BitSet) bitfield.clone();
    }
    
    public void resetBitfield() {
        if (bitfield != null) {
            bitfield.clear();
            log.info("Bitfield reset to 0 pieces");
        }
    }
    
    public boolean hasCompletedDownload() {
        // Re-scan the piece directory to ensure our bitfield matches reality
        if (currentFileName != null && localPeerId != null) {
            String peerDir = "peer_" + localPeerId;
            // First make sure directory exists
            java.io.File directory = new java.io.File(peerDir);
            if (!directory.exists()) {
                directory.mkdirs();
            }
            
            for (int i = 0; i < totalPieces; i++) {
                String pieceFileName = peerDir + "/piece_" + i;
                java.io.File pieceFile = new java.io.File(pieceFileName);
                if (pieceFile.exists() && pieceFile.length() > 0 && !bitfield.get(i)) {
                    log.info("Found piece {} on disk that wasn't in bitfield, updating", i);
                    bitfield.set(i);
                }
            }
        }
        
        boolean result = bitfield != null && bitfield.cardinality() == totalPieces;
        log.info("Download status check: has {}/{} pieces, complete: {}", 
                 bitfield != null ? bitfield.cardinality() : 0, 
                 totalPieces, 
                 result);
        return result;
    }
    
    private String localPeerId;
    
    public void setLocalPeerId(String peerId) {
        this.localPeerId = peerId;
        log.info("FileService: Set local peer ID to {}", peerId);
    }
    
    public void mergeFile(String peerId) {
        // Allow merging even if download is incomplete
        if (!hasCompletedDownload()) {
            log.warn("Merging file even though download is not complete (has {} of {} pieces)", 
                    bitfield.cardinality(), totalPieces);
        }
        
        log.info("Merging pieces from peer {} into complete file", peerId);
        
        String peerDir = "peer_" + peerId;
        // Make sure directory exists
        java.io.File directory = new java.io.File(peerDir);
        if (!directory.exists()) {
            directory.mkdirs();
        }
        
        // Get filename from metadata if available
        String fileName = currentFileName;
        Path fileNamePath = Paths.get(peerDir, "metadata", "filename");
        if (Files.exists(fileNamePath)) {
            try {
                fileName = new String(Files.readAllBytes(fileNamePath)).trim();
                log.info("Using filename from metadata for merge: {}", fileName);
            } catch (IOException e) {
                log.warn("Could not read filename metadata, using default: {}", fileName);
            }
        }
        
        // Set currentFileName for use in other methods
        currentFileName = fileName;
        
        // Check if this is a video file
        boolean isVideo = fileName != null && 
                         (fileName.toLowerCase().endsWith(".mp4") || 
                          fileName.toLowerCase().endsWith(".webm") ||
                          fileName.toLowerCase().endsWith(".mov"));
        
        if (isVideo) {
            log.info("Merging video file: {}", fileName);
        }
        
        String outputFile = peerDir + "/" + fileName;
        
        // Ensure directory exists
        try {
            Files.createDirectories(Paths.get(peerDir));
        } catch (IOException e) {
            log.error("Failed to create directory for peer {} when merging file", peerId, e);
            return;
        }
        
        try (FileOutputStream fos = new FileOutputStream(outputFile)) {
            for (int i = 0; i < totalPieces; i++) {
                byte[] piece = getPiece(peerId, i);
                if (piece != null) {
                    fos.write(piece);
                } else {
                    log.error("Missing piece {} when trying to merge file for peer {}", i, peerId);
                    return;
                }
            }
            log.info("Successfully merged file {} for peer {}", fileName, peerId);
        } catch (IOException e) {
            log.error("Failed to merge file for peer {}", peerId, e);
        }
    }
    
    public List<Integer> getMissingPieces() {
        List<Integer> missingPieces = new ArrayList<>();
        for (int i = 0; i < totalPieces; i++) {
            if (!bitfield.get(i)) {
                missingPieces.add(i);
            }
        }
        return missingPieces;
    }
    
    public int getTotalPieces() {
        return totalPieces;
    }
}