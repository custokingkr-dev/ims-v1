ALTER TABLE catalog.order_design_email_intents
    DROP CONSTRAINT order_design_email_intents_image_count_check;

ALTER TABLE catalog.order_design_email_intents
    ADD CONSTRAINT order_design_email_intents_image_count_check CHECK (image_count >= 4);
