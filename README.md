# P2P File Sharing - Spring Boot API

This is a Spring Boot implementation of a P2P (Peer-to-Peer) file sharing application inspired by the BitTorrent protocol. The application allows users to share files in a distributed manner, where each peer can act as both client and server.

## Features

- Upload files to be shared with other peers
- Download files from other peers
- Track available peers and their file status
- View detailed file piece information
- WebSocket-based real-time communication between peers
- REST API endpoints for testing with Postman

## Technologies Used

- Spring Boot 2.7.5
- Spring WebSocket
- Java 11
- Maven
- RESTful API
- WebSockets for real-time communication

## Getting Started

### Prerequisites

- Java 11 or higher
- Maven

### Building the Application

```bash
mvn clean install
```

### Running the Application

```bash
mvn spring-boot:run
```

The application will start on port 8080 by default.

## API Endpoints

### Peer Management

- `POST /api/torrent/init/{peerId}` - Initialize the local peer

  - Request body: `{"hasFile": boolean, "hostname": string, "port": number}`

- `POST /api/torrent/peer` - Register a new peer

  - Request body: `{"peerId": string, "hostname": string, "port": number, "hasFile": boolean}`

- `GET /api/torrent/peers` - Get all registered peers

- `POST /api/torrent/connect/{targetPeerId}` - Connect to a specific peer

- `GET /api/torrent/status` - Get the download status of the local peer

### File Management

- `POST /api/files/upload` - Upload a file

  - Form data: `file` (MultipartFile), `peerId` (string)

- `GET /api/files/download/{peerId}` - Download the complete file from a peer

- `GET /api/files/pieces/{peerId}` - Get information about file pieces for a peer

- `GET /api/files/piece/{peerId}/{pieceIndex}` - Download a specific piece from a peer

## WebSocket Communication

The application uses WebSockets for real-time communication between peers. The following message types are supported:

- HANDSHAKE - Establish connection between peers
- BITFIELD - Share which pieces the peer has
- INTERESTED - Express interest in downloading from peer
- NOT_INTERESTED - Express lack of interest
- CHOKE - Block peer from downloading
- UNCHOKE - Allow peer to download
- REQUEST - Request a specific piece
- PIECE - Send piece data
- HAVE - Notify about having a new piece

## Testing with Postman

1. Initialize your peer: `POST /api/torrent/init/{peerId}`
2. Register other peers: `POST /api/torrent/peer`
3. Upload a file: `POST /api/files/upload`
4. Connect to other peers: `POST /api/torrent/connect/{targetPeerId}`
5. Monitor download status: `GET /api/torrent/status`
6. Download completed file: `GET /api/files/download/{peerId}`

## Configuration

The application can be configured using `application.properties`:

```
# P2P Configuration
p2p.numberOfPreferredNeighbors=2
p2p.unchokingInterval=5
p2p.optimisticUnchokingInterval=15
p2p.fileName=TheFile
p2p.fileSize=2000000
p2p.pieceSize=100000
```

## Future Work

- React frontend for easier interaction
- Improved peer discovery mechanism
- DHT (Distributed Hash Table) for better peer management
- Enhanced security features
- File integrity verification

![Setup Screenshot]([https://i.postimg.cc/dtVykJBf/P2P.png](https://i.postimg.cc/jKzQ15JR/P2P.png?dl=1))
