"""Insta-recording: record a dragged screen area and export it as a 9:16 Instagram Reel.

Pipeline:
  1. Drag a rectangle over the screen, or pick the whole screen.
  2. After a 3-2-1-0 countdown, ffmpeg records that region losslessly (GPU Desktop
     Duplication + NVENC when available, falling back to CPU encoders / gdigrab) while
     system audio is captured via WASAPI loopback.
  3. On stop, the recording is composited onto a 1080x1920 canvas: a scaled-to-fill,
     heavily blurred copy in the background and the full recording centred on top.

New releases are published on GitHub; the app checks for one at start-up and can replace
its own exe with it.
"""
import ctypes
import datetime
import hashlib
import json
import os
import re
import subprocess
import sys
import tempfile
import threading
import time
import tkinter as tk
import urllib.request
from tkinter import filedialog, messagebox, ttk

APP_NAME = "Insta-recording"
APP_VERSION = "1.2.0"
GITHUB_REPO = "Futurehouser/Insta-recording"
RELEASES_API = f"https://api.github.com/repos/{GITHUB_REPO}/releases/latest"
UPDATE_ASSET = f"{APP_NAME}.exe"
UPDATED_FLAG = "--updated"          # passed to the new exe right after it replaces the old one
OUT_W, OUT_H = 1080, 1920          # Instagram Reels native resolution (9:16)
BLUR_PERCENT = 80
BLUR_SIGMA = BLUR_PERCENT / 100 * 50  # 100% == sigma 50 (fully abstract), 80% -> 40
LIMIT_SECONDS = 59
LIMIT_GRACE_SECONDS = 5             # time ffmpeg gets to reach the limit by itself
SETTINGS_PATH = os.path.join(os.environ.get("APPDATA", os.path.expanduser("~")), APP_NAME,
                             "settings.json")
STOP_KEY_VK = 0x78                  # F9
STOP_KEY_NAME = "F9"
CREATE_NO_WINDOW = 0x08000000
ABOVE_NORMAL_PRIORITY_CLASS = 0x00008000
WDA_EXCLUDEFROMCAPTURE = 0x11

# Work in physical pixels so Tk coordinates match what gdigrab captures.
try:
    ctypes.windll.shcore.SetProcessDpiAwareness(2)
except Exception:
    try:
        ctypes.windll.user32.SetProcessDPIAware()
    except Exception:
        pass

user32 = ctypes.windll.user32
user32.MonitorFromWindow.restype = ctypes.c_void_p


def resource_path(name):
    base = getattr(sys, "_MEIPASS", os.path.dirname(os.path.abspath(__file__)))
    return os.path.join(base, name)


def ffmpeg_path():
    bundled = resource_path("ffmpeg.exe")
    if os.path.exists(bundled):
        return bundled
    try:
        import imageio_ffmpeg
        return imageio_ffmpeg.get_ffmpeg_exe()
    except ImportError:
        return "ffmpeg"


def virtual_screen():
    """Bounding box of all monitors: (x, y, w, h)."""
    return (user32.GetSystemMetrics(76), user32.GetSystemMetrics(77),
            user32.GetSystemMetrics(78), user32.GetSystemMetrics(79))


class _MONITORINFO(ctypes.Structure):
    _fields_ = [("cbSize", ctypes.c_uint32), ("rcMonitor", ctypes.c_long * 4),
                ("rcWork", ctypes.c_long * 4), ("dwFlags", ctypes.c_uint32)]


def monitor_region(win):
    """The (x, y, w, h) of the monitor a Tk window is on, with even dimensions."""
    hwnd = user32.GetAncestor(win.winfo_id(), 2)  # GA_ROOT
    monitor = ctypes.c_void_p(user32.MonitorFromWindow(hwnd, 2))  # MONITOR_DEFAULTTONEAREST
    info = _MONITORINFO(cbSize=ctypes.sizeof(_MONITORINFO))
    user32.GetMonitorInfoW(monitor, ctypes.byref(info))
    left, top, right, bottom = info.rcMonitor
    return left, top, (right - left) & ~1, (bottom - top) & ~1


def exclude_from_capture(win):
    """Hide a Tk window from screen capture (Windows 10 2004+); harmless elsewhere."""
    try:
        win.update_idletasks()
        hwnd = user32.GetAncestor(win.winfo_id(), 2)  # GA_ROOT
        user32.SetWindowDisplayAffinity(hwnd, WDA_EXCLUDEFROMCAPTURE)
    except Exception:
        pass


class SystemAudioRecorder:
    """Records what the default speakers play (WASAPI loopback) to raw float32 PCM.

    WASAPI delivers nothing while the system is silent, so each chunk is placed on a
    wall-clock timeline and gaps are filled with silence to stay in sync with the video.
    """

    def __init__(self, path):
        self.path = path
        self.t0 = None          # time.perf_counter() of the first sample
        self.rate = 48000
        self.channels = 2
        self.written = 0
        self._pa = self._stream = self._file = None

    def start(self):
        import pyaudiowpatch as pyaudio
        self._pa = pyaudio.PyAudio()
        wasapi = self._pa.get_host_api_info_by_type(pyaudio.paWASAPI)
        dev = self._pa.get_device_info_by_index(wasapi["defaultOutputDevice"])
        if not dev.get("isLoopbackDevice"):
            for lb in self._pa.get_loopback_device_info_generator():
                if dev["name"] in lb["name"]:
                    dev = lb
                    break
            else:
                raise RuntimeError(f"No loopback device for '{dev['name']}'")
        self.device_name = dev["name"].replace(" [Loopback]", "")
        self.rate = int(dev["defaultSampleRate"])
        self.channels = max(1, int(dev["maxInputChannels"]))
        self._frame_bytes = 4 * self.channels
        self._file = open(self.path, "wb")
        self._stream = self._pa.open(format=pyaudio.paFloat32, channels=self.channels,
                                     rate=self.rate, input=True, frames_per_buffer=1024,
                                     input_device_index=dev["index"],
                                     stream_callback=self._callback)
        self.t0 = time.perf_counter()
        self._stream.start_stream()

    def _callback(self, data, frames, time_info, status):
        import pyaudiowpatch as pyaudio
        chunk_start = time.perf_counter() - frames / self.rate
        expected = int((chunk_start - self.t0) * self.rate)
        gap = expected - self.written
        if gap > self.rate // 20:  # >50 ms behind the clock: nothing was playing
            self._file.write(b"\0" * gap * self._frame_bytes)
            self.written += gap
        self._file.write(data)
        self.written += frames
        return (None, pyaudio.paContinue)

    def stop(self):
        for close in (lambda: self._stream.stop_stream(), lambda: self._stream.close(),
                      lambda: self._pa.terminate(), lambda: self._file.close()):
            try:
                close()
            except Exception:
                pass


class _GUID(ctypes.Structure):
    _fields_ = [("d1", ctypes.c_ulong), ("d2", ctypes.c_ushort), ("d3", ctypes.c_ushort),
                ("d4", ctypes.c_ubyte * 8)]


class _DXGI_OUTPUT_DESC(ctypes.Structure):
    _fields_ = [("name", ctypes.c_wchar * 32), ("left", ctypes.c_long), ("top", ctypes.c_long),
                ("right", ctypes.c_long), ("bottom", ctypes.c_long), ("attached", ctypes.c_int),
                ("rotation", ctypes.c_uint), ("monitor", ctypes.c_void_p)]


def dxgi_outputs():
    """Desktop rectangles (l, t, r, b) of the default GPU's outputs, in ddagrab output_idx order."""
    def method(obj, index, *argtypes):
        vtbl = ctypes.cast(obj, ctypes.POINTER(ctypes.POINTER(ctypes.c_void_p))).contents
        return ctypes.WINFUNCTYPE(ctypes.c_long, ctypes.c_void_p, *argtypes)(vtbl[index])

    rects = []
    try:
        iid = _GUID(0x770AAE78, 0xF26F, 0x4DBA,  # IID_IDXGIFactory1
                    (ctypes.c_ubyte * 8)(0xA8, 0x29, 0x25, 0x3C, 0x83, 0xD1, 0xB3, 0x87))
        factory = ctypes.c_void_p()
        if ctypes.windll.dxgi.CreateDXGIFactory1(ctypes.byref(iid), ctypes.byref(factory)) != 0:
            return rects
        pp = ctypes.POINTER(ctypes.c_void_p)
        adapter = ctypes.c_void_p()
        if method(factory, 7, ctypes.c_uint, pp)(factory, 0, ctypes.byref(adapter)) == 0:  # EnumAdapters
            i = 0
            while True:
                out = ctypes.c_void_p()
                if method(adapter, 7, ctypes.c_uint, pp)(adapter, i, ctypes.byref(out)) != 0:  # EnumOutputs
                    break
                desc = _DXGI_OUTPUT_DESC()
                method(out, 7, ctypes.POINTER(_DXGI_OUTPUT_DESC))(out, ctypes.byref(desc))  # GetDesc
                rects.append((desc.left, desc.top, desc.right, desc.bottom))
                method(out, 2)(out)  # Release
                i += 1
            method(adapter, 2)(adapter)
        method(factory, 2)(factory)
    except Exception:
        pass
    return rects


def capture_methods(region):
    """Capture back-ends to try, best first, with the ddagrab monitor/offset if usable.

    ddagrab (Desktop Duplication) grabs frames on the GPU and keeps a steady frame rate even
    on big areas; gdigrab copies through the CPU and drops frames, causing stutter/freezes.
    """
    x, y, w, h = region
    for idx, (l, t, r, b) in enumerate(dxgi_outputs()):
        if l <= x and t <= y and x + w <= r and y + h <= b:
            dda = (idx, x - l, y - t)
            return [("dda_nvenc", dda), ("dda_x264", dda), ("gdigrab", None)]
    return [("gdigrab", None)]  # region spans monitors, or no DXGI output found


def capture_cmd(ffmpeg, method, dda, region, fps, cursor, limit, out_path):
    x, y, w, h = region
    limit_args = ["-t", str(limit)] if limit else []
    if method == "gdigrab":
        src = ["-f", "gdigrab", "-framerate", fps, "-draw_mouse", "1" if cursor else "0",
               "-offset_x", str(x), "-offset_y", str(y), "-video_size", f"{w}x{h}",
               *limit_args, "-i", "desktop"]
    else:
        idx, ox, oy = dda
        src = ["-f", "lavfi", *limit_args, "-i",
               f"ddagrab=output_idx={idx}:framerate={fps}:draw_mouse={int(cursor)}:"
               f"offset_x={ox}:offset_y={oy}:video_size={w}x{h}"]
    if method == "dda_nvenc":
        # Lossless 4:4:4 encoded on the GPU: frames never leave video memory.
        enc = ["-c:v", "h264_nvenc", "-preset", "p1", "-tune", "lossless", "-rgb_mode", "yuv444"]
    elif method == "dda_x264":
        enc = ["-vf", "hwdownload,format=bgra", "-c:v", "libx264rgb", "-preset", "ultrafast",
               "-crf", "0"]
    else:
        enc = ["-c:v", "libx264rgb", "-preset", "ultrafast", "-crf", "0"]
    return [ffmpeg, "-hide_banner", "-y", *src, *enc,
            "-progress", "pipe:1", "-stats_period", "0.1", out_path]


def _make_stop_sound():
    """Short two-note 'ding-dong' as an in-memory WAV."""
    import io, math, struct, wave
    rate = 44100
    pcm = bytearray()
    for freq, dur in ((1046.5, 0.13), (784.0, 0.26)):
        n = int(rate * dur)
        for i in range(n):
            env = min(1.0, i / (rate * 0.004)) * (1 - i / n) ** 2
            pcm += struct.pack("<h", int(14000 * env * math.sin(2 * math.pi * freq * i / rate)))
    buf = io.BytesIO()
    with wave.open(buf, "wb") as wav:
        wav.setnchannels(1)
        wav.setsampwidth(2)
        wav.setframerate(rate)
        wav.writeframes(bytes(pcm))
    return buf.getvalue()


STOP_SOUND = _make_stop_sound()


def play_stop_sound():
    import winsound
    threading.Thread(target=winsound.PlaySound, args=(STOP_SOUND, winsound.SND_MEMORY),
                     daemon=True).start()


def media_duration(ffmpeg, path):
    """Container duration in seconds, or None if ffmpeg can't tell."""
    try:
        r = subprocess.run([ffmpeg, "-hide_banner", "-i", path], capture_output=True, text=True,
                           errors="replace", creationflags=CREATE_NO_WINDOW, timeout=20)
    except subprocess.TimeoutExpired:
        return None
    m = re.search(r"Duration: (\d+):(\d+):([\d.]+)", r.stderr)
    return int(m.group(1)) * 3600 + int(m.group(2)) * 60 + float(m.group(3)) if m else None


def load_settings():
    try:
        with open(SETTINGS_PATH, encoding="utf-8") as f:
            return json.load(f)
    except (OSError, ValueError):
        return {}


def save_settings(data):
    try:
        os.makedirs(os.path.dirname(SETTINGS_PATH), exist_ok=True)
        with open(SETTINGS_PATH, "w", encoding="utf-8") as f:
            json.dump(data, f, indent=2)
    except OSError:
        pass


def version_tuple(version):
    return tuple(int(n) for n in re.findall(r"\d+", version)[:3])


def fetch_latest_release():
    """Newest GitHub release as (version, exe asset); the asset is None if it has no exe."""
    req = urllib.request.Request(RELEASES_API, headers={"User-Agent": APP_NAME,
                                                        "Accept": "application/vnd.github+json"})
    with urllib.request.urlopen(req, timeout=15) as r:
        release = json.load(r)
    asset = next((a for a in release.get("assets", []) if a["name"] == UPDATE_ASSET), None)
    return release["tag_name"].lstrip("v"), asset


def download_update(asset, dest, on_progress):
    """Download a release asset to dest, checking its size and (if GitHub gives one) SHA-256."""
    req = urllib.request.Request(asset["browser_download_url"], headers={"User-Agent": APP_NAME})
    total = asset["size"]
    done = 0
    sha = hashlib.sha256()
    with urllib.request.urlopen(req, timeout=30) as r, open(dest, "wb") as f:
        while chunk := r.read(1024 * 256):
            f.write(chunk)
            sha.update(chunk)
            done += len(chunk)
            on_progress(done / max(total, 1) * 100)
    digest = asset.get("digest") or ""
    if done != total or (digest.startswith("sha256:") and digest[7:] != sha.hexdigest()):
        os.remove(dest)
        raise RuntimeError("The download was incomplete or corrupted. Please try again.")


def install_update(new_exe):
    """Swap new_exe in for the running exe and start it.

    Windows won't overwrite a running exe but does allow renaming it, so the old one moves
    aside and the new version removes it on start-up (see remove_old_exe).
    """
    exe = sys.executable
    old = exe + ".old"
    if os.path.exists(old):
        os.remove(old)
    os.replace(exe, old)
    try:
        os.replace(new_exe, exe)
        # Without the reset, the new exe would take this process's unpacked files as its own.
        env = {k: v for k, v in os.environ.items() if k not in ("TCL_LIBRARY", "TK_LIBRARY")}
        env["PYINSTALLER_RESET_ENVIRONMENT"] = "1"
        subprocess.Popen([exe, UPDATED_FLAG], env=env, cwd=os.path.dirname(exe), close_fds=True)
    except OSError:
        if os.path.exists(exe):
            os.replace(exe, new_exe)
        os.replace(old, exe)
        raise


def remove_old_exe():
    """Delete what an update left behind; the old exe stays locked until it has fully exited."""
    if not getattr(sys, "frozen", False):
        return
    for leftover in (sys.executable + ".old", sys.executable + ".new"):
        for _ in range(40):
            try:
                os.remove(leftover)
            except FileNotFoundError:
                break
            except OSError:
                time.sleep(0.5)


def fmt_time(seconds):
    seconds = int(seconds)
    return f"{seconds // 3600:02d}:{seconds % 3600 // 60:02d}:{seconds % 60:02d}"


class AreaSelector(tk.Toplevel):
    """Full-desktop dimmed overlay; drag to select a rectangle, Esc to cancel."""

    HOLE = "#ff00fe"  # made fully transparent so the selection shows the real screen

    def __init__(self, master, on_done):
        super().__init__(master)
        self.on_done = on_done
        self.vx, self.vy, vw, vh = virtual_screen()
        self.overrideredirect(True)
        self.attributes("-topmost", True)
        self.attributes("-alpha", 0.45)
        self.attributes("-transparentcolor", self.HOLE)
        self.geometry(f"{vw}x{vh}+{self.vx}+{self.vy}")
        self.canvas = tk.Canvas(self, bg="black", highlightthickness=0, cursor="crosshair")
        self.canvas.pack(fill="both", expand=True)
        # Hint centred on the primary monitor, whose top-left is screen (0, 0).
        self.canvas.create_text(user32.GetSystemMetrics(0) // 2 - self.vx, 60 - self.vy,
                                text="Drag to select the recording area  ·  Esc to cancel",
                                fill="white", font=("Segoe UI", 20, "bold"))
        self.start = None
        self.rect = self.label = None
        self.canvas.bind("<ButtonPress-1>", self._press)
        self.canvas.bind("<B1-Motion>", self._drag)
        self.canvas.bind("<ButtonRelease-1>", self._release)
        self.bind("<Escape>", lambda e: self._finish(None))
        self.after(50, self._grab)

    def _grab(self):
        self.focus_force()
        try:
            self.grab_set()
        except tk.TclError:
            pass

    def _press(self, e):
        self.start = (e.x_root, e.y_root)

    def _drag(self, e):
        if not self.start:
            return
        x0, y0 = self.start
        cx0, cy0 = x0 - self.vx, y0 - self.vy
        cx1, cy1 = e.x_root - self.vx, e.y_root - self.vy
        if self.rect is None:
            self.rect = self.canvas.create_rectangle(cx0, cy0, cx1, cy1, outline="#ff3b5c",
                                                     width=2, fill=self.HOLE)
            self.label = self.canvas.create_text(0, 0, fill="white", anchor="sw",
                                                 font=("Segoe UI", 12, "bold"))
        self.canvas.coords(self.rect, cx0, cy0, cx1, cy1)
        w, h = abs(e.x_root - x0), abs(e.y_root - y0)
        self.canvas.coords(self.label, min(cx0, cx1), min(cy0, cy1) - 4)
        self.canvas.itemconfigure(self.label, text=f"{w} × {h}")

    def _release(self, e):
        if not self.start:
            return
        x0, y0 = self.start
        x, y = min(x0, e.x_root), min(y0, e.y_root)
        w, h = abs(e.x_root - x0), abs(e.y_root - y0)
        # Even dimensions keep every encoder happy.
        w -= w % 2
        h -= h % 2
        if w < 16 or h < 16:
            self.start = None
            if self.rect:
                self.canvas.delete(self.rect, self.label)
                self.rect = self.label = None
            return
        self._finish((x, y, w, h))

    def _finish(self, region):
        try:
            self.grab_release()
        except tk.TclError:
            pass
        self.destroy()
        self.on_done(region)


class RecordingFrame:
    """Thin red border drawn just outside the captured region (never recorded)."""

    def __init__(self, master, region, thickness=3, color="#ff3b5c"):
        x, y, w, h = region
        t = thickness
        self.windows = []
        for gx, gy, gw, gh in ((x - t, y - t, w + 2 * t, t), (x - t, y + h, w + 2 * t, t),
                               (x - t, y, t, h), (x + w, y, t, h)):
            win = tk.Toplevel(master)
            win.overrideredirect(True)
            win.attributes("-topmost", True)
            win.configure(bg=color)
            win.geometry(f"{gw}x{gh}+{gx}+{gy}")
            exclude_from_capture(win)
            self.windows.append(win)

    def destroy(self):
        for w in self.windows:
            w.destroy()


class Countdown(tk.Toplevel):
    """Big 3-2-1-0 counter centred on the selected region; Esc cancels."""

    KEY = "#ff00fe"
    SIZE = 240

    def __init__(self, master, region, on_done, start=3):
        super().__init__(master)
        x, y, w, h = region
        s = self.SIZE
        self.on_done = on_done
        self.overrideredirect(True)
        self.attributes("-topmost", True)
        self.attributes("-transparentcolor", self.KEY)
        self.geometry(f"{s}x{s}+{x + (w - s) // 2}+{y + (h - s) // 2}")
        c = tk.Canvas(self, width=s, height=s, bg=self.KEY, highlightthickness=0)
        c.pack()
        c.create_oval(6, 6, s - 6, s - 6, fill="#1e1e1e", outline="#ff3b5c", width=6)
        self.num = c.create_text(s // 2, s // 2 - 10, fill="white", font=("Segoe UI", 96, "bold"))
        c.create_text(s // 2, s - 46, text="Esc to cancel", fill="#aaaaaa", font=("Segoe UI", 10))
        self.canvas = c
        self.bind("<Escape>", lambda e: self._finish(False))
        self.focus_force()
        self._job = None
        self._show(start)

    def _show(self, n):
        self.canvas.itemconfigure(self.num, text=str(n))
        if n == 0:
            # Leave "0" up for a moment, then remove the overlay before capture begins.
            self._job = self.after(250, lambda: self._finish(True))
        else:
            self._job = self.after(1000, lambda: self._show(n - 1))

    def _finish(self, go):
        if self._job:
            self.after_cancel(self._job)
        self.destroy()
        self.on_done(go)


class FinishingDialog(tk.Toplevel):
    """Shown from the moment recording ends until the video starts converting."""

    def __init__(self, master):
        super().__init__(master)
        self.overrideredirect(True)
        self.attributes("-topmost", True)
        self.configure(bg="#1e1e1e")
        body = tk.Frame(self, bg="#1e1e1e", padx=26, pady=18)
        body.pack()
        tk.Label(body, text="Recording finished", fg="white", bg="#1e1e1e",
                 font=("Segoe UI", 13, "bold")).pack(anchor="w")
        tk.Label(body, text="Getting it ready, one moment…", fg="#bbbbbb", bg="#1e1e1e",
                 font=("Segoe UI", 10)).pack(anchor="w", pady=(2, 10))
        self.bar = ttk.Progressbar(body, mode="indeterminate", length=300)
        self.bar.pack(fill="x")
        self.bar.start(12)
        self.update_idletasks()
        sw, sh = self.winfo_screenwidth(), self.winfo_screenheight()
        self.geometry(f"+{(sw - self.winfo_reqwidth()) // 2}+{(sh - self.winfo_reqheight()) // 3}")
        # ffmpeg may still be capturing its last frames, so keep this out of the video.
        exclude_from_capture(self)
        self.update()


class App(tk.Tk):
    def __init__(self):
        super().__init__()
        self.title(APP_NAME)
        self.resizable(False, False)
        try:
            self.iconbitmap(default=resource_path("icon.ico"))  # also used by dialogs
        except tk.TclError:
            pass

        self.ffmpeg = ffmpeg_path()
        s = load_settings()
        self.fps = tk.StringVar(value=s.get("fps", "60"))
        self.cursor = tk.BooleanVar(value=s.get("cursor", True))
        self.record_audio = tk.BooleanVar(value=s.get("audio", True))
        self.limit_59 = tk.BooleanVar(value=s.get("limit_59", False))
        self.audio = None
        self.audio_warning = None
        self.rec_limited = False
        default_out = os.path.join(os.path.expanduser("~"), "Videos", APP_NAME)
        self.out_dir = tk.StringVar(value=s.get("out_dir", default_out))
        for var in (self.fps, self.cursor, self.record_audio, self.limit_59, self.out_dir):
            var.trace_add("write", lambda *a: self._save_settings())
        self.status = tk.StringVar(value="Ready. Click the button and drag over the area to record.")
        self.proc = None
        self.raw_path = None
        self.region = None
        self.rec_start = 0.0
        self.rec_duration = 0.0
        self.progress_value = 0.0
        self.export_result = None
        self.last_output = None
        self.key_was_down = False
        self.finishing = None
        self.finish_result = None
        self.update_check = None
        self.update_asset = None
        self.update_version = None
        self.update_result = None
        self.updating = False

        self._build_ui()
        threading.Thread(target=remove_old_exe, daemon=True).start()
        if UPDATED_FLAG in sys.argv:
            self.status.set(f"Updated to version {APP_VERSION}.")
        self.check_for_updates()

    # ---------- UI ----------
    def _build_ui(self):
        style = ttk.Style(self)
        try:
            style.theme_use("vista")
        except tk.TclError:
            pass
        style.configure("Big.TButton", font=("Segoe UI", 12, "bold"), padding=10)
        style.configure("Title.TLabel", font=("Segoe UI", 16, "bold"))

        root = ttk.Frame(self, padding=16)
        root.pack(fill="both", expand=True)

        ttk.Label(root, text=APP_NAME, style="Title.TLabel").grid(row=0, column=0, columnspan=3, sticky="w")
        ttk.Label(root, text="Record any area of your screen → 1080×1920 MP4 for Instagram Reels",
                  foreground="#666").grid(row=1, column=0, columnspan=3, sticky="w", pady=(0, 12))

        ttk.Label(root, text="Frame rate:").grid(row=2, column=0, sticky="w")
        ttk.Combobox(root, textvariable=self.fps, values=["30", "60"], width=6,
                     state="readonly").grid(row=2, column=1, sticky="w", pady=3)
        ttk.Checkbutton(root, text="Record mouse cursor", variable=self.cursor).grid(
            row=3, column=0, columnspan=3, sticky="w", pady=3)
        ttk.Checkbutton(root, text="Record system audio (what your speakers play)",
                        variable=self.record_audio).grid(row=4, column=0, columnspan=3, sticky="w", pady=3)
        ttk.Checkbutton(root, text=f"Stop automatically at {LIMIT_SECONDS} seconds",
                        variable=self.limit_59).grid(row=5, column=0, columnspan=3, sticky="w", pady=3)

        ttk.Label(root, text="Save to:").grid(row=6, column=0, sticky="w")
        ttk.Entry(root, textvariable=self.out_dir, width=42).grid(row=6, column=1, sticky="we", pady=3)
        ttk.Button(root, text="Browse…", command=self._browse).grid(row=6, column=2, padx=(6, 0))

        buttons = ttk.Frame(root)
        buttons.grid(row=8, column=0, columnspan=3, sticky="we", pady=(14, 8))
        buttons.columnconfigure((0, 1), weight=1, uniform="record")
        self.rec_btn = ttk.Button(buttons, text="●  Select area", style="Big.TButton",
                                  command=self.select_area)
        self.rec_btn.grid(row=0, column=0, sticky="we", padx=(0, 4))
        self.full_btn = ttk.Button(buttons, text="▣  Full screen", style="Big.TButton",
                                   command=self.record_full_screen)
        self.full_btn.grid(row=0, column=1, sticky="we", padx=(4, 0))

        self.bar = ttk.Progressbar(root, mode="determinate", maximum=100)
        self.bar.grid(row=9, column=0, columnspan=3, sticky="we")
        ttk.Label(root, textvariable=self.status, wraplength=440).grid(
            row=10, column=0, columnspan=3, sticky="w", pady=(6, 0))
        self.open_btn = ttk.Button(root, text="Open output folder", command=self._open_folder)
        self.open_btn.grid(row=11, column=0, columnspan=3, sticky="e", pady=(8, 0))

        footer = ttk.Frame(root)
        footer.grid(row=11, column=0, columnspan=2, sticky="w", pady=(8, 0))
        ttk.Label(footer, text=f"v{APP_VERSION}", foreground="#888").pack(side="left", padx=(0, 8))
        self.check_btn = ttk.Button(footer, text="Check for updates",
                                    command=lambda: self.check_for_updates(manual=True))
        self.check_btn.pack(side="left")

        self.update_bar = ttk.LabelFrame(root, text="Update available", padding=(10, 6))
        self.update_bar.grid(row=12, column=0, columnspan=3, sticky="we", pady=(10, 0))
        self.update_lbl = ttk.Label(self.update_bar)
        self.update_lbl.pack(side="left")
        self.install_btn = ttk.Button(self.update_bar, text="Install update",
                                      command=self.start_update)
        self.install_btn.pack(side="right")
        self.update_bar.grid_remove()
        root.columnconfigure(1, weight=1)

    def _set_busy(self, busy):
        for btn in (self.rec_btn, self.full_btn):
            btn.state(["disabled" if busy else "!disabled"])

    def _save_settings(self):
        save_settings({"fps": self.fps.get(), "cursor": self.cursor.get(),
                       "audio": self.record_audio.get(), "limit_59": self.limit_59.get(),
                       "out_dir": self.out_dir.get()})

    def _browse(self):
        d = filedialog.askdirectory(initialdir=self.out_dir.get() or os.path.expanduser("~"))
        if d:
            self.out_dir.set(d)

    def _open_folder(self):
        if self.last_output and os.path.exists(self.last_output):
            subprocess.Popen(["explorer", "/select,", os.path.normpath(self.last_output)])
        else:
            os.makedirs(self.out_dir.get(), exist_ok=True)
            os.startfile(self.out_dir.get())

    # ---------- Selection ----------
    def select_area(self):
        self.withdraw()
        self.after(250, lambda: AreaSelector(self, self._area_selected))

    def record_full_screen(self):
        region = monitor_region(self)
        self.withdraw()
        self.after(250, lambda: self._area_selected(region))

    def _area_selected(self, region):
        if region is None:
            self.deiconify()
            return
        self.region = region
        self.frame = RecordingFrame(self, region)
        Countdown(self, region, self._countdown_done)

    def _countdown_done(self, go):
        if go:
            self.start_recording()
        else:
            self._teardown_recording_ui()
            self.deiconify()

    # ---------- Recording ----------
    def start_recording(self):
        x, y, w, h = self.region
        tmp_dir = os.path.join(tempfile.gettempdir(), APP_NAME)
        os.makedirs(tmp_dir, exist_ok=True)
        stamp = datetime.datetime.now().strftime("%Y%m%d_%H%M%S")
        self.raw_path = os.path.join(tmp_dir, f"raw_{stamp}.mkv")
        self.log_path = os.path.join(tmp_dir, f"record_{stamp}.log")
        self.audio = None
        self.audio_warning = None
        if self.record_audio.get():
            # Started before the video so the audio covers the very first frame.
            rec = SystemAudioRecorder(os.path.join(tmp_dir, f"audio_{stamp}.f32"))
            try:
                rec.start()
                self.audio = rec
            except Exception as exc:
                rec.stop()
                self.audio_warning = f"System audio unavailable ({exc}); recording video only."
        self.rec_fps = self.fps.get()
        self.rec_limited = self.limit_59.get()
        self.methods = capture_methods(self.region)
        self.log_file = open(self.log_path, "w", encoding="utf-8", errors="replace")
        self._launch_capture()
        self._show_controls()
        self._tick()

    def _launch_capture(self):
        method, dda = self.methods[0]
        # With the limit on, ffmpeg itself stops after exactly LIMIT_SECONDS of video.
        cmd = capture_cmd(self.ffmpeg, method, dda, self.region, self.rec_fps, self.cursor.get(),
                          LIMIT_SECONDS if self.rec_limited else None, self.raw_path)
        self.log_file.write(f"=== capture method: {method}\n{subprocess.list2cmdline(cmd)}\n")
        self.log_file.flush()
        self.capture_method = method
        self.video_start = None
        self.capture_frames = 0
        self.proc = subprocess.Popen(cmd, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                     stderr=self.log_file,
                                     creationflags=CREATE_NO_WINDOW | ABOVE_NORMAL_PRIORITY_CLASS)
        threading.Thread(target=self._read_progress, args=(self.proc,), daemon=True).start()
        self.rec_start = time.time()
        self.after(1500, self._check_started)
        if method.startswith("dda"):
            self.after(3000, lambda p=self.proc: self._check_stalled(p))

    def _check_stalled(self, proc):
        """Desktop Duplication delivers nothing while Windows isn't drawing (monitor off,
        some exclusive full-screen apps…). If no frame arrived, switch to gdigrab."""
        if proc is not self.proc or self.capture_frames > 0 or proc.poll() is not None:
            return
        proc.kill()
        proc.wait()
        self.log_file.write("\n=== no frames from Desktop Duplication; falling back\n")
        self.methods = [m for m in self.methods if not m[0].startswith("dda")]
        self._launch_capture()

    def _read_progress(self, proc):
        """Estimate when the first video frame was captured (perf_counter clock), for A/V sync.

        Each progress report says how much video has been written so far; a frame can only be
        written after it was captured, so min(now - written) converges on the start time.
        """
        frames = 0
        for line in proc.stdout:
            if line.startswith(b"frame="):
                frames = int(line[6:] or 0)
                if proc is self.proc:
                    self.capture_frames = frames
            elif line.startswith(b"out_time_us=") and frames > 0:
                try:
                    est = time.perf_counter() - int(line[12:]) / 1e6
                except ValueError:
                    continue
                if self.video_start is None or est < self.video_start:
                    self.video_start = est

    def _check_started(self):
        if self.proc and self.proc.poll() is not None:
            if len(self.methods) > 1:
                # This capture method isn't supported here (no NVIDIA GPU, etc.): try the next.
                self.methods.pop(0)
                self._launch_capture()
                return
            self._teardown_recording_ui()
            self.log_file.close()
            if self.audio:
                self.audio.stop()
                self.audio = None
            with open(self.log_path, encoding="utf-8", errors="replace") as f:
                tail = f.read()[-1500:]
            self.deiconify()
            self.status.set("Recording failed to start.")
            messagebox.showerror(APP_NAME, "ffmpeg could not start recording:\n\n" + tail)
            self.proc = None

    def _show_controls(self):
        x, y, w, h = self.region
        vx, vy, vw, vh = virtual_screen()
        c = tk.Toplevel(self)
        c.overrideredirect(True)
        c.attributes("-topmost", True)
        c.configure(bg="#1e1e1e")
        inner = tk.Frame(c, bg="#1e1e1e", padx=10, pady=6)
        inner.pack()
        self.timer_lbl = tk.Label(inner, text=self._timer_text(0), fg="#ff3b5c", bg="#1e1e1e",
                                  font=("Segoe UI", 11, "bold"))
        self.timer_lbl.pack(side="left", padx=(0, 10))
        tk.Button(inner, text=f"■ Stop  ({STOP_KEY_NAME})", command=self.stop_recording,
                  bg="#ff3b5c", fg="white", activebackground="#d92b49", relief="flat",
                  font=("Segoe UI", 10, "bold"), padx=10, cursor="hand2").pack(side="left")
        c.update_idletasks()
        cw, ch = c.winfo_reqwidth(), c.winfo_reqheight()
        # Prefer a spot outside the recorded area: below, above, then inside the top-right corner.
        if y + h + 8 + ch <= vy + vh:
            px, py = x, y + h + 8
        elif y - 8 - ch >= vy:
            px, py = x, y - 8 - ch
        else:
            px, py = x + w - cw - 8, y + 8
        px = max(vx, min(px, vx + vw - cw))
        c.geometry(f"+{px}+{py}")
        exclude_from_capture(c)
        self.controls = c

    def _timer_text(self, elapsed):
        text = f"● REC {fmt_time(elapsed)}"
        if self.rec_limited:
            text += f" / {fmt_time(LIMIT_SECONDS)}"
        return text

    def _tick(self):
        if not self.proc:
            return
        elapsed = time.time() - self.rec_start
        self.timer_lbl.configure(text=self._timer_text(min(elapsed, LIMIT_SECONDS)
                                                       if self.rec_limited else elapsed))
        # ffmpeg exits by itself at the 59 s limit (or if capture breaks); an exit in the
        # first moments is a start-up failure, which _check_started handles instead.
        # At the limit, stop now anyway: ffmpeg can take a moment longer to get there.
        if ((elapsed > 2 and self.proc.poll() is not None)
                or (self.rec_limited and elapsed >= LIMIT_SECONDS)):
            self.stop_recording()
            return
        down = bool(user32.GetAsyncKeyState(STOP_KEY_VK) & 0x8000)
        if down and not self.key_was_down:
            self.key_was_down = True
            self.stop_recording()
            return
        self.key_was_down = down
        self.after(100, self._tick)

    def _teardown_recording_ui(self):
        for attr in ("frame", "controls"):
            obj = getattr(self, attr, None)
            if obj is not None:
                obj.destroy()
                setattr(self, attr, None)

    def stop_recording(self):
        if not self.proc:
            return
        proc, self.proc = self.proc, None
        self.rec_duration = time.time() - self.rec_start
        at_limit = self.rec_limited and self.rec_duration >= LIMIT_SECONDS
        self._teardown_recording_ui()
        self._set_busy(True)
        self.finish_result = None
        self.finishing = FinishingDialog(self)
        # Waiting for ffmpeg and the audio stream can take seconds, so keep it off the UI thread.
        threading.Thread(target=self._finish_worker, args=(proc, at_limit), daemon=True).start()
        self.after(100, self._poll_finish)

    def _finish_worker(self, proc, at_limit):
        try:
            try:
                if at_limit:
                    try:
                        proc.wait(timeout=LIMIT_GRACE_SECONDS)
                    except subprocess.TimeoutExpired:
                        pass
                if proc.poll() is None:  # may already have stopped itself at the time limit
                    proc.stdin.write(b"q")
                    proc.stdin.flush()
                proc.wait(timeout=15)
            except Exception:
                proc.terminate()
                proc.wait()
            self.log_file.close()
            if self.audio:
                stopper = threading.Thread(target=self.audio.stop, daemon=True)
                stopper.start()
                stopper.join(5)
            play_stop_sound()  # after audio capture has ended, so the cue isn't recorded
            captured = os.path.exists(self.raw_path) and os.path.getsize(self.raw_path) > 0
            duration = media_duration(self.ffmpeg, self.raw_path) if captured else None
            self.finish_result = (captured, duration or self.rec_duration)
        except Exception:
            self.finish_result = (False, 0.0)

    def _poll_finish(self):
        if self.finish_result is None:
            self.after(100, self._poll_finish)
            return
        self.finishing.destroy()
        self.finishing = None
        self.deiconify()
        self.lift()
        captured, duration = self.finish_result
        if not captured:
            self._set_busy(False)
            self.status.set("Recording failed — no video was captured.")
            return
        self.start_export(duration)

    # ---------- Export ----------
    def build_export_cmd(self, src, dst, fps, audio=None, duration=None):
        """audio: (pcm_path, rate, channels, offset) where offset is how many seconds
        after the first video frame the first audio sample was captured.
        duration: video length; the audio is padded/trimmed to exactly match it."""
        inputs = ["-i", src]
        audio_args = []
        afc = ""
        if audio:
            pcm, rate, channels, offset = audio
            inputs += ["-f", "f32le", "-ar", str(rate), "-ac", str(channels), "-i", pcm]
            if offset >= 0:
                align = f"adelay={int(offset * 1000)}:all=1"
            else:
                align = f"atrim=start={-offset:.4f},asetpts=PTS-STARTPTS"
            # Silence fills any tail where nothing was playing; trim to the video length.
            afc = (f";[1:a]{align},aresample=48000,"
                   f"apad=whole_dur={duration:.4f},atrim=end={duration:.4f}[aout]")
            audio_args = ["-map", "[aout]", "-c:a", "aac", "-b:a", "320k", "-ar", "48000"]
        fc = (
            "[0:v]split=2[bg][fg];"
            # Background: duplicate scaled to completely cover 9:16, cropped, then blurred.
            f"[bg]scale={OUT_W}:{OUT_H}:force_original_aspect_ratio=increase:flags=bicubic,"
            f"crop={OUT_W}:{OUT_H},gblur=sigma={BLUR_SIGMA}:steps=3[bgb];"
            # Foreground: whole recording fitted inside the frame, sharp.
            f"[fg]scale={OUT_W}:{OUT_H}:force_original_aspect_ratio=decrease:"
            "force_divisible_by=2:flags=lanczos[fgs];"
            "[bgb][fgs]overlay=(W-w)/2:(H-h)/2:format=gbrp,"
            "scale=out_color_matrix=bt709:out_range=tv,format=yuv420p,setsar=1,"
            "setparams=range=tv:color_primaries=bt709:color_trc=bt709:colorspace=bt709[out]"
            + afc
        )
        return [self.ffmpeg, "-hide_banner", "-y", *inputs,
                "-filter_complex", fc, "-map", "[out]", *audio_args,
                # gdigrab drops frames under load; Instagram wants constant frame rate.
                "-fps_mode", "cfr", "-r", fps,
                "-c:v", "libx264", "-preset", "slow", "-crf", "14",
                "-profile:v", "high", "-tune", "film",
                "-colorspace", "bt709", "-color_primaries", "bt709", "-color_trc", "bt709",
                "-color_range", "tv",
                "-movflags", "+faststart",
                "-progress", "pipe:1", "-nostats", dst]

    def start_export(self, duration):
        out_dir = self.out_dir.get()
        try:
            os.makedirs(out_dir, exist_ok=True)
        except OSError as exc:
            self._set_busy(False)
            self.status.set("Export failed. The raw recording was kept in the temp folder.")
            messagebox.showerror(APP_NAME, f"Can't use the folder {out_dir}:\n\n{exc}")
            return
        stamp = datetime.datetime.now().strftime("%Y-%m-%d_%H-%M-%S")
        dst = os.path.join(out_dir, f"{APP_NAME}_{stamp}.mp4")
        self._set_busy(True)
        self.progress_value = 0.0
        self.export_result = None
        self.status.set("Converting to 9:16 for Reels…")
        self.rec_duration = duration
        audio = None
        if self.audio and self.audio.written > 0:
            offset = self.audio.t0 - self.video_start if self.video_start else 0.0
            audio = (self.audio.path, self.audio.rate, self.audio.channels, offset)
        self.audio = None
        threading.Thread(target=self._export_worker,
                         args=(self.raw_path, dst, self.rec_fps, audio, duration),
                         daemon=True).start()
        self._poll_export()

    def _export_worker(self, src, dst, fps, audio, duration):
        log_path = src + ".export.log"
        try:
            with open(log_path, "w", encoding="utf-8", errors="replace") as log:
                p = subprocess.Popen(self.build_export_cmd(src, dst, fps, audio, duration),
                                     stdout=subprocess.PIPE,
                                     stderr=log, stdin=subprocess.DEVNULL,
                                     creationflags=CREATE_NO_WINDOW, text=True)
                total = max(self.rec_duration, 0.1)
                for line in p.stdout:
                    if line.startswith("out_time_us="):
                        try:
                            self.progress_value = min(99.0, int(line.split("=")[1]) / 1e6 / total * 100)
                        except ValueError:
                            pass
                p.wait()
            if p.returncode == 0:
                for f in (src, log_path, self.log_path, audio[0] if audio else None):
                    if not f:
                        continue
                    try:
                        os.remove(f)
                    except OSError:
                        pass
                self.export_result = ("ok", dst)
            else:
                with open(log_path, encoding="utf-8", errors="replace") as f:
                    self.export_result = ("error", f.read()[-1500:])
        except Exception as exc:
            self.export_result = ("error", str(exc))

    def _poll_export(self):
        self.bar["value"] = self.progress_value
        if self.export_result is None:
            self.status.set(f"Converting to 9:16 for Reels… {self.progress_value:.0f}%")
            self.after(200, self._poll_export)
            return
        self._set_busy(False)
        kind, info = self.export_result
        if kind == "ok":
            self.bar["value"] = 100
            self.last_output = info
            msg = f"Done! Saved {os.path.basename(info)}"
            if self.audio_warning:
                msg += "\n" + self.audio_warning
            self.status.set(msg)
        else:
            self.bar["value"] = 0
            self.status.set("Export failed. The raw recording was kept in the temp folder.")
            messagebox.showerror(APP_NAME, "Export failed:\n\n" + info)

    # ---------- Updates ----------
    def check_for_updates(self, manual=False):
        self.check_btn.state(["disabled"])
        self.update_check = None
        threading.Thread(target=self._update_check_worker, daemon=True).start()
        self._poll_update_check(manual)

    def _update_check_worker(self):
        try:
            self.update_check = fetch_latest_release()
        except Exception as exc:
            self.update_check = (None, exc)

    def _poll_update_check(self, manual):
        if self.update_check is None:
            self.after(200, lambda: self._poll_update_check(manual))
            return
        self.check_btn.state(["!disabled"])
        version, asset = self.update_check
        if version is None:
            if manual:  # stay quiet at start-up: being offline isn't worth a message
                self.status.set(f"Couldn't check for updates: {asset}")
        elif asset and version_tuple(version) > version_tuple(APP_VERSION):
            self.update_version = version
            self.update_asset = asset
            self.update_lbl.configure(text=f"Version {version} is ready to install "
                                           f"(you have {APP_VERSION}).")
            self.update_bar.grid()
            if manual:
                self.status.set(f"Version {version} is available.")
        elif manual:
            self.status.set(f"You have the latest version ({APP_VERSION}).")

    def start_update(self):
        if not getattr(sys, "frozen", False):
            messagebox.showinfo(APP_NAME, "Updates can only be installed from the exe.")
            return
        if self.rec_btn.instate(["disabled"]):
            messagebox.showinfo(APP_NAME, "Please wait for the current video to finish first.")
            return
        self.updating = True
        self.update_result = None
        self.progress_value = 0.0
        self._set_busy(True)
        self.install_btn.state(["disabled"])
        self.check_btn.state(["disabled"])
        threading.Thread(target=self._update_worker, daemon=True).start()
        self._poll_update()

    def _update_worker(self):
        new_exe = sys.executable + ".new"
        try:
            download_update(self.update_asset, new_exe,
                            lambda pct: setattr(self, "progress_value", pct))
            install_update(new_exe)
            self.update_result = "ok"
        except Exception as exc:
            self.update_result = str(exc)

    def _poll_update(self):
        self.bar["value"] = self.progress_value
        if self.update_result is None:
            self.status.set(f"Downloading version {self.update_version}… "
                            f"{self.progress_value:.0f}%")
            self.after(200, self._poll_update)
            return
        if self.update_result == "ok":
            self.destroy()  # the new version is already starting
            return
        self.updating = False
        self.bar["value"] = 0
        self._set_busy(False)
        self.install_btn.state(["!disabled"])
        self.check_btn.state(["!disabled"])
        self.status.set("The update failed; your current version still works.")
        messagebox.showerror(APP_NAME, "Couldn't install the update:\n\n" + self.update_result)

    def on_close(self):
        if self.finishing or self.updating:
            return
        if self.proc:
            if not messagebox.askyesno(APP_NAME, "A recording is in progress. Stop and export it first?"):
                return
            self.stop_recording()
            return
        if self.export_result is None and self.rec_btn.instate(["disabled"]):
            if not messagebox.askyesno(APP_NAME, "Export still running. Quit anyway?"):
                return
        self.destroy()


def main():
    app = App()
    app.protocol("WM_DELETE_WINDOW", app.on_close)
    app.mainloop()


if __name__ == "__main__":
    main()
