from flask import Flask, jsonify, send_from_directory, request
import os
import sqlite3
import json
from flask_cors import CORS
import urllib.parse
import time
import random
from threading import Thread, Lock
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

# --- 모니터링 상태 관리 ---
status_lock = Lock()
update_status = {
    "is_running": False,
    "is_complete": False,
    "current_task": "대기 중...",
    "progress_text": "0/0",
    "progress_percent": 0,
    "stats_text": "성공: 0 | 실패: 0",
    "complete_message": "",
    "logs": ["모니터가 시작되었습니다."]
}

MONITOR_PAGE_HTML = '''
<!DOCTYPE html>
<html lang="ko">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>실시간 인덱싱 모니터</title>
    <style>
        body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Oxygen, Ubuntu, Cantarell, 'Open Sans', 'Helvetica Neue', sans-serif; background-color: #f0f2f5; color: #333; margin: 0; padding: 20px; }
        .container { max-width: 1000px; margin: auto; background: #fff; border-radius: 8px; box-shadow: 0 2px 4px rgba(0,0,0,0.1); padding: 20px; }
        h1 { font-size: 24px; color: #1c1e21; border-bottom: 1px solid #ddd; padding-bottom: 10px; margin-top: 0; }
        .button-group { display: flex; flex-wrap: wrap; gap: 10px; margin-bottom: 20px; }
        .button { padding: 10px 15px; border: none; border-radius: 6px; font-weight: 600; cursor: pointer; display: inline-flex; align-items: center; gap: 8px; }
        .btn-primary { background-color: #1877f2; color: white; }
        .status-box { background-color: #f7f7f7; padding: 15px; border-radius: 6px; margin-bottom: 20px; }
        .progress-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 10px; }
        #task-name { font-size: 16px; font-weight: bold; }
        #stats { font-size: 14px; color: #606770; }
        .progress-bar-container { width: 100%; background-color: #e0e0e0; border-radius: 4px; overflow: hidden; height: 20px; }
        #progress-bar { width: 0%; height: 100%; background-color: #4caf50; transition: width 0.3s ease; text-align: center;}
        #progress-text { line-height: 20px; color: black; font-weight: 500; font-size: 12px; }
        .log-box { background-color: #1c1e21; color: #e0e0e0; font-family: 'SF Mono', 'Menlo', 'Monaco', monospace; font-size: 13px; height: 400px; overflow-y: auto; padding: 15px; border-radius: 6px; white-space: pre-wrap; word-wrap: break-word; }
    </style>
</head>
<body>
    <div class="container">
        <h1>🎵 음악 라이브러리 실시간 모니터</h1>
        <div class="button-group">
            <button class="button btn-primary" onclick="startScan()">🔄 전체 라이브러리 재스캔</button>
        </div>
        <div class="status-box">
            <div class="progress-header">
                <span id="task-name">대기 중...</span>
                <span id="stats">성공: 0 | 실패: 0</span>
            </div>
            <div class="progress-bar-container">
                 <div id="progress-bar"><span id="progress-text">0 / 0 (0%)</span></div>
            </div>
        </div>
        <div id="log-box" class="log-box">서버 로그가 여기에 표시됩니다...</div>
    </div>

    <script>
        const taskNameEl = document.getElementById('task-name');
        const statsEl = document.getElementById('stats');
        const progressBarEl = document.getElementById('progress-bar');
        const progressTextEl = document.getElementById('progress-text');
        const logBoxEl = document.getElementById('log-box');

        function startScan() {
            if (confirm('전체 라이브러리 재스캔을 시작하시겠습니까? 시간이 오래 걸릴 수 있습니다.')) {
                fetch('/api/indexing/start', { method: 'POST' })
                    .then(response => response.json())
                    .then(data => {
                        console.log(data.message);
                        updateMonitor();
                    });
            }
        }

        function updateMonitor() {
            fetch('/api/indexing/status')
                .then(response => response.json())
                .then(data => {
                    taskNameEl.textContent = data.is_running ? data.current_task : (data.is_complete ? "✅ " + data.complete_message : "대기 중...");
                    statsEl.textContent = data.stats_text;

                    const percent = data.progress_percent.toFixed(1);
                    progressBarEl.style.width = percent + '%';
                    progressTextEl.textContent = `${data.progress_text} (${percent}%)`;

                    if (data.is_running) {
                        progressBarEl.style.backgroundColor = '#4caf50';
                    } else if (data.is_complete) {
                        progressBarEl.style.backgroundColor = '#1877f2';
                    } else {
                        progressBarEl.style.backgroundColor = '#e0e0e0';
                    }

                    logBoxEl.innerHTML = data.logs.join('<br>');
                })
                .catch(error => {
                    console.error('Error fetching status:', error);
                    if (!logBoxEl.innerHTML.startsWith('모니터링 서버에 연결할 수 없습니다.')) {
                        logBoxEl.innerHTML = '모니터링 서버에 연결할 수 없습니다.<br>' + logBoxEl.innerHTML;
                    }
                });
        }

        setInterval(updateMonitor, 1500);
        window.onload = updateMonitor;
    </script>
</body>
</html>
'''
# -------------------------

def add_log(message, is_error=False):
    with status_lock:
        timestamp = time.strftime('%H:%M:%S')
        symbol = "⚠️" if is_error else "✅"
        log_entry = f"{timestamp} {symbol} {message}"
        update_status["logs"].insert(0, log_entry)
        if len(update_status["logs"]) > 200:
            update_status["logs"].pop()
    print(message, flush=True)

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

def find_artist_themes_recursively(root_path):
    artist_themes = []
    if not os.path.exists(root_path):
        return []

    dir_count = 0
    try:
        for dirpath, dirnames, filenames in os.walk(root_path):
            dir_count += 1
            if dir_count % 500 == 0:
                add_log(f"[Theme] 아티스트 폴더 스캔 중... {dir_count}개 확인")
                with status_lock:
                    update_status["current_task"] = f"아티스트 테마 찾는 중... ({dir_count}개 폴더 확인)"

            has_music = any(fname.lower().endswith(('.mp3', '.m4a', '.flac', '.dsf')) for fname in filenames)

            if has_music:
                artist_name = os.path.basename(dirpath)
                relative_path = os.path.relpath(dirpath, ARTIST_ROOT)
                artist_themes.append({
                    "name": artist_name,
                    "path": f"가수/{relative_path.replace(os.sep, '/')}"
                })
                dirnames.clear()
    except OSError as e:
        add_log(f"아티스트 폴더 스캔 중 오류 발생: {root_path}: {e}", is_error=True)

    return artist_themes

def scan_and_index_library():
    with status_lock:
        if update_status["is_running"]:
            add_log("이미 스캔 작업이 진행 중입니다.", is_error=True)
            return
        update_status.update({
            "is_running": True, "is_complete": False, "logs": [],
            "current_task": "스캔 준비 중...", "progress_text": "0/0",
            "progress_percent": 0, "stats_text": "성공: 0 | 실패: 0"
        })
    add_log("--- 🔄 전체 라이브러리 스캔을 시작합니다 ---")

    # 1단계: 테마 검색
    add_log("--- ⚡ 1단계: 테마 목록 생성 시작 ---")

    def get_subdirs(path):
        try: return [d.name for d in os.scandir(path) if d.is_dir()]
        except: return []

    charts = [{"name": d, "path": f"차트/{d}"} for d in sorted(get_subdirs(CHART_ROOT))]
    colls = [{"name": d, "path": f"모음/{d}"} for d in sorted(get_subdirs(COLLECTION_ROOT))]
    genres = [{"name": g, "path": f"장르/{g}"} for g in GENRE_ROOTS.keys()]
    add_log("[Theme] '차트', '모음', '장르' 테마 스캔 완료.")

    all_artist_themes = find_artist_themes_recursively(ARTIST_ROOT)

    if all_artist_themes:
        artist_themes = random.sample(all_artist_themes, min(len(all_artist_themes), 30))
        add_log(f"[Theme] '아티스트' 테마 스캔 완료. 총 {len(all_artist_themes)}명 발견 (30명 랜덤 표시).")
    else:
        artist_themes = []
        add_log("[Theme] '아티스트' 테마를 찾지 못했습니다.")

    global cache
    cache.update({"themes_charts": charts, "themes_collections": colls, "themes_artists": artist_themes, "themes_genres": genres, "last_updated": time.time()})
    add_log("--- ✅ 1단계: 테마 목록 생성 완료 ---")

    # 2단계: 전체 노래 인덱싱
    add_log("--- 🚀 2단계: 전체 노래 인덱싱 시작 ---")
    start_time = time.time()

    with status_lock: update_status["current_task"] = "전체 디렉토리 수집 중..."
    add_log("[Indexing] Collecting all directories to scan...")
    all_dirs = []
    search_roots = [ROOT_DIR] + list(GENRE_ROOTS.values())
    dir_count = 0
    for r_path in search_roots:
        try:
            for root, _, _ in os.walk(r_path):
                all_dirs.append(root)
                dir_count += 1
                if dir_count % 500 == 0:
                    with status_lock:
                        update_status["current_task"] = f"전체 디렉토리 수집 중... ({dir_count}개 발견)"
        except OSError as e:
            add_log(f"디렉토리 접근 오류: {r_path}: {e}", is_error=True)

    total_dirs = len(all_dirs)
    add_log(f"[Indexing] 총 {total_dirs}개의 디렉토리를 찾았습니다. 병렬 스캔을 시작합니다.")

    with status_lock: update_status["current_task"] = "음악 파일 스캔 중..."
    all_songs = []
    processed_count, success_count, failed_count = 0, 0, 0
    with ThreadPoolExecutor(max_workers=12) as executor:
        futures = {executor.submit(scan_folder_parallel, d): d for d in all_dirs}
        for future in as_completed(futures):
            processed_count += 1
            try:
                res = future.result()
                if res: all_songs.extend(res)
                success_count += 1
            except Exception as e:
                failed_count += 1
                add_log(f"디렉토리 스캔 오류: {futures[future]}: {e}", is_error=True)

            if processed_count % 200 == 0 or processed_count == total_dirs:
                percent = (processed_count / total_dirs) * 100 if total_dirs > 0 else 0
                with status_lock:
                    update_status["progress_text"] = f"{processed_count}/{total_dirs}"
                    update_status["progress_percent"] = percent
                    update_status["stats_text"] = f"성공: {success_count} | 실패: {failed_count}"
                    update_status["current_task"] = "음악 파일 스캔 중..."

    scan_time = time.time() - start_time
    add_log(f"파일 스캔 완료. 총 {len(all_songs)}곡 발견. (소요 시간: {scan_time:.2f}초)")

    db_start_time = time.time()
    with sqlite3.connect(DB_PATH) as conn:
        cursor = conn.cursor()

        with status_lock: update_status["current_task"] = "DB: 메타데이터 백업 중..."
        add_log("[DB] Backing up old metadata...")
        cursor.execute("CREATE TEMP TABLE old_meta AS SELECT artist, album, meta_poster FROM global_songs WHERE meta_poster IS NOT NULL")
        posters_in_backup = cursor.execute("SELECT count(*) FROM old_meta").fetchone()[0]

        with status_lock: update_status["current_task"] = "DB: 기존 데이터 삭제 중..."
        add_log("[DB] Clearing old song data...")
        cursor.execute("DELETE FROM global_songs")

        with status_lock: update_status["current_task"] = f"DB: {len(all_songs)}곡 저장 중..."
        add_log(f"[DB] Inserting {len(all_songs)} new songs...")
        cursor.executemany("INSERT INTO global_songs (name, artist, album, stream_url, parent_path) VALUES (?, ?, ?, ?, ?)", all_songs)

        with status_lock: update_status["current_task"] = "DB: 메타데이터 복원 중..."
        add_log("[DB] Restoring metadata...")
        cursor.execute("UPDATE global_songs SET meta_poster = (SELECT meta_poster FROM old_meta WHERE old_meta.artist = global_songs.artist AND old_meta.album = global_songs.album)")
        posters_restored = cursor.execute("SELECT count(*) FROM global_songs WHERE meta_poster IS NOT NULL").fetchone()[0]

        cursor.execute("DROP TABLE old_meta")
        conn.commit()

    db_time = time.time() - db_start_time
    total_time = time.time() - start_time

    final_message = f"총 {len(all_songs)}곡 인덱싱 완료 ({total_time:.2f}초 소요)"
    add_log("--- ✅🎉 모든 스캔 및 인덱싱 작업이 완료되었습니다! ---")
    add_log(f"📊 총 소요 시간: {total_time:.2f}s (테마/파일 스캔: {scan_time:.2f}s, DB: {db_time:.2f}s)")
    add_log(f"📂 디렉토리: {success_count}개 스캔, {failed_count}개 실패")
    add_log(f"🖼️ 포스터: {posters_restored}개 복원 (백업: {posters_in_backup}개)")

    with status_lock:
        update_status["is_running"] = False
        update_status["is_complete"] = True
        update_status["complete_message"] = final_message
        update_status["current_task"] = final_message

@app.route('/monitor')
def monitor_page():
    return MONITOR_PAGE_HTML

@app.route('/api/indexing/start', methods=['POST'])
def start_indexing():
    with status_lock:
        if update_status['is_running']:
            return jsonify({"message": "이미 인덱싱 작업이 진행 중입니다."}), 409

    thread = Thread(target=scan_and_index_library)
    thread.daemon = True
    thread.start()
    return jsonify({"message": "라이브러리 전체 스캔 및 인덱싱 작업을 시작했습니다."})

@app.route('/api/indexing/status', methods=['GET'])
def get_indexing_status():
    with status_lock:
        return jsonify(update_status)

@app.route('/api/top100', methods=['GET'])
def get_top100():
    try:
        if not os.path.exists(WEEKLY_CHART_PATH): return jsonify([])
        subdirs = sorted([d for d in os.listdir(WEEKLY_CHART_PATH) if os.path.isdir(os.path.join(WEEKLY_CHART_PATH, d))])
        if not subdirs: return jsonify([])

        latest_folder = subdirs[-1]
        latest_dir = os.path.join(WEEKLY_CHART_PATH, latest_folder)
        rel_path = os.path.relpath(latest_dir, MUSIC_BASE)

        with sqlite3.connect(DB_PATH) as conn:
            conn.row_factory = sqlite3.Row
            cursor = conn.cursor()
            cursor.execute("SELECT * FROM global_songs WHERE parent_path = ? ORDER BY stream_url ASC", (rel_path,))
            rows = cursor.fetchall()
            if rows: return jsonify([dict(row) for row in rows])

        print(f"[*] Direct scanning for Top 100: {latest_folder}")
        songs = [get_song_info(e.name, latest_dir) for e in sorted(os.scandir(latest_dir), key=lambda x: x.name) if e.is_file() and e.name.lower().endswith(('.mp3', '.m4a', '.flac', '.dsf'))]
        return jsonify([{"name": s[0], "artist": s[1], "album": s[2], "stream_url": s[3], "parent_path": s[4], "meta_poster": None} for s in songs])
    except Exception as e:
        print(f"Top100 Error: {e}")
        return jsonify([])

@app.route('/api/themes', methods=['GET'])
def get_themes():
    return jsonify(cache)

@app.route('/api/theme-details/<path:theme_path>', methods=['GET'])
def get_theme_details(theme_path):
    decoded_path = urllib.parse.unquote(theme_path).replace("장르/", "")
    with sqlite3.connect(DB_PATH) as conn:
        conn.row_factory = sqlite3.Row
        cursor = conn.cursor()
        cursor.execute("SELECT * FROM global_songs WHERE parent_path LIKE ? ORDER BY parent_path, stream_url ASC", (f"{decoded_path}%"))
        rows = cursor.fetchall()
        groups = {}
        for row in rows:
            cat = row['parent_path'].split('/')[-1]
            if cat not in groups: groups[cat] = []
            groups[cat].append(dict(row))
        return jsonify([{"category_name": k, "songs": v} for k, v in groups.items()])

@app.route('/stream/<path:file_path>', methods=['GET'])
def stream_file(file_path):
    return send_from_directory(MUSIC_BASE, urllib.parse.unquote(file_path))

if __name__ == '__main__':
    init_db()
    app.run(host='0.0.0.0', port=4444, debug=False)
