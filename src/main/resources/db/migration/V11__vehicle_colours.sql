-- Vehicle colours drivers pick from (a dropdown, so "white", "White " and "wht" don't all appear).
-- SchoolDrop staff can add more.
CREATE TABLE vehicle_colours (
    id         UUID           PRIMARY KEY,
    name       VARCHAR(40)    NOT NULL,
    active     BOOLEAN        NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ(6) NOT NULL
);
CREATE UNIQUE INDEX uq_vehicle_colours_name ON vehicle_colours (LOWER(name));

INSERT INTO vehicle_colours (id, name, created_at)
SELECT gen_random_uuid(), c, now()
FROM unnest(ARRAY['White', 'Silver', 'Grey', 'Black', 'Blue', 'Navy blue', 'Red', 'Maroon', 'Green', 'Yellow',
                  'Orange', 'Brown', 'Beige', 'Gold', 'Purple', 'Pink']) AS c;
