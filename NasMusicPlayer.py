from flask import Flask, jsonify, send_from_directory, request, render_template_string
import os, sqlite3, json, urllib.parse, time, random, requests, subprocess, shutil,re
from flask_cors import CORS
from threading import Thread
from concurrent.futures import ThreadPoolExecutor, as_completed
import queue

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
    print("[*] 🛠️ DB 엔진 최적화 및 메타데이터 필드 확장 중...")
    with sqlite3.connect(DB_PATH, timeout=600) as conn:
        conn.execute('PRAGMA journal_mode=WAL')
        conn.execute('PRAGMA synchronous=NORMAL')
        conn.execute('PRAGMA temp_store=MEMORY')
        c = conn.cursor()
        c.execute('CREATE TABLE IF NOT EXISTS themes (type TEXT, name TEXT, path TEXT)')
        c.execute('''
            CREATE TABLE IF NOT EXISTS global_songs (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT, artist TEXT, albumName TEXT,
                stream_url TEXT, parent_path TEXT, meta_poster TEXT
            )
        ''')

        # 새로운 컬럼들 추가 (기존 DB에 없으면 추가됨)
        new_cols = ["albumName", "genre", "release_date", "album_artist"]
        for col in new_cols:
            try:
                c.execute(f"ALTER TABLE global_songs ADD COLUMN {col} TEXT")
            except:
                pass

        # 인덱스 재생성 (업데이트 속도 보장)
        c.execute('CREATE INDEX IF NOT EXISTS idx_meta_v5 ON global_songs(meta_poster, artist, albumName)')
        c.execute('CREATE INDEX IF NOT EXISTS idx_path_v5 ON global_songs(parent_path)')
        conn.commit()
    print("[*] ✅ DB 최적화 및 필드 확장 완료.")

print("[*] ✅ DB 최적화 및 준비 완료.")

def clean_query_text(text):
    if not text or text == 'Unknown Artist': return ""

    # 1. 모든 종류의 괄호 태그 제거 (날짜, 싱글 정보 등)
    # [2024.01.01], (Remastered), {Edition} 등 처리
    text = re.sub(r'\[.*?\]', '', text)
    text = re.sub(r'\(.*?\)', '', text)
    text = re.sub(r'\{.*?\}', '', text)

    # 2. 검색 방해 패턴 제거 (에디션, 버전, 한국식 앨범 정보)
    noise_patterns = [
        r'(?i)\b(CD|Disc|Disk|Vol|Volume|Part|Pt|Side)\.?\s*\d*',
        r'(?i)\b(Deluxe|Special|Limited|Remastered|Bonus|Digital|Live|Single|EP|Version|Edition|Remaster)\b',
        r'정규\s*\d*집', r'싱글\s*EP', r'베스트\s*앨범', r'OST\s*Part\.?\s*\d*',
        r'\d{4}\.?\d{2}\.?\d{2}'  # 날짜 형식 (2024.01.01)
    ]
    for pattern in noise_patterns:
        text = re.sub(pattern, '', text)

    # 3. 특수문자 제거 및 공백 정리
    text = re.sub(r'[:\-&/_+,.]', ' ', text)
    result = " ".join(text.split()).strip()

    # 4. 결과 검증: 숫자뿐이거나 너무 짧으면 무효 처리 (검색 노이즈 방지)
    if not result or result.isdigit() or len(result) < 2:
        return ""

    return result

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

    # 1. 파일명에서 추출 시도 (가수 - 제목)
    if " - " in nm:
        parts = nm.split(" - ", 1)
        art_part = parts[0]
        # "01. 가수" 형태에서 번호 제거
        art = art_part.split(". ", 1)[-1].strip() if ". " in art_part else art_part.strip()
        tit = parts[1].strip()

    rel_dir = os.path.relpath(d, MUSIC_BASE)

    # 2. 파일명에 가수가 없으면 폴더 구조 분석
    if art == "Unknown Artist":
        path_parts = rel_dir.split(os.sep)
        # 예: 국내/가수/ㄱ/김범수/1집 -> 김범수 추출
        if len(path_parts) >= 4 and path_parts[0] == "국내" and path_parts[1] == "가수":
            art = path_parts[3]
        # 예: 외국/Adele/25 -> Adele 추출
        elif len(path_parts) >= 2 and any(g in path_parts[0] for g in GENRE_ROOTS.keys()):
            art = path_parts[1]
        # 기타: 상위 폴더명을 가수로 간주
        elif len(path_parts) >= 2:
            parent_name = os.path.basename(os.path.dirname(d))
            if parent_name and parent_name not in [os.path.basename(MUSIC_BASE), "MUSIC", ""]:
                art = parent_name

    rel_file = os.path.join(rel_dir, f)
    stream_url = f"{BASE_URL}/stream/{urllib.parse.quote(rel_file)}"
    return (tit, art, os.path.basename(d), stream_url, rel_dir)

def fix_unknown_artists_in_db():
    print("[*] 🛠️ DB 내 Unknown Artist 복구 작업을 시작합니다...")
    try:
        with sqlite3.connect(DB_PATH) as conn:
            conn.row_factory = sqlite3.Row
            # id 대신 고유값인 stream_url을 사용하여 조회
            rows = conn.execute("SELECT stream_url, parent_path FROM global_songs WHERE artist = 'Unknown Artist'").fetchall()

            updates = []
            for r in rows:
                p = r['parent_path']
                parts = p.split('/')
                new_art = None

                # 경로 기반 가수 추출 (구조: 국내/가수/초성/가수명/...)
                if len(parts) >= 4 and parts[0] == "국내" and parts[1] == "가수":
                    new_art = parts[3]
                # 경로 기반 가수 추출 (구조: 장르/가수명/...)
                elif len(parts) >= 2 and any(g in parts[0] for g in GENRE_ROOTS.keys()):
                    new_art = parts[1]

                if new_art:
                    updates.append((new_art, r['stream_url']))

            if updates:
                # stream_url을 기준으로 가수 이름 업데이트
                conn.executemany("UPDATE global_songs SET artist = ? WHERE stream_url = ?", updates)
                conn.commit()
                print(f"[*] ✅ 총 {len(updates):,}개의 Unknown Artist를 실제 이름으로 복구했습니다!")
            else:
                print("[*] 복구할 항목이 없습니다.")
    except Exception as e:
        print(f"[!] 복구 작업 중 오류 발생: {e}")

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

        # [중요] 테이블이 바뀌었으므로 인덱스를 반드시 다시 생성해줘야 합니다.
        conn.execute("CREATE INDEX IF NOT EXISTS idx_meta_lookup ON global_songs(artist, albumName)")
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

def fetch_maniadb_metadata(artist, album):
    try:
        url = f"http://www.maniadb.com/api/search/{urllib.parse.quote(f'{artist} {album}')}/?sr=album&display=1&key=example&v=0.5"
        res = requests.get(url, timeout=5)
        if res.status_code == 200:
            text = res.text
            meta = {}
            if "<image>" in text:
                meta["poster"] = text.split("<image><![CDATA[")[1].split("]]>")[0].replace("/s/", "/l/")
            if "majorgenre" in text:
                meta["genre"] = text.split("<maniadb:majorgenre><![CDATA[")[1].split("]]>")[0]
            if "<pubDate>" in text:
                meta["release_date"] = text.split("<pubDate>")[1].split("</pubDate>")[0]
            if "maniadb:artist" in text:
                # 앨범 아티스트 추출 시도
                try: meta["album_artist"] = text.split("<maniadb:artist>")[1].split("<![CDATA[")[1].split("]]>")[0]
                except: pass
            return meta if meta else None
    except: pass
    return None

def fetch_deezer_metadata(artist, album):
    try:
        # 1. 앨범 검색
        search_url = f"https://api.deezer.com/search/album?q={urllib.parse.quote(f'{artist} {album}')}&limit=1"
        res = requests.get(search_url, timeout=5).json()
        if res.get("data") and len(res["data"]) > 0:
            alb = res["data"][0]
            alb_id = alb['id']
            # 2. 앨범 상세 정보 (장르, 날짜 등을 위해)
            detail = requests.get(f"https://api.deezer.com/album/{alb_id}", timeout=5).json()
            return {
                "poster": detail.get('cover_xl'),
                "genre": detail.get('genres', {}).get('data', [{}])[0].get('name') if detail.get('genres') else None,
                "release_date": detail.get('release_date'),
                "album_artist": detail.get('artist', {}).get('name')
            }
    except: pass
    return None

def start_metadata_update_thread():
    global up_st, idx_st
    if up_st["is_running"]: return
    up_st["is_running"] = True
    idx_st["last_log"] = "🚀 메타데이터 확장 엔진 가동! (장르/날짜 포함 수집)"

    try:
        with sqlite3.connect(DB_PATH, timeout=120) as conn:
            targets = conn.execute("""
                SELECT artist, albumName FROM global_songs
                WHERE (meta_poster IS NULL OR meta_poster = '' OR meta_poster = 'FAIL')
                GROUP BY artist, albumName
            """).fetchall()

        if not targets:
            up_st["is_running"] = False
            return

        up_st.update({"total": len(targets), "current": 0, "success": 0, "fail": 0})
        db_q = queue.Queue()

        def db_writer():
            conn = sqlite3.connect(DB_PATH, timeout=120, isolation_level=None)
            conn.execute("PRAGMA journal_mode=WAL")
            batch = []
            while up_st["is_running"] or not db_q.empty():
                try:
                    data = db_q.get(timeout=1)
                    batch.append(data)
                    if len(batch) >= 100 or (not up_st["is_running"] and db_q.empty()):
                        if not batch: continue
                        conn.execute("BEGIN IMMEDIATE")
                        for art, alb, meta in batch:
                            if meta:
                                conn.execute("""
                                    UPDATE global_songs
                                    SET meta_poster = ?, genre = ?, release_date = ?, album_artist = ?
                                    WHERE artist = ? AND albumName = ?
                                """, (meta.get("poster"), meta.get("genre"), meta.get("release_date"),
                                      meta.get("album_artist"), art, alb))
                            else:
                                conn.execute(
                                    "UPDATE global_songs SET meta_poster = 'FAIL' WHERE artist = ? AND albumName = ?",
                                    (art, alb))
                        conn.commit()
                        batch = []
                    db_q.task_done()
                except queue.Empty:
                    continue
                except Exception as e:
                    print(f"[!] DB 쓰기 오류: {e}")
                    try:
                        conn.execute("ROLLBACK")
                    except:
                        pass
            conn.close()

        Thread(target=db_writer, daemon=True).start()

        def fetch_task(art, alb):
            try:
                time.sleep(random.uniform(0.3, 0.6))
                q_art, q_alb = clean_query_text(art), clean_query_text(alb)
                meta = None
                if q_alb:
                    meta = fetch_maniadb_metadata(q_art, q_alb) or fetch_deezer_metadata(q_art, q_alb)

                db_q.put((art, alb, meta))
                up_st["current"] += 1
                if meta and meta.get("poster"):
                    up_st["success"] += 1
                else:
                    up_st["fail"] += 1

                log = f"[{up_st['current']}/{up_st['total']}] {'✨' if meta else '➖'} {art}"
                up_st["last_log"] = log
                idx_st["last_log"] = log
            except:
                up_st["current"] += 1
                db_q.put((art, alb, None))

        CHUNK_SIZE = 5000
        for i in range(0, len(targets), CHUNK_SIZE):
            if not up_st["is_running"]: break
            chunk = targets[i:i + CHUNK_SIZE]
            with ThreadPoolExecutor(max_workers=5) as exe:
                for art, alb in chunk:
                    exe.submit(fetch_task, art, alb)
            print(f"[{time.strftime('%H:%M:%S')}] Chunk {i // CHUNK_SIZE + 1} 완료...")

    except Exception as e:
        print(f"[ERROR] 메타데이터 엔진 에러: {e}")
    finally:
        time.sleep(2)
        up_st["is_running"] = False

@app.route('/api/admin/query', methods=['POST'])
def run_query():
    sql = request.json.get('query')
    # 기본 방어 코드: SELECT 문만 허용 (필요 시 수정)
    if not sql.strip().upper().startswith("SELECT"):
        return jsonify({"error": "안전을 위해 SELECT 문만 실행 가능합니다."})
    try:
        with sqlite3.connect(DB_PATH, timeout=10) as conn:
            conn.row_factory = sqlite3.Row
            res = conn.execute(sql).fetchall()
            return jsonify([dict(r) for r in res])
    except Exception as e:
        return jsonify({"error": str(e)})

@app.route('/monitor')
def render_monitor(): return render_template_string(MONITOR_HTML)

@app.route('/api/indexing/status')
def get_idx(): return jsonify(idx_st)

@app.route('/api/metadata/status')
def get_meta(): return jsonify(up_st)

@app.route('/api/metadata/start')
def start_meta():
    print("[*] 🔔 메타데이터 가동 요청 수신!")
    if not up_st["is_running"]:
        # 실제 메타데이터 업데이트 스레드 실행
        Thread(target=start_metadata_update_thread).start()
        return jsonify({"status": "ok", "message": "엔진을 가동합니다."})
    else:
        return jsonify({"status": "error", "message": "이미 엔진이 작동 중입니다."})

@app.route('/api/themes')
def get_themes(): return jsonify(cache)

@app.route('/api/theme-details/<path:tp>')
def get_details(tp):
    p = urllib.parse.unquote(tp)
    with sqlite3.connect(DB_PATH) as conn:
        conn.row_factory = sqlite3.Row
        # 데이터 구조를 단순하게 평탄화(Flat List)해서 전달합니다.
        rows = conn.execute(
            "SELECT name, artist, albumName, stream_url, parent_path, meta_poster FROM global_songs WHERE parent_path LIKE ? ORDER BY artist, name ASC LIMIT 500",
            (f"{p}%",)
        ).fetchall()

        # 앱에서 변환하기 쉽게 리스트로 바로 전달
        return jsonify([dict(r) for r in rows])


@app.route('/api/refresh')
def refresh():
    Thread(target=rebuild_library).start()
    return jsonify({"status": "started"})

@app.route('/stream/<path:fp>')
def stream(fp): return send_from_directory(MUSIC_BASE, urllib.parse.unquote(fp))

if __name__ == '__main__':
    init_db()
    load_cache()
    # 서버 시작 시 Unknown Artist들을 경로 기반으로 자동 수정
    # fix_unknown_artists_in_db()
    app.run(host='0.0.0.0', port=4444, debug=False)
