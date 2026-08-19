# AutoClipper AI v6

Standalone Android app for Gemini-powered video moment detection and local MP4 clipping.

## Important
- Gemini API keys are stored locally without encryption, as requested.
- Gemini is called directly from the APK.
- The app can upload a local video to Gemini Files API and ask for timestamped highlights.
- The app cuts MP4 clips locally with Android MediaExtractor/MediaMuxer; no FFmpeg backend is required for basic MP4 clipping.
- A YouTube URL field is included for reference, but the standalone APK does not bypass YouTube restrictions or download arbitrary YouTube media. To create clips from a YouTube video, first obtain the video file through a lawful/authorized method, then choose it with PILIH VIDEO MP4.
- For very long videos, upload time and Gemini limits depend on the account/API limits.

## GitHub Actions
Open Actions -> Build AutoClipper AI v6 -> Run workflow.
