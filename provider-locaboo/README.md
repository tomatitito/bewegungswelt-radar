# Locaboo provider boundary

This module will own the read-only Locaboo client, remote DTOs, session handling,
and translation to `AvailabilitySource`. Networking is intentionally not implemented
in the foundation. Core modules must never depend on this module.

Do not model, persist, or log personal booking details or raw HTTP responses.
