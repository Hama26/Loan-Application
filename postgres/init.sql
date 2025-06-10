CREATE TABLE applications (
    id UUID PRIMARY KEY,
    customer_id VARCHAR(50) NOT NULL,
    loan_amount DECIMAL(12,2) NOT NULL,
    loan_purpose VARCHAR(100) NOT NULL,
    income DECIMAL(12,2) NOT NULL,
    status VARCHAR(20) DEFAULT 'PENDING',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE documents (
    id UUID PRIMARY KEY,
    application_id UUID REFERENCES applications(id),
    document_type VARCHAR(50) NOT NULL,
    file_name VARCHAR(255) NOT NULL,
    file_size INTEGER NOT NULL,
    minio_object_key VARCHAR(500) NOT NULL,
    uploaded_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE application_events (
    id UUID PRIMARY KEY,
    application_id UUID NOT NULL,
    event_type VARCHAR(50) NOT NULL,
    event_data JSONB NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Risk assessments table
CREATE TABLE risk_assessments (
    id UUID PRIMARY KEY,
    application_id UUID NOT NULL,
    assessment_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    credit_score INTEGER,
    debt_ratio DECIMAL(5,2),
    risk_score DECIMAL(5,2),
    decision VARCHAR(20),
    decision_reason TEXT,
    processing_time_ms INTEGER
);

-- Risk factors table
CREATE TABLE risk_factors (
    id UUID PRIMARY KEY,
    assessment_id UUID REFERENCES risk_assessments(id),
    factor_name VARCHAR(100),
    factor_value DECIMAL(10,2),
    weight DECIMAL(3,2),
    contribution DECIMAL(5,2)
);

-- External api calls log
CREATE TABLE external_api_calls (
    id UUID PRIMARY KEY,
    application_id UUID,
    api_name VARCHAR(50),
    request_time TIMESTAMP,
    response_time TIMESTAMP,
    status_code INTEGER,
    cached BOOLEAN DEFAULT FALSE
);
