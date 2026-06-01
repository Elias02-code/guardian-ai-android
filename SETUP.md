# Guardian AI - Local Setup Guide

## Android App Setup

### 1. Create `local.properties` file
In the project root directory, create a file named `local.properties`:

```properties
API_KEY=your_render_api_key_here
```

**Important:** This file is git-ignored and should never be committed.

### 2. Build and Run
```bash
./gradlew build
./gradlew assembleDebug
```

The `API_KEY` from `local.properties` will be automatically injected into `BuildConfig.API_KEY`.

## Web App Setup

### 1. Create `config.js` file
Copy the example file and add your API key:

```bash
cp config.js.example config.js
```

Then edit `config.js` and add your Render API key:

```javascript
const CONFIG = {
    API_URL: 'https://phishing-api-j7fs.onrender.com/predict',
    API_KEY: 'your_render_api_key_here',
    REPORT_EMAIL: 'your_email@example.com'
};
```

**Important:** `config.js` is git-ignored and should never be committed.

### 2. Run locally
Open `Guardian-web-app.html` in your browser or serve with a local HTTP server:

```bash
python -m http.server 8000
# Then visit http://localhost:8000/Guardian-web-app.html
```

## Environment Variables (Alternative)

Instead of local files, you can use environment variables:

### Android
```bash
export GUARDIAN_API_KEY="your_render_api_key_here"
./gradlew build
```

### Web
Store the API key in your CI/CD pipeline (GitHub Secrets, etc.) and inject it during the build process.

## Security Notes

- **Never commit API keys** to the repository
- `local.properties` and `config.js` are git-ignored for a reason
- If you accidentally commit sensitive data, use `git-filter-repo` to remove it from history
- Always use environment variables or local files for secrets in production
