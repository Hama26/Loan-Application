# Reactive Loan Application System

This project implements a reactive microservices-based loan application submission system.

## Architecture

```mermaid
graph TB
    subgraph "Client Layer"
        WEB[Web App]
        MOB[Mobile App]
    end
    
    subgraph "Edge Layer"
        LB[Load Balancer]
        GW[API Gateway<br/>Spring Cloud Gateway]
    end
    
    subgraph "Service Layer"
        AS[Application Service<br/>Node.js]
        CS[Commercial Service<br/>Spring Boot]
        RS[Risk Service<br/>Spring Boot]
        CR[Credit Service<br/>Spring Boot]
        NS[Notification Service<br/>Node.js]
        OCR[OCR Service<br/>Python/FastAPI]
    end
    
    subgraph "Integration Layer"
        KAFKA[Apache Kafka<br/>Event Bus]
        CB[Central Bank API]
    end
    
    subgraph "Data Layer"
        PG[(PostgreSQL<br/>Transactional)]
        REDIS[(Redis<br/>Cache)]
        MINIO[(MinIO<br/>Documents)]
    end
    
    subgraph "Infrastructure"
        K8S[Kubernetes]
        PROM[Prometheus]
        ELK[ELK Stack]
    end
    
    WEB --> LB
    MOB --> LB
    LB --> GW
    GW --> AS
    AS <--> KAFKA
    CS <--> KAFKA
    RS <--> KAFKA
    CR <--> KAFKA
    NS <--> KAFKA
    CS --> OCR
    RS --> CB
    
    AS --> PG
    CS --> PG
    RS --> PG
    AS --> REDIS
    AS --> MINIO
```

- **API Gateway**: Spring Cloud Gateway
- **Application Service**: Node.js with Express
- **Message Broker**: Apache Kafka
- **Storage**: PostgreSQL, MinIO, Redis
- **Containerization**: Docker

## How to run

1. Make sure you have Docker and Docker Compose installed.
2. Clone this repository.
3. Run `docker-compose up --build` from the root directory.

The API Gateway will be available at `http://localhost:8080`.
