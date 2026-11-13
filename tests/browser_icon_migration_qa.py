#!/usr/bin/env python3
"""Offline browser smoke test for migration, social features, and mobile layout.

Start headless Chrome with ``--remote-debugging-port=9237 --window-size=390,844``
before running this script. Set WTC_DEVTOOLS_PORT to use another local port.
"""

import base64
import json
import os
import socket
import struct
import time
import urllib.request
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
APP_URL = (ROOT / "way-to-consistency.html").as_uri()
PORT = int(os.environ.get("WTC_DEVTOOLS_PORT", "9237"))


def wait_for_browser():
    endpoint = f"http://127.0.0.1:{PORT}/json"
    for _ in range(80):
        try:
            return json.load(urllib.request.urlopen(endpoint, timeout=0.2))
        except Exception:
            time.sleep(0.1)
    raise RuntimeError("Chrome DevTools did not start")


class DevTools:
    def __init__(self, url):
        hostport, path = url.split("//", 1)[1].split("/", 1)
        host, port = hostport.split(":")
        self.sock = socket.create_connection((host, int(port)))
        key = base64.b64encode(os.urandom(16)).decode()
        request = (
            f"GET /{path} HTTP/1.1\r\nHost: {hostport}\r\nUpgrade: websocket\r\n"
            f"Connection: Upgrade\r\nSec-WebSocket-Key: {key}\r\n"
            "Sec-WebSocket-Version: 13\r\n\r\n"
        )
        self.sock.sendall(request.encode())
        if b"101" not in self.sock.recv(4096):
            raise RuntimeError("DevTools WebSocket handshake failed")
        self.counter = 0

    def call(self, method, params=None):
        self.counter += 1
        wanted = self.counter
        payload = json.dumps({"id": wanted, "method": method, "params": params or {}}).encode()
        mask = os.urandom(4)
        size = len(payload)
        header = bytes([0x81, 0x80 | (size if size < 126 else 126)])
        if size >= 126:
            header += struct.pack("!H", size)
        self.sock.sendall(header + mask + bytes(byte ^ mask[i % 4] for i, byte in enumerate(payload)))
        while True:
            head = self.sock.recv(2)
            size = head[1] & 127
            if size == 126:
                size = struct.unpack("!H", self.sock.recv(2))[0]
            elif size == 127:
                size = struct.unpack("!Q", self.sock.recv(8))[0]
            data = b""
            while len(data) < size:
                data += self.sock.recv(size - len(data))
            message = json.loads(data)
            if message.get("id") == wanted:
                return message

    def evaluate(self, expression):
        result = self.call("Runtime.evaluate", {
            "expression": expression,
            "returnByValue": True,
            "awaitPromise": True,
        })
        return result["result"]["result"].get("value")


def main():
    legacy = {
        "v": 1,
        "profile": {"onboarded": True, "name": "Migration QA"},
        "cats": [{"id": "legacy-cat", "kind": "task", "name": "Study", "emoji": "\ud83d\udcda", "color": "#2b5fd9", "order": 0}],
        "tasks": [{"id": "legacy-task", "title": "Morning walk", "emoji": "\ud83d\udc5f", "date": "2026-10-16", "allowEarly": False, "state": "active", "rec": {"type": "none", "days": []}, "rem": {"on": False}}],
        "goals": [{"id": "legacy-goal", "title": "Finish lessons", "emoji": "?", "target": 10, "actual": 1}],
        "checkins": [{"id": "legacy-checkin", "mood": "\ud83d\ude0a", "date": "2026-10-16"}],
        "occs": [], "income": [], "expenses": [], "budgets": [], "audio": [], "log": [], "runs": []
    }
    pages = wait_for_browser()
    page = next(item for item in pages if item["type"] == "page")
    dev = DevTools(page["webSocketDebuggerUrl"])
    try:
            dev.call("Page.navigate", {"url": APP_URL})
            time.sleep(1)
            payload = json.dumps(json.dumps(legacy))
            dev.evaluate(f"localStorage.setItem('wtc.v1', {payload}); location.reload(); true")
            time.sleep(1.5)
            state = dev.evaluate("""(() => {
              const data = JSON.parse(localStorage.getItem('wtc.v1'));
              document.querySelector('[data-v="tasks"]')?.click();
              document.querySelector('[data-a="newTask"]')?.click();
              const choices = [...document.querySelectorAll('.icon-choice')];
              return {
                version: data.v,
                taskIcon: data.tasks[0].iconKey,
                taskPolicy: data.tasks[0].availabilityPolicy,
                categoryIcon: data.cats.find(c => c.id === 'legacy-cat').iconKey,
                goalIcon: data.goals[0].iconKey,
                mood: data.checkins[0].mood,
                legacyFields: [...data.tasks, ...data.cats, ...data.goals].filter(x => 'emoji' in x).length,
                iconChoices: choices.length,
                iconGroups: document.querySelectorAll('.icon-group').length,
                smallestTarget: Math.min(...choices.map(x => Math.min(x.getBoundingClientRect().width, x.getBoundingClientRect().height))),
                pageOverflow: document.documentElement.scrollWidth > document.documentElement.clientWidth,
                labelledIconButtons: [...document.querySelectorAll('button.iconbtn')].every(x => x.getAttribute('aria-label'))
              };
            })()""")
            expected = {
                "version": 6, "taskIcon": "walk", "taskPolicy": "scheduled",
                "categoryIcon": "book", "goalIcon": "book", "mood": "Good",
                "legacyFields": 0, "iconGroups": 6, "pageOverflow": False,
                "labelledIconButtons": True,
            }
            for key, value in expected.items():
                assert state[key] == value, f"{key}: expected {value!r}, got {state[key]!r}"
            assert state["iconChoices"] >= 30
            assert state["smallestTarget"] >= 43.5
            availability = dev.evaluate("""(() => {
              document.querySelector('.icon-choice[data-v="book"]').click();
              const form = document.querySelector('form[data-form="task"]');
              form.elements.title.value = 'Icon picker QA';
              form.elements.date.value = '2099-12-31';
              form.elements.startTime.value = '09:30';
              form.requestSubmit();
              return {
                title: document.querySelector('#sheetTitle')?.textContent.trim(),
                choices: document.querySelectorAll('[data-a="chooseAvailability"]').length,
                unlockText: document.querySelector('[data-v="scheduled"]')?.textContent.replace(/\\s+/g, ' ').trim()
              };
            })()""")
            assert availability["choices"] == 2
            assert availability["title"].lower().startswith("when can this task")
            assert "2099" in availability["unlockText"] and "9:30" in availability["unlockText"]
            dev.evaluate("document.querySelector('[data-a=\"chooseAvailability\"][data-v=\"scheduled\"]').click(); true")
            time.sleep(0.5)
            saved = dev.evaluate("""(() => {
              const data = JSON.parse(localStorage.getItem('wtc.v1'));
              const task = data.tasks.find(item => item.title === 'Icon picker QA');
              return {iconKey: task?.iconKey, policy: task?.availabilityPolicy, hasEmoji: task ? 'emoji' in task : true};
            })()""")
            assert saved == {"iconKey": "book", "policy": "scheduled", "hasEmoji": False}
            state["availabilityPopup"] = availability["choices"]
            state["savedIcon"] = saved["iconKey"]
            motivation = dev.evaluate("""(() => {
              document.querySelector('.nav-btn[data-v="motivation"]')?.click();
              const page = document.querySelector('.motivation-page');
              document.querySelector('[data-a="shareStreak"]')?.click();
              const preview = document.querySelector('.share-preview');
              const privacyCopy = document.querySelector('#sheetRoot')?.textContent.includes('Task names, money, photos, and health details stay private.');
              return {page: !!page, preview: !!preview, privacyCopy};
            })()""")
            assert motivation["page"] and motivation["preview"] and motivation["privacyCopy"], motivation
            share_invoked = dev.evaluate("""(() => { window.__shareErrors = [];
              window.addEventListener('error', event => window.__shareErrors.push(event.message));
              window.addEventListener('unhandledrejection', event => window.__shareErrors.push(String(event.reason)));
              const button = document.querySelector('[data-a="shareCard"]');
              if (!button) return false;
              button.click(); return true; })()""")
            assert share_invoked, "share action was not available"
            time.sleep(2.5)
            share_state = dev.evaluate("""({downloaded: [...document.querySelectorAll('.toast')].some(item => item.textContent.includes('Share card downloaded')),
              toasts: [...document.querySelectorAll('.toast')].map(item => item.textContent.trim()), errors: window.__shareErrors})""")
            assert share_state["downloaded"], share_state
            share_result = share_state["downloaded"]
            dev.evaluate("document.querySelector('[data-a=\"closeSheet\"]')?.click(); true")
            music_form = dev.evaluate("""(() => {
              document.querySelector('.nav-btn[data-v="motivation"]')?.click();
              const provider = document.querySelector('#music-provider');
              provider.value = 'spotify';
              provider.dispatchEvent(new Event('change', {bubbles: true}));
              document.querySelector('#music-url').value = 'https://open.spotify.com/playlist/qa';
              document.querySelector('#music-label').value = 'QA playlist';
              const form = document.querySelector('form[data-form="runMusic"]');
              return {submitted: Object.fromEntries(new FormData(form).entries()), valid: form.checkValidity(), inert: document.querySelector('#app').inert};
            })()""")
            assert music_form["valid"] and not music_form["inert"], music_form
            motivation["submitted"] = music_form["submitted"]
            dev.evaluate("document.querySelector('form[data-form=\"runMusic\"]').requestSubmit(); true")
            time.sleep(0.2)
            saved_music = dev.evaluate("""(() => { const music = JSON.parse(localStorage.getItem('wtc.v1')).motivation.music;
              return {provider: music.provider, label: music.label}; })()""")
            assert saved_music == {"provider": "spotify", "label": "QA playlist"}, saved_music
            motivation["savedMusic"] = saved_music
            motivation["sharePng"] = share_result
            dev.evaluate("""(() => {
              document.querySelector('[data-a="partnerSettings"]').click();
              document.querySelector('#partner-name').value = 'Sam';
              document.querySelector('form[data-form="partner"]').requestSubmit();
              return true;
            })()""")
            time.sleep(0.2)
            dev.evaluate("""(() => {
              document.querySelector('[data-a="importPartnerUpdate"]').click();
              const payload = {v: 1, name: 'Sam', streak: 12, rate: 80, updatedAt: new Date().toISOString()};
              document.querySelector('#partner-code').value = 'WTC1:' + btoa(JSON.stringify(payload));
              document.querySelector('form[data-form="partnerUpdate"]').requestSubmit();
              return true;
            })()""")
            time.sleep(0.2)
            partner = dev.evaluate("""(() => {
              const value = JSON.parse(localStorage.getItem('wtc.v1')).motivation.partner;
              return {name: value.name, streak: value.streak, rate: value.rate};
            })()""")
            assert partner == {"name": "Sam", "streak": 12, "rate": 80}, partner
            motivation["partnerImport"] = partner
            state["motivation"] = motivation
            layout_failures = []
            views = ["home", "tasks", "calendar", "habits", "money", "reports", "journey", "motivation", "integrations", "settings", "runs"]
            for width, height, scale in [(320, 800, 1.25), (430, 932, 1.25), (844, 390, 1.15)]:
                dev.call("Emulation.setDeviceMetricsOverride", {
                    "width": width, "height": height, "deviceScaleFactor": 1, "mobile": True,
                })
                dev.evaluate(f"document.documentElement.style.setProperty('--scale', '{scale}'); true")
                for view in views:
                    result = dev.evaluate(f"""(() => {{
                      document.querySelector('.nav-btn[data-v="{view}"]')?.click();
                      return {{overflow: document.documentElement.scrollWidth > document.documentElement.clientWidth,
                        width: document.documentElement.scrollWidth, client: document.documentElement.clientWidth}};
                    }})()""")
                    if result["overflow"]:
                        layout_failures.append(f"{width}x{height}/{view}: {result['width']}>{result['client']}")
            dev.evaluate("document.querySelector('.nav-btn[data-v=\"settings\"]')?.click(); true")
            theme_count = dev.evaluate("""(() => {
              const cards = [...document.querySelectorAll('.themecard')];
              cards.forEach(card => card.click());
              const contrast = document.querySelector('[data-change="highContrast"]');
              if (contrast && !contrast.checked) contrast.click();
              return cards.length;
            })()""")
            assert not layout_failures, "; ".join(layout_failures)
            assert theme_count == 6
            state["responsiveViews"] = len(views) * 3
            state["themesExercised"] = theme_count
            print(json.dumps(state, sort_keys=True))
    finally:
        dev.sock.close()


if __name__ == "__main__":
    main()
