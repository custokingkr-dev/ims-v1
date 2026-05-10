-- Seed default workflow definitions and steps

INSERT INTO workflow_definitions (id, name, description) VALUES
    ('SUPPLY_ORDER_DEFAULT',   'Supply Order Approval',          'Standard 2-step approval for supply orders'),
    ('FIREFIGHTING_DEFAULT',   'Firefighting Request Approval',  'Bursar then Principal approval for firefighting')
ON CONFLICT (id) DO NOTHING;

-- Supply order steps
INSERT INTO workflow_steps (definition_id, step_order, step_name, required_permission) VALUES
    ('SUPPLY_ORDER_DEFAULT', 1, 'Bursar Review',     'order:approve'),
    ('SUPPLY_ORDER_DEFAULT', 2, 'Principal Approval', 'order:approve')
ON CONFLICT (definition_id, step_order) DO NOTHING;

-- Firefighting steps
INSERT INTO workflow_steps (definition_id, step_order, step_name, required_permission) VALUES
    ('FIREFIGHTING_DEFAULT', 1, 'Bursar Review',          'firefighting:approve'),
    ('FIREFIGHTING_DEFAULT', 2, 'Principal Approval',     'firefighting:approve'),
    ('FIREFIGHTING_DEFAULT', 3, 'Custoking Fulfillment',  'firefighting:fulfill')
ON CONFLICT (definition_id, step_order) DO NOTHING;
