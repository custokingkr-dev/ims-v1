package com.custoking.ims.schoolcoreservice.persistence;

/**
 * Thrown when a review item is edited while its owning campaign is COMPLETED (frozen).
 * Deliberately extends RuntimeException, NOT IllegalArgumentException, so controllers can map it
 * to HTTP 409 (Conflict) instead of the 400 the IllegalArgumentException handler produces.
 */
public class CampaignCompletedException extends RuntimeException {
    public CampaignCompletedException(String message) {
        super(message);
    }
}
