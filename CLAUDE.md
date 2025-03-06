# P2P-File-Sharing Development Guidelines

## Build Commands
- Build: `./mvnw clean install`
- Run: `./mvnw spring-boot:run`
- Test: `./mvnw test`
- Run single test: `./mvnw test -Dtest=TestClassName#methodName`

## Code Style Guidelines

### Imports
- Java standard library imports first
- Third-party imports second (Spring, Jackson)
- Project imports last
- Group by package, no wildcard imports

### Formatting & Naming
- Classes: PascalCase (FileController, PeerService)
- Methods/Variables: camelCase (registerPeer, localPeerId)
- Constants: UPPER_SNAKE_CASE
- Use Lombok annotations to reduce boilerplate (@Data, @RequiredArgsConstructor)
- Maximum line length: 120 characters

### Architecture
- Follow Spring Boot conventions (controllers, services, models)
- Constructor injection with @RequiredArgsConstructor
- RESTful API design for endpoints
- Use WebSocket for real-time peer communication

### Error Handling
- Use try-catch with appropriate logging (log.error)
- Use Optional for nullable values
- Include detailed error messages in exceptions