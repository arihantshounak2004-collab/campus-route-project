-- =====================================================================
-- Campus Route Optimization - database setup script
-- Database : campus_route_db      Engine: MySQL 8.0.16 or newer
--
-- HOW TO RUN (MySQL Workbench):
--   File > Open SQL Script > pick this file > click the lightning-bolt icon.
--
-- This script is SAFE TO RE-RUN: it never drops tables or deletes data.
-- (Sample rows use INSERT IGNORE, so duplicates are skipped.)
-- To start completely fresh, run the optional RESET block at the very bottom.
--
-- NOTES ON THE DESIGN
--  * A pathway is an UNDIRECTED edge stored once, with source id < destination id.
--    The Java graph adds it in both directions. The CHECK constraint prevents
--    duplicate edges stored in reverse order.
--  * distance is in METRES, estimated_time is in SECONDS (walking about 1.2 m/s).
--  * pathways holds the CURRENT crowd/status. campus_conditions keeps a HISTORY
--    of every change, so we never store two conflicting "current" values.
--  * Map coordinates use a 1000 x 650 canvas. Distances are at least
--    0.5 m per map pixel, which keeps the A* straight-line heuristic safe.
-- =====================================================================

CREATE DATABASE IF NOT EXISTS campus_route_db
  CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE campus_route_db;

-- ---------------------------------------------------------------------
-- Table 1: locations (graph vertices)
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS locations (
  location_id    INT AUTO_INCREMENT PRIMARY KEY,
  location_name  VARCHAR(100) NOT NULL,
  location_type  ENUM('MAIN_GATE','BUILDING','ACADEMIC','LIBRARY','CAFETERIA','HOSTEL','PARKING','SPORTS','MEDICAL') NOT NULL,
  description    VARCHAR(255) NULL,
  x_coordinate   INT NOT NULL,
  y_coordinate   INT NOT NULL,
  is_active      BOOLEAN NOT NULL DEFAULT TRUE,
  CONSTRAINT uq_location_name UNIQUE (location_name),
  CONSTRAINT chk_loc_x CHECK (x_coordinate BETWEEN 0 AND 1000),
  CONSTRAINT chk_loc_y CHECK (y_coordinate BETWEEN 0 AND 650),
  INDEX idx_locations_type (location_type),
  INDEX idx_locations_active (is_active)
) ENGINE=InnoDB;

-- ---------------------------------------------------------------------
-- Table 2: pathways (graph edges)
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS pathways (
  pathway_id               INT AUTO_INCREMENT PRIMARY KEY,
  source_location_id       INT NOT NULL,
  destination_location_id  INT NOT NULL,
  distance                 DECIMAL(8,1) NOT NULL,
  estimated_time           INT NOT NULL,
  crowd_level              ENUM('LOW','MEDIUM','HIGH') NOT NULL DEFAULT 'LOW',
  pathway_status           ENUM('OPEN','RESTRICTED','BLOCKED') NOT NULL DEFAULT 'OPEN',
  is_accessible            BOOLEAN NOT NULL DEFAULT TRUE,
  CONSTRAINT fk_path_source FOREIGN KEY (source_location_id)
      REFERENCES locations(location_id) ON DELETE RESTRICT,
  CONSTRAINT fk_path_dest FOREIGN KEY (destination_location_id)
      REFERENCES locations(location_id) ON DELETE RESTRICT,
  CONSTRAINT uq_path_pair UNIQUE (source_location_id, destination_location_id),
  CONSTRAINT chk_path_order CHECK (source_location_id < destination_location_id),
  CONSTRAINT chk_path_distance CHECK (distance > 0),
  CONSTRAINT chk_path_time CHECK (estimated_time > 0),
  INDEX idx_pathways_dest (destination_location_id),
  INDEX idx_pathways_status (pathway_status)
) ENGINE=InnoDB;

-- ---------------------------------------------------------------------
-- Table 3: facilities
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS facilities (
  facility_id          INT AUTO_INCREMENT PRIMARY KEY,
  facility_name        VARCHAR(100) NOT NULL,
  facility_type        ENUM('RESTROOM','DRINKING_WATER','CAFETERIA','COMPUTER_LAB','ATM','PRINT_SHOP','FIRST_AID','STUDY_AREA') NOT NULL,
  location_id          INT NOT NULL,
  description          VARCHAR(255) NULL,
  availability_status  ENUM('AVAILABLE','UNAVAILABLE','UNDER_MAINTENANCE')
                       NOT NULL DEFAULT 'AVAILABLE',
  CONSTRAINT fk_facility_location FOREIGN KEY (location_id)
      REFERENCES locations(location_id) ON DELETE RESTRICT,
  CONSTRAINT uq_facility UNIQUE (facility_name, location_id),
  INDEX idx_facilities_type (facility_type),
  INDEX idx_facilities_location (location_id)
) ENGINE=InnoDB;

-- ---------------------------------------------------------------------
-- Table 4: administrators
-- password_hash format: iterations:saltHex:hashHex  (PBKDF2-HMAC-SHA256)
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS administrators (
  admin_id       INT AUTO_INCREMENT PRIMARY KEY,
  username       VARCHAR(50) NOT NULL,
  password_hash  VARCHAR(255) NOT NULL,
  CONSTRAINT uq_admin_username UNIQUE (username)
) ENGINE=InnoDB;

-- ---------------------------------------------------------------------
-- Table 5: campus_conditions (history of crowd/status changes)
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS campus_conditions (
  condition_id    INT AUTO_INCREMENT PRIMARY KEY,
  pathway_id      INT NOT NULL,
  crowd_level     ENUM('LOW','MEDIUM','HIGH') NOT NULL,
  pathway_status  ENUM('OPEN','RESTRICTED','BLOCKED') NOT NULL,
  updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_cond_pathway FOREIGN KEY (pathway_id)
      REFERENCES pathways(pathway_id) ON DELETE CASCADE,
  INDEX idx_cond_pathway_time (pathway_id, updated_at)
) ENGINE=InnoDB;

-- =====================================================================
-- SAMPLE DATA (fictional campus)
-- =====================================================================

-- Locations (ids are set explicitly so pathways can refer to them)
INSERT IGNORE INTO locations (location_id, location_name, location_type, description, x_coordinate, y_coordinate) VALUES
  (1, 'Main Gate', 'MAIN_GATE', 'Primary campus entrance with security desk.', 80, 320),
  (2, 'Administration Block', 'BUILDING', 'Offices for admissions, accounts and the principal.', 240, 320),
  (3, 'Visitor Parking', 'PARKING', 'Parking for visitors and guests near the main gate.', 90, 480),
  (4, 'Staff Parking', 'PARKING', 'Covered parking for faculty and staff.', 90, 160),
  (5, 'Electrical Block', 'ACADEMIC', 'Electrical and Electronics Engineering department.', 260, 150),
  (6, 'Computer Science Block', 'ACADEMIC', 'CSE department, classrooms and programming labs.', 450, 110),
  (7, 'Mechanical Block', 'ACADEMIC', 'Mechanical Engineering department and workshops.', 650, 110),
  (8, 'Central Library', 'LIBRARY', 'Main library with reading halls and digital resources.', 450, 260),
  (9, 'Auditorium', 'BUILDING', '1000-seat auditorium for events and seminars.', 620, 260),
  (10, 'Central Cafeteria', 'CAFETERIA', 'Main student cafeteria and snack counter.', 420, 420),
  (11, 'Medical Centre', 'MEDICAL', 'Campus clinic with first-aid and a visiting doctor.', 600, 420),
  (12, 'Sports Complex', 'SPORTS', 'Indoor stadium, gym and playing fields.', 850, 260),
  (13, 'Boys Hostel', 'HOSTEL', 'Residential block for male students.', 850, 430),
  (14, 'Girls Hostel', 'HOSTEL', 'Residential block for female students.', 700, 570),
  (15, 'Student Activity Centre', 'BUILDING', 'Clubs, society rooms and project spaces.', 270, 470),
  (16, 'Civil Block', 'ACADEMIC', 'Civil Engineering department and survey lab.', 800, 110),
  (17, 'Open Air Theatre', 'BUILDING', 'Outdoor amphitheatre for cultural events.', 480, 570),
  (18, 'Hostel Mess', 'CAFETERIA', 'Dining hall serving hostel residents.', 850, 570);

-- Pathways: (source, destination, distance in m, time in s, crowd, status, accessible)
INSERT IGNORE INTO pathways (source_location_id, destination_location_id, distance, estimated_time, crowd_level, pathway_status, is_accessible) VALUES
  (1, 2, 88.0, 73, 'HIGH', 'OPEN', 1),
  (1, 3, 92.2, 77, 'LOW', 'OPEN', 1),
  (1, 4, 88.2, 74, 'LOW', 'OPEN', 1),
  (2, 5, 89.9, 75, 'LOW', 'OPEN', 1),
  (2, 8, 114.7, 96, 'MEDIUM', 'OPEN', 1),
  (2, 15, 91.8, 76, 'LOW', 'OPEN', 0),
  (3, 15, 99.2, 83, 'LOW', 'OPEN', 1),
  (4, 5, 89.4, 75, 'LOW', 'OPEN', 1),
  (5, 6, 106.8, 89, 'LOW', 'OPEN', 0),
  (6, 7, 105.0, 88, 'LOW', 'OPEN', 1),
  (6, 8, 75.0, 62, 'MEDIUM', 'BLOCKED', 1),
  (7, 16, 78.8, 66, 'LOW', 'OPEN', 1),
  (7, 9, 88.0, 73, 'LOW', 'OPEN', 1),
  (8, 9, 85.0, 71, 'MEDIUM', 'OPEN', 1),
  (8, 10, 89.5, 75, 'HIGH', 'OPEN', 1),
  (9, 11, 88.7, 74, 'LOW', 'OPEN', 1),
  (9, 12, 120.8, 101, 'MEDIUM', 'OPEN', 1),
  (10, 11, 94.5, 79, 'MEDIUM', 'OPEN', 1),
  (10, 15, 87.0, 72, 'LOW', 'OPEN', 1),
  (10, 17, 92.9, 77, 'LOW', 'OPEN', 0),
  (11, 13, 137.6, 115, 'LOW', 'RESTRICTED', 1),
  (11, 14, 99.2, 83, 'LOW', 'OPEN', 1),
  (11, 17, 110.5, 92, 'LOW', 'OPEN', 1),
  (12, 13, 89.2, 74, 'MEDIUM', 'OPEN', 1),
  (12, 16, 94.9, 79, 'LOW', 'RESTRICTED', 1),
  (13, 14, 112.9, 94, 'LOW', 'OPEN', 1),
  (13, 18, 73.5, 61, 'HIGH', 'OPEN', 1),
  (14, 18, 78.8, 66, 'MEDIUM', 'OPEN', 1),
  (14, 17, 126.5, 105, 'LOW', 'OPEN', 1),
  (2, 10, 113.3, 94, 'MEDIUM', 'OPEN', 1),
  (2, 3, 131.6, 110, 'LOW', 'OPEN', 1),
  (8, 17, 202.4, 169, 'LOW', 'OPEN', 1);

-- Facilities (location found by name so the script stays readable)
INSERT IGNORE INTO facilities (facility_name, facility_type, location_id, description, availability_status) VALUES
  ('Library Restroom', 'RESTROOM', (SELECT location_id FROM locations WHERE location_name = 'Central Library'), 'Restrooms on the ground floor of the library.', 'AVAILABLE'),
  ('Library Water Cooler', 'DRINKING_WATER', (SELECT location_id FROM locations WHERE location_name = 'Central Library'), 'Filtered drinking water near the reading hall.', 'AVAILABLE'),
  ('Library Study Rooms', 'STUDY_AREA', (SELECT location_id FROM locations WHERE location_name = 'Central Library'), 'Quiet group-study rooms (bookable).', 'AVAILABLE'),
  ('Library Print Counter', 'PRINT_SHOP', (SELECT location_id FROM locations WHERE location_name = 'Central Library'), 'Printing and photocopying service.', 'AVAILABLE'),
  ('Cafeteria Snack Counter', 'CAFETERIA', (SELECT location_id FROM locations WHERE location_name = 'Central Cafeteria'), 'Tea, coffee, snacks and meals.', 'AVAILABLE'),
  ('Cafeteria Water Cooler', 'DRINKING_WATER', (SELECT location_id FROM locations WHERE location_name = 'Central Cafeteria'), 'Drinking water beside the seating area.', 'AVAILABLE'),
  ('Cafeteria Restroom', 'RESTROOM', (SELECT location_id FROM locations WHERE location_name = 'Central Cafeteria'), 'Restrooms behind the cafeteria.', 'AVAILABLE'),
  ('CSE Computer Lab', 'COMPUTER_LAB', (SELECT location_id FROM locations WHERE location_name = 'Computer Science Block'), 'Main programming lab, 60 systems.', 'AVAILABLE'),
  ('CSE Project Lab', 'COMPUTER_LAB', (SELECT location_id FROM locations WHERE location_name = 'Computer Science Block'), 'Lab reserved for final-year projects.', 'UNDER_MAINTENANCE'),
  ('CSE Water Cooler', 'DRINKING_WATER', (SELECT location_id FROM locations WHERE location_name = 'Computer Science Block'), 'Drinking water on each floor.', 'AVAILABLE'),
  ('CSE Restroom', 'RESTROOM', (SELECT location_id FROM locations WHERE location_name = 'Computer Science Block'), 'Restrooms on every floor.', 'AVAILABLE'),
  ('Campus ATM', 'ATM', (SELECT location_id FROM locations WHERE location_name = 'Administration Block'), '24-hour ATM beside the admission office.', 'AVAILABLE'),
  ('Admin Restroom', 'RESTROOM', (SELECT location_id FROM locations WHERE location_name = 'Administration Block'), 'Restrooms near the reception.', 'AVAILABLE'),
  ('Admin Water Cooler', 'DRINKING_WATER', (SELECT location_id FROM locations WHERE location_name = 'Administration Block'), 'Drinking water at reception.', 'AVAILABLE'),
  ('Campus Clinic', 'FIRST_AID', (SELECT location_id FROM locations WHERE location_name = 'Medical Centre'), 'Doctor on duty 9 AM to 5 PM.', 'AVAILABLE'),
  ('Ambulance Bay', 'FIRST_AID', (SELECT location_id FROM locations WHERE location_name = 'Medical Centre'), 'Emergency vehicle access.', 'AVAILABLE'),
  ('Sports Water Station', 'DRINKING_WATER', (SELECT location_id FROM locations WHERE location_name = 'Sports Complex'), 'Water points around the field.', 'AVAILABLE'),
  ('Sports Changing Rooms', 'RESTROOM', (SELECT location_id FROM locations WHERE location_name = 'Sports Complex'), 'Changing rooms and showers.', 'AVAILABLE'),
  ('Sports First-Aid Post', 'FIRST_AID', (SELECT location_id FROM locations WHERE location_name = 'Sports Complex'), 'Basic first aid for injuries.', 'AVAILABLE'),
  ('Mechanical Workshop Restroom', 'RESTROOM', (SELECT location_id FROM locations WHERE location_name = 'Mechanical Block'), 'Restrooms near the workshop.', 'AVAILABLE'),
  ('Mechanical Water Cooler', 'DRINKING_WATER', (SELECT location_id FROM locations WHERE location_name = 'Mechanical Block'), 'Drinking water in the main corridor.', 'UNAVAILABLE'),
  ('Auditorium Restroom', 'RESTROOM', (SELECT location_id FROM locations WHERE location_name = 'Auditorium'), 'Restrooms on both sides of the lobby.', 'AVAILABLE'),
  ('Auditorium Water Cooler', 'DRINKING_WATER', (SELECT location_id FROM locations WHERE location_name = 'Auditorium'), 'Drinking water in the lobby.', 'AVAILABLE'),
  ('Activity Centre Computer Corner', 'COMPUTER_LAB', (SELECT location_id FROM locations WHERE location_name = 'Student Activity Centre'), 'Shared computers for clubs.', 'AVAILABLE'),
  ('Activity Centre Restroom', 'RESTROOM', (SELECT location_id FROM locations WHERE location_name = 'Student Activity Centre'), 'Restrooms on the ground floor.', 'AVAILABLE'),
  ('Hostel Mess Dining Hall', 'CAFETERIA', (SELECT location_id FROM locations WHERE location_name = 'Hostel Mess'), 'Breakfast, lunch and dinner for residents.', 'AVAILABLE'),
  ('Boys Hostel Water Cooler', 'DRINKING_WATER', (SELECT location_id FROM locations WHERE location_name = 'Boys Hostel'), 'Drinking water on each floor.', 'AVAILABLE'),
  ('Girls Hostel Water Cooler', 'DRINKING_WATER', (SELECT location_id FROM locations WHERE location_name = 'Girls Hostel'), 'Drinking water on each floor.', 'AVAILABLE'),
  ('Electrical Block Computer Lab', 'COMPUTER_LAB', (SELECT location_id FROM locations WHERE location_name = 'Electrical Block'), 'Simulation and PLC lab.', 'AVAILABLE'),
  ('Electrical Block Restroom', 'RESTROOM', (SELECT location_id FROM locations WHERE location_name = 'Electrical Block'), 'Restrooms on the ground floor.', 'AVAILABLE'),
  ('Civil Block Restroom', 'RESTROOM', (SELECT location_id FROM locations WHERE location_name = 'Civil Block'), 'Restrooms near the survey lab.', 'AVAILABLE'),
  ('Open Air Theatre Water Point', 'DRINKING_WATER', (SELECT location_id FROM locations WHERE location_name = 'Open Air Theatre'), 'Water point near the stage.', 'AVAILABLE');

-- Initial snapshot of conditions (one history row per pathway).
-- NOT EXISTS keeps this from duplicating rows if the script is re-run.
INSERT INTO campus_conditions (pathway_id, crowd_level, pathway_status)
SELECT p.pathway_id, p.crowd_level, p.pathway_status FROM pathways p
WHERE NOT EXISTS (SELECT 1 FROM campus_conditions c WHERE c.pathway_id = p.pathway_id);

-- Sample administrator.  Username: admin    Password: Admin@123
-- CHANGE THIS PASSWORD before showing the project outside your lab.
INSERT IGNORE INTO administrators (username, password_hash)
VALUES ('admin', '65536:a1b2c3d4e5f60718293a4b5c6d7e8f90:36bb2fcee61e58a6feef9fd4e24c3cc6c56a49dfecaa6f1860710bfce54824bc');

-- =====================================================================
-- Quick check queries (run them after the script to verify the data)
-- =====================================================================
-- SELECT COUNT(*) AS locations FROM locations;            -- expect 18
-- SELECT COUNT(*) AS pathways FROM pathways;              -- expect 32
-- SELECT COUNT(*) AS facilities FROM facilities;          -- expect 32
-- SELECT pathway_status, COUNT(*) FROM pathways GROUP BY pathway_status;

-- =====================================================================
-- OPTIONAL RESET (DESTRUCTIVE: deletes ALL project data!)
-- Remove the leading "-- " from the lines below ONLY if you want a clean start,
-- then run the whole script again.
-- =====================================================================
-- DROP DATABASE campus_route_db;
