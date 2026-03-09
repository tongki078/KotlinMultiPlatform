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
    print("[*] 🛠️ DB 엔진 최적화 및 캐시 테이블 생성 중...")
    with sqlite3.connect(DB_PATH, timeout=600) as conn:
        conn.execute('PRAGMA journal_mode=WAL')
        conn.execute('''
            CREATE TABLE IF NOT EXISTS global_songs (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT, artist TEXT, albumName TEXT,
                stream_url TEXT, parent_path TEXT, meta_poster TEXT,
                genre TEXT, release_date TEXT, album_artist TEXT
            )
        ''')
        # [핵심] 검색할 데이터만 따로 관리하는 뷰 또는 테이블 생성
        conn.execute('''
            CREATE TABLE IF NOT EXISTS metadata_cache (
                artist TEXT, albumName TEXT,
                PRIMARY KEY (artist, albumName)
            )
        ''')
        # 검색 대상 인덱스
        conn.execute('CREATE INDEX IF NOT EXISTS idx_grouping ON global_songs(artist, albumName)')
        conn.commit()
    print("[*] ✅ DB 최적화 및 캐시 테이블 준비 완료.")


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

# 기존의 fetch_maniadb_metadata와 fetch_metadata_smart를 아래 코드로 대체하세요.

def fetch_maniadb_metadata(artist, album):
    try:
        # Maniadb 타임아웃 문제를 해결하기 위해 타임아웃을 8초로 늘리고
        # 검색 실패 시 바로 예외 처리하도록 합니다.
        query = f"{artist} {album}".strip()
        url = f"http://www.maniadb.com/api/search/{urllib.parse.quote(query)}/?sr=album&display=1&key=example&v=0.5"
        res = requests.get(url, timeout=8, headers={'User-Agent': 'Mozilla/5.0'})

        if res.status_code == 200:
            t = res.text
            img = re.search(r'<image><!\[CDATA\[(.*?)]]>', t)
            if img:
                return {
                    "poster": img.group(1).replace("/s/", "/l/"),
                    "genre": "K-Pop"
                }
    except Exception as e:
        # 타임아웃 발생 시 무거운 Maniadb를 다시 시도하지 않도록 로그만 남김
        return None
    return None


# ==========================================
# 4. 메타데이터 엔진 (국내 폴더 우선순위 적용)
# ==========================================

def clean_query_text(text, is_artist=False, folder_type=None):
    if not text or text == 'Unknown Artist': return ""
    s = text

    # [확장성] 나중에 folder_type(예: 'OST', '외국')에 따라 다른 규칙 적용 가능
    if folder_type == 'OST':
        # OST 전용 정제 로직 (예시)
        s = re.sub(r'(?i)ost|original soundtrack', '', s)

    # 1. 괄호 내용 삭제
    s = re.sub(r'\(.*?\)|\[.*?\]|\{.*?\}', ' ', s)
    # 2. 트랙 번호만 제거 (숫자 뒤에 공백/마침표 등이 있는 경우만)
    s = re.sub(r'^[0-9]+[\s\.\-_/]+', '', s)

    # 3. 무의미한 앨범명 필터링 (CD1 등)
    if not is_artist:
        if re.match(r'^(cd|disc|vol|volume|track)\s*[0-9]*$', s, re.IGNORECASE):
            return ""

    # 4. 기술 태그 제거
    s = re.sub(
        r'(?i)\b(\d+bit|\d+khz|flac|mp3|wav|m4a|dsf|dsd|hi-res|320k|remastered|live|deluxe|version|edit|cd\d+|disc\d+)\b',
        ' ', s)
    # 5. 특수문자 정리
    s = re.sub(r'[^\w\s가-힣]', ' ', s)
    return re.sub(r'\s+', ' ', s).strip()

def fetch_metadata_smart(artist, album, title):
    q_art = clean_query_text(artist, is_artist=True)
    q_alb = clean_query_text(album, is_artist=False)
    q_tit = clean_query_text(title, is_artist=False)

    if not q_art or q_art.lower() == 'unknown': return None

    # [매칭 전략] 1. 가수+앨범 -> 2. 가수+제목 -> 3. 가수단독
    if q_alb:
        res = fetch_maniadb_metadata(q_art, q_alb) or fetch_deezer_metadata(q_art, q_alb)
        if res: return res
    if q_tit:
        res = fetch_maniadb_metadata(q_art, q_tit) or fetch_deezer_metadata(q_art, q_tit)
        if res: return res
    return fetch_maniadb_metadata(q_art, "") or fetch_deezer_metadata(q_art, "")

def fetch_deezer_metadata(artist, album):
    try:
        res = requests.get(f"https://api.deezer.com/search/album?q={urllib.parse.quote(f'{artist} {album}')}&limit=1", timeout=5).json()
        if res.get("data") and len(res["data"]) > 0:
            alb_id = res["data"][0]['id']
            d = requests.get(f"https://api.deezer.com/album/{alb_id}", timeout=5).json()
            return {
                "poster": d.get('cover_xl'),
                "genre": d.get('genres', {}).get('data', [{}])[0].get('name') if d.get('genres') else None,
                "release_date": d.get('release_date'),
                "album_artist": d.get('artist', {}).get('name')
            }
    except: pass
    return None


def start_metadata_update_thread(query_tag=None):
    global up_st
    if up_st["is_running"]: return
    up_st["is_running"] = True

    display_name = query_tag if query_tag else "전체"
    print(f"[*] 🚀 메타데이터 엔진 가동 - [대상: {display_name}]")

    try:
        with sqlite3.connect(DB_PATH, timeout=120) as conn:
            # 쿼리 동적 생성
            sql = "SELECT artist, albumName, MAX(name) FROM global_songs WHERE (meta_poster IS NULL OR meta_poster = '' OR meta_poster = 'FAIL')"
            params = []

            if query_tag:
                sql += " AND parent_path LIKE ?"
                params.append(f"{query_tag}%")

            sql += " GROUP BY artist, albumName LIMIT 30000"
            targets = conn.execute(sql, params).fetchall()

        if not targets:
            print(f"[*] ✅ [{display_name}] 폴더에 처리할 대상이 없습니다.")
            up_st["is_running"] = False
            return

        up_st.update({"total": len(targets), "current": 0, "success": 0, "fail": 0})
        db_q = queue.Queue()

        # (db_worker 정의 부분은 기존과 동일)
        def db_worker():
            while up_st["is_running"]:
                try:
                    item = db_q.get(timeout=5)
                    if item is None: break
                    art, alb, res = item
                    with sqlite3.connect(DB_PATH, timeout=60) as conn:
                        if res and res.get('poster'):
                            conn.execute(
                                "UPDATE global_songs SET meta_poster=?, genre=?, release_date=?, album_artist=? WHERE artist=? AND albumName=?",
                                (res['poster'], res.get('genre'), res.get('release_date'), res.get('album_artist'), art,
                                 alb))
                            up_st["success"] += 1
                        else:
                            conn.execute("UPDATE global_songs SET meta_poster='FAIL' WHERE artist=? AND albumName=?",
                                         (art, alb))
                            up_st["fail"] += 1
                        conn.commit()
                    db_q.task_done()
                except queue.Empty:
                    continue
                except Exception as e:
                    print(f"[!] DB Error: {e}")

        Thread(target=db_worker, daemon=True).start()

        with ThreadPoolExecutor(max_workers=5) as executor:
            futures = []
            for art, alb, tit in targets:
                if not up_st["is_running"]: break

                def run_match(a=art, b=alb, t=tit, f_type=query_tag):
                    try:
                        # folder_type을 전달하여 맞춤형 정제 수행
                        q_art = clean_query_text(a, is_artist=True, folder_type=f_type)
                        q_title = clean_query_text(b if b else t, is_artist=False, folder_type=f_type)

                        res = fetch_metadata_smart(a, b, t)
                        db_q.put((a, b, res))

                        up_st["current"] += 1
                        icon = "✅ 성공" if res else "❌ 실패"
                        progress = (up_st['current'] / up_st['total'] * 100)
                        print(
                            f"[*] [{progress:.1f}%] {up_st['current']}/{up_st['total']} | {a} - {b} ({q_art} + {q_title}) -> {icon}")
                    except Exception as e:
                        print(f"[!] Error: {a} - {e}")

                futures.append(executor.submit(run_match))

            for future in as_completed(futures): pass

        db_q.put(None)
    except Exception as e:
        print(f"[!] Fatal: {e}")
    finally:
        up_st["is_running"] = False
        print(f"[*] 🏁 [{display_name}] 엔진 종료 (성공: {up_st['success']})")

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
    # scan_all_songs()  # <--- 이 줄을 주석 처리하세요.
    # 이제 서버 재시작 후 즉시 /api/metadata/start 로 엔진 가동 가능합니다.
    app.run(host='0.0.0.0', port=4444, debug=False)
