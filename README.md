# Bitshort

A lightweight URL shortener built with **Spring Boot** and **React**. Create shareable short links, generate QR codes, and manage link expiration with built-in URL safety screening.

[Live Demo](https://bitshortner.vercel.app/)

## Features

- Short URLs with generated Base62 codes or custom aliases
- QR code generation and download
- Configurable link expiration with automatic cleanup
- Google Safe Browsing URL screening
- URL validation and protection against recursive short links
- Endpoint-level rate limiting with Bucket4j
- PostgreSQL persistence

## Tech Stack

| Backend | Frontend |
| --- | --- |
| Java 17 · Spring Boot 4.0.5 | React 18 · Vite |
| Spring Data JPA · PostgreSQL | `qrcode.react` |
| Bucket4j · Google Safe Browsing API | |

## How It Works

1. Submit a destination URL, optionally with a custom alias and expiry.
2. The backend validates the URL and checks it with Google Safe Browsing.
3. A short code is generated or the requested alias is assigned, then saved.
4. Visiting the short link redirects to the original destination.

## Run Locally

**Backend**

```bash
# Configure the database and Safe Browsing settings, then run:
./mvnw spring-boot:run
```

**Frontend**

```bash
cd frontend
npm install
npm run dev
```

## Configuration

Set these environment variables for the backend and database configuration:

| Variable                       | Purpose                                               |
| ------------------------------ | ----------------------------------------------------- |
| `DB_URL`                       | PostgreSQL JDBC connection URL                        |
| `DB_USERNAME`                  | PostgreSQL database username                          |
| `DB_PASSWORD`                  | PostgreSQL database password                          |
| `SAFE_BROWSING_API_KEY`        | Google Safe Browsing API key                          |
| `BITSHORT_SHORT_LINK_HOSTNAME` | Public hostname used to prevent recursive short links |

URL safety screening is enabled by default and uses **fail-closed** behavior: 
if the threat lookup is unavailable, URL creation is rejected. 
Set `BITSHORT_URL_SAFETY_FAIL_CLOSED=false` to allow creation when the 
lookup is unavailable. URLs positively identified as unsafe are rejected in either mode.



## API

| Method | Endpoint | Description |
| --- | --- | --- |
| `POST` | `/api/shorten` | Create a short URL |
| `GET` | `/{shortCode}` | Redirect to the original URL |

Example:

```bash
curl -X POST http://localhost:8080/api/shorten \
  -H "Content-Type: application/json" \
  -d '{"longUrl":"https://example.com","customCode":"mylink"}'
```

## Project Structure

```text
src/main/java/com/vipul/urlshortener/
├── config/       # Application configuration
├── controller/   # REST endpoints
├── dto/          # Request and response objects
├── entity/       # JPA entities
├── exception/    # Custom exceptions
├── rateLimit/    # Rate-limiting components
├── repository/   # Database access
├── service/      # Business logic and Safe Browsing integration
└── util/         # URL validation, Base62 and expiry utilities

frontend/         
```

## Notes

Google Safe Browsing screening helps identify known threats but does 
not guarantee that every destination is safe. 
Screening runs when a URL is created.
