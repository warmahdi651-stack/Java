# GameTranslate

An Android prototype that captures on-screen game text, recognizes it with ML Kit OCR, identifies its language and displays Persian translations in a floating overlay.

## Build
The GitHub Actions workflow builds a debug APK on every push to `main` or manually from the Actions tab. Download the `GameTranslate-debug-apk` artifact from the completed run.

## Notes
- Requires Android 8.0 (API 26) or newer.
- The user must explicitly grant screen-capture and display-over-other-apps permissions.
- ML Kit downloads language models on demand; first use needs internet.
- The app samples the screen periodically. It is a prototype, not guaranteed to translate every game element or provide instant subtitles.
- Screen content is processed on-device by ML Kit; no account or translation API key is needed.
