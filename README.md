# URL Shortener

A full-stack web application to generate short URLs, generate QR codes, and configure URL expiration. Built with Spring Boot backend and React frontend.

## Features

* Generate short URLs with auto-generated or custom codes
* Base62-based short code generation
* Redirect to original URLs with expiration checks
* QR code generation and download
* Configurable URL expiration
* Automatic cleanup of expired URLs
* Endpoint-level rate limiting with Bucket4j
* PostgreSQL database for persistence

## Tech Stack

**Backend:** Java 17 | Spring Boot 4.0.5 | Spring Data JPA | PostgreSQL | Bucket4j
**Frontend:** React 18 | Vite | qrcode.react

## Project Structure

```text
urlshortener/
├── src/main/java/com/vipul/urlshortener/
│   ├── controller/     → HTTP request handling
│   ├── service/        → Business logic
│   ├── repository/     → Database operations (JPA)
│   ├── entity/         → UrlMapping
│   ├── dto/            → Request/Response objects
│   ├── filter/         → Rate limiting filter
│   └── util/           → Base62 and expiry utilities
├── frontend/
│   └── src/
│       ├── App.jsx           → Main React component
│       ├── components/       → UI components
│       └── App.css           → Styling
├── application.properties    → Application configuration
└── pom.xml                   → Maven dependencies
```

## How to Run

### Backend

```bash
# Configure the database and URL safety variables shown below.
./mvnw spring-boot:run
```

Backend runs on `http://localhost:8080`

### URL safety screening

URL creation first validates syntax and structure locally, then checks the
destination with Google's Safe Browsing v4 Lookup API. The request checks
malware, social engineering, unwanted software, and potentially harmful
applications. A listed destination is rejected; inconclusive provider errors
return HTTP 503 by default and do not create a mapping. Setting
`BITSHORT_URL_SAFETY_FAIL_CLOSED=false` explicitly enables fail-open behavior.

Safe Browsing API v4 is restricted by Google to non-commercial use. If Bitshort
is used commercially or for revenue generation, Google's documented product
for this use is Web Risk instead. The Lookup API sends the submitted URL to
Google over HTTPS, and threat detection is not guaranteed to be comprehensive
or error-free.

Enable the Safe Browsing API for a Google Cloud project and create an API key
that permits this backend API. For quick local testing, paste the key into the
`bitshort.url-safety.safe-browsing.api-key` setting in
`src/main/resources/application.properties`. That file is ignored by Git; do
not commit the real key or put it in React. For deployment, use the backend
environment variable instead:

```bash
export SAFE_BROWSING_API_KEY='<your-google-cloud-api-key>'
export BITSHORT_SHORT_LINK_HOSTNAME='<your-public-short-link-hostname>'
./mvnw spring-boot:run
```

When safety screening is enabled, the application fails at startup if
the API key is missing or blank. `src/main/resources/application.yml` binds
these environment variables:

| Environment variable | Default | Purpose |
| --- | --- | --- |
| `SAFE_BROWSING_API_KEY` | empty | Backend Safe Browsing credential; required when screening is enabled |
| `BITSHORT_URL_SAFETY_ENABLED` | `true` | Enable/disable URL safety screening |
| `BITSHORT_URL_SAFETY_FAIL_CLOSED` | `true` | Reject creation on inconclusive lookup when true |
| `SAFE_BROWSING_CONNECT_TIMEOUT` | `2s` | Provider connection timeout |
| `SAFE_BROWSING_RESPONSE_TIMEOUT` | `4s` | Per-request timeout |
| `BITSHORT_MAX_URL_LENGTH` | `8192` | Maximum destination length in characters |
| `BITSHORT_SHORT_LINK_HOSTNAME` | empty | Canonical public short-link hostname |

Set the public hostname per deployed environment and use the appropriate host
for development/tests too. Only URLs on that exact host whose single path
segment is a redirect code are rejected as recursive short links; related
subdomains and unrelated hostnames are not blocked. Destinations may contain
valid Unicode/IDN hostnames, IPv4/IPv6 literals, and `@` in paths/queries, but
authority user information is rejected. IP addresses are not rejected merely
for being IPs. Checks run only during URL creation, not on redirects, so a
destination that becomes unsafe later is not rechecked by this application.

For local tests, no external key or API call is needed:

```bash
./mvnw test
```

Manual live API verification (do not use this command as an automated test):

```bash
curl -X POST \
  "https://safebrowsing.googleapis.com/v4/threatMatches:find?key=${SAFE_BROWSING_API_KEY}" \
  -H "Content-Type: application/json" \
  -d '{
    "client": {
      "clientId": "bitshort",
      "clientVersion": "1.0"
    },
    "threatInfo": {
      "threatTypes": ["MALWARE"],
      "platformTypes": ["ANY_PLATFORM"],
      "threatEntryTypes": ["URL"],
      "threatEntries": [
        {
          "url": "http://testsafebrowsing.appspot.com/apiv4/MALWARE/URL/"
        }
      ]
    }
  }'
```

An empty `{}` response means the request returned no match; a recognized test
URL should normally produce a match. An empty response by itself does not prove
that the key has the expected permissions for every requested threat category.

### Frontend

```bash
cd frontend

npm install
npm run dev
```

Frontend runs on `http://localhost:3000`

## API Endpoints

| Method | Endpoint       | Purpose                  |
| ------ | -------------- | ------------------------ |
| POST   | `/api/shorten` | Create short URL         |
| GET    | `/{shortCode}` | Redirect to original URL |

## Example Request

**Create Short URL:**

```bash
curl -X POST http://localhost:8080/api/shorten \
  -H "Content-Type: application/json" \
  -d '{"longUrl":"https://example.com","customCode":"mylink"}'
```

**Response:**

```json
{
  "shortUrl": "http://localhost:8080/mylink",
  "shortCode": "mylink"
}
```

## Live Demo

Frontend: https://bitshortner.vercel.app/

Backend: Deployed on Render
