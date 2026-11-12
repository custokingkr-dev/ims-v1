-- Product orders (every catalogue item except notebooks) reuse the school's WhatsApp groups that the notebook tracker
-- keeps here (SCHOOL_GROUP, INTERNAL_APPROVAL) and add one group of their own: the product vendor's (PRODUCT_VENDOR).
-- school-core keeps no copy, so there is one row per school and group however many order types read it.
--
-- V15 declared the key list as an unnamed column check, which PostgreSQL named <table>_<column>_check. It is dropped
-- by that exact name, without IF EXISTS, so a database where the name differs stops here instead of keeping the old
-- list next to the new one and refusing every PRODUCT_VENDOR row. The invite_url check is left as V15 created it.
ALTER TABLE workflow.workflow_school_whatsapp_group_links
    DROP CONSTRAINT workflow_school_whatsapp_group_links_target_key_check;

ALTER TABLE workflow.workflow_school_whatsapp_group_links
    ADD CONSTRAINT workflow_school_whatsapp_group_links_target_key_check CHECK (target_key IN (
        'SCHOOL_GROUP', 'INTERNAL_APPROVAL', 'PRODUCT_VENDOR',
        'board_shree', 'board_national', 'board_prime',
        'lam_glosspro', 'lam_crystal', 'lam_brightfinish',
        'binder_perfect', 'binder_classic', 'binder_metro',
        'offset_metro', 'offset_precision', 'offset_cityline'
    ));
