# BountyPilot v3.0

- Fixed OpenSurveillanceDB camera search parsing for `records` responses.
- Added nearby public-camera directory search by place using OpenSurveillanceDB public metadata.
- Added public feed viewer input for intentionally public/authorized feed URLs.
- Added HIBP breach-exposure check for authorized email addresses (requires user's HIBP API key; returns breach metadata, not raw credentials).
- Added translucent green full-screen background scan/event log.
- Added local inner-scroll touch handling for camera result panes.
- Preserved phone-camera removal: no camera/location permission is required for public camera intelligence.
- Added OSINT scan logging for public intel, Shodan, recon, vulnerability scans, and public camera searches.
- Version 3.0 / versionCode 30.
