const express = require('express');
const { Pool } = require('pg');
const { Kafka } = require('kafkajs');
const Minio = require('minio');
const { createClient } = require('redis');
const winston = require('winston');
const { v4: uuidv4 } = require('uuid');
const multer = require('multer');
const { WebSocketServer } = require('ws');
const stream = require('stream');

// --- Configuration ---
const PORT = process.env.PORT || 3000;

const pgPool = new Pool({
    user: process.env.POSTGRES_USER,
    host: process.env.POSTGRES_HOST,
    database: process.env.POSTGRES_DB,
    password: process.env.POSTGRES_PASSWORD,
    port: process.env.POSTGRES_PORT,
});

const kafka = new Kafka({
    clientId: 'application-service',
    brokers: [process.env.KAFKA_BROKER],
});

const minioClient = new Minio.Client({
    endPoint: process.env.MINIO_ENDPOINT,
    port: parseInt(process.env.MINIO_PORT, 10),
    useSSL: process.env.MINIO_USE_SSL === 'true',
    accessKey: process.env.MINIO_ACCESS_KEY,
    secretKey: process.env.MINIO_SECRET_KEY,
});

const redisClient = createClient({
    url: `redis://${process.env.REDIS_HOST}:${process.env.REDIS_PORT}`
});

const logger = winston.createLogger({
    level: 'info',
    format: winston.format.json(),
    transports: [new winston.transports.Console()],
});

const app = express();
app.use(express.json());

// --- Correlation ID Middleware ---
app.use((req, res, next) => {
    req.correlationId = req.headers['x-correlation-id'] || uuidv4();
    res.setHeader('X-Correlation-ID', req.correlationId);
    next();
});

// --- WebSocket Server ---
const wss = new WebSocketServer({ noServer: true });
wss.on('connection', ws => {
    logger.info('WebSocket client connected');
    ws.on('message', message => logger.info(`Received WebSocket message: ${message}`));
});

// --- MinIO Bucket Setup ---
const BUCKET_NAME = 'loan-documents';
(async () => {
    try {
        const bucketExists = await minioClient.bucketExists(BUCKET_NAME);
        if (!bucketExists) {
            await minioClient.makeBucket(BUCKET_NAME, 'us-east-1');
            logger.info(`Bucket ${BUCKET_NAME} created.`);
        }
    } catch (err) {
        logger.error('Error creating MinIO bucket:', err);
        process.exit(1);
    }
})();

// --- Multer Configuration for Streaming ---
const storage = multer.memoryStorage();
const upload = multer({
    storage: storage,
    limits: { files: 5, fileSize: 10 * 1024 * 1024 },
    fileFilter: (req, file, cb) => {
        if (file.mimetype === 'application/pdf' || file.mimetype === 'image/jpeg' || file.mimetype === 'image/png') {
            cb(null, true);
        } else {
            cb(new Error('Invalid file type. Only PDF, JPG, and PNG are allowed.'), false);
        }
    }
});

// --- Helper Functions ---
const streamToMinio = (file, applicationId) => {
    return new Promise((resolve, reject) => {
        const documentId = uuidv4();
        const objectKey = `applications/${applicationId}/${documentId}-${file.originalname}`;
        const passThrough = new stream.PassThrough();
        minioClient.putObject(BUCKET_NAME, objectKey, passThrough, file.size, file.mimetype, (err, etag) => {
            if (err) return reject(err);
            resolve({ documentId, objectKey, etag });
        });
        passThrough.end(file.buffer);
    });
};

// --- API Endpoints ---
app.get('/health', (req, res) => res.status(200).send('OK'));

app.post('/api/loans/applications', upload.array('documents', 5), async (req, res) => {
    const { customerId, loanAmount, loanPurpose, income } = req.body;
    const log = (level, message, data) => logger[level]({ message, correlationId: req.correlationId, ...data });

    if (!customerId || !loanAmount || !loanPurpose || !income) {
        return res.status(400).json({ error: 'Missing required fields.' });
    }

    const applicationId = uuidv4();
    const client = await pgPool.connect();

    try {
        await client.query('BEGIN');

        const appResult = await client.query(
            'INSERT INTO applications(id, customer_id, loan_amount, loan_purpose, income) VALUES($1, $2, $3, $4, $5) RETURNING *',
            [applicationId, customerId, loanAmount, loanPurpose, income]
        );

        const uploadedDocuments = [];
        for (const file of req.files) {
            const { documentId, objectKey } = await streamToMinio(file, applicationId);
            await client.query(
                'INSERT INTO documents(id, application_id, document_type, file_name, file_size, minio_object_key) VALUES($1, $2, $3, $4, $5, $6)',
                [documentId, applicationId, file.fieldname, file.originalname, file.size, objectKey]
            );
            uploadedDocuments.push({ documentId, documentType: file.fieldname, fileName: file.originalname });
        }

        const event = {
            eventId: uuidv4(),
            eventType: 'ApplicationSubmitted',
            timestamp: new Date().toISOString(),
            applicationId,
            customerId,
            correlationId: req.correlationId,
            payload: { loanAmount, loanPurpose, income, documents: uploadedDocuments }
        };

        const producer = kafka.producer();
        await producer.connect();
        await producer.send({
            topic: 'loan-applications',
            messages: [{ value: JSON.stringify(event) }]
        });
        await producer.disconnect();

        await client.query('COMMIT');
        log('info', 'Application submitted successfully', { applicationId });
        res.status(201).json({ applicationId, status: 'PENDING', ...appResult.rows[0] });

    } catch (error) {
        await client.query('ROLLBACK');
        log('error', 'Application submission failed', { error: error.message });
        res.status(500).json({ error: 'Internal server error during application submission.' });
    } finally {
        client.release();
    }
});

app.get('/api/loans/applications/:id', async (req, res) => {
    const { id } = req.params;
    const log = (level, message, data) => logger[level]({ message, correlationId: req.correlationId, ...data });

    try {
        const cachedStatus = await redisClient.get(`status:${id}`);
        if (cachedStatus) {
            log('info', 'Fetched status from cache', { applicationId: id });
            return res.json({ applicationId: id, status: cachedStatus, source: 'cache' });
        }

        const result = await pgPool.query('SELECT status FROM applications WHERE id = $1', [id]);
        if (result.rows.length === 0) {
            return res.status(404).json({ error: 'Application not found.' });
        }

        const status = result.rows[0].status;
        await redisClient.set(`status:${id}`, status, { EX: 300 }); // Cache for 5 minutes
        log('info', 'Fetched status from DB', { applicationId: id });
        res.json({ applicationId: id, status, source: 'database' });

    } catch (error) {
        log('error', 'Failed to get application status', { applicationId: id, error: error.message });
        res.status(500).json({ error: 'Internal server error.' });
    }
});

app.get('/api/loans/applications/:id/documents', async (req, res) => {
    const { id } = req.params;
    const log = (level, message, data) => logger[level]({ message, correlationId: req.correlationId, ...data });

    try {
        const result = await pgPool.query('SELECT id, document_type, file_name, uploaded_at FROM documents WHERE application_id = $1', [id]);
        log('info', 'Fetched documents for application', { applicationId: id, count: result.rows.length });
        res.json(result.rows);
    } catch (error) {
        log('error', 'Failed to get documents', { applicationId: id, error: error.message });
        res.status(500).json({ error: 'Internal server error.' });
    }
});

// --- Server Initialization ---
const server = app.listen(PORT, async () => {
    await redisClient.connect();
    logger.info(`Application service listening on port ${PORT}`);
});

server.on('upgrade', (request, socket, head) => {
    wss.handleUpgrade(request, socket, head, ws => {
        wss.emit('connection', ws, request);
    });
});

process.on('SIGTERM', async () => {
    logger.info('SIGTERM signal received: closing HTTP server');
    await redisClient.quit();
    server.close(() => {
        logger.info('HTTP server closed');
        pgPool.end(() => {
            logger.info('PostgreSQL pool has ended');
            process.exit(0);
        });
    });
});
