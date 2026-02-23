from flask import Flask, jsonify, send_from_directory, request
import os
import sqlite3
import json
from flask_cors import CORS
import urllib.parse
import time
import random
from threading import Thread
from concurrent.futures import ThreadPoolExecutor

app = Flask(__name__)
CORS(app)

CHART_ROOT_DIR = "/volume2/video/GDS3/GDRIVE/MUSIC/국내/차트"
COLLECTION_ROOT_DIR = "/volume2/video/GDS3/GDRIVE/MUSIC/국내/모음"
ARTIST_ROOT_DIR = "/volume2/video/GDS3/GDRIVE/MUSIC/국내/가수"
BASE_URL = "http://192.168.0.2:4444"
DB_PATH = "music_cache.db"

# 전역 캐시
cache = {
    "themes_charts": [],
    "themes_collections": [],
    "themes_artists": [],
    "details": {},
    "last_updated": 0
}

def init_db():
    with sqlite3.connect(DB_PATH) as conn:
        cursor = conn.cursor()
        cursor.execute('CREATE TABLE IF NOT EXISTS cache_meta (key TEXT PRIMARY KEY, value TEXT)')
        cursor.execute('CREATE TABLE IF NOT EXISTS themes (type TEXT, name TEXT, path TEXT)')
        cursor.execute('CREATE TABLE IF NOT EXISTS details (path TEXT PRIMARY KEY, data TEXT)')
        cursor.execute('CREATE INDEX IF NOT EXISTS idx_themes_type ON themes(type)')
        conn.commit()

def save_cache_to_db():
    try:
        with sqlite3.connect(DB_PATH) as conn:
            cursor = conn.cursor()
            cursor.execute("BEGIN TRANSACTION")
            cursor.execute("DELETE FROM themes")
            cursor.execute("DELETE FROM details")

            for t in cache["themes_charts"]:
                cursor.execute("INSERT INTO themes (type, name, path) VALUES (?, ?, ?)", ("charts", t["name"], t["path"]))
            for t in cache["themes_collections"]:
                cursor.execute("INSERT INTO themes (type, name, path) VALUES (?, ?, ?)", ("collections", t["name"], t["path"]))
            for t in cache["themes_artists"]:
                cursor.execute("INSERT INTO themes (type, name, path) VALUES (?, ?, ?)", ("artists", t["name"], t["path"]))

            for path, detail_data in cache["details"].items():
                cursor.execute("INSERT INTO details (path, data) VALUES (?, ?)", (path, json.dumps(detail_data)))

            cursor.execute("REPLACE INTO cache_meta (key, value) VALUES (?, ?)", ("last_updated", str(time.time())))
            conn.commit()
    except Exception as e:
        print(f"Error saving to DB: {e}")

def load_cache_from_db():
    global cache
    try:
        if not os.path.exists(DB_PATH): return False
        with sqlite3.connect(DB_PATH) as conn:
            cursor = conn.cursor()
            cursor.execute("SELECT name, path FROM themes WHERE type='charts'")
            cache["themes_charts"] = [{"name": r[0], "path": r[1]} for r in cursor.fetchall()]
            cursor.execute("SELECT name, path FROM themes WHERE type='collections'")
            cache["themes_collections"] = [{"name": r[0], "path": r[1]} for r in cursor.fetchall()]
            cursor.execute("SELECT name, path FROM themes WHERE type='artists'")
            cache["themes_artists"] = [{"name": r[0], "path": r[1]} for r in cursor.fetchall()]

            cursor.execute("SELECT path, data FROM details")
            cache["details"] = {r[0]: json.loads(r[1]) for r in cursor.fetchall()}

            cursor.execute("SELECT value FROM cache_meta WHERE key='last_updated'")
            row = cursor.fetchone()
            if row: cache["last_updated"] = float(row[0])
            return len(cache["themes_charts"]) > 0
    except Exception as e:
        print(f"Error loading from DB: {e}")
        return False

def get_songs_in_dir(directory, rel_path_prefix, root_dir_type):
    songs = []
    try:
        if os.path.exists(directory):
            # 파일 리스트 읽기 최적화
            for file in os.listdir(directory):
                if file.lower().endswith(('.mp3', '.m4a', '.flac')):
                    clean_name = os.path.splitext(file)[0]
                    artist = "Unknown Artist"
                    title = clean_name
                    if " - " in clean_name:
                        parts = clean_name.split(" - ", 1)
                        artist = parts[0].split(". ", 1)[-1] if ". " in parts[0] else parts[0]
                        title = parts[1]
                    songs.append({
                        "name": title,
                        "stream_url": f"{BASE_URL}/stream/{root_dir_type}/{urllib.parse.quote(rel_path_prefix)}/{urllib.parse.quote(file)}",
                        "artist": artist,
                        "albumName": os.path.basename(directory),
                        "parent_path": rel_path_prefix
                    })
            songs.sort(key=lambda x: x["name"])
    except Exception as e:
        print(f"Error reading songs: {e}")
    return songs

def scan_single_theme(root_dir, entry, root_type):
    """테마 하나를 정밀 스캔하는 함수 (멀티스레드용)"""
    full_path = os.path.join(root_dir, entry)
    theme_path = f"{root_type}/{entry}"
    theme_details = []

    # 1. 하위 폴더 스캔
    sub_entries = sorted(os.listdir(full_path))
    for sub_entry in sub_entries:
        sub_path = os.path.join(full_path, sub_entry)
        if os.path.isdir(sub_path):
            songs = get_songs_in_dir(sub_path, f"{entry}/{sub_entry}", root_type)
            if songs:
                theme_details.append({"category_name": sub_entry, "songs": songs})

    # 2. 루트 폴더 곡 스캔
    root_songs = get_songs_in_dir(full_path, entry, root_type)
    if root_songs:
        theme_details.insert(0, {"category_name": entry, "songs": root_songs})

    return {"name": entry, "path": theme_path}, theme_details

def scan_dir_parallel(root_dir, root_type):
    themes = []
    details = {}
    if not os.path.exists(root_dir): return themes, details

    entries = [e for e in os.listdir(root_dir) if os.path.isdir(os.path.join(root_dir, e))]

    # 멀티스레드로 동시 스캔 (최대 10개 스레드)
    with ThreadPoolExecutor(max_workers=10) as executor:
        results = list(executor.map(lambda e: scan_single_theme(root_dir, e, root_type), entries))

    for theme_info, theme_details in results:
        if theme_details:
            themes.append(theme_info)
            details[theme_info["path"]] = theme_details

    themes.sort(key=lambda x: x["name"])
    return themes, details

def scan_artists_optimized():
    """가수 목록은 초고속으로 확보하고, 샘플링된 30명만 딥스캔"""
    all_singers = []
    if os.path.exists(ARTIST_ROOT_DIR):
        for initial in sorted(os.listdir(ARTIST_ROOT_DIR)):
            initial_path = os.path.join(ARTIST_ROOT_DIR, initial)
            if os.path.isdir(initial_path):
                for singer in os.listdir(initial_path):
                    singer_path = os.path.join(initial_path, singer)
                    if os.path.isdir(singer_path):
                        all_singers.append({"name": singer, "initial": initial, "full_path": singer_path})

    if not all_singers: return [], {}

    sample_singers = random.sample(all_singers, min(len(all_singers), 30))
    themes = []
    details = {}

    # 샘플링된 가수들만 병렬로 딥스캔
    def scan_singer(s):
        theme_path = f"artists/{s['initial']}/{s['name']}"
        theme_details = []
        for album in sorted(os.listdir(s['full_path'])):
            album_path = os.path.join(s['full_path'], album)
            if os.path.isdir(album_path):
                songs = get_songs_in_dir(album_path, f"{s['initial']}/{s['name']}/{album}", "artists")
                if songs: theme_details.append({"category_name": album, "songs": songs})
        return {"name": s['name'], "path": theme_path}, theme_details

    with ThreadPoolExecutor(max_workers=10) as executor:
        results = list(executor.map(scan_singer, sample_singers))

    for theme_info, theme_details in results:
        themes.append(theme_info)
        details[theme_info["path"]] = theme_details

    return themes, details

def scan_music_library():
    global cache
    print("--- ⚡ Optimized Scanning Started ---")
    start_time = time.time()

    # 1. 차트와 모음을 병렬로 스캔
    with ThreadPoolExecutor(max_workers=2) as executor:
        chart_future = executor.submit(scan_dir_parallel, CHART_ROOT_DIR, "charts")
        collection_future = executor.submit(scan_dir_parallel, COLLECTION_ROOT_DIR, "collections")
        artist_future = executor.submit(scan_artists_optimized)

        charts, charts_details = chart_future.result()
        collections, collections_details = collection_future.result()
        artists, artists_details = artist_future.result()

    cache["themes_charts"] = charts
    cache["themes_collections"] = collections
    cache["themes_artists"] = artists
    cache["details"] = {**charts_details, **collections_details, **artists_details}
    cache["last_updated"] = time.time()

    save_cache_to_db()
    print(f"--- ✅ Scanning Finished! (Taken: {time.time() - start_time:.2f}s) ---")

@app.route('/api/themes', methods=['GET'])
def get_themes():
    return jsonify({
        "charts": cache["themes_charts"],
        "collections": cache["themes_collections"],
        "artists": cache["themes_artists"]
    })

@app.route('/api/theme-details/<path:theme_path>', methods=['GET'])
def get_theme_details(theme_path):
    decoded_path = urllib.parse.unquote(theme_path)
    return jsonify(cache["details"].get(decoded_path, []))

@app.route('/stream/<type>/<path:file_path>', methods=['GET'])
def stream_file(type, file_path):
    roots = {"charts": CHART_ROOT_DIR, "collections": COLLECTION_ROOT_DIR, "artists": ARTIST_ROOT_DIR}
    return send_from_directory(roots.get(type, CHART_ROOT_DIR), urllib.parse.unquote(file_path))

@app.route('/api/refresh', methods=['GET'])
def refresh_cache():
    Thread(target=scan_music_library).start()
    return jsonify({"status": "Refresh started"})

if __name__ == '__main__':
    init_db()
    if not load_cache_from_db():
        scan_music_library()
    else:
        # DB 로드 후 백그라운드 갱신
        Thread(target=scan_music_library).start()
    app.run(host='0.0.0.0', port=4444, debug=False)
