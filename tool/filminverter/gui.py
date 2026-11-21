"""Tk GUI: Camera / Develop / Themes / Developer tabs over a shared log. Long jobs run on a worker thread and
report through a queue, so the window never freezes. Tk ships with Python on Windows and macOS; on Linux it
is a separate package (see `filminverter doctor`)."""
from __future__ import annotations

import os
import queue
import threading
import tkinter as tk
from pathlib import Path
from tkinter import colorchooser, filedialog, messagebox, ttk

from . import __version__, camera, develop, platforms, themes, toolchain


class App:
    def __init__(self, root: tk.Tk):
        self.root = root
        self.q: "queue.Queue[str]" = queue.Queue()
        self.busy = False
        root.title(f"Film Inverter {__version__}")
        root.geometry("980x720")
        root.minsize(760, 560)

        nb = ttk.Notebook(root)
        nb.pack(fill="both", expand=True, padx=8, pady=(8, 0))
        self._camera_tab(nb)
        self._develop_tab(nb)
        self._theme_tab(nb)
        self._dev_tab(nb)

        logf = ttk.LabelFrame(root, text="Log")
        logf.pack(fill="both", expand=False, padx=8, pady=8)
        self.logtext = tk.Text(logf, height=10, wrap="word", font=("TkFixedFont", 9))
        sb = ttk.Scrollbar(logf, command=self.logtext.yview)
        self.logtext.configure(yscrollcommand=sb.set)
        self.logtext.pack(side="left", fill="both", expand=True)
        sb.pack(side="right", fill="y")
        self.log(f"{platforms.describe()}  ·  work folder {platforms.work_dir()}")
        root.after(100, self._drain)
        self.refresh_card()

    # ------------------------------------------------------------ plumbing
    def log(self, s: str) -> None:
        self.q.put(s)

    def _drain(self) -> None:
        try:
            while True:
                s = self.q.get_nowait()
                self.logtext.insert("end", s + "\n")
                self.logtext.see("end")
        except queue.Empty:
            pass
        self.root.after(100, self._drain)

    def job(self, title: str, fn) -> None:
        if self.busy:
            messagebox.showinfo("Busy", "Another job is still running.")
            return
        self.busy = True
        self.log(f"--- {title}")

        def run():
            try:
                fn()
                self.log(f"--- {title}: done")
            except Exception as e:                                  # noqa: BLE001
                self.log(f"!! {title} failed: {e}")
            finally:
                self.busy = False
                self.root.after(0, self.refresh_card)
        threading.Thread(target=run, daemon=True).start()

    # ------------------------------------------------------------ camera
    def _camera_tab(self, nb):
        f = ttk.Frame(nb, padding=12)
        nb.add(f, text="Camera")
        self.card_var = tk.StringVar(value="Looking for the camera card…")
        row = ttk.Frame(f)
        row.pack(fill="x")
        ttk.Label(row, textvariable=self.card_var).pack(side="left")
        ttk.Button(row, text="Refresh", command=self.refresh_card).pack(side="right")

        box = ttk.LabelFrame(f, text="Install the app on the camera", padding=10)
        box.pack(fill="x", pady=10)
        ttk.Label(box, text="Camera: Menu › Setup › USB Connection = Mass Storage, then connect USB.").pack(anchor="w")
        r = ttk.Frame(box)
        r.pack(fill="x", pady=6)
        self.apk_var = tk.StringVar(value=str(camera.default_apk() or ""))
        ttk.Label(r, text="APK").pack(side="left")
        ttk.Entry(r, textvariable=self.apk_var).pack(side="left", fill="x", expand=True, padx=6)
        ttk.Button(r, text="Browse…", command=lambda: self.apk_var.set(
            filedialog.askopenfilename(filetypes=[("Android app", "*.apk")]) or self.apk_var.get())).pack(side="left")
        r2 = ttk.Frame(box)
        r2.pack(fill="x")
        ttk.Button(r2, text="Install", command=self.install).pack(side="left")
        ttk.Button(r2, text="Download latest release + install", command=self.install_latest).pack(side="left", padx=6)
        if platforms.OS == "linux":
            ttk.Button(r2, text="USB permission help", command=lambda: messagebox.showinfo(
                "USB permission", camera.udev_instructions())).pack(side="right")

        box2 = ttk.LabelFrame(f, text="Card", padding=10)
        box2.pack(fill="x")
        ttk.Button(box2, text="Copy the app's logs and positives to this computer", command=self.fetch_logs).pack(side="left")
        ttk.Button(box2, text="Open card", command=lambda: self._with_card(platforms.open_path)).pack(side="left", padx=6)

    def refresh_card(self):
        cards = camera.find_cards()
        self.card_var.set("Camera card: " + ", ".join(map(str, cards)) if cards
                          else "Camera card: not mounted (connect in Mass Storage mode)")

    def _with_card(self, fn):
        cards = camera.find_cards()
        if not cards:
            messagebox.showwarning("No card", "Connect the camera in Mass Storage mode first.")
            return None
        return fn(cards[0])

    def install(self):
        apk = self.apk_var.get().strip()
        if not apk:
            messagebox.showwarning("No APK", "Choose an APK, build one, or use the latest release.")
            return
        self.job("install", lambda: camera.install(Path(apk), self.log))

    def install_latest(self):
        self.job("install latest release", lambda: camera.install(camera.latest_release_apk(self.log), self.log))

    def fetch_logs(self):
        self._with_card(lambda c: self.job("copy logs", lambda: platforms.open_path(camera.fetch_logs(c, self.log))))

    # ------------------------------------------------------------ develop
    def _develop_tab(self, nb):
        f = ttk.Frame(nb, padding=12)
        nb.add(f, text="Develop")
        ttk.Label(f, text="Invert a folder of negative scans on this computer (originals are never changed).").pack(anchor="w")
        r = ttk.Frame(f)
        r.pack(fill="x", pady=8)
        self.dev_dir = tk.StringVar()
        ttk.Entry(r, textvariable=self.dev_dir).pack(side="left", fill="x", expand=True)
        ttk.Button(r, text="Choose folder…", command=lambda: self.dev_dir.set(
            filedialog.askdirectory() or self.dev_dir.get())).pack(side="left", padx=6)
        o = ttk.Frame(f)
        o.pack(fill="x")
        self.prefer = tk.StringVar(value="raw")
        ttk.Radiobutton(o, text="Use RAW (best quality)", variable=self.prefer, value="raw").pack(side="left")
        ttk.Radiobutton(o, text="Use JPEG", variable=self.prefer, value="jpeg").pack(side="left", padx=10)
        self.crop = tk.BooleanVar(value=True)
        ttk.Checkbutton(o, text="Auto-crop film border", variable=self.crop).pack(side="left", padx=10)
        self.per_frame = tk.BooleanVar(value=False)
        ttk.Checkbutton(o, text="Levels per frame (not per roll)", variable=self.per_frame).pack(side="left")
        ttk.Button(f, text="Develop", command=self.develop).pack(anchor="w", pady=10)
        ttk.Label(f, foreground="#555", text="Positives go to <folder>/positives. JPEGs captured in the app are already "
                  "positives; use RAW for those frames.").pack(anchor="w")

    def develop(self):
        d = self.dev_dir.get().strip()
        if not d or not Path(d).is_dir():
            messagebox.showwarning("Folder", "Choose the folder with the scans.")
            return
        args = [d, "--prefer", self.prefer.get()]
        if self.crop.get():
            args.append("--crop")
        if self.per_frame.get():
            args.append("--per-frame-levels")

        def run():
            develop.develop(args, self.log)
            out = Path(d) / "positives"
            if out.exists():
                platforms.open_path(out)
        self.job("develop", run)

    # ------------------------------------------------------------ themes
    def _theme_tab(self, nb):
        f = ttk.Frame(nb, padding=12)
        nb.add(f, text="Themes")
        left = ttk.Frame(f)
        left.pack(side="left", fill="y")
        right = ttk.Frame(f)
        right.pack(side="left", fill="both", expand=True, padx=(16, 0))

        ttk.Label(left, text="Start from").pack(anchor="w")
        self.preset = ttk.Combobox(left, state="readonly", values=[t["name"] for t in themes.BUILTINS], width=18)
        self.preset.current(0)
        self.preset.bind("<<ComboboxSelected>>", lambda e: self.load_theme(dict(themes.BUILTINS[self.preset.current()])))
        self.preset.pack(anchor="w", pady=(0, 8))
        ttk.Button(left, text="Open .JSN…", command=self.open_theme).pack(anchor="w")
        ttk.Label(left, text="Name").pack(anchor="w", pady=(10, 0))
        self.tname = tk.StringVar()
        self.tname.trace_add("write", lambda *a: self.render_theme())
        ttk.Entry(left, textvariable=self.tname, width=22).pack(anchor="w")
        ttk.Label(left, text="Layout").pack(anchor="w", pady=(10, 0))
        self.tlayout = ttk.Combobox(left, state="readonly", values=themes.LAYOUTS, width=18)
        self.tlayout.bind("<<ComboboxSelected>>", lambda e: self.render_theme())
        self.tlayout.pack(anchor="w")

        self.swatches = {}
        self.alphas = {}
        grid = ttk.Frame(left)
        grid.pack(anchor="w", pady=8)
        for r, (k, label) in enumerate(themes.KEYS):
            ttk.Label(grid, text=label).grid(row=r, column=0, sticky="w")
            b = tk.Button(grid, width=3, relief="groove", command=lambda k=k: self.pick(k))
            b.grid(row=r, column=1, padx=4, pady=2)
            self.swatches[k] = b
            a = tk.Scale(grid, from_=0, to=255, orient="horizontal", length=110, showvalue=False,
                         command=lambda v, k=k: self.set_alpha(k, int(float(v))))
            a.grid(row=r, column=2)
            self.alphas[k] = a
        ttk.Label(left, text="Corner radius").pack(anchor="w")
        self.radius = tk.Scale(left, from_=0, to=28, orient="horizontal", command=lambda v: self.render_theme())
        self.radius.pack(anchor="w", fill="x")
        ttk.Label(left, text="Text size %").pack(anchor="w")
        self.tscale = tk.Scale(left, from_=70, to=160, resolution=5, orient="horizontal", command=lambda v: self.render_theme())
        self.tscale.pack(anchor="w", fill="x")
        rb = ttk.Frame(left)
        rb.pack(anchor="w", pady=8)
        ttk.Button(rb, text="Save .JSN…", command=self.save_theme).pack(side="left")
        ttk.Button(rb, text="Install to card", command=self.install_theme).pack(side="left", padx=6)

        self.canvas = tk.Canvas(right, width=640, height=480, highlightthickness=0, bg="#000")
        self.canvas.pack(anchor="nw")
        ttk.Label(right, foreground="#555", text="Color preview on the Minimal layout (640×480); on the camera the theme "
                  "uses its own layout. Files are saved with an 8.3 name, which the camera requires.").pack(anchor="w", pady=4)
        self.load_theme(dict(themes.BUILTINS[0], name="My theme"))

    def load_theme(self, t: dict):
        self.theme = t
        self.tname.set(t["name"])
        self.tlayout.set(t.get("layout", "minimal"))
        for k, _ in themes.KEYS:
            r, g, b, a = themes.parse_color(t[k])
            self.alphas[k].set(a)
        self.radius.set(int(t.get("radius", 12)))
        self.tscale.set(int(round(float(t.get("textScale", 1.0)) * 100)))
        self.render_theme()

    def pick(self, k):
        r, g, b, a = themes.parse_color(self.theme[k])
        c = colorchooser.askcolor(color=f"#{r:02x}{g:02x}{b:02x}", title=k)
        if c and c[0]:
            rr, gg, bb = (int(x) for x in c[0])
            self.theme[k] = themes.make_color(rr, gg, bb, a)
            self.render_theme()

    def set_alpha(self, k, a):
        if not hasattr(self, "theme"):
            return
        r, g, b, _ = themes.parse_color(self.theme[k])
        self.theme[k] = themes.make_color(r, g, b, a)
        self.render_theme()

    def _current(self) -> dict:
        t = dict(self.theme)
        t["name"] = self.tname.get().strip() or "Untitled"
        t["layout"] = self.tlayout.get() or "minimal"
        t["radius"] = int(self.radius.get())
        t["textScale"] = int(self.tscale.get()) / 100
        return t

    def render_theme(self):
        if not hasattr(self, "theme") or not hasattr(self, "canvas"):
            return
        t = self._current()
        c = self.canvas
        c.delete("all")
        sky, ground = (169, 188, 203), (75, 84, 55)

        def col(key, over):
            r, g, b, a = themes.parse_color(t[key])
            f = a / 255
            return "#%02x%02x%02x" % tuple(int(x * f + o * (1 - f)) for x, o in zip((r, g, b), over))

        for y in range(0, 480, 4):                                  # live view stand-in
            k = y / 480
            base = sky if k < 0.45 else ground
            c.create_rectangle(0, y, 640, y + 4, fill="#%02x%02x%02x" % base, width=0)
        s = t["textScale"]
        font = ("Helvetica", int(10 * s))
        x = 12
        for label in ("POS", "Live"):
            w = 14 + 8 * len(label) * s
            c.create_rectangle(x, 12, x + w, 36, fill=col("pill", sky), width=0)
            c.create_text(x + w / 2, 24, text=label, fill=col("pillText", sky), font=font)
            x += w + 8
        x = 628
        for label in ("2700 K · B2", "Base set"):
            w = 14 + 7 * len(label) * s
            c.create_rectangle(x - w, 12, x, 36, fill=col("pill", sky), width=0)
            c.create_text(x - w / 2, 24, text=label, fill=col("pillText", sky), font=font)
            x -= w + 8
        c.create_rectangle(0, 368, 640, 480, fill=col("bar", ground), width=0)
        barbg = themes.parse_color(t["bar"])[:3]
        c.create_rectangle(16, 398, 80, 450, outline=col("chipBorder", barbg), width=1)
        c.create_text(48, 424, text="Menu", fill=col("text", barbg), font=font)
        x = 96
        for i, label in enumerate(("Black", "White", "Gamma", "Temp", "A–B", "G–M")):
            w = 18 + 7 * len(label) * s
            if i == 0:
                c.create_rectangle(x, 384, x + w, 408, fill=col("accent", barbg), width=0)
                c.create_text(x + w / 2, 396, text=label, fill=col("onAccent", themes.parse_color(t["accent"])[:3]), font=font)
            else:
                c.create_rectangle(x, 384, x + w, 408, outline=col("chipBorder", barbg), width=1)
                c.create_text(x + w / 2, 396, text=label, fill=col("textDim", barbg), font=font)
            x += w + 6
        c.create_rectangle(118, 434, 460, 438, fill=col("chipBorder", barbg), width=0)
        c.create_rectangle(118, 434, 180, 438, fill=col("accent", barbg), width=0)
        c.create_oval(168, 424, 192, 448, fill=col("text", barbg), width=0)
        c.create_text(520, 436, text="184", fill=col("text", barbg), font=("Courier", int(12 * s)))
        c.create_oval(548, 386, 624, 462, outline=col("text", barbg), width=3)
        c.create_oval(556, 394, 616, 454, fill=col("text", barbg), width=0)
        for k, _ in themes.KEYS:
            r, g, b, a = themes.parse_color(t[k])
            self.swatches[k].configure(bg=f"#{r:02x}{g:02x}{b:02x}", activebackground=f"#{r:02x}{g:02x}{b:02x}")

    def open_theme(self):
        p = filedialog.askopenfilename(filetypes=[("Theme", "*.JSN *.jsn *.json")])
        if p:
            try:
                self.load_theme(themes.load(Path(p)))
            except Exception as e:                                  # noqa: BLE001
                messagebox.showerror("Theme", str(e))

    def save_theme(self):
        t = self._current()
        errs = themes.validate(t)
        if errs:
            messagebox.showerror("Theme", "\n".join(errs))
            return
        p = filedialog.asksaveasfilename(initialfile=themes.file_name(t["name"]), defaultextension=".JSN",
                                         filetypes=[("Theme", "*.JSN")])
        if p:
            Path(p).write_text(themes.to_json(t), encoding="utf-8")
            self.log(f"saved {p}")

    def install_theme(self):
        t = self._current()
        self._with_card(lambda card: self.job("install theme", lambda: themes.install_to_card(card, t, self.log)))

    # ------------------------------------------------------------ developer
    def _dev_tab(self, nb):
        f = ttk.Frame(nb, padding=12)
        nb.add(f, text="Developer")
        ttk.Label(f, text="Only needed to build the camera app yourself (downloads ~1 GB once).").pack(anchor="w")
        r = ttk.Frame(f)
        r.pack(anchor="w", pady=10)
        from . import cli
        ttk.Button(r, text="Check system (doctor)", command=lambda: self.job("doctor", lambda: _doctor_to(self.log))).pack(side="left")
        ttk.Button(r, text="Set up toolchain", command=lambda: self.job("setup", lambda: toolchain.setup(self.log))).pack(side="left", padx=6)
        ttk.Button(r, text="Build app", command=self.build).pack(side="left")
        ttk.Button(r, text="Open work folder", command=lambda: platforms.open_path(platforms.work_dir())).pack(side="left", padx=6)
        _ = cli

    def build(self):
        from . import build

        def run():
            apk = build.build(self.log)
            self.root.after(0, lambda: self.apk_var.set(str(apk)))
        self.job("build", run)


def _doctor_to(log):
    """Run `doctor`, sending its printed lines to the GUI log."""
    import contextlib
    from . import cli
    w = develop._LogWriter(log)
    with contextlib.redirect_stdout(w):
        cli.doctor()


def run() -> int:
    try:
        root = tk.Tk()
    except tk.TclError as e:
        print(f"cannot open a window ({e}). Use the command line: filminverter --help")
        return 1
    app = App(root)
    if os.environ.get("FI_GUI_SELFTEST"):                         # CI / smoke test: open, draw, close
        tab = os.environ.get("FI_GUI_TAB")
        if tab:                                                    # optionally show a tab (index) first
            root.after(300, lambda: root.nametowidget(root.winfo_children()[0]).select(int(tab)))
        shot = os.environ.get("FI_GUI_SHOT")
        if shot:                                                   # optionally save a screenshot of the window

            def grab():
                try:
                    from PIL import ImageGrab
                    root.update()
                    x, y = root.winfo_rootx(), root.winfo_rooty()
                    ImageGrab.grab(bbox=(x, y, x + root.winfo_width(), y + root.winfo_height())).save(shot)
                except Exception as e:                             # noqa: BLE001
                    print(f"screenshot failed: {e}")
            root.after(1200, grab)
        root.after(1800, root.destroy)
    _ = app
    root.mainloop()
    return 0
