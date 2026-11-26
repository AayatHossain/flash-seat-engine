-- One database and one user per service, so no service can read another one's tables.
CREATE USER inventory WITH PASSWORD 'inventory';
CREATE DATABASE inventory OWNER inventory;

CREATE USER orders WITH PASSWORD 'orders';
CREATE DATABASE orders OWNER orders;

CREATE USER tickets WITH PASSWORD 'tickets';
CREATE DATABASE tickets OWNER tickets;
