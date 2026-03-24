package com.servicelens.ingestion;

/**
 * Strategy chosen by {@link IngestionStrategyResolver} for a given ingestion request.
 *
 * <ul>
 *   <li>{@link #FRESH}      — service has never been ingested; walk and index all files,
 *                             no purge required.</li>
 *   <li>{@link #INCREMENTAL}— service is already registered and {@code force} is false;
 *                             use SHA-256 hash comparison to process only changed files.</li>
 *   <li>{@link #FORCE_FULL} — service is already registered but {@code force=true} was
 *                             requested; purge all existing data and re-index everything.</li>
 * </ul>
 */
public enum IngestionStrategy {
    FRESH,
    INCREMENTAL,
    FORCE_FULL
}
