CREATE DATABASE iers_auth;
CREATE DATABASE iers_iot;
CREATE DATABASE iers_dispatch;

\c iers_auth
CREATE EXTENSION IF NOT EXISTS postgis;

\c iers_dispatch
CREATE EXTENSION IF NOT EXISTS postgis;