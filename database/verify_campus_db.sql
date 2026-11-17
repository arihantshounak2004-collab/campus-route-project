-- Run this in MySQL Workbench AFTER campus_db.sql to check the setup.
USE campus_route_db;

-- Expected: locations 18, pathways 32, facilities 32, admins 1, conditions 32
SELECT (SELECT COUNT(*) FROM locations)         AS locations,
       (SELECT COUNT(*) FROM pathways)          AS pathways,
       (SELECT COUNT(*) FROM facilities)        AS facilities,
       (SELECT COUNT(*) FROM administrators)    AS admins,
       (SELECT COUNT(*) FROM campus_conditions) AS conditions;

-- Expected: OPEN 29, RESTRICTED 2, BLOCKED 1
SELECT pathway_status, COUNT(*) AS total FROM pathways GROUP BY pathway_status;

-- Expected: LOW 21, MEDIUM 8, HIGH 3
SELECT crowd_level, COUNT(*) AS total FROM pathways GROUP BY crowd_level;

-- Readable list of all pathways
SELECT a.location_name AS from_place, b.location_name AS to_place,
       p.distance AS metres, p.estimated_time AS seconds,
       p.crowd_level, p.pathway_status, p.is_accessible
FROM pathways p
JOIN locations a ON a.location_id = p.source_location_id
JOIN locations b ON b.location_id = p.destination_location_id
ORDER BY p.pathway_id;
