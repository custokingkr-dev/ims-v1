package com.custoking.ims.operationsservice.application.orderworkflow.template;

/**
 * The kinds of typed stage field (design §3.3). Checklists are not fields: they are stored as lists of ticked item
 * indexes under their own key.
 * <ul>
 *   <li>{@code TEXT}: at most {@code max} characters.</li>
 *   <li>{@code WORDS}: at most {@code max} words, and never more than 4,000 characters.</li>
 *   <li>{@code URL}: an {@code https://} link of at most {@code max} (at most 1,000) characters.</li>
 *   <li>{@code DATE}: an ISO date, {@code yyyy-mm-dd}.</li>
 *   <li>{@code WHOLE}: an integer between {@code min} and {@code max}.</li>
 *   <li>{@code NUMBER}: a decimal between {@code min} and {@code max}.</li>
 *   <li>{@code CHOICE}: one of the field's choice values.</li>
 * </ul>
 */
public enum FieldKind { TEXT, WORDS, URL, DATE, WHOLE, NUMBER, CHOICE }
