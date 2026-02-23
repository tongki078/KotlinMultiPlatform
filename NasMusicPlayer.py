from flask import Flask, jsonify, send_from_directory, request
import os
import sqlite3
import json
from flask_cors import CORS
import urllib.parse
import time
import random
from threading import Thread
import xml.etree.ElementTree as ET
import requests
from concurrent.futures import ThreadPoolExecutor, as_completed

app = Flask(__name__)
CORS(app)

# 경로 설정
MUSIC_BASE = "/volume2/video/GDS3/GDRIVE/MUSIC"
ROOT_DIR = os.path.join(MUSIC_BASE, "국내")
CHART_ROOT = os.path.join(ROOT_DIR, "차트")
WEEKLY_CHART_PATH = os.path.join(CHART_ROOT, "멜론 주간 차트")
COLLECTION_ROOT = os.path.join(ROOT_DIR, "모음")
ARTIST_ROOT = os.path.join(ROOT_DIR, "가수")

GENRE_ROOTS = {
    "외국": os.path.join(MUSIC_BASE, "외국"),
    "일본": os.path.join(MUSIC_BASE, "일본"),
    "클래식": os.path.join(MUSIC_BASE, "클래식"),
    "DSD": os.path.join(MUSIC_BASE, "DSD"),
    "OST": os.path.join(MUSIC_BASE, "OST")
}

BASE_URL = "http://192.168.0.2:4444"
DB_PATH = "music_cache_v2.db"

update_status = {"is_running": False, "total": 0, "current": 0, "success": 0, "fail": 0, "last_log": "대기 중..."}
cache = {"themes_charts": [], "themes_collections": [], "themes_artists": [], "themes_genres": [], "last_updated": 0}

def init_db():
    with sqlite3.connect(DB_PATH) as conn:
        cursor = conn.cursor()
        cursor.execute('CREATE TABLE IF NOT EXISTS cache_meta (key TEXT PRIMARY KEY, value TEXT)')
        cursor.execute('CREATE TABLE IF NOT EXISTS themes (type TEXT, name TEXT, path TEXT)')
        cursor.execute('''
            CREATE TABLE IF NOT EXISTS global_songs (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT, artist TEXT, album TEXT,
                stream_url TEXT, parent_path TEXT,
                meta_poster TEXT, meta_year TEXT
            )
        ''')
        cursor.execute('CREATE INDEX IF NOT EXISTS idx_song_name ON global_songs(name)')
        cursor.execute('CREATE INDEX IF NOT EXISTS idx_song_artist ON global_songs(artist)')
        cursor.execute('CREATE INDEX IF NOT EXISTS idx_song_parent ON global_songs(parent_path)')
        conn.commit()

def get_song_info(file_name, directory):
    clean_name = os.path.splitext(file_name)[0]
    artist, title = "Unknown Artist", clean_name
    if " - " in clean_name:
        parts = clean_name.split(" - ", 1)
        artist = parts[0].split(". ", 1)[-1] if ". " in parts[0] else parts[0]
        title = parts[1]
    rel_path = os.path.relpath(directory, MUSIC_BASE)
    return (title, artist, os.path.basename(directory), f"{BASE_URL}/stream/{urllib.parse.quote(rel_path)}/{urllib.parse.quote(file_name)}", rel_path)

def scan_folder_parallel(path):
    songs = []
    try:
        with os.scandir(path) as it:
            for entry in it:
                if entry.is_file() and entry.name.lower().endswith(('.mp3', '.m4a', '.flac', '.dsf')):
                    songs.append(get_song_info(entry.name, path))
    except: pass
    return songs

def scan_all_songs_to_db():
    """전체 인덱싱: 12개 스레드로 초광속 스캔 및 진행 로그 출력"""
    print("--- 🚀 Global Indexing Started ---")
    start_time = time.time()
    all_dirs = []
    # 모든 루트 경로 합치기
    search_roots = [ROOT_DIR] + list(GENRE_ROOTS.values())
    for r_path in search_roots:
        for root, dirs, files in os.walk(r_path):
            all_dirs.append(root)

    total_dirs = len(all_dirs)
    print(f"[*] Total directories to scan: {total_dirs}")

    all_songs = []
    processed_count = 0
    with ThreadPoolExecutor(max_workers=12) as executor:
        futures = {executor.submit(scan_folder_parallel, d): d for d in all_dirs}
        for future in as_completed(futures):
            res = future.result()
            all_songs.extend(res)
            processed_count += 1
            if processed_count % 100 == 0 or processed_count == total_dirs:
                print(f"[Indexing] {processed_count}/{total_dirs} directories scanned ({len(all_songs)} songs found)...")

    print("[*] Updating Database...")
    with sqlite3.connect(DB_PATH) as conn:
        cursor = conn.cursor()
        cursor.execute("CREATE TEMP TABLE old_meta AS SELECT artist, album, meta_poster FROM global_songs WHERE meta_poster IS NOT NULL")
        cursor.execute("DELETE FROM global_songs")
        cursor.executemany("INSERT INTO global_songs (name, artist, album, stream_url, parent_path) VALUES (?, ?, ?, ?, ?)", all_songs)
        cursor.execute("UPDATE global_songs SET meta_poster = (SELECT meta_poster FROM old_meta WHERE old_meta.artist = global_songs.artist AND old_meta.album = global_songs.album)")
        conn.commit()
    print(f"--- ✅ Indexing Finished! ({len(all_songs)} songs, {time.time() - start_time:.2f}s) ---")

def scan_music_library():
    """홈 화면용 테마 구조만 번개처럼 스캔"""
    global cache
    print("--- ⚡ Quick Theme Discovery Started ---")

    def get_subdirs(path):
        try: return [d.name for d in os.scandir(path) if d.is_dir()]
        except: return []

    # 1. 차트/모음 (폴더 이름만 수집)
    charts = [{"name": d, "path": f"차트/{d}"} for d in sorted(get_subdirs(CHART_ROOT))]
    colls = [{"name": d, "path": f"모음/{d}"} for d in sorted(get_subdirs(COLLECTION_ROOT))]

    # 2. 장르 (사전 정의된 경로)
    genres = [{"name": g, "path": f"장르/{g}"} for g in GENRE_ROOTS.keys()]

    # 3. 가수 (초성 폴더 안의 가수 이름만)
    artist_themes = []
    if os.path.exists(ARTIST_ROOT):
        all_singers = []
        for ini in os.scandir(ARTIST_ROOT):
            if ini.is_dir():
                for s in os.scandir(ini.path):
                    if s.is_dir(): all_singers.append({"name": s.name, "path": f"가수/{ini.name}/{s.name}"})
        if all_singers:
            artist_themes = random.sample(all_singers, min(len(all_singers), 30))

    cache.update({
        "themes_charts": charts, "themes_collections": colls,
        "themes_artists": artist_themes, "themes_genres": genres,
        "last_updated": time.time()
    })

    # 테마 수집이 끝났으므로 전체 인덱싱 시작
    scan_all_songs_to_db()

@app.route('/api/theme-details/<path:theme_path>', methods=['GET'])
def get_theme_details(theme_path):
    """DB에서 해당 경로의 곡들을 즉시 찾아 그룹화하여 반환 (매우 빠름)"""
    decoded_path = urllib.parse.unquote(theme_path)

    # 장르의 경우 '장르/외국' 형태이므로 실제 경로는 '외국' 임
    search_path = decoded_path.replace("장르/", "")

    with sqlite3.connect(DB_PATH) as conn:
        conn.row_factory = sqlite3.Row
        cursor = conn.cursor()
        # 해당 경로로 시작하는 모든 곡 가져오기
        cursor.execute("SELECT * FROM global_songs WHERE parent_path LIKE ? ORDER BY parent_path, name", (f"{search_path}%",))
        rows = cursor.fetchall()

        # 카테고리별(부모 폴더별) 그룹화
        groups = {}
        for row in rows:
            cat = row['parent_path'].split('/')[-1]
            if cat not in groups: groups[cat] = []
            groups[cat].append(dict(row))

        result = [{"category_name": k, "songs": v} for k, v in groups.items()]
        return jsonify(result)

@app.route('/api/themes', methods=['GET'])
def get_themes():
    return jsonify({
        "charts": cache["themes_charts"], "collections": cache["themes_collections"],
        "artists": cache["themes_artists"], "genres": cache["themes_genres"]
    })

@app.route('/api/top100', methods=['GET'])
def get_top100():
    try:
        subdirs = sorted([d for d in os.listdir(WEEKLY_CHART_PATH) if os.path.isdir(os.path.join(WEEKLY_CHART_PATH, d))])
        if not subdirs: return jsonify([])
        latest_folder = subdirs[-1]
        rel_path = os.path.relpath(os.path.join(WEEKLY_CHART_PATH, latest_folder), MUSIC_BASE)

        with sqlite3.connect(DB_PATH) as conn:
            conn.row_factory = sqlite3.Row
            cursor = conn.cursor()
            cursor.execute("SELECT * FROM global_songs WHERE parent_path = ? ORDER BY name", (rel_path,))
            return jsonify([dict(row) for row in cursor.fetchall()])
    except: return jsonify([])

@app.route('/stream/<path:file_path>', methods=['GET'])
def stream_file(file_path):
    return send_from_directory(MUSIC_BASE, urllib.parse.unquote(file_path))

# ... 메타데이터 API 생략 (기존과 동일) ...

if __name__ == '__main__':
    init_db()
    # 서버 실행 시 캐시 로드
    with sqlite3.connect(DB_PATH) as conn:
        cursor = conn.cursor()
        # 간단한 로드 로직 (필요시 보강)
        pass

    Thread(target=scan_music_library).start()
    app.run(host='0.0.0.0', port=4444, debug=False)
