# Render deployment

Deploy this repository as a Render **Docker** web service. Render builds the root
`Dockerfile`, which compiles the application from source and packages Tesseract with
the Spanish (`spa`) OCR data in the runtime image.

## Service settings

1. Create a new Render Web Service from the repository.
2. Select **Docker** as the environment and leave the Dockerfile path at `Dockerfile`.
3. Configure the health-check path as `/health`.
4. Do not set `DOC_ANONYMIZER_PORT` for a normal Render service. The server uses it
   only as an explicit override; otherwise it uses Render's `PORT`, then defaults to
   `8080` for local execution and Docker Compose.

The service has no persistent disk. Uploaded documents are written only to temporary
container storage while they are processed and are deleted by the application, but a
Render deployment is not an offline workflow. Documents are sent to and processed on
a remote service. The Compose `tmpfs` protection is local-only and does not apply on
Render; assess the deployment's privacy, retention, access-control, and compliance
requirements before uploading sensitive material.
