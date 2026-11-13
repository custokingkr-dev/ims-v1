package com.custoking.ims.operationsservice.application.orderworkflow.rules;

/** A stage's place on an order's path: completed, the one being worked (one per order), or not yet reached. */
public enum StepStatus { LOCKED, ACTIVE, COMPLETED }
