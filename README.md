# CLIPPER AI

Android client and server-side automatic video clipping prototype.

## Current MVP
- Upload a video to a configured HTTPS server (maximum 10 minutes by default).
- Server transcribes speech with faster-whisper.
- A lightweight transcript scoring heuristic selects up to five speech sections.
- FFmpeg exports vertical 9:16 MP4 clips and matching SRT subtitle files.
- Android app can download clips to Movies/Clipper and subtitles to Download/Clipper.

**Important:** this is an early MVP. Highlight selection is transcript-based, not a full multimodal editor. It does not yet track faces, automatically reframe speakers, or burn subtitles into the video. The SRT subtitle is a separate download.

## Run the server
Requires a Linux host with Docker and enough memory/storage for FFmpeg and the Whisper model. CPU processing can be slow; the first job downloads the speech model.

```bash
cd server
docker build -t clipper-ai-server .
docker run --rm -p 8000:8000 -e MODEL_SIZE=tiny -e MAX_VIDEO_MINUTES=10 clipper-ai-server
```

For a public deployment, put the service behind HTTPS and add authentication/rate limits before allowing uploads. Do not expose an unauthenticated instance to the public internet. The app currently expects the server URL in its home screen.

Check `https://YOUR-SERVER/health` to verify the API is online.

## Android
Build with the included GitHub Actions workflow. In the app, enter the server's HTTPS base URL, choose a video, and tap **BUAT KLIP OTOMATIS DENGAN AI**.

## Limits
The default duration limit is 10 minutes and the upload size limit is 800 MB. The server stores temporary job state in memory and temporary files under `/tmp/clipper-data`; restarting the server clears job state. This prototype is not yet suitable for production or sensitive videos.
