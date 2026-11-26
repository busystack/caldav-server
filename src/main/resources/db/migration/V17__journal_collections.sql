ALTER TABLE collections DROP CONSTRAINT collections_component_check;
ALTER TABLE collections ADD CONSTRAINT collections_component_check
    CHECK (component IN ('VEVENT', 'VTODO', 'VJOURNAL'));
