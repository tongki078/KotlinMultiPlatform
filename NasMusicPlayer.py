from flask import Flask, jsonify, send_from_directory, request, render_template_string
import os, sqlite3, json, urllib.parse, time, random, requests, subprocess, shutil
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

# [중요] DB 위치를 시스템 파티션(/root)에서 쓰기 가능한 데이터 볼륨(/volume2)으로 변경
OLD_DB_PATH = "music_cache_v3.db" # 현재(root) 위치
WRITEABLE_DIR = "/volume2/video" # 쓰기 권한이 확실한 8TB 볼륨 루트
DB_PATH = os.path.join(WRITEABLE_DIR, "music_cache_v3.db") # 안전한(8TB) 위치

# 5일간의 노력이 담긴 DB 파일을 안전한 곳으로 자동 대피
if os.path.exists(OLD_DB_PATH) and not os.path.exists(DB_PATH):
    print(f"[*] 5일간의 데이터를 쓰기 가능한 {WRITEABLE_DIR}로 이동 중... ({OLD_DB_PATH} -> {DB_PATH})")
    try:
        shutil.move(OLD_DB_PATH, DB_PATH)
        print("[*] 이동 완료! 이제 용량 걱정 없이 인덱싱을 이어갑니다.")
    except Exception as e:
        print(f"[!] 이동 실패: {e}. 수동으로 파일을 {DB_PATH}로 옮겨주세요.")

# 상태 전역 변수
up_st = {"is_running": False, "total": 0, "current": 0, "success": 0, "fail": 0, "last_log": "대기 중..."}
idx_st = {
    "is_running": False,
    "total_dirs": 0,
    "processed_dirs": 0,
    "songs_found": 0,
    "last_log": "대기 중...",
    "start_time": 0,
    "speed": 0,
    "eta": "계산 중..."
}
cache = {"charts": [], "collections": [], "artists": [], "genres": []}

# ==========================================
# 2. 모니터 대시보드 UI (HTML/CSS/JS)
# ==========================================
MONITOR_HTML = '''
<!DOCTYPE html>
<html>
<head>
    <meta charset="utf-8">
    <title>NasMusic Pro Monitor</title>
    <style>
        :root { --bg: #0f172a; --card: #1e293b; --text: #f8fafc; --accent: #38bdf8; --success: #22c55e; --warning: #f59e0b; }
        body { font-family: 'Pretendard', sans-serif; background: var(--bg); color: var(--text); margin: 0; padding: 20px; }
        .dashboard { max-width: 1200px; margin: 0 auto; display: grid; grid-template-columns: 2fr 1fr; gap: 20px; }
        .box { background: var(--card); padding: 20px; border-radius: 16px; box-shadow: 0 10px 25px rgba(0,0,0,0.2); }
        .full-width { grid-column: 1 / -1; }
        h1, h2, h3 { margin-top: 0; color: var(--accent); }

        .stat-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(150px, 1fr)); gap: 15px; margin-bottom: 20px; }
        .stat-card { background: rgba(255,255,255,0.05); padding: 15px; border-radius: 12px; text-align: center; }
        .stat-value { font-size: 1.5rem; font-weight: bold; display: block; }
        .stat-label { font-size: 0.8rem; opacity: 0.7; }

        .progress-container { margin: 20px 0; }
        .bar-bg { width: 100%; background: #334155; height: 12px; border-radius: 6px; overflow: hidden; }
        .bar { height: 100%; background: linear-gradient(90deg, #38bdf8, #818cf8); width: 0%; transition: width 0.5s ease; }

        .log-container { background: #000; color: #10b981; padding: 15px; height: 400px; overflow-y: auto; font-family: 'Fira Code', monospace; font-size: 13px; border-radius: 8px; border: 1px solid #334155; }
        .log-entry { margin-bottom: 4px; border-bottom: 1px solid rgba(255,255,255,0.05); padding-bottom: 2px; }
        .timestamp { color: #64748b; margin-right: 8px; }

        button { background: var(--accent); color: white; border: none; padding: 12px 24px; border-radius: 8px; font-weight: bold; cursor: pointer; transition: 0.2s; }
        button:hover { filter: brightness(1.1); transform: translateY(-2px); }
        button.secondary { background: #475569; }

        .badge { padding: 4px 8px; border-radius: 4px; font-size: 11px; font-weight: bold; text-transform: uppercase; }
        .badge-running { background: var(--success); color: white; }
        .badge-idle { background: #475569; color: white; }
    </style>
</head>
<body>
    <div class="dashboard">
        <div class="box full-width" style="display: flex; justify-content: space-between; align-items: center;">
            <h1>🎵 NasMusic Pro 시스템 센터</h1>
            <div id="status-badge" class="badge badge-idle">시스템 대기 중</div>
        </div>

        <div class="box">
            <h2>📁 인덱싱 상태 (1.7M+ 대용량 최적화)</h2>
            <div class="stat-grid">
                <div class="stat-card">
                    <span id="stat-total" class="stat-value">0</span>
                    <span class="stat-label">총 발견 곡</span>
                </div>
                <div class="stat-card">
                    <span id="stat-speed" class="stat-value">0</span>
                    <span class="stat-label">처리 속도 (곡/초)</span>
                </div>
                <div class="stat-card">
                    <span id="stat-eta" class="stat-value" style="color: var(--warning);">계산 중...</span>
                    <span class="stat-label">예상 완료 시간</span>
                </div>
            </div>

            <div class="progress-container">
                <div style="display: flex; justify-content: space-between; margin-bottom: 8px;">
                    <span id="progress-text">0 / 0 파일 (0%)</span>
                    <span id="processed-count">진행률</span>
                </div>
                <div class="bar-bg"><div id="progress-bar" class="bar"></div></div>
            </div>

            <div style="display: flex; gap: 10px;">
                <button onclick="fetch('/api/refresh')">🚀 라이브러리 전체 재스캔</button>
                <button class="secondary" onclick="fetch('/api/metadata/start')">✨ 메타데이터 엔진 가동</button>
            </div>
        </div>

        <div class="box">
            <h3>🖼️ 메타데이터 현황</h3>
            <div class="stat-grid" style="grid-template-columns: 1fr;">
                <div class="stat-card">
                    <span id="meta-progress" class="stat-value">0%</span>
                    <span class="stat-label">진행률</span>
                </div>
                <div class="stat-card">
                    <span id="meta-success" class="stat-value" style="color: var(--success);">0</span>
                    <span class="stat-label">이미지 매칭 성공</span>
                </div>
            </div>
        </div>

        <div class="box full-width">
            <h3>📝 실시간 시스템 로그</h3>
            <div id="log-box" class="log-container"></div>
        </div>
    </div>

    <script>
        let lastIdxLog = "";
        const logBox = document.getElementById('log-box');

        function addLog(tag, msg) {
            const div = document.createElement('div');
            div.className = 'log-entry';
            const time = new Date().toLocaleTimeString();
            div.innerHTML = `<span class="timestamp">[${time}]</span><span style="color: var(--accent)">[${tag}]</span> ${msg}`;
            logBox.appendChild(div);
            logBox.scrollTop = logBox.scrollHeight;
            if(logBox.childElementCount > 500) logBox.removeChild(logBox.firstChild);
        }

        function update() {
            fetch('/api/indexing/status').then(r=>r.json()).then(d=>{
                const p = (d.total_dirs > 0 ? (d.processed_dirs / d.total_dirs * 100) : 0).toFixed(2);
                document.getElementById('progress-bar').style.width = p + '%';
                document.getElementById('progress-text').innerText = `${d.processed_dirs.toLocaleString()} / ${d.total_dirs.toLocaleString()} (${p}%)`;
                document.getElementById('stat-total').innerText = d.songs_found.toLocaleString();
                document.getElementById('stat-speed').innerText = d.speed;
                document.getElementById('stat-eta').innerText = d.eta;

                const badge = document.getElementById('status-badge');
                if(d.is_running) {
                    badge.innerText = "인덱싱 진행 중";
                    badge.className = "badge badge-running";
                } else {
                    badge.innerText = "시스템 대기 중";
                    badge.className = "badge badge-idle";
                }

                if(d.last_log && d.last_log !== lastIdxLog) {
                    addLog("INDEX", d.last_log);
                    lastIdxLog = d.last_log;
                }
            });

            fetch('/api/metadata/status').then(r=>r.json()).then(d=>{
                const p = (d.total > 0 ? (d.current / d.total * 100) : 0).toFixed(1);
                document.getElementById('meta-progress').innerText = p + '%';
                document.getElementById('meta-success').innerText = d.success.toLocaleString();
            });
        }

        setInterval(update, 1000);
        addLog("SYSTEM", "모니터링 대시보드가 준비되었습니다.");
    </script>
</body>
</html>
'''

# ==========================================
# 3. 핵심 로직: DB, 스캔, 인덱싱 (중간 저장 및 이어하기)
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
        # 1. albumName 컬럼 유지 보수
        try:
            c.execute("ALTER TABLE global_songs ADD COLUMN albumName TEXT")
        except:
            pass

        # 2. 검색 최적화를 위한 복합 인덱스 생성
        # 기존 인덱스가 있다면 삭제하고 새로 생성
        try:
            c.execute('DROP INDEX IF EXISTS idx_path')
        except:
            pass
        c.execute('CREATE INDEX IF NOT EXISTS idx_songs_optimized ON global_songs(parent_path, artist, name)')

        conn.commit()

def load_cache():
    global cache
    print("[*] 🔄 시스템 캐시 및 테마 로딩 시작...")
    try:
        with sqlite3.connect(DB_PATH) as conn:
            conn.row_factory = sqlite3.Row
            for t in ["charts", "collections", "artists", "genres"]:
                rows = conn.execute("SELECT name, path FROM themes WHERE type=?", (t,)).fetchall()
                cache[t] = [dict(r) for r in rows]
                print(f"    ✅ [캐싱 완료] {t.upper():<12} : {len(rows):,} 항목 로드됨")
        print("[*] 🎉 모든 테마 캐싱 작업이 성공적으로 완료되었습니다.")
    except Exception as e:
        print(f"[!] 캐시 로딩 실패: {e}")

def get_info(f, d):
    nm = os.path.splitext(f)[0]
    art, tit = ("Unknown Artist", nm)
    if " - " in nm:
        parts = nm.split(" - ", 1)
        art = parts[0].split(". ")[-1] if ". " in parts[0] else parts[0]
        tit = parts[1]
    rel_dir = os.path.relpath(d, MUSIC_BASE)
    rel_file = os.path.join(rel_dir, f)
    stream_url = f"{BASE_URL}/stream/{urllib.parse.quote(rel_file)}"
    return (tit, art, os.path.basename(d), stream_url, rel_dir)

def process_path(full_path):
    if not full_path: return None
    try:
        return get_info(os.path.basename(full_path), os.path.dirname(full_path))
    except: return None

def scan_all_songs():
    global idx_st
    if idx_st["is_running"]: return

    idx_st.update({
        "is_running": True, "songs_found": 0, "processed_dirs": 0, "total_dirs": 0,
        "start_time": time.time(), "speed": 0, "eta": "계산 중...",
        "last_log": "🚀 1.7M 대용량 스캔 엔진 가동! 이어하기 확인 및 파일 목록 수집 중..."
    })

    try:
        # 1. DB 설정 및 임시 폴더 지정 (8TB 볼륨 사용)
        with sqlite3.connect(DB_PATH) as conn:
            conn.execute(f"PRAGMA temp_store_directory = '{WRITEABLE_DIR}'")
            conn.execute("PRAGMA journal_mode = WAL")
            conn.execute("CREATE TABLE IF NOT EXISTS global_songs_staging (name TEXT, artist TEXT, albumName TEXT, stream_url TEXT, parent_path TEXT, meta_poster TEXT)")
            # staging 테이블에도 albumName 컬럼 확인
            try: conn.execute("ALTER TABLE global_songs_staging ADD COLUMN albumName TEXT")
            except: pass
            conn.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_staging_url ON global_songs_staging(stream_url)")

            # 이어하기 핵심: 이미 인덱싱된 URL 확인
            cursor = conn.execute("SELECT stream_url FROM global_songs_staging UNION SELECT stream_url FROM global_songs")
            indexed_urls = {row[0] for row in cursor.fetchall()}

        # 2. 파일 목록 수집 (find)
        command = ['find', MUSIC_BASE, '-type', 'f', '(', '-iname', '*.mp3', '-o', '-iname', '*.m4a', '-o', '-iname', '*.flac', '-o', '-iname', '*.dsf', ')']
        all_files = []
        proc = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, encoding='utf-8')
        for line in iter(proc.stdout.readline, ''):
            all_files.append(line.strip())
            if len(all_files) % 20000 == 0:
                idx_st["last_log"] = f"🔍 NAS 파일 탐색 중... {len(all_files):,}개 발견"
        proc.wait()

        total_files = len(all_files)
        idx_st["total_dirs"] = total_files

        # 3. 신규 파일 필터링 (이어하기 로직)
        files_to_process = []
        skipped_count = 0
        for f in all_files:
            info = get_info(os.path.basename(f), os.path.dirname(f))
            if info[3] in indexed_urls:
                skipped_count += 1
            else:
                files_to_process.append(f)

        idx_st["processed_dirs"] = skipped_count
        idx_st["songs_found"] = skipped_count

        if not files_to_process:
            idx_st["last_log"] = f"✅ 처리 완료된 {skipped_count:,}곡이 모두 최신입니다. 동기화를 진행합니다."
        else:
            idx_st["last_log"] = f"⏭️ 이미 완료된 {skipped_count:,}곡을 건너뛰고, 남은 {len(files_to_process):,}곡 작업을 시작합니다."

            batch = []
            BATCH_SIZE = 5000
            processing_start = time.time()
            done_in_this_run = 0

            with ThreadPoolExecutor(max_workers=12) as exe:
                futures = {exe.submit(process_path, f): f for f in files_to_process}
                for future in as_completed(futures):
                    res = future.result()
                    if res: batch.append(res)

                    if len(batch) >= BATCH_SIZE:
                        with sqlite3.connect(DB_PATH) as conn:
                            conn.executemany("INSERT OR IGNORE INTO global_songs_staging VALUES (?,?,?,?,?,NULL)", batch)
                            conn.commit()
                        done_in_this_run += len(batch)
                        idx_st["processed_dirs"] = skipped_count + done_in_this_run
                        idx_st["songs_found"] = idx_st["processed_dirs"]

                        elapsed = time.time() - processing_start
                        speed = int(done_in_this_run / elapsed) if elapsed > 0 else 0
                        eta = (len(files_to_process) - done_in_this_run) / speed if speed > 0 else 0
                        idx_st.update({
                            "speed": speed,
                            "eta": time.strftime("%H:%M:%S", time.gmtime(eta)),
                            "last_log": f"⏳ {idx_st['processed_dirs']:,} / {total_files:,} 처리 중... ({speed}곡/초)"
                        })
                        batch = []

            if batch:
                with sqlite3.connect(DB_PATH) as conn:
                    conn.executemany("INSERT OR IGNORE INTO global_songs_staging VALUES (?,?,?,?,?,NULL)", batch)
                    conn.commit()

        # 4. 최종 동기화
        idx_st["last_log"] = "💾 마지막 단계: 전체 라이브러리 병합 및 인덱싱..."
        finalize_library()

    except Exception as e:
        idx_st.update({"is_running": False, "last_log": f"❌ 오류 발생: {str(e)}"})

def finalize_library():
    with sqlite3.connect(DB_PATH) as conn:
        conn.execute("PRAGMA journal_mode = WAL")
        # 기존 데이터를 staging으로 병합 (중복 제외)
        conn.execute("INSERT OR IGNORE INTO global_songs_staging SELECT name, artist, albumName, stream_url, parent_path, meta_poster FROM global_songs")

        # 메타데이터 복구
        conn.execute("""
            UPDATE global_songs_staging
            SET meta_poster = (SELECT meta_poster FROM global_songs WHERE global_songs.artist = global_songs_staging.artist AND global_songs.albumName = global_songs_staging.albumName AND meta_poster IS NOT NULL LIMIT 1)
            WHERE meta_poster IS NULL
        """)

        conn.execute("DROP TABLE IF EXISTS global_songs")
        conn.execute("ALTER TABLE global_songs_staging RENAME TO global_songs")
        conn.execute("CREATE INDEX IF NOT EXISTS idx_path ON global_songs(parent_path)")
        conn.commit()
    idx_st.update({"is_running": False, "last_log": "✅ 라이브러리 업데이트 완료!"})

def rebuild_library():
    global cache
    print("[*] Rebuilding Themes...")
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

def start_metadata_update_thread():
    global up_st
    if up_st["is_running"]: return
    up_st["is_running"] = True
    print("[*] 🖼️ 메타데이터 업데이트 시작...")
    try:
        with sqlite3.connect(DB_PATH) as conn:
            cursor = conn.cursor()
            # 이미 메타가 있는 앨범은 건너뜀 (이어하기 자동 지원)
            cursor.execute(
                "SELECT DISTINCT artist, albumName FROM global_songs WHERE (meta_poster IS NULL OR meta_poster = '')")
            targets = cursor.fetchall()
            up_st.update({"total": len(targets), "current": 0, "success": 0, "fail": 0})

            print(f"[*] 총 {len(targets)}개의 앨범 메타데이터 작업 필요.")

            for art, alb in targets:
                if not up_st["is_running"]: break

                # 메타데이터 검색
                meta = fetch_maniadb_metadata(art, alb) or fetch_deezer_metadata(art, alb)

                if meta and meta.get("poster"):
                    cursor.execute("UPDATE global_songs SET meta_poster = ? WHERE artist = ? AND albumName = ?",
                                   (meta["poster"], art, alb))
                    up_st["success"] += 1
                else:
                    up_st["fail"] += 1

                up_st["current"] += 1
                if up_st["current"] % 10 == 0:
                    conn.commit()
                    up_st["last_log"] = f"진행중: {up_st['current']}/{up_st['total']} (성공: {up_st['success']})"

                # API 차단 방지용 대기
                time.sleep(1.0)
    finally:
        up_st.update({"is_running": False, "last_log": "작업 완료"})
        print("[*] 🎉 메타데이터 업데이트 종료.")

@app.route('/monitor')
def render_monitor(): return render_template_string(MONITOR_HTML)

@app.route('/api/indexing/status')
def get_idx(): return jsonify(idx_st)

@app.route('/api/metadata/status')
def get_meta(): return jsonify(up_st)

@app.route('/api/metadata/start')
def start_meta():
    def task():
        global up_st
        up_st["is_running"] = True
        try:
            with sqlite3.connect(DB_PATH) as conn:
                cursor = conn.cursor()
                cursor.execute("SELECT DISTINCT artist, albumName FROM global_songs WHERE meta_poster IS NULL OR meta_poster = ''")
                targets = cursor.fetchall()
                up_st.update({"total": len(targets), "current": 0, "success": 0})
                for art, alb in targets:
                    if not up_st["is_running"]: break
                    up_st["current"] += 1
        finally: up_st["is_running"] = False
    Thread(target=task).start()
    return jsonify({"status": "ok"})

@app.route('/api/themes')
def get_themes(): return jsonify(cache)

@app.route('/api/theme-details/<path:tp>')
def get_details(tp):
    p = urllib.parse.unquote(tp)
    with sqlite3.connect(DB_PATH) as conn:
        conn.row_factory = sqlite3.Row
        # 장르 조회 시 LIMIT 1000을 추가하여 서버 멈춤 방지
        rows = conn.execute(
            "SELECT name, artist, albumName, stream_url, parent_path, meta_poster FROM global_songs WHERE parent_path LIKE ? ORDER BY artist, name ASC LIMIT 1000",
            (f"{p}%",)
        ).fetchall()

        groups = {}
        for r in rows:
            cat = r['parent_path'].split('/')[-1]
            if cat not in groups: groups[cat] = []
            groups[cat].append(dict(r))
        return jsonify([{"category_name": k, "songs": v} for k, v in groups.items()])

@app.route('/api/refresh')
def refresh():
    Thread(target=rebuild_library).start()
    return jsonify({"status": "started"})

@app.route('/stream/<path:fp>')
def stream(fp): return send_from_directory(MUSIC_BASE, urllib.parse.unquote(fp))

if __name__ == '__main__':
    init_db()
    load_cache()
    app.run(host='0.0.0.0', port=4444, debug=False)
