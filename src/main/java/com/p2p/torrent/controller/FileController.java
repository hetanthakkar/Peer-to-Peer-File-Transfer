package com.p2p.torrent.controller;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.p2p.torrent.config.TorrentConfig;
import com.p2p.torrent.service.FileService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@RestController
@RequestMapping("/api/files")
@RequiredArgsConstructor
@Slf4j
public class FileController {
    private final FileService fileService;
    private final TorrentConfig config;
    
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> uploadFile(
            @RequestParam(value = "file", required = false) MultipartFile file,
            @RequestParam(value = "peerId", required = false) String peerId,
            @RequestParam(value = "displayName", required = false) String displayName) {
        
        // Debug information
        log.info("Received upload request with peerId: {}", peerId);
        log.info("File present: {}", (file != null));
        if (file != null) {
            log.info("File name: {}, size: {}", file.getOriginalFilename(), file.getSize());
        }
        
        try {
            if (file == null) {
                Map<String, Object> errorMap = new HashMap<>();
                errorMap.put("error", "No file received in the request");
                errorMap.put("success", false);
                return ResponseEntity.badRequest().body(errorMap);
            }
            
            // Get file name - either from displayName parameter or original filename
            String fileName = (displayName != null && !displayName.isEmpty()) ? 
                              displayName : file.getOriginalFilename();
            
            // Ensure we have a valid filename
            if (fileName == null || fileName.isEmpty()) {
                fileName = "uploaded_file";
            }
            
            // Update the config's fileName for this run (needed for compatibility with other services)
            config.setFileName(fileName);
            
            byte[] fileData = file.getBytes();
            
            // Get content type information
            String contentType = file.getContentType();
            log.info("Content type: {}, filename: {}", contentType, fileName);
            
            // Create directory for this peer if it doesn't exist
            String peerDir = "peer_" + peerId;
            Files.createDirectories(Paths.get(peerDir));
            
            // Create metadata directory if it doesn't exist
            Files.createDirectories(Paths.get(peerDir, "metadata"));
            
            // Save content type metadata
            if (contentType != null) {
                Path metadataPath = Paths.get(peerDir, "metadata", "content_type");
                Files.write(metadataPath, contentType.getBytes());
            }
            
            // Save the filename in metadata
            Path fileNamePath = Paths.get(peerDir, "metadata", "filename");
            Files.write(fileNamePath, fileName.getBytes());
            
            // Save the original file
            Path filePath = Paths.get(peerDir, fileName);
            Files.write(filePath, fileData);
            
            // Split file into pieces
            int pieceSize = config.getPieceSize();
            int totalPieces = (int) Math.ceil((double) fileData.length / pieceSize);
            
            for (int i = 0; i < totalPieces; i++) {
                int start = i * pieceSize;
                int length = Math.min(pieceSize, fileData.length - start);
                
                byte[] piece = new byte[length];
                System.arraycopy(fileData, start, piece, 0, length);
                
                fileService.savePieceToDisk(peerId, i, piece);
            }
            
            Map<String, Object> responseMap = new HashMap<>();
            responseMap.put("fileName", fileName);
            responseMap.put("fileSize", fileData.length);
            responseMap.put("contentType", contentType);
            responseMap.put("pieces", totalPieces);
            responseMap.put("pieceSize", pieceSize);
            responseMap.put("success", true);
            
            return ResponseEntity.ok(responseMap);
        } catch (IOException e) {
            log.error("Failed to upload file", e);
            
            Map<String, Object> errorMap = new HashMap<>();
            errorMap.put("error", e.getMessage());
            errorMap.put("success", false);
            
            return ResponseEntity.badRequest().body(errorMap);
        }
    }
    
    @GetMapping("/download/{peerId}")
    public ResponseEntity<InputStreamResource> downloadFile(@PathVariable String peerId) {
        try {
            if (fileService.hasCompletedDownload()) {
                log.info("Download is complete, merging file for peer {}", peerId);
                fileService.mergeFile(peerId);
            } else {
                log.warn("Forcing file merge for peer {} despite incomplete download", peerId);
                // Force merge anyway for demonstration purposes
                fileService.mergeFile(peerId);
            }
        } catch (Exception e) {
            log.error("Error merging file for download", e);
        }
        
        String peerDir = "peer_" + peerId;
        
        // Read filename from metadata if available
        String fileName = config.getFileName();
        Path fileNamePath = Paths.get(peerDir, "metadata", "filename");
        if (Files.exists(fileNamePath)) {
            try {
                fileName = new String(Files.readAllBytes(fileNamePath)).trim();
                log.info("Using filename from metadata: {}", fileName);
            } catch (IOException e) {
                log.warn("Could not read filename metadata, using default: {}", fileName);
            }
        }
        
        Path filePath = Paths.get(peerDir, fileName);
        
        try {
            File file = filePath.toFile();
            
            if (!file.exists()) {
                // Try to merge the file
                fileService.mergeFile(peerId);
                
                // Check again if file exists
                if (!file.exists()) {
                    return ResponseEntity.notFound().build();
                }
            }
            
            // Read content type from metadata if available
            MediaType contentType = MediaType.APPLICATION_OCTET_STREAM;
            Path metadataPath = Paths.get(peerDir, "metadata", "content_type");
            if (Files.exists(metadataPath)) {
                String contentTypeStr = new String(Files.readAllBytes(metadataPath)).trim();
                try {
                    contentType = MediaType.parseMediaType(contentTypeStr);
                    log.info("Using content type from metadata: {}", contentTypeStr);
                } catch (Exception e) {
                    log.warn("Invalid content type in metadata: {}", contentTypeStr);
                }
            }
            
            InputStreamResource resource = new InputStreamResource(new ByteArrayInputStream(Files.readAllBytes(filePath)));
            
            // If it's a video file, serve inline for browser playback
            boolean isVideo = contentType != null && 
                             (contentType.toString().startsWith("video/") || 
                              fileName.toLowerCase().endsWith(".mp4") ||
                              fileName.toLowerCase().endsWith(".webm") ||
                              fileName.toLowerCase().endsWith(".mov"));
            
            if (isVideo) {
                log.info("Serving video file {} for streaming", fileName);
                return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=" + fileName)
                    .contentType(contentType)
                    .contentLength(file.length())
                    .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                    .body(resource);
            } else {
                return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + fileName)
                    .contentType(contentType)
                    .contentLength(file.length())
                    .body(resource);
            }
        } catch (IOException e) {
            log.error("Failed to download file", e);
            return ResponseEntity.badRequest().build();
        }
    }
    
    @GetMapping("/pieces/{peerId}")
    public ResponseEntity<Map<String, Object>> getPiecesInfo(@PathVariable String peerId) {
        BitSet bitfield = fileService.getBitfield();
        int totalPieces = fileService.getTotalPieces();
        int downloadedPieces = bitfield.cardinality();
        
        StringBuilder bitfieldStr = new StringBuilder();
        for (int i = 0; i < totalPieces; i++) {
            bitfieldStr.append(bitfield.get(i) ? '1' : '0');
        }
        
        Map<String, Object> infoMap = new HashMap<>();
        infoMap.put("totalPieces", totalPieces);
        infoMap.put("downloadedPieces", downloadedPieces);
        infoMap.put("bitfield", bitfieldStr.toString());
        infoMap.put("isComplete", fileService.hasCompletedDownload());
        
        return ResponseEntity.ok(infoMap);
    }
    
    @GetMapping("/piece/{peerId}/{pieceIndex}")
    public ResponseEntity<byte[]> downloadPiece(
            @PathVariable String peerId,
            @PathVariable int pieceIndex) {
        
        byte[] piece = fileService.getPiece(peerId, pieceIndex);
        
        if (piece == null) {
            return ResponseEntity.notFound().build();
        }
        
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .body(piece);
    }
}