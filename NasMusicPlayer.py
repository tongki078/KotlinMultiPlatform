from flask import Flask, jsonify, send_from_directory, request, render_template_string
import os, sqlite3, json, urllib.parse, time, random, requests
from flask_cors import CORS
from threading import Thread
from concurrent.futures import ThreadPoolExecutor, as_completed

app = Flask(__name__)
CORS(app)

# ==========================================
# 1. 경로 및 시스템 설정
# ==========================================
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
DB_PATH = "music_cache_v3.db"

# 상태 전역 변수
up_st = {"is_running": False, "total": 0, "current": 0, "success": 0, "fail": 0, "last_log": "대기 중..."}
idx_st = {"is_running": False, "total_dirs": 0, "processed_dirs": 0, "songs_found": 0, "last_log": "대기 중..."}
cache = {"charts": [], "collections": [], "artists": [], "genres": []}

# ==========================================
# 2. 모니터 대시보드 UI (HTML)
# ==========================================
MONITOR_HTML = """
<!DOCTYPE html>
<html>
<head><meta charset="utf-8"><title>NasMusic Monitor</title>
<style>
    body{font-family:sans-serif;padding:20px;background:#f0f2f5;color:#333}
    .container{max-width:900px;margin:0 auto}
    .box{background:white;padding:25px;border-radius:12px;margin-bottom:20px;box-shadow:0 4px 10px rgba(0,0,0,0.05)}
    .bar-bg{width:100%;background:#eee;height:24px;border-radius:12px;overflow:hidden;margin:15px 0}
    .bar{height:100%;background:#4caf50;width:0%;transition:0.4s}
    .idx{background:#1a73e8}
    .log{background:#1e1e1e;color:#a9dc76;padding:15px;height:250px;overflow-y:auto;font-family:monospace;font-size:13px;border-radius:8px}
    button{padding:10px 20px;cursor:pointer;background:#1a73e8;color:white;border:none;border-radius:6px;font-weight:bold}
    .stats{display:flex;justify-content:space-between;font-weight:bold;font-size:14px}
</style>
</head><body><div class="container">
    <h1>🎵 NasMusic 시스템 센터</h1>
    <div class="box">
        <h2>📁 인덱싱 상태 (NAS 파일 수집)</h2>
        <div class="bar-bg"><div id="ib" class="bar idx"></div></div>
        <div class="stats"><span id="ii">대기 중</span><span id="ic">곡 발견: 0</span></div>
        <button style="margin-top:10px" onclick="fetch('/api/refresh')">🚀 강제 전체 재스캔</button>
    </div>
    <div class="box">
        <h2>🖼️ 메타데이터 업데이트 (ManiaDB/Deezer)</h2>
        <div class="bar-bg"><div id="mb" class="bar"></div></div>
        <div class="stats"><span id="mi">0 / 0 (0%)</span><span>성공: <span id="ms" style="color:#4caf50">0</span></span></div>
        <button style="margin-top:10px;background:#34a853" onclick="fetch('/api/metadata/start')">✨ 엔진 가동 시작</button>
    </div>
    <div class="box"><h3>📝 실시간 작업 로그</h3><div id="lg" class="log"></div></div>
</div>
<script>
    let lastIdxLog = "";
    let lastMetaLog = "";
    function update(){
        fetch('/api/indexing/status').then(r=>r.json()).then(d=>{
            let p = (d.total_dirs>0 ? d.processed_dirs/d.total_dirs*100 : 0).toFixed(1);
            document.getElementById('ib').style.width = p+'%';
            document.getElementById('ii').innerText = d.is_running ? `진행중: ${d.processed_dirs}/${d.total_dirs} 폴더` : '인덱싱 완료됨';
            document.getElementById('ic').innerText = `총 발견 곡: ${d.songs_found.toLocaleString()}`;
            if(d.is_running && d.last_log !== lastIdxLog){
                addLog("INDEX", d.last_log);
                lastIdxLog = d.last_log;
            }
        });
        fetch('/api/metadata/status').then(r=>r.json()).then(d=>{
            let p = (d.total>0 ? d.current/d.total*100 : 0).toFixed(1);
            document.getElementById('mb').style.width = p+'%';
            document.getElementById('mi').innerText = `${d.current} / ${d.total} (${p}%)`;
            document.getElementById('ms').innerText = d.success;
            if(d.is_running && d.last_log !== lastMetaLog){
                addLog("META", d.last_log);
                lastMetaLog = d.last_log;
            }
        });
    }
    function addLog(tag, msg) {
        const logBox = document.getElementById('lg');
        logBox.innerHTML += `<div>[${new Date().toLocaleTimeString()}] [${tag}] ${msg}</div>`;
        logBox.scrollTop = logBox.scrollHeight;
    }
    setInterval(update, 1000);
</script></body></html>
"""

# ==========================================
# 3. 핵심 로직: DB, 스캔, 인덱싱
# ==========================================
def init_db():
    with sqlite3.connect(DB_PATH) as conn:
        c = conn.cursor()
        c.execute('CREATE TABLE IF NOT EXISTS themes (type TEXT, name TEXT, path TEXT)')
        c.execute('''
            CREATE TABLE IF NOT EXISTS global_songs (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT, artist TEXT, albumName TEXT,
                stream_url TEXT, parent_path TEXT, meta_poster TEXT
            )
        ''')
        c.execute('CREATE INDEX IF NOT EXISTS idx_path ON global_songs(parent_path)')
        conn.commit()

def load_cache():
    global cache
    try:
        with sqlite3.connect(DB_PATH) as conn:
            conn.row_factory = sqlite3.Row
            for t in ["charts", "collections", "artists", "genres"]:
                rows = conn.execute("SELECT name, path FROM themes WHERE type=?", (t,)).fetchall()
                cache[t] = [dict(r) for r in rows]
    except: pass

def get_info(f, d):
    nm = os.path.splitext(f)[0]
    art, tit = ("Unknown Artist", nm)
    if " - " in nm:
        parts = nm.split(" - ", 1)
        art = parts[0].split(". ")[-1] if ". " in parts[0] else parts[0]
        tit = parts[1]
    rel = os.path.relpath(d, MUSIC_BASE)
    return (tit, art, os.path.basename(d), f"{BASE_URL}/stream/{urllib.parse.quote(rel)}/{urllib.parse.quote(f)}", rel)

def scan_folder_parallel(path):
    songs = []
    try:
        with os.scandir(path) as it:
            for entry in it:
                if entry.is_file() and entry.name.lower().endswith(('.mp3', '.m4a', '.flac', '.dsf')):
                    songs.append(get_info(entry.name, path))
    except: pass
    return songs

def scan_all_songs():
    global idx_st
    if idx_st["is_running"]: return
    idx_st.update({"is_running": True, "songs_found": 0, "processed_dirs": 0, "last_log": "전체 인덱싱 시작"})

    all_d = []
    search_roots = [ROOT_DIR] + list(GENRE_ROOTS.values())
    for r in search_roots:
        if os.path.exists(r):
            for root, _, _ in os.walk(r): all_d.append(root)

    idx_st["total_dirs"] = len(all_d)
    all_s = []
    with ThreadPoolExecutor(max_workers=12) as exe:
        futs = {exe.submit(scan_folder_parallel, d): d for d in all_d}
        for f in as_completed(futs):
            res = f.result()
            all_s.extend(res)
            idx_st["processed_dirs"] += 1
            idx_st["songs_found"] += len(res)
            if idx_st["processed_dirs"] % 200 == 0:
                idx_st["last_log"] = f"{idx_st['processed_dirs']}개 폴더 스캔 중..."

    with sqlite3.connect(DB_PATH) as conn:
        cursor = conn.cursor()
        cursor.execute("CREATE TEMP TABLE old_meta AS SELECT artist, albumName, meta_poster FROM global_songs WHERE meta_poster IS NOT NULL")
        cursor.execute("DELETE FROM global_songs")
        cursor.executemany("INSERT INTO global_songs (name, artist, albumName, stream_url, parent_path) VALUES (?,?,?,?,?)", all_s)
        cursor.execute("UPDATE global_songs SET meta_poster = (SELECT meta_poster FROM old_meta WHERE old_meta.artist = global_songs.artist AND old_meta.albumName = global_songs.albumName)")
        conn.commit()
    idx_st.update({"is_running": False, "last_log": "인덱싱 완료"})

def rebuild_library():
    global cache
    print("[*] Rebuilding Library Themes...")
    def sub(p): return [d.name for d in os.scandir(p) if d.is_dir()] if os.path.exists(p) else []

    c_list = [{"name": d, "path": f"국내/차트/{d}"} for d in sorted(sub(CHART_ROOT))]
    m_list = [{"name": d, "path": f"국내/모음/{d}"} for d in sorted(sub(COLLECTION_ROOT))]
    g_list = [{"name": g, "path": g} for g in GENRE_ROOTS.keys()]

    a_list = []
    if os.path.exists(ARTIST_ROOT):
        all_a = [{"name": s.name, "path": f"국내/가수/{i.name}/{s.name}"} for i in os.scandir(ARTIST_ROOT) if i.is_dir() for s in os.scandir(i.path) if s.is_dir()]
        if all_a: a_list = random.sample(all_a, min(len(all_a), 30))

    cache.update({"charts": c_list, "collections": m_list, "artists": a_list, "genres": g_list})

    with sqlite3.connect(DB_PATH) as conn:
        conn.execute("DELETE FROM themes")
        for t, l in [('charts', c_list), ('collections', m_list), ('artists', a_list), ('genres', g_list)]:
            conn.executemany("INSERT INTO themes VALUES (?,?,?)", [(t, i['name'], i['path']) for i in l])
        conn.commit()
    scan_all_songs()

# ==========================================
# 4. 메타데이터 엔진
# ==========================================
def fetch_maniadb_metadata(artist, album):
    try:
        url = f"http://www.maniadb.com/api/search/{urllib.parse.quote(f'{artist} {album}')}/?sr=album&display=1&key=example&v=0.5"
        res = requests.get(url, timeout=5)
        if res.status_code == 200 and "<image>" in res.text:
            poster = res.text.split("<![CDATA[")[1].split("]]>")[0].replace("/s/", "/l/")
            return {"poster": poster}
    except: pass
    return None

def fetch_deezer_metadata(artist, album):
    try:
        res = requests.get(f"https://api.deezer.com/search?q={urllib.parse.quote(f'{artist} {album}')}&limit=1", timeout=5).json()
        if res.get("data"):
            return {"poster": res["data"][0]['album']['cover_xl']}
    except: pass
    return None

def start_metadata_update_thread():
    global up_st
    if up_st["is_running"]: return
    up_st["is_running"] = True
    try:
        with sqlite3.connect(DB_PATH) as conn:
            cursor = conn.cursor()
            cursor.execute("SELECT DISTINCT artist, albumName FROM global_songs WHERE meta_poster IS NULL OR meta_poster = ''")
            targets = cursor.fetchall()
            up_st.update({"total": len(targets), "current": 0, "success": 0, "fail": 0})
            for art, alb in targets:
                if not up_st["is_running"]: break
                up_st["current"] += 1
                up_st["last_log"] = f"찾는 중: {art} - {alb}"
                meta = fetch_maniadb_metadata(art, alb) or fetch_deezer_metadata(art, alb)
                if meta and meta.get("poster"):
                    cursor.execute("UPDATE global_songs SET meta_poster = ? WHERE artist = ? AND albumName = ?", (meta["poster"], art, alb))
                    up_st["success"] += 1
                    conn.commit()
                else:
                    up_st["fail"] += 1
                time.sleep(0.3)
    finally:
        up_st.update({"is_running": False, "last_log": "작업 완료"})

# ==========================================
# 5. API 엔드포인트
# ==========================================
@app.route('/monitor')
def render_monitor(): return render_template_string(MONITOR_HTML)

@app.route('/api/indexing/status')
def get_idx(): return jsonify(idx_st)

@app.route('/api/metadata/status')
def get_meta(): return jsonify(up_st)

@app.route('/api/metadata/start')
def start_meta():
    Thread(target=start_metadata_update_thread).start()
    return jsonify({"status": "ok"})

@app.route('/api/search')
def search():
    q = f"%{request.args.get('q', '')}%"
    with sqlite3.connect(DB_PATH) as conn:
        conn.row_factory = sqlite3.Row
        rows = conn.execute('SELECT name, artist, albumName, stream_url, parent_path, meta_poster FROM global_songs WHERE name LIKE ? OR artist LIKE ? OR albumName LIKE ? LIMIT 100', (q,q,q)).fetchall()
        return jsonify([dict(r) for r in rows])

@app.route('/api/themes')
def get_themes():
    return jsonify(cache)

@app.route('/api/theme-details/<path:tp>')
def get_details(tp):
    p = urllib.parse.unquote(tp)
    with sqlite3.connect(DB_PATH) as conn:
        conn.row_factory = sqlite3.Row
        rows = conn.execute("SELECT * FROM global_songs WHERE parent_path LIKE ? ORDER BY parent_path, stream_url ASC", (f"{p}%",)).fetchall()
        groups = {}
        for r in rows:
            cat = r['parent_path'].split('/')[-1]
            if cat not in groups: groups[cat] = []
            groups[cat].append(dict(r))
        return jsonify([{"category_name": k, "songs": v} for k, v in groups.items()])

@app.route('/api/top100')
def get_top():
    try:
        sub = sorted([d for d in os.listdir(WEEKLY_CHART_PATH) if os.path.isdir(os.path.join(WEEKLY_CHART_PATH, d))])
        if not sub: return jsonify([])
        p = os.path.relpath(os.path.join(WEEKLY_CHART_PATH, sub[-1]), MUSIC_BASE)
        with sqlite3.connect(DB_PATH) as conn:
            conn.row_factory = sqlite3.Row
            rows = conn.execute("SELECT * FROM global_songs WHERE parent_path=? ORDER BY stream_url ASC", (p,)).fetchall()
            if rows: return jsonify([dict(r) for r in rows])
        # Fallback 스캔
        path = os.path.join(MUSIC_BASE, p)
        entries = sorted([e for e in os.scandir(path) if e.is_file() and e.name.lower().endswith(('.mp3', '.m4a', '.flac'))], key=lambda x: x.name)
        songs = []
        for e in entries:
            res = get_info(e.name, path)
            songs.append({"name": res[0], "artist": res[1], "albumName": res[2], "stream_url": res[3], "parent_path": res[4], "meta_poster": None})
        return jsonify(songs)
    except: return jsonify([])

@app.route('/api/refresh')
def refresh():
    Thread(target=rebuild_library).start()
    return jsonify({"status": "started"})

@app.route('/stream/<path:fp>')
def stream(fp): return send_from_directory(MUSIC_BASE, urllib.parse.unquote(fp))

if __name__ == '__main__':
    init_db()
    load_cache()
    with sqlite3.connect(DB_PATH) as db_conn:
        song_count = db_conn.execute("SELECT count(*) FROM global_songs").fetchone()[0]
        if song_count == 0:
            Thread(target=rebuild_library).start()
        else:
            print(f"[*] Server Ready. {song_count} songs loaded from cache.")
    app.run(host='0.0.0.0', port=4444, debug=False)
