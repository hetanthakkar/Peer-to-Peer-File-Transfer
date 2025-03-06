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
        
        log.info("INIT: Peer {} initialized with filename '{}', hasFile={}, totalPieces={}", 
                peerId, currentFileName, hasFile, totalPieces);
        
        // Debug file path resolution
        checkSourceFile(peerId, hasFile);
        
        if (hasFile) {
            try {
                // If peer has complete file, split it into pieces
                if (hasFile) {
                    log.info("INIT: Splitting file into {} pieces for seeder {}", totalPieces, peerId);
                    splitFileIntoPieces(peerId);
                    bitfield.set(0, totalPieces);
                    log.info("INIT: Set full bitfield for seeder {}", peerId);
                }
            } catch (IOException e) {
                log.error("INIT ERROR: Failed to split file into pieces: {}", e.getMessage());
                e.printStackTrace();
            }
        } else {
            log.info("INIT: Checking for existing pieces for leecher {}", peerId);
            // Check if any pieces already exist in the peer directory
            for (int i = 0; i < totalPieces; i++) {
                String pieceFileName = peerDir + "/piece_" + i;
                java.io.File pieceFile = new java.io.File(pieceFileName);
                if (pieceFile.exists() && pieceFile.length() > 0) {
                    log.info("INIT: Found existing piece {} for peer {}, updating bitfield", i, peerId);
                    bitfield.set(i);
                }
            }
            log.info("INIT: Found {} existing pieces for leecher {}", bitfield.cardinality(), peerId);
        }
    }
    
    /**
     * Checks source file existence and logs detailed info for debugging
     */
    private void checkSourceFile(String peerId, boolean hasFile) {
        try {
            log.info("FILE CHECK: Checking source file for peer {}", peerId);
            
            // Check configuration
            log.info("CONFIG: fileName={}, pieceSize={}, totalPieces={}", 
                     config.getFileName(), config.getPieceSize(), totalPieces);
            
            // Try different file path resolutions
            File absoluteFile = new File(currentFileName);
            File relativeFile = new File(".", currentFileName);
            File peerFile = new File("peer_" + peerId, currentFileName);
            
            log.info("PATH RESOLUTION:");
            log.info("- Absolute: {} (exists: {})", absoluteFile.getAbsolutePath(), absoluteFile.exists());
            log.info("- Relative: {} (exists: {})", relativeFile.getAbsolutePath(), relativeFile.exists());
            log.info("- Peer Dir: {} (exists: {})", peerFile.getAbsolutePath(), peerFile.exists());
            
            // Check working directory and peer directory
            log.info("DIRECTORIES:");
            log.info("- Working dir: {}", System.getProperty("user.dir"));
            log.info("- Peer dir exists: {}", new File("peer_" + peerId).exists());
            
            // If file exists somewhere, log its size and permissions
            File existingFile = null;
            if (absoluteFile.exists()) existingFile = absoluteFile;
            else if (relativeFile.exists()) existingFile = relativeFile;
            else if (peerFile.exists()) existingFile = peerFile;
            
            if (existingFile != null) {
                log.info("FILE DETAILS: size={} bytes, canRead={}, canWrite={}", 
                         existingFile.length(), existingFile.canRead(), existingFile.canWrite());
                
                // Update current filename to the actual location
                currentFileName = existingFile.getAbsolutePath();
                log.info("FILE RESOLUTION: Updated currentFileName to '{}'", currentFileName);
            } else if (hasFile) {
                log.error("FILE NOT FOUND: Peer {} marked as having file '{}' but file not found!", 
                         peerId, currentFileName);
            }
        } catch (Exception e) {
            log.error("FILE CHECK ERROR: Failed while checking source file: {}", e.getMessage());
        }
    }
    
    private void splitFileIntoPieces(String peerId) throws IOException {
        // Get provided filename or fall back to default
        String sourceFilePath = config.getFileName();
        
        // Try multiple different locations for the file
        File sourceFile = null;
        File directFile = new File(sourceFilePath);
        File relativeFile = new File(".", sourceFilePath);
        File peerDirFile = new File("peer_" + peerId, sourceFilePath);
        
        log.info("SPLIT FILE: Looking for source file '{}' in multiple locations", sourceFilePath);
        log.info("SPLIT FILE: Direct path: {} (exists: {})", directFile.getAbsolutePath(), directFile.exists());
        log.info("SPLIT FILE: Relative path: {} (exists: {})", relativeFile.getAbsolutePath(), relativeFile.exists());
        log.info("SPLIT FILE: Peer dir path: {} (exists: {})", peerDirFile.getAbsolutePath(), peerDirFile.exists());
        
        // Find the first existing file
        if (directFile.exists()) {
            sourceFile = directFile;
            sourceFilePath = directFile.getAbsolutePath();
            log.info("SPLIT FILE: Using direct path: {}", sourceFilePath);
        } else if (relativeFile.exists()) {
            sourceFile = relativeFile;
            sourceFilePath = relativeFile.getAbsolutePath();
            log.info("SPLIT FILE: Using relative path: {}", sourceFilePath);
        } else if (peerDirFile.exists()) {
            sourceFile = peerDirFile;
            sourceFilePath = peerDirFile.getAbsolutePath();
            log.info("SPLIT FILE: Using peer dir path: {}", sourceFilePath);
        } else {
            // Look in all peer directories
            log.info("SPLIT FILE: Looking in all peer directories");
            File peersRoot = new File(".");
            File[] peerDirs = peersRoot.listFiles(file -> 
                file.isDirectory() && file.getName().startsWith("peer_"));
            
            if (peerDirs != null) {
                for (File dir : peerDirs) {
                    File possibleFile = new File(dir, config.getFileName());
                    if (possibleFile.exists()) {
                        sourceFile = possibleFile;
                        sourceFilePath = possibleFile.getAbsolutePath();
                        log.info("SPLIT FILE: Found in peer directory: {}", sourceFilePath);
                        break;
                    }
                }
            }
        }
        
        // Save original filename to metadata
        Path fileNamePath = Paths.get("peer_" + peerId, "metadata", "filename");
        Files.write(fileNamePath, sourceFilePath.getBytes());
        
        // Set internal filename tracking to the actual found location
        currentFileName = sourceFilePath;
        
        if (sourceFile == null || !sourceFile.exists()) {
            log.error("SPLIT FILE ERROR: Source file not found in any location: {}", sourceFilePath);
            
            // As a last resort, ask the user to provide it
            log.info("MANUAL INTERVENTION NEEDED: Please place the file '{}' in the current directory", config.getFileName());
            
            return;
        }
        
        log.info("SPLIT FILE: Beginning to split file {} into {} pieces", sourceFilePath, totalPieces);
        
        try (RandomAccessFile raf = new RandomAccessFile(sourceFile, "r")) {
            long fileSize = raf.length();
            int pieceSize = config.getPieceSize();
            
            log.info("SPLIT FILE: File size: {} bytes, piece size: {} bytes", fileSize, pieceSize);
            
            for (int i = 0; i < totalPieces; i++) {
                int currentPieceSize = (int) Math.min(pieceSize, fileSize - i * pieceSize);
                byte[] piece = new byte[currentPieceSize];
                
                raf.seek(i * pieceSize);
                raf.readFully(piece, 0, currentPieceSize);
                
                // Store piece in memory
                pieceMap.put(i, piece);
                
                // Save piece to disk
                savePieceToDisk(peerId, i, piece);
                
                if (i % 10 == 0 || i == totalPieces - 1) {
                    log.info("SPLIT FILE: Processed {}/{} pieces", i+1, totalPieces);
                }
            }
            
            log.info("SPLIT FILE: Successfully split file into {} pieces", totalPieces);
        } catch (Exception e) {
            log.error("SPLIT FILE ERROR: Failed to split file: {}", e.getMessage(), e);
            throw new IOException("Failed to split file", e);
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
        // Validate piece index is within valid range
        if (pieceIndex < 0 || pieceIndex >= totalPieces) {
            log.error("Invalid piece index {} requested (total pieces: {})", pieceIndex, totalPieces);
            return null;
        }
        
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
                if (data.length > 0) {
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
                    log.warn("Piece file exists but is empty for piece {}", pieceIndex);
                }
            }
            
            // Check if this peer should have the source file
            Peer thisPeer = null;
            try {
                // Find current peer in the peers map
                thisPeer = new ArrayList<>(peers.values()).stream()
                          .filter(p -> p.getPeerId().equals(peerId))
                          .findFirst()
                          .orElse(null);
            } catch (Exception e) {
                log.warn("Error looking up peer {} in peers map", peerId, e);
            }
            
            // If we're a seeder with the source file, generate the piece
            boolean isSeeder = thisPeer != null && thisPeer.isHasFile();
            String sourceFilePath = config.getFileName();
            File sourceFile = new File(sourceFilePath);
            
            if (isSeeder && sourceFile.exists()) {
                log.info("This peer is a seeder, generating piece {} from source file '{}'", pieceIndex, sourceFilePath);
                try (RandomAccessFile raf = new RandomAccessFile(sourceFile, "r")) {
                    int pieceSize = config.getPieceSize();
                    long fileSize = raf.length();
                    
                    log.info("Source file size: {}, piece size: {}, total pieces: {}", 
                             fileSize, pieceSize, totalPieces);
                    
                    // Ensure piece index is valid for the file size
                    if (pieceIndex * pieceSize < fileSize) {
                        int currentPieceSize = (int) Math.min(pieceSize, fileSize - pieceIndex * pieceSize);
                        byte[] newPiece = new byte[currentPieceSize];
                        
                        raf.seek(pieceIndex * pieceSize);
                        raf.readFully(newPiece, 0, currentPieceSize);
                        
                        log.info("Successfully read piece {} from source file, size: {}", 
                                pieceIndex, currentPieceSize);
                        
                        // Store piece in memory
                        pieceMap.put(pieceIndex, newPiece);
                        
                        // Save piece to disk
                        savePieceToDisk(peerId, pieceIndex, newPiece);
                        
                        // Update bitfield
                        if (bitfield != null) {
                            bitfield.set(pieceIndex);
                        }
                        
                        log.info("Generated piece {} from source file (size: {})", pieceIndex, currentPieceSize);
                        return newPiece;
                    } else {
                        log.error("Piece index {} is beyond file size {} (piece size: {})", 
                                 pieceIndex, fileSize, pieceSize);
                    }
                } catch (Exception e) {
                    log.error("Failed to generate piece {} from source file: {}", pieceIndex, e.getMessage());
                    e.printStackTrace(); // Add full stack trace for debugging
                }
            }
            
            // Look for the piece in other peer directories
            log.info("Piece {} not found for peer {}, searching in other peers", pieceIndex, peerId);
            
            // Try to find it in any peer directory
            File peersRoot = new File(".");
            File[] peerDirs = peersRoot.listFiles(file -> 
                file.isDirectory() && file.getName().startsWith("peer_"));
            
            if (peerDirs != null) {
                for (File peerDir1 : peerDirs) {
                    String otherPeerId = peerDir1.getName().substring("peer_".length());
                    if (otherPeerId.equals(peerId)) continue; // Skip current peer
                    
                    String otherPiecePath = peerDir1.getPath() + "/piece_" + pieceIndex;
                    File otherPieceFile = new File(otherPiecePath);
                    
                    if (otherPieceFile.exists() && otherPieceFile.length() > 0) {
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
            }
            
            // Special handling for seeders - last resort, try to generate the piece even if we don't think we should have it
            // This is a fallback when the bitfield communication has issues
            if (sourceFile.exists()) {
                log.warn("Last resort: Trying to generate piece {} from source file '{}' even though we're not marked as a seeder", 
                        pieceIndex, sourceFilePath);
                try (RandomAccessFile raf = new RandomAccessFile(sourceFile, "r")) {
                    int pieceSize = config.getPieceSize();
                    long fileSize = raf.length();
                    
                    if (pieceIndex * pieceSize < fileSize) {
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
                        
                        log.info("Last resort: Generated piece {} from source file (size: {})", pieceIndex, currentPieceSize);
                        return newPiece;
                    }
                } catch (Exception e) {
                    // This is expected to fail if we're not a seeder, so log at debug level
                    log.debug("Last resort attempt failed for piece {}", pieceIndex);
                }
            }
            
            log.warn("Piece {} not found on disk for peer {} or any other peers", pieceIndex, peerId);
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
        log.info("PEER ID SET: FileService local peer ID is now {}", peerId);
    }
    
    public String getLocalPeerId() {
        if (localPeerId == null || localPeerId.isEmpty()) {
            log.error("PEER ID ERROR: FileService local peer ID is null or empty!");
        }
        return localPeerId;
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