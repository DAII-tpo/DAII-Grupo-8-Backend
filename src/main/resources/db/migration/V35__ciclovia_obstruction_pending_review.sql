ALTER TABLE ciclovia_obstructions DROP CHECK chk_ciclovia_obstructions_status;

ALTER TABLE ciclovia_obstructions
    ADD CONSTRAINT chk_ciclovia_obstructions_status
        CHECK (status IN ('PENDING_REVIEW', 'ACTIVE', 'RESOLVED'));
