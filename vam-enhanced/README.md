# Virtual Account Management (VAM) System - Enhanced

A comprehensive Corporate Digital Banking platform for Virtual Account Management with **Digital Escrow**, **KYCC**, **Wallet Programs**, and **BaNCS Fallback** capabilities.

## 🏗️ Architecture Overview

```
┌─────────────────────────────────────────────────────────────────────┐
│                        Frontend (React + TypeScript)                 │
│         Swiss Minimalist Design • Tailwind CSS • Vite               │
└─────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────┐
│                    Backend (Spring Boot 3.2)                         │
│    REST APIs • OAuth 2.0 • Scheduled Jobs • Health Monitor          │
├─────────────────────────────────────────────────────────────────────┤
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────────────────┐  │
│  │  KYCC        │  │  Escrow      │  │  Wallet                  │  │
│  │  Service     │  │  Service     │  │  Service                 │  │
│  └──────────────┘  └──────────────┘  └──────────────────────────┘  │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────────────────┐  │
│  │  Virtual     │  │  Beneficiary │  │  Transaction             │  │
│  │  Account     │  │  Service     │  │  Service                 │  │
│  └──────────────┘  └──────────────┘  └──────────────────────────┘  │
├─────────────────────────────────────────────────────────────────────┤
│                    BaNCS Integration Layer                           │
│  ┌──────────────────────────────────────────────────────────────┐  │
│  │  Health Monitor  │  Sync Queue Service  │  Sync Processor    │  │
│  │  (Availability)  │  (Queue Operations)  │  (Background Job)  │  │
│  └──────────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────┘
              │                              │
              ▼                              ▼
       ┌──────────┐                   ┌──────────────┐
       │PostgreSQL│                   │  TCS BaNCS   │
       │    DB    │◄──────────────────│    APIs      │
       │ (Primary)│   Sync Queue      │  (Optional)  │
       └──────────┘                   └──────────────┘
```

## ✨ New Features

### 1. Digital Escrow Management
Corporate = **Seller**, Beneficiary = **Buyer**

```
Contract Flow:
DRAFT → PENDING_FUNDING → FUNDED → IN_PROGRESS → PENDING_RELEASE → RELEASED → COMPLETED
                                         ↓
                                    DISPUTED (can occur at any active stage)
```

Features:
- **Milestone-based releases** - Partial fund releases tied to deliverables
- **Full release** - Single release on completion
- **Dispute handling** - Raise and resolve disputes
- **Auto-release** - Configurable automatic release after conditions met
- **Document management** - Attach contracts, invoices, delivery notes

### 2. KYCC - Know Your Customer's Clients
Minimal KYC for beneficiaries (buyers/wallet holders):
- **Full Name**
- **Mobile Number**
- **Email Address**
- **KYC ID** (Type, Number, Expiry, Country)

Features:
- OTP/Manual verification
- Risk scoring and categorization
- Expiry tracking
- Consent management

### 3. Wallet Program Management
Corporate = **Program Operator**, Beneficiary = **Wallet Holder**

Features:
- **Program configuration** - Limits, fees, features
- **Published virtual accounts** - Each wallet backed by a VA
- **Load/Spend/Transfer** - Full transaction lifecycle
- **Limit management** - Daily/Monthly spend limits
- **Fee structure** - Issuance, monthly, load, transaction fees

### 4. BaNCS Fallback Mechanism
**Store locally, sync later** pattern:

```
When BaNCS Available:
  Request → BaNCS API → Response → Local DB

When BaNCS Unavailable:
  Request → Local DB (sync_status='PENDING') → Sync Queue
                                                    ↓
                                          Background Processor
                                                    ↓
                                    (When BaNCS recovers) → Sync to BaNCS
```

Components:
- **Health Monitor** - Tracks BaNCS availability
- **Sync Queue** - Stores pending operations (JSONB payload)
- **Sync Processor** - Background job with exponential backoff retry

## 📁 Project Structure

```
vam-enhanced/
├── database/
│   └── schema.sql              # Enhanced PostgreSQL schema
│
├── backend/src/main/java/com/bank/vam/
│   ├── model/
│   │   ├── entity/
│   │   │   ├── KyccRecord.java        # KYCC entity
│   │   │   ├── EscrowContract.java    # Escrow entity
│   │   │   ├── EscrowMilestone.java   # Milestone entity
│   │   │   ├── WalletProgram.java     # Wallet program entity
│   │   │   ├── WalletAccount.java     # Wallet account entity
│   │   │   └── BancsSyncQueue.java    # Sync queue entity
│   │   ├── dto/
│   │   │   ├── KyccDto.java
│   │   │   ├── EscrowDto.java
│   │   │   └── WalletDto.java
│   │   └── enums/
│   │       ├── EscrowStatus.java
│   │       ├── KyccVerificationStatus.java
│   │       ├── SyncOperationType.java
│   │       └── SyncStatus.java
│   │
│   ├── service/
│   │   ├── KyccService.java           # KYCC business logic
│   │   ├── EscrowService.java         # Escrow business logic
│   │   ├── WalletService.java         # Wallet business logic
│   │   ├── BancsHealthMonitor.java    # Health monitoring
│   │   ├── BancsSyncQueueService.java # Queue management
│   │   └── BancsSyncProcessor.java    # Background sync
│   │
│   ├── integration/
│   │   └── BancsIntegrationService.java # Enhanced with fallback
│   │
│   ├── controller/
│   │   ├── KyccController.java
│   │   ├── EscrowController.java
│   │   ├── WalletController.java
│   │   └── BancsSyncController.java   # Admin endpoints
│   │
│   └── repository/
│       └── AllRepositories.java       # JPA repositories
│
└── docs/
    └── ARCHITECTURE.md
```

## 🚀 API Endpoints

### KYCC APIs
| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/v1/kycc` | Create KYCC record |
| GET | `/api/v1/kycc/{id}` | Get KYCC by ID |
| GET | `/api/v1/kycc/mobile/{mobile}` | Find by mobile |
| POST | `/api/v1/kycc/{id}/verify` | Verify KYCC |
| POST | `/api/v1/kycc/{id}/reject` | Reject KYCC |

### Escrow APIs
| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/v1/escrow` | Create escrow contract |
| POST | `/api/v1/escrow/{id}/activate` | Activate contract |
| POST | `/api/v1/escrow/{id}/fund` | Record funding |
| POST | `/api/v1/escrow/{id}/milestones/{mid}/approve` | Approve milestone |
| POST | `/api/v1/escrow/{id}/milestones/{mid}/release` | Release milestone |
| POST | `/api/v1/escrow/{id}/dispute` | Raise dispute |
| POST | `/api/v1/escrow/{id}/dispute/resolve` | Resolve dispute |

### Wallet APIs
| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/v1/wallet/programs` | Create wallet program |
| POST | `/api/v1/wallet/accounts` | Issue wallet |
| POST | `/api/v1/wallet/accounts/{id}/load` | Load funds |
| POST | `/api/v1/wallet/accounts/{id}/spend` | Spend from wallet |
| POST | `/api/v1/wallet/accounts/{id}/transfer` | P2P transfer |
| POST | `/api/v1/wallet/accounts/{id}/suspend` | Suspend wallet |

### Admin/Sync APIs
| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/v1/admin/sync/health` | BaNCS health status |
| GET | `/api/v1/admin/sync/queue/stats` | Queue statistics |
| POST | `/api/v1/admin/sync/queue/process` | Manual sync trigger |
| POST | `/api/v1/admin/sync/queue/retry-failed` | Retry failed items |

## 🔄 BaNCS Sync Flow

```
1. Client Request → Virtual Account Service
                            │
2. Check BaNCS Health ──────┼───────────────────────────────────┐
        │                   │                                   │
        ▼                   ▼                                   ▼
   [Available]         [Unavailable]                      [Call Failed]
        │                   │                                   │
        ▼                   ▼                                   ▼
   Call BaNCS API     Create Local Entity              Create Local Entity
        │             (sync_status='PENDING')          (sync_status='PENDING')
        │                   │                                   │
        ▼                   ▼                                   ▼
   Update Local DB    Queue to bancs_sync_queue        Queue to bancs_sync_queue
   (sync_status=      (operation, payload, retry)      (operation, payload, retry)
    'SYNCED')               │                                   │
                            └───────────────┬───────────────────┘
                                            ▼
                            Background Processor (Every 60s)
                                            │
                            ┌───────────────┴───────────────┐
                            │   For each pending item:      │
                            │   1. Mark as PROCESSING       │
                            │   2. Call BaNCS API           │
                            │   3. On Success:              │
                            │      - Update entity status   │
                            │      - Mark queue COMPLETED   │
                            │   4. On Failure:              │
                            │      - Increment retry count  │
                            │      - Calculate next retry   │
                            │      - If max retries: FAILED │
                            └───────────────────────────────┘
```

## 📊 Database Tables

### New Tables
| Table | Description |
|-------|-------------|
| `kycc_records` | KYCC verification records |
| `escrow_contracts` | Digital escrow contracts |
| `escrow_milestones` | Milestone-based releases |
| `escrow_transactions` | Escrow fund movements |
| `escrow_documents` | Contract documents |
| `wallet_programs` | Wallet program configuration |
| `wallet_accounts` | Individual wallets |
| `wallet_transactions` | Wallet transactions |
| `bancs_sync_queue` | Pending sync operations |
| `bancs_sync_queue_history` | Completed/failed syncs |
| `bancs_health_status` | Health check history |

### Enhanced Tables
| Table | Changes |
|-------|---------|
| `corporate_schemes` | Added `program_type`, `kycc_required` |
| `virtual_accounts` | Added `account_type`, `bancs_sync_*` fields |
| `beneficiaries` | Added `kycc_id`, `beneficiary_role`, `bancs_sync_*` |
| `transactions` | Added `transaction_context`, `bancs_sync_*` |

## 🔒 Security Considerations

- KYCC records store masked ID numbers
- Consent tracking for privacy compliance
- Role-based access for escrow operations
- Audit logging for all transactions
- Encrypted payloads in sync queue

## 🏃 Quick Start

```bash
# 1. Database Setup
createdb vam_db
psql -d vam_db -f database/schema.sql

# 2. Backend
cd backend
mvn clean install
mvn spring-boot:run

# 3. Access Swagger UI
open http://localhost:8080/swagger-ui.html
```

## 📝 Configuration

Key environment variables:
```
DB_PASSWORD=your_db_password
BANCS_BASE_URL=https://your-bancs-endpoint
BANCS_CLIENT_ID=your_client_id
BANCS_CLIENT_SECRET=your_client_secret
JWT_SECRET=your_jwt_secret
```

## 📄 License

Proprietary - All rights reserved
