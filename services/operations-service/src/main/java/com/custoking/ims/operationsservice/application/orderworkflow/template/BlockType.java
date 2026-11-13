package com.custoking.ims.operationsservice.application.orderworkflow.template;

/** The closed set of stage cards (design §3.3 and §4.3). The browser renders each stage from these, in template order. */
public enum BlockType {
    ORDER_PDF_LINK, INTRO_MESSAGE, SCHOOL_GROUP, DESIGN_FILES, FIELDS, CHECKLIST, CDR_FOR_APPROVAL, HEAD_APPROVAL,
    ORDER_FOLDER, TITLE_CARD_QUANTITIES, SAVED_TITLE_CARDS, ITEM_QUANTITIES, VENDOR_DIRECTORY, VENDOR_SELECTION,
    VENDOR_GROUPS, INTERNAL_APPROVAL_GROUP, SIGN_OFF, SEND_BACK, LEGACY_DETAILS
}
