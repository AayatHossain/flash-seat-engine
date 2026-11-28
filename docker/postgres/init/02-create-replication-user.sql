-- Used only by the read replica to stream changes from the primary.
CREATE USER replicator WITH REPLICATION PASSWORD 'replicator';
