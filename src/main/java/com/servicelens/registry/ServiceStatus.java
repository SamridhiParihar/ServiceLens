package com.servicelens.registry;

/**
 * Lifecycle status of a registered service in the ServiceLens registry.
 *
 * <ul>
 *   <li>{@link #INGESTING} — a full or force ingestion is currently in progress.</li>
 *   <li>{@link #ACTIVE}    — the service is fully indexed and ready to query.</li>
 *   <li>{@link #DELETING}  — data deletion is in progress.</li>
 *   <li>{@link #ERROR}     — the last ingestion attempt failed; data may be partial.</li>
 * </ul>
 */
public enum ServiceStatus {
    INGESTING,
    ACTIVE,
    DELETING,
    ERROR
}
