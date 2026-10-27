#!/usr/bin/env python3
"""Puts photos from your Google Photos on the TV: the home screen background and the screensaver show them.

    python3 tools/google_photos.py 192.168.1.50            # pick photos in the browser: they replace the TV's
    python3 tools/google_photos.py 192.168.1.50 --add      # pick more: added to the ones already on the TV
    python3 tools/google_photos.py 192.168.1.50 --clear    # remove them (back to how it was)

It signs in to your Google account in the browser, opens the Google Photos picker there (open an album and select
its photos, up to 200), downloads them at 2560 px and copies them over adb into the app's folder on the TV
(/sdcard/Android/data/io.freemytv/files/photos), replacing the ones copied before (with --add, next to them; a
photo picked again isn't copied twice). On the TV, Home Settings sets how often they change.

Google doesn't let an app read your albums any more, only the photos you pick (the Picker API), and its TV sign-in
can't ask for Photos, so the picking happens here. Once: in console.cloud.google.com, make a project, enable the
"Photos Picker API", set up the OAuth consent screen (External, add yourself as a test user) and create an OAuth
client of type "Desktop app". Put its id and secret in config.properties (google_client_id, google_client_secret).
Nothing is kept: no token on this computer or on the TV. Standard library only.
"""
import base64
import hashlib
import http.server
import json
import os
import secrets
import shutil
import ssl
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
import webbrowser

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SCOPE = "https://www.googleapis.com/auth/photospicker.mediaitems.readonly"
PICKER = "https://photospicker.googleapis.com/v1"
TV_DIR = "/sdcard/Android/data/io.freemytv/files/photos"
MAX_ITEMS = 200
# Bounded by width for landscape photos; the tall height lets a portrait one keep enough width for the 16:9 crop.
SIZE = "=w2560-h5000"


def prop(name):
    path = os.path.join(ROOT, "config.properties")
    if not os.path.exists(path):
        return ""
    value = ""
    with open(path, encoding="utf-8") as f:
        for line in f:
            key, sep, v = line.partition("=")
            if sep and key.strip() == name:
                value = v.strip().strip('"')
    return value


def find_adb():
    found = shutil.which("adb")
    if found:
        return found
    for p in (os.path.expanduser("~/Library/Android/sdk/platform-tools/adb"), "/opt/homebrew/bin/adb", "/usr/local/bin/adb"):
        if os.access(p, os.X_OK):
            return p
    sys.exit("adb not found. Install it with:  brew install --cask android-platform-tools")


def adb(serial, *args, check=True):
    r = subprocess.run([find_adb(), "-s", serial, *args], capture_output=True, text=True)
    if check and r.returncode != 0:
        sys.exit("adb %s failed: %s" % (args[0], (r.stderr or r.stdout).strip()))
    return r.stdout


def connect(ip):
    serial = ip if ":" in ip else ip + ":5555"
    subprocess.run([find_adb(), "connect", serial], capture_output=True, text=True)
    if adb(serial, "get-state", check=False).strip() != "device":
        sys.exit("The TV at %s didn't answer adb. Is it on, and was 'Allow USB debugging' accepted?" % ip)
    return serial


def trust_system_certificates():
    """python.org's Python on macOS has no certificates until its "Install Certificates" step: use the system's too."""
    ctx = ssl.create_default_context()
    for bundle in ("/etc/ssl/cert.pem", "/opt/homebrew/etc/ca-certificates/cert.pem", "/usr/local/etc/ca-certificates/cert.pem"):
        if os.path.exists(bundle):
            ctx.load_verify_locations(bundle)
    urllib.request.install_opener(urllib.request.build_opener(urllib.request.HTTPSHandler(context=ctx)))


def request(url, data=None, token=None, method=None, form=False):
    headers = {}
    if token:
        headers["Authorization"] = "Bearer " + token
    body = None
    if data is not None:
        if form:
            body = urllib.parse.urlencode(data).encode()
            headers["Content-Type"] = "application/x-www-form-urlencoded"
        else:
            body = json.dumps(data).encode()
            headers["Content-Type"] = "application/json"
    req = urllib.request.Request(url, data=body, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            raw = r.read()
            return json.loads(raw) if raw else {}
    except urllib.error.HTTPError as e:
        detail = e.read().decode("utf-8", "replace")
        sys.exit("Google said %d for %s:\n%s" % (e.code, url.split("?")[0], detail[:800]))


def sign_in(client_id, client_secret):
    """OAuth for installed apps: the browser comes back to a one-shot server on 127.0.0.1 with the code (PKCE)."""
    verifier = secrets.token_urlsafe(64)
    challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).rstrip(b"=").decode()
    state = secrets.token_urlsafe(16)
    result = {}

    class Handler(http.server.BaseHTTPRequestHandler):
        def do_GET(self):
            q = urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query)
            if q.get("state", [""])[0] != state:
                self.send_response(400)
                self.end_headers()
                return
            result["code"] = q.get("code", [""])[0]
            result["error"] = q.get("error", [""])[0]
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.end_headers()
            self.wfile.write("<h2>Signed in. You can close this tab and go back to the terminal.</h2>".encode())

        def log_message(self, *args):
            pass

    server = http.server.HTTPServer(("127.0.0.1", 0), Handler)
    redirect = "http://127.0.0.1:%d" % server.server_port
    url = "https://accounts.google.com/o/oauth2/v2/auth?" + urllib.parse.urlencode({
        "client_id": client_id, "redirect_uri": redirect, "response_type": "code", "scope": SCOPE,
        "code_challenge": challenge, "code_challenge_method": "S256", "state": state, "prompt": "select_account",
    })
    print("Opening the browser to sign in to Google...")
    print("(if it doesn't open, go to: %s )" % url)
    webbrowser.open(url)
    server.timeout = 300
    deadline = time.time() + 300
    while "code" not in result and time.time() < deadline:
        server.handle_request()
    server.server_close()
    if not result.get("code"):
        sys.exit("Sign-in didn't finish: %s" % (result.get("error") or "timed out"))
    token = request("https://oauth2.googleapis.com/token", {
        "code": result["code"], "client_id": client_id, "client_secret": client_secret, "redirect_uri": redirect,
        "grant_type": "authorization_code", "code_verifier": verifier,
    }, form=True)
    return token["access_token"]


def seconds(duration, fallback):
    """Google's durations look like "5s" or "1.5s"."""
    try:
        return float(str(duration).rstrip("s"))
    except ValueError:
        return fallback


def pick(token):
    session = request(PICKER + "/sessions", {"pickingConfig": {"maxItemCount": str(MAX_ITEMS)}}, token)
    print("\nOpening Google Photos. Pick your photos (open an album, then select them; up to %d), then press Done." % MAX_ITEMS)
    print("(if it doesn't open, go to: %s )" % session["pickerUri"])
    webbrowser.open(session["pickerUri"])
    poll = session.get("pollingConfig", {})
    interval = max(2.0, seconds(poll.get("pollInterval"), 5))
    deadline = time.time() + max(60.0, seconds(poll.get("timeoutIn"), 1800))
    while time.time() < deadline:
        time.sleep(interval)
        s = request(PICKER + "/sessions/" + session["id"], token=token)
        if s.get("mediaItemsSet"):
            break
    else:
        request(PICKER + "/sessions/" + session["id"], token=token, method="DELETE")
        sys.exit("Nothing was picked in time. Run it again when you're ready.")
    items, page = [], ""
    while True:
        q = {"sessionId": session["id"], "pageSize": 100}
        if page:
            q["pageToken"] = page
        r = request(PICKER + "/mediaItems?" + urllib.parse.urlencode(q), token=token)
        items += r.get("mediaItems", [])
        page = r.get("nextPageToken", "")
        if not page:
            break
    return session["id"], items


def download(token, items, folder):
    photos = [i for i in items if i.get("type") == "PHOTO"]
    if len(photos) < len(items):
        print("Skipping %d video(s): only photos are used." % (len(items) - len(photos)))
    saved = 0
    for n, item in enumerate(photos, 1):
        f = item.get("mediaFile", {})
        req = urllib.request.Request(f["baseUrl"] + SIZE, headers={"Authorization": "Bearer " + token})
        try:
            with urllib.request.urlopen(req, timeout=120) as r:
                kind = r.headers.get("Content-Type", "image/jpeg")
                data = r.read()
        except urllib.error.URLError as e:
            print("  couldn't download %s: %s" % (f.get("filename", "a photo"), e))
            continue
        ext = {"image/png": ".png", "image/webp": ".webp"}.get(kind.split(";")[0], ".jpg")
        # The date first: the screensaver shows it under the photo, and the names sort by it.
        day = (item.get("createTime") or "")[:10] or "0000-00-00"
        # Then the photo's own id: picked again (--add), it replaces itself instead of showing twice.
        name = "%s_%s%s" % (day, hashlib.sha1(item["id"].encode()).hexdigest()[:12], ext)
        with open(os.path.join(folder, name), "wb") as out:
            out.write(data)
        saved += 1
        print("\r  downloaded %d of %d" % (n, len(photos)), end="", flush=True)
    print()
    return saved


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    if not args:
        sys.exit(__doc__.split("\n\n")[1])
    trust_system_certificates()
    serial = connect(args[0])
    if "--clear" in sys.argv:
        adb(serial, "shell", "rm -rf '%s'" % TV_DIR)
        print("Removed the photos from the TV: the home screen and screensaver go back to how they were.")
        return
    client_id, client_secret = prop("google_client_id"), prop("google_client_secret")
    if not client_id or not client_secret:
        sys.exit("Add google_client_id and google_client_secret to config.properties first (see the top of this file).")
    token = sign_in(client_id, client_secret)
    session, items = pick(token)
    folder = tempfile.mkdtemp(prefix="freemytv-photos-")
    try:
        saved = download(token, items, folder)
        request(PICKER + "/sessions/" + session, token=token, method="DELETE")
        if not saved:
            sys.exit("No photos to copy.")
        print("Copying %d photos to the TV..." % saved)
        if "--add" in sys.argv:
            adb(serial, "shell", "mkdir -p '%s'" % TV_DIR)
        else:
            adb(serial, "shell", "rm -rf '%s' && mkdir -p '%s'" % (TV_DIR, TV_DIR))
        adb(serial, "push", folder + "/.", TV_DIR + "/")
    finally:
        shutil.rmtree(folder, ignore_errors=True)
    total = adb(serial, "shell", "ls '%s' | wc -l" % TV_DIR).strip()
    print("Done: %d photos copied, %s on the TV. The home screen picks them up within a minute; the screensaver next "
          "time it starts." % (saved, total))


if __name__ == "__main__":
    main()
