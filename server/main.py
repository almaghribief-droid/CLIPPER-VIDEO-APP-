import os, uuid, shutil, subprocess, threading
from pathlib import Path
from typing import Dict, Any
from fastapi import FastAPI, UploadFile, File, HTTPException, BackgroundTasks
from fastapi.middleware.cors import CORSMiddleware
from fastapi.staticfiles import StaticFiles
from faster_whisper import WhisperModel

ROOT = Path(os.getenv("CLIPPER_DATA", "/tmp/clipper-data"))
UPLOADS, OUTPUTS = ROOT / "uploads", ROOT / "outputs"
UPLOADS.mkdir(parents=True, exist_ok=True)
OUTPUTS.mkdir(parents=True, exist_ok=True)
MAX_SECONDS = int(os.getenv("MAX_VIDEO_MINUTES", "10")) * 60
MODEL_SIZE = os.getenv("MODEL_SIZE", "small")
JOBS: Dict[str, Dict[str, Any]] = {}
MODEL = None
LOCK = threading.Lock()

app = FastAPI(title="Clipper AI API", version="0.1.0")
app.add_middleware(CORSMiddleware, allow_origins=["*"], allow_methods=["*"], allow_headers=["*"])
app.mount("/files", StaticFiles(directory=str(OUTPUTS)), name="files")

def get_model():
    global MODEL
    with LOCK:
        if MODEL is None:
            MODEL = WhisperModel(MODEL_SIZE, device="cpu", compute_type="int8")
    return MODEL

def run(cmd):
    p = subprocess.run(cmd, capture_output=True, text=True)
    if p.returncode:
        raise RuntimeError((p.stderr or p.stdout or "FFmpeg gagal")[-1200:])
    return p.stdout

def duration_of(path: Path) -> float:
    out = run(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "default=noprint_wrappers=1:nokey=1", str(path)])
    return float(out.strip())

def rank_text(text: str, avg_logprob: float) -> float:
    t = text.lower()
    hooks = ["ternyata", "akhirnya", "rahasia", "penting", "wow", "luar biasa", "mengejutkan", "jangan", "kenapa", "bagaimana", "tips", "trik", "masalah", "solusi", "berhasil", "gagal", "terbaik", "paling"]
    score = 1.0 + min(len(text) / 180.0, 1.5) + sum(0.7 for w in hooks if w in t)
    score += max(0.0, min(1.0, 1.0 + avg_logprob))
    if len(text.split()) < 8: score -= 1.2
    return score

def process_job(job_id: str, source: Path, base_url: str):
    JOBS[job_id]["status"] = "processing"
    try:
        duration = duration_of(source)
        if duration > MAX_SECONDS:
            raise ValueError(f"Durasi video maksimal {MAX_SECONDS // 60} menit.")
        model = get_model()
        segments, info = model.transcribe(str(source), vad_filter=True, word_timestamps=False)
        transcript = []
        for seg in segments:
            txt = (seg.text or "").strip()
            if txt:
                transcript.append({"start": float(seg.start), "end": float(seg.end), "text": txt, "score": rank_text(txt, float(getattr(seg, "avg_logprob", -1.0)))})
        if not transcript:
            raise ValueError("Tidak ditemukan ucapan yang bisa dianalisis. Coba video dengan suara yang jelas.")
        # Build candidate windows from neighboring speech, roughly 25–55 seconds.
        candidates, current = [], []
        for seg in transcript:
            if not current or seg["end"] - current[0]["start"] <= 55:
                current.append(seg)
            else:
                if current[-1]["end"] - current[0]["start"] >= 12: candidates.append(current)
                current = [seg]
        if current and current[-1]["end"] - current[0]["start"] >= 12: candidates.append(current)
        if not candidates:
            candidates = [transcript]
        candidates.sort(key=lambda group: sum(x["score"] for x in group) / len(group), reverse=True)
        chosen = candidates[:5]
        results = []
        for idx, group in enumerate(chosen, 1):
            start = max(0.0, group[0]["start"] - 1.2)
            end = min(duration, group[-1]["end"] + 1.5)
            if end - start < 5: continue
            name = f"{job_id}_clip_{idx}.mp4"
            target = OUTPUTS / name
            # Center crop to 9:16; captions are also returned as an SRT file.
            run(["ffmpeg", "-y", "-ss", str(start), "-i", str(source), "-t", str(end-start),
                 "-vf", "crop='min(iw,ih*9/16)':'min(ih,iw*16/9)',scale=720:1280:force_original_aspect_ratio=decrease,pad=720:1280:(ow-iw)/2:(oh-ih)/2",
                 "-c:v", "libx264", "-preset", "veryfast", "-crf", "24", "-c:a", "aac", "-b:a", "128k", "-movflags", "+faststart", str(target)])
            srt_name = f"{job_id}_clip_{idx}.srt"
            with open(OUTPUTS / srt_name, "w", encoding="utf-8") as f:
                for n, seg in enumerate(group, 1):
                    a, b = max(0, seg["start"]-start), max(0, seg["end"]-start)
                    def stamp(v):
                        ms = int(v * 1000); h, ms = divmod(ms, 3600000); m, ms = divmod(ms, 60000); s, ms = divmod(ms, 1000)
                        return f"{h:02}:{m:02}:{s:02},{ms:03}"
                    f.write(f"{n}\n{stamp(a)} --> {stamp(b)}\n{seg['text']}\n\n")
            results.append({"title": f"Klip {idx}", "start_seconds": round(start,1), "end_seconds": round(end,1),
                            "video_url": f"{base_url}/files/{name}", "subtitle_url": f"{base_url}/files/{srt_name}",
                            "transcript": " ".join(x["text"] for x in group)})
        JOBS[job_id].update({"status": "completed", "clips": results, "message": f"{len(results)} klip berhasil dibuat."})
    except Exception as e:
        JOBS[job_id].update({"status": "failed", "message": str(e)})
    finally:
        try: source.unlink(missing_ok=True)
        except Exception: pass

@app.get("/health")
def health():
    return {"ok": True, "max_video_minutes": MAX_SECONDS // 60, "model": MODEL_SIZE}

@app.post("/api/clip")
async def create_clip(background_tasks: BackgroundTasks, video: UploadFile = File(...), base_url: str = ""):
    suffix = Path(video.filename or "video.mp4").suffix.lower() or ".mp4"
    if suffix not in {".mp4", ".mov", ".mkv", ".webm", ".m4v"}:
        raise HTTPException(400, "Format video tidak didukung.")
    job_id = uuid.uuid4().hex
    source = UPLOADS / f"{job_id}{suffix}"
    size = 0
    try:
        with source.open("wb") as out:
            while chunk := await video.read(1024 * 1024):
                size += len(chunk)
                if size > 1024 * 1024 * 800:
                    raise HTTPException(413, "Ukuran file maksimal 800 MB.")
                out.write(chunk)
    except Exception:
        source.unlink(missing_ok=True)
        raise
    JOBS[job_id] = {"status": "queued", "message": "Video diterima. Analisis AI akan dimulai."}
    background_tasks.add_task(process_job, job_id, source, base_url.rstrip("/"))
    return {"job_id": job_id, "status": "queued"}

@app.get("/api/jobs/{job_id}")
def get_job(job_id: str):
    job = JOBS.get(job_id)
    if not job: raise HTTPException(404, "Pekerjaan tidak ditemukan atau server baru saja dimulai ulang.")
    return job
