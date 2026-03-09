from flask import Flask, jsonify, send_from_directory, request, render_template_string
import os, sqlite3, json, urllib.parse, time, random, requests, subprocess, shutil, re
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
OLD_DB_PATH = "music_cache_v3.db"  # 현재(root) 위치
WRITEABLE_DIR = "/volume2/video"  # 쓰기 권한이 확실한 8TB 볼륨 루트
DB_PATH = os.path.join(WRITEABLE_DIR, "music_cache_v3.db")  # 안전한(8TB) 위치

# 5일간의 노력이 담긴 DB 파일을 안전한 곳으로 자동 대피
if os.path.exists(OLD_DB_PATH) and not os.path.exists(DB_PATH):
    print(f"[*] 5일간의 데이터를 쓰기 가능한 {WRITEABLE_DIR}로 이동 중... ({OLD_DB_PATH} -> {DB_PATH})")
    try:
        shutil.move(OLD_DB_PATH, DB_PATH)
        print("[*] 이동 완료! 이제 용량 걱정 없이 인덱싱을 이어갑니다.")
    except Exception as e:
        print(f"[!] 이동 실패: {e}. 수동으로 파일을 {DB_PATH}로 옮겨주세요.")

# 상태 전역 변수
up_st = {"is_running": False, "total": 0, "current": 0, "success": 0, "fail": 0, "last_log": "대기 중...", "target": "전체"}
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
        :root { --bg: #0f172a; --card: #1e293b; --text: #f8fafc; --accent: #38bdf8; --success: #22c55e; --warning: #f59e0b; --danger: #ef4444; }
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
        button.mini { padding: 8px 12px; font-size: 0.8rem; }
        button:disabled { opacity: 0.5; cursor: not-allowed; transform: none; }

        .badge { padding: 4px 8px; border-radius: 4px; font-size: 11px; font-weight: bold; text-transform: uppercase; }
        .badge-running { background: var(--success); color: white; }
        .badge-idle { background: #475569; color: white; }

        .category-grid { display: grid; grid-template-columns: repeat(2, 1fr); gap: 10px; margin-top: 15px; }
    </style>
</head>
<body>
    <div class="dashboard">
        <div class="box full-width" style="display: flex; justify-content: space-between; align-items: center;">
            <h1>🎵 NasMusic Pro 시스템 센터</h1>
            <div id="status-badge" class="badge badge-idle">시스템 대기 중</div>
        </div>

        <div class="box">
            <h2>📁 라이브러리 관리</h2>
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
                <button onclick="fetch('/api/refresh')" style="background: #10b981;">🖼️ 메인 화면 썸네일 동기화 (이미지 연결)</button>
            </div>

            <hr style="margin: 20px 0; border: 0; border-top: 1px solid rgba(255,255,255,0.1);">

            <!-- MONITOR_HTML 변수 안의 <body> 부분 핵심 섹션 -->
            <div class="box">
                <h2>📦 메타데이터 정밀 제어</h2>
                <div class="category-grid">
                    <button class="secondary mini" onclick="startMeta('국내')">🇰🇷 국내</button>
                    <button class="secondary mini" onclick="startMeta('외국')">🌎 외국</button>
                    <button class="secondary mini" onclick="startMeta('일본')">🇯🇵 일본</button>
                    <button class="secondary mini" onclick="startMeta('클래식')">🎻 클래식</button>
                    <button class="secondary mini" onclick="startMeta('DSD')">💿 DSD</button>
                    <button class="secondary mini" onclick="startMeta('OST')">🎬 OST</button>
                </div>

                <div style="display: flex; gap: 8px; margin-top: 15px;">
                    <button style="flex: 2; background: #6366f1;" onclick="startMeta('')">🔥 전체 자동 갱신</button>
                    <button class="danger" style="flex: 1;" onclick="resetFail()">🔄 실패 항목 초기화</button>
                </div>
            </div>
            <button class="secondary" style="width: 100%; margin-top: 10px; background: #6366f1;" onclick="startMeta('')">🔥 전체 메타데이터 갱신</button>
        </div>

        <div class="box">
            <h3>🖼️ 메타데이터 실시간 현황</h3>
            <div style="text-align: center; margin-bottom: 15px;">
                <span id="meta-target-badge" class="badge badge-idle" style="background: #818cf8;">대상: 전체</span>
            </div>
            <div class="stat-grid" style="grid-template-columns: 1fr 1fr;">
                <div class="stat-card" style="grid-column: 1 / -1;">
                    <span id="meta-db-success" class="stat-value" style="color: var(--success);">0</span>
                    <span class="stat-label">라이브러리 전체 성공</span>
                </div>
                <div class="stat-card">
                    <span id="meta-db-pending" class="stat-value" style="color: var(--warning);">0</span>
                    <span class="stat-label">남은 미매칭</span>
                </div>
                <div class="stat-card">
                    <span id="meta-db-fail" class="stat-value" style="color: var(--danger);">0</span>
                    <span class="stat-label">실패(FAIL)</span>
                </div>
                <div class="stat-card" style="grid-column: 1 / -1; background: rgba(56, 189, 248, 0.1);">
                    <span id="meta-progress" class="stat-value">0%</span>
                    <span class="stat-label">현재 작업 진행률 (배치)</span>
                </div>
            </div>
            <button id="stop-meta-btn" class="secondary" style="width: 100%; background: var(--danger); margin-top:10px; display: none;" onclick="stopMeta()">엔진 중지</button>
        </div>

        <div class="box full-width">
            <h3>📝 실시간 시스템 로그</h3>
            <div id="log-box" class="log-container"></div>
        </div>
    </div>

    <script>
        // 1. 전역 변수 설정
        let lastIdxLog = "";
        let lastMetaLog = "";
        const logBox = document.getElementById('log-box');

        // 2. 로그 출력 함수
        function addLog(tag, msg) {
            const div = document.createElement('div');
            div.className = 'log-entry';
            const time = new Date().toLocaleTimeString();
            div.innerHTML = `<span class="timestamp">[${time}]</span><span style="color: var(--accent)">[${tag}]</span> ${msg}`;
            logBox.appendChild(div);
            logBox.scrollTop = logBox.scrollHeight;
            if(logBox.childElementCount > 500) logBox.removeChild(logBox.firstChild);
        }

        // 3. 메타데이터 엔진 시작 함수
        function startMeta(category) {
            const url = category ? `/api/metadata/start?q=${encodeURIComponent(category)}` : '/api/metadata/start';
            fetch(url).then(r=>r.json()).then(d=>{
                if(d.status === 'ok') addLog("SYSTEM", d.message);
                else alert(d.message);
            });
        }

        // 4. 엔진 중지 함수
        function stopMeta() {
            if(!confirm("진행 중인 매칭 작업을 중지하시겠습니까?")) return;
            fetch('/api/metadata/stop').then(r=>r.json()).then(d=>{ addLog("SYSTEM", d.message); });
        }

        // 5. 실패 항목 초기화 함수
        function resetFail() {
            if(!confirm("매칭에 실패했던 항목들을 초기화하고 다시 시도하시겠습니까?")) return;
            fetch('/api/metadata/reset_fail').then(r=>r.json()).then(d=>{
                alert(d.message);
                addLog("SYSTEM", d.message);
            });
        }

        // 6. 상태 실시간 업데이트 함수
        function update() {
            fetch('/api/indexing/status').then(r=>r.json()).then(d=>{
                const p = (d.total_dirs > 0 ? (d.processed_dirs / d.total_dirs * 100) : 0).toFixed(2);
                document.getElementById('progress-bar').style.width = p + '%';
                document.getElementById('progress-text').innerText = `${d.processed_dirs.toLocaleString()} / ${d.total_dirs.toLocaleString()} (${p}%)`;
                document.getElementById('stat-total').innerText = d.songs_found.toLocaleString();
                if(d.last_log && d.last_log !== lastIdxLog) { addLog("INDEX", d.last_log); lastIdxLog = d.last_log; }
            });

            fetch('/api/metadata/status').then(r=>r.json()).then(d=>{
                const p = (d.total > 0 ? (d.current / d.total * 100) : 0).toFixed(1);
                document.getElementById('meta-progress').innerText = `${p}% (${d.current.toLocaleString()} / ${d.total.toLocaleString()})`;
                document.getElementById('meta-db-success').innerText = (d.db_success || 0).toLocaleString();
                document.getElementById('meta-db-fail').innerText = (d.db_fail || 0).toLocaleString();
                document.getElementById('meta-db-pending').innerText = (d.db_pending || 0).toLocaleString();
                document.getElementById('meta-target-badge').innerText = "대상: " + (d.target || "전체");
                if(d.last_log && d.last_log !== lastMetaLog) { addLog("META", d.last_log); lastMetaLog = d.last_log; }
                document.getElementById('stop-meta-btn').style.display = d.is_running ? 'block' : 'none';
            });
        }

        // 초기화
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
    print("[*] 🛠️ DB 엔진 최적화 및 테이블 점검 중...")
    with sqlite3.connect(DB_PATH, timeout=600) as conn:
        conn.execute('PRAGMA journal_mode=WAL')

        # 1. global_songs 테이블
        conn.execute('''
            CREATE TABLE IF NOT EXISTS global_songs (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT, artist TEXT, albumName TEXT,
                stream_url TEXT, parent_path TEXT, meta_poster TEXT,
                genre TEXT, release_date TEXT, album_artist TEXT
            )
        ''')

        # 2. themes 테이블 생성
        conn.execute('''
            CREATE TABLE IF NOT EXISTS themes (
                type TEXT, name TEXT, path TEXT, image_url TEXT,
                PRIMARY KEY (type, path)
            )
        ''')

        # [중요] 기존 테이블에 image_url 컬럼이 없는 경우를 위한 강제 추가 로직
        try:
            conn.execute("ALTER TABLE themes ADD COLUMN image_url TEXT")
            print("[*] themes 테이블에 image_url 컬럼을 추가했습니다.")
        except sqlite3.OperationalError:
            # 이미 컬럼이 있는 경우 에러가 나므로 무시함
            pass

        conn.execute('CREATE INDEX IF NOT EXISTS idx_grouping ON global_songs(artist, albumName)')
        conn.execute('CREATE INDEX IF NOT EXISTS idx_path ON global_songs(parent_path)')
        conn.commit()
    print("[*] ✅ DB 구조 점검 완료.")


def load_cache():
    global cache
    print("[*] 🔄 시스템 캐시 로딩 시작...")
    try:
        with sqlite3.connect(DB_PATH) as conn:
            conn.row_factory = sqlite3.Row
            total_count = 0
            for t in ["charts", "collections", "artists", "genres"]:
                rows = conn.execute("SELECT name, path, image_url FROM themes WHERE type=?", (t,)).fetchall()
                cache[t] = [dict(r) for r in rows]
                count = len(rows)
                total_count += count
                print(f"    - {t.upper():<12}: {count}개 항목 로드됨")

            print(f"[*] 🎉 캐시 로딩 완료 (총 {total_count}개 항목)")

            # 데이터가 비어있으면 경고 로그 출력
            if total_count == 0:
                print("[!] 경고: DB에 저장된 테마 데이터가 없습니다. '라이브러리 재스캔'이 필요합니다.")

    except Exception as e:
        print(f"[!] 캐시 로딩 중 치명적 오류: {e}")


def get_info(f, d):
    nm = os.path.splitext(f)[0]    art, tit = ("Unknown Artist", nm)

    # 1. 파일명에서 추출 시도 (가수 - 제목)
    if " - " in nm:
        parts = nm.split(" - ", 1)
        art_part = parts[0]
        art = art_part.split(". ", 1)[-1].strip() if ". " in art_part else art_part.strip()
        tit = parts[1].strip()
    elif " - " not in nm and "-" in nm: # 추가: 공백 없는 하이픈 처리
        parts = nm.split("-", 1)
        if len(parts[0]) > 1: # 너무 짧은 앞부분은 제외 (예: 01-제목)
            art = parts[0].strip()
            tit = parts[1].strip()

    rel_dir = os.path.relpath(d, MUSIC_BASE)

    # 2. 파일명에 가수가 없으면 폴더 구조 분석
    if art == "Unknown Artist":
        path_parts = rel_dir.split(os.sep)
        if len(path_parts) >= 4 and path_parts[0] == "국내" and path_parts[1] == "가수":
            art = path_parts[3]
        elif len(path_parts) >= 2 and any(g in path_parts[0] for g in GENRE_ROOTS.keys()):
            art = path_parts[1]
        elif len(path_parts) >= 2:
            parent_name = os.path.basename(d) # 부모 폴더명
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
            rows = conn.execute(
                "SELECT stream_url, parent_path FROM global_songs WHERE artist = 'Unknown Artist'").fetchall()

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
    except:
        return None


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
            conn.execute(
                "CREATE TABLE IF NOT EXISTS global_songs_staging (name TEXT, artist TEXT, albumName TEXT, stream_url TEXT, parent_path TEXT, meta_poster TEXT)")
            # staging 테이블에도 albumName 컬럼 확인
            try:
                conn.execute("ALTER TABLE global_songs_staging ADD COLUMN albumName TEXT")
            except:
                pass
            conn.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_staging_url ON global_songs_staging(stream_url)")

            # 이어하기 핵심: 이미 인덱싱된 URL 확인
            cursor = conn.execute(
                "SELECT stream_url FROM global_songs_staging UNION SELECT stream_url FROM global_songs")
            indexed_urls = {row[0] for row in cursor.fetchall()}

        # 2. 파일 목록 수집 (find)
        command = ['find', MUSIC_BASE, '-type', 'f', '(', '-iname', '*.mp3', '-o', '-iname', '*.m4a', '-o', '-iname',
                   '*.flac', '-o', '-iname', '*.dsf', ')']
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
                            conn.executemany("INSERT OR IGNORE INTO global_songs_staging VALUES (?,?,?,?,?,NULL)",
                                             batch)
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
        # [수정] 모든 컬럼을 명시하여 데이터 유실 방지
        conn.execute("""
            INSERT OR IGNORE INTO global_songs_staging (name, artist, albumName, stream_url, parent_path, meta_poster, genre, release_date, album_artist)
            SELECT name, artist, albumName, stream_url, parent_path, meta_poster, genre, release_date, album_artist FROM global_songs
        """)

        # (중략: 메타데이터 복구 쿼리 동일)
        conn.execute("""
            UPDATE global_songs_staging
            SET meta_poster = (SELECT meta_poster FROM global_songs WHERE global_songs.artist = global_songs_staging.artist AND global_songs.albumName = global_songs_staging.albumName AND meta_poster IS NOT NULL LIMIT 1)
            WHERE meta_poster IS NULL
        """)

        conn.execute("DROP TABLE IF EXISTS global_songs")
        conn.execute("ALTER TABLE global_songs_staging RENAME TO global_songs")
        conn.execute("CREATE INDEX IF NOT EXISTS idx_meta_lookup ON global_songs(artist, albumName)")
        conn.execute("CREATE INDEX IF NOT EXISTS idx_path ON global_songs(parent_path)")
        conn.commit()
    idx_st.update({"is_running": False, "last_log": "✅ 라이브러리 업데이트 완료!"})

def rebuild_library():
    global cache
    print("[*] 🔄 테마별 대표 이미지 추출 및 리스트 갱신 중...")

    def sub(p):
        return [d.name for d in os.scandir(p) if d.is_dir()] if os.path.exists(p) else []

    c_list = [{"name": d, "path": f"국내/차트/{d}"} for d in sorted(sub(CHART_ROOT))]
    m_list = [{"name": d, "path": f"국내/모음/{d}"} for d in sorted(sub(COLLECTION_ROOT))]
    g_list = [{"name": g, "path": g} for g in GENRE_ROOTS.keys()]

    a_list = []
    if os.path.exists(ARTIST_ROOT):
        all_a = [{"name": s.name, "path": f"국내/가수/{i.name}/{s.name}"}
                 for i in os.scandir(ARTIST_ROOT) if i.is_dir()
                 for s in os.scandir(i.path) if s.is_dir()]
        if all_a: a_list = random.sample(all_a, min(len(all_a), 60))

    with sqlite3.connect(DB_PATH) as conn:
        conn.execute("DELETE FROM themes")
        for t, l in [('charts', c_list), ('collections', m_list), ('artists', a_list), ('genres', g_list)]:
            for item in l:
                # [개선] meta_poster가 NULL, 'FAIL', 빈 문자열이 아닌 정상적인 URL만 가져오도록 함
                row = conn.execute(
                    "SELECT meta_poster FROM global_songs WHERE parent_path LIKE ? AND meta_poster IS NOT NULL AND meta_poster != 'FAIL' AND meta_poster != '' LIMIT 1",
                    (f"{item['path']}%",)
                ).fetchone()
                img = row[0] if row else None
                item['image_url'] = img
                conn.execute("INSERT OR REPLACE INTO themes (type, name, path, image_url) VALUES (?,?,?,?)",
                             (t, item['name'], item['path'], img))
        conn.commit()

    cache.update({"charts": c_list, "collections": m_list, "artists": a_list, "genres": g_list})
    print(f"[*] ✅ 테마 이미지 갱신 완료! (차트:{len(c_list)}, 모음:{len(m_list)}, 가수:{len(a_list)})")
    load_cache()


# ==========================================
# 4. 메타데이터 엔진 (국내 폴더 우선순위 적용)
# ==========================================
def clean_query_text(text, is_artist=False, folder_type=None):
    if not text or text == 'Unknown Artist': return ""
    s = text

    # 1단계: [가수 전용] 앨범 번호 제거 ("윤종신 2집" -> "윤종신")
    if is_artist:
        s = re.sub(r'\s*\d+\.?\d*\s*집', '', s).strip()

    # 2단계: [공통] 연도 및 날짜 제거 ("2000 대한민국" -> "대한민국", "2024.01.01" 제거)
    s = re.sub(r'^\d{4}\s+', ' ', s)
    s = re.sub(r'\d{2,4}\.\d{2}\.\d{2}', ' ', s)

    # 3단계: [공통] 괄호 및 대괄호 내용 제거 (가수는 fetch_metadata_smart에서 미리 분리함)
    s = re.sub(r'\(.*?\)|\[.*?\]|\{.*?\}', ' ', s)

    # 4단계: [공통] 트랙 번호 제거 ("001. ", "01- " 등 제거하되 "10cm" 보호)
    s = re.sub(r'^\d{1,3}[\s\.\-_/]+', '', s.strip())

    # 5단계: [앨범/제목 전용] 불필요한 음반 용어 제거
    if not is_artist:
        s = re.sub(r'(?i)\b(싱글|정규|베스트|미니앨범|EP|Album|Collection|TOP\s*100|차트)\b', ' ', s)

    # 6단계: [추가] 클래식/오페라/기술 태그 제거 ("Act 2", "Op.12", "FLAC" 등)
    keywords = [
        'CD\s*\d+', 'Disc\s*\d+', 'Vol\.\s*\d+', 'Part\.\s*\d+', 'Ver\.\s*\d+',
        'FLAC', '320K', 'HIDEL', 'Act\s*\d+', 'Scene\s*\d+', 'Op\.\s*\d+',
        'No\.\s*\d+', 'BWV\s*\d+', 'K\.\s*\d+', 'Selection', 'And', '그리고'
    ]
    s = re.sub(r'(?i)\b(' + '|'.join(keywords) + r')\b', ' ', s)

    # 7단계: [추가] 특수문자 정리 및 한자(大) 허용 (\u4e00-\u9fff 범위 추가)
    s = re.sub(r'[^\w\s가-힣ぁ-んァ-ヶー一-龠\u4e00-\u9fff]', ' ', s)

    # 8단계: [마무리] 다중 공백 제거
    return re.sub(r'\s+', ' ', s).strip()


def fetch_metadata_smart(artist, album, title, folder_type=None):
    # 1. 가수명 분리: 한글/영어 혼용 또는 여러 가수 대응
    # 예: "LE SSERAFIM 르세라핌 j hope" -> ["LE SSERAFIM", "르세라핌", "j hope"]
    raw_parts = re.split(r'[,/&]|\(|\)', artist)
    artist_list = []
    for rp in raw_parts:
        rp = rp.strip()
        if not rp: continue
        artist_list.append(rp)
        # 한글과 영어가 공백으로 섞여 있으면 추가로 쪼개기
        m = re.findall(r'[a-zA-Z\s]{2,}|[가-힣\s]{2,}', rp)
        if len(m) > 1:
            artist_list.extend([x.strip() for x in m if x.strip()])

    # 중복 제거 및 정제
    artist_list = list(dict.fromkeys(artist_list))

    q_alb = clean_query_text(album, is_artist=False, folder_type=folder_type)
    q_tit = clean_query_text(title, is_artist=False, folder_type=folder_type)
    is_chart = any(x in (album or "") for x in ['멜론', '지니', '벅스', '월간', '주간', '차트', 'TOP', 'Top'])
    is_theme = any(x in (folder_type or "") for x in ['차트', '모음']) or is_chart

    # 분리된 이름들로 루프를 돌며 검색 성공할 때까지 시도
    for raw_art in artist_list:
        q_art = clean_query_text(raw_art, is_artist=True, folder_type=folder_type)
        if not q_art: continue

        strategies = []
        if is_theme:
            if q_art and q_tit: strategies.append((q_art, q_tit))
            if q_art: strategies.append((q_art, ""))
        else:
            if q_art and q_alb and len(q_alb) > 1: strategies.append((q_art, q_alb))
            if q_art and q_tit: strategies.append((q_art, q_tit))
            if q_art: strategies.append((q_art, ""))

        seen = set()
        for a_q, b_q in strategies:
            if (a_q, b_q) in seen: continue
            seen.add((a_q, b_q))

            # Deezer (글로벌 소스 - 르세라핌 같은 영어권 가수 매칭에 강력함)
            res = fetch_deezer_metadata(a_q, b_q)
            if res: return res

            # Maniadb (국내 소스)
            if folder_type not in ['외국', 'DSD']:
                res = fetch_maniadb_metadata(a_q, b_q)
                if res: return res
            time.sleep(0.2)
    return None


def fetch_deezer_metadata(artist, album):
    # 1. 앨범(Album) 전용 검색 시도
    try:
        url = f"https://api.deezer.com/search/album?q={urllib.parse.quote(f'{artist} {album}')}&limit=1"
        res = requests.get(url, timeout=5).json()
        if res.get("data") and len(res["data"]) > 0:
            alb_id = res["data"][0]['id']
            d = requests.get(f"https://api.deezer.com/album/{alb_id}", timeout=5).json()
            return {
                "poster": d.get('cover_xl'),
                "genre": d.get('genres', {}).get('data', [{}])[0].get('name') if d.get('genres') else None,
                "release_date": d.get('release_date'),
                "album_artist": d.get('artist', {}).get('name')
            }
    except:
        pass

    # 2. 일반 검색 시도 (트랙 단위 - 앨범이 없는 곡일 때 효과적)
    try:
        url = f"https://api.deezer.com/search?q={urllib.parse.quote(f'{artist} {album}')}&limit=1"
        res = requests.get(url, timeout=5).json()
        if res.get("data") and len(res["data"]) > 0:
            alb = res["data"][0].get('album', {})
            if alb.get('cover_xl'):
                return {"poster": alb.get('cover_xl'), "album_artist": res["data"][0].get('artist', {}).get('name')}
    except:
        pass
    return None

def fetch_maniadb_metadata(artist, album):
    # 쿼리 문자열 양끝 공백 제거 (매칭률 향상)
    q = f"{artist} {album}".strip()
    for mode in ['album', 'song']:
        try:
            url = f"http://www.maniadb.com/api/search/{urllib.parse.quote(q)}/?sr={mode}&display=1&key=example&v=0.5"
            res = requests.get(url, timeout=8, headers={'User-Agent': 'Mozilla/5.0'})
            if res.status_code == 200:
                img = re.search(r'<image><!\[CDATA\[(.*?)]]>', res.text)
                if img: return {"poster": img.group(1).replace("/s/", "/l/"), "genre": "K-Pop"}
        except: continue
    return None


def start_metadata_update_thread(query_tag=None):
    global up_st
    if up_st["is_running"]: return

    # 1. Unknown Artist 사전 복구 작업 실행
    fix_unknown_artists_in_db()

    up_st["is_running"] = True
    up_st["target"] = query_tag if query_tag else "전체"
    display_name = up_st["target"]
    print(f"[*] 🚀 메타데이터 엔진 가동 - [대상: {display_name}]")

    try:
        with sqlite3.connect(DB_PATH, timeout=120) as conn:
            conn.row_factory = sqlite3.Row
            # 'Unknown Artist'는 검색 대상에서 제외하거나 뒤로 미룸 (검색 효율성)
            sql = "SELECT artist, albumName, MAX(name) as title FROM global_songs WHERE (meta_poster IS NULL OR meta_poster = '' OR meta_poster = 'FAIL')"
            params = []
            if query_tag:
                sql += " AND parent_path LIKE ?"
                params.append(f"{query_tag}%")
            sql += " AND artist != 'Unknown Artist' AND artist != ''" # 정체 불명 제외
            sql += " GROUP BY artist, albumName LIMIT 30000"
            targets = conn.execute(sql, params).fetchall()

        if not targets:
            up_st["last_log"] = "✅ 처리할 수 있는 대상이 없습니다 (Unknown Artist 제외)."
            up_st["is_running"] = False
            return

        up_st.update({"total": len(targets), "current": 0, "success": 0, "fail": 0})
        db_q = queue.Queue()
        update_lock = threading.Lock() # 스레드 안전한 카운터 증가용

        def db_worker():
            batch = []
            while True:
                try:
                    item = db_q.get(timeout=3)
                    if item is None: break
                    batch.append(item)
                    if len(batch) >= 100:
                        save_batch(batch)
                        batch = []
                    db_q.task_done()
                except queue.Empty:
                    if batch: save_batch(batch)
                    if not up_st["is_running"]: break
            if batch: save_batch(batch)

        def save_batch(items):
            with sqlite3.connect(DB_PATH, timeout=60) as conn:
                for art, alb, res in items:
                    if res and res.get('poster'):
                        conn.execute(
                            "UPDATE global_songs SET meta_poster=?, genre=?, release_date=?, album_artist=? WHERE artist=? AND albumName=?",
                            (res['poster'], res.get('genre'), res.get('release_date'), res.get('album_artist'), art, alb))
                        with update_lock: up_st["success"] += 1
                    else:
                        conn.execute("UPDATE global_songs SET meta_poster='FAIL' WHERE artist=? AND albumName=?", (art, alb))
                        with update_lock: up_st["fail"] += 1
                conn.commit()

        worker_thread = Thread(target=db_worker, daemon=True)
        worker_thread.start()

        with ThreadPoolExecutor(max_workers=5) as executor:
            futures = []
            for row in targets:
                if not up_st["is_running"]: break

                def run_match(r=row, f_type=query_tag):
                    try:
                        q_a = clean_query_text(r['artist'], is_artist=True)
                        if not q_a: # 정제 후 이름이 없으면 실패 처리
                            db_q.put((r['artist'], r['albumName'], None))
                            with update_lock: up_st["current"] += 1
                            return

                        res = fetch_metadata_smart(r['artist'], r['albumName'], r['title'], folder_type=f_type)
                        db_q.put((r['artist'], r['albumName'], res))

                        with update_lock:
                            up_st["current"] += 1
                            icon = "✅" if res else "❌"
                            up_st["last_log"] = f"[{display_name}] {up_st['current']}/{up_st['total']} | {q_a} -> {icon}"
                    except:
                        with update_lock: up_st["current"] += 1

                futures.append(executor.submit(run_match))

            for future in as_completed(futures): pass

        db_q.put(None)
        worker_thread.join()
        rebuild_library()
    except Exception as e:
        print(f"[!] Fatal: {e}")
    finally:
        up_st["is_running"] = False
        print(f"[*] 🏁 [{display_name}] 엔진 종료")

@app.route('/api/admin/query', methods=['POST'])
def run_query():
    sql = request.json.get('query')
    if not sql.strip().upper().startswith("SELECT"):
        return jsonify({"error": "안전을 위해 SELECT 문만 실행 가능합니다."})
    try:
        with sqlite3.connect(DB_PATH, timeout=10) as conn:
            conn.row_factory = sqlite3.Row
            res = conn.execute(sql).fetchall()
            return jsonify([dict(r) for r in res])
    except Exception as e:
        return jsonify({"error": str(e)})


@app.route('/api/metadata/stop')
def stop_meta():
    global up_st
    up_st["is_running"] = False
    return jsonify({"status": "ok", "message": "엔진 중지 명령을 보냈습니다."})


@app.route('/monitor')
def render_monitor(): return render_template_string(MONITOR_HTML)


@app.route('/api/indexing/status')
def get_idx(): return jsonify(idx_st)


@app.route('/api/metadata/start')
def start_meta():
    q = request.args.get('q')
    print(f"[*] 🔔 메타데이터 가동 요청 수신! (대상: {q if q else '전체'})")
    if not up_st["is_running"]:
        Thread(target=start_metadata_update_thread, args=(q,)).start()
        return jsonify({"status": "ok", "message": f"[{q if q else '전체'}] 엔진을 가동합니다."})
    else:
        return jsonify({"status": "error", "message": f"이미 엔진이 작동 중입니다. (현재 대상: {up_st['target']})"})


@app.route('/api/themes')
def get_themes(): return jsonify(cache)


@app.route('/api/theme-details/<path:tp>')
def get_details(tp):
    p = urllib.parse.unquote(tp)
    with sqlite3.connect(DB_PATH) as conn:
        conn.row_factory = sqlite3.Row
        rows = conn.execute(
            "SELECT name, artist, albumName, stream_url, parent_path, meta_poster FROM global_songs WHERE parent_path LIKE ? ORDER BY artist, name ASC LIMIT 500",
            (f"{p}%",)
        ).fetchall()
        return jsonify([dict(r) for r in rows])


@app.route('/api/refresh')
def refresh():
    Thread(target=rebuild_library).start()
    return jsonify({"status": "started"})


@app.route('/api/metadata/reset_fail')
def reset_fail():
    try:
        with sqlite3.connect(DB_PATH) as conn:
            # 'FAIL'이나 빈 문자열인 항목을 모두 NULL로 초기화 (노래 단위)
            cursor = conn.execute(
                "UPDATE global_songs SET meta_poster = NULL WHERE meta_poster = 'FAIL' OR meta_poster = ''")
            count = cursor.rowcount
            conn.commit()

        msg = f"🔄 {count:,}개 항목의 실패/미매칭 기록을 초기화했습니다."
        up_st["last_log"] = msg
        return jsonify({"status": "ok", "message": msg})
    except Exception as e:
        error_msg = f"❌ 초기화 중 오류: {str(e)}"
        up_st["last_log"] = error_msg
        return jsonify({"status": "error", "message": error_msg})


# NasMusicPlayer.py에 추가할 내용

@app.route('/api/top100')
def get_top100():
    """메인 화면 하단 '멜론 주간 TOP 100' 리스트를 반환"""
    try:
        # DB의 경로 형식과 맞추기 위해 os.sep를 처리
        rel_path = os.path.relpath(WEEKLY_CHART_PATH, MUSIC_BASE).replace('\\', '/')
        with sqlite3.connect(DB_PATH) as conn:
            conn.row_factory = sqlite3.Row
            # [중요] genre, release_date, album_artist 및 is_dir(0) 추가
            rows = conn.execute(
                """SELECT name, artist, albumName, stream_url, parent_path, meta_poster,
                          genre, release_date, album_artist, 0 as is_dir
                   FROM global_songs
                   WHERE parent_path LIKE ?
                   ORDER BY name ASC""",
                (f"{rel_path}%",)
            ).fetchall()

            result = [dict(r) for r in rows]
            print(f"[*] Top100 호출: {len(result)}곡 발견 (경로: {rel_path})")
            return jsonify(result)
    except Exception as e:
        print(f"[!] Top100 오류: {e}")
        return jsonify([])


@app.route('/api/search')
def search_songs():
    """서버 내 라이브러리 검색 API"""
    q = request.args.get('q', '').strip()
    if not q: return jsonify([])
    try:
        with sqlite3.connect(DB_PATH) as conn:
            conn.row_factory = sqlite3.Row
            # 검색에서도 동일하게 0 as is_dir 추가
            rows = conn.execute(
                """SELECT name, artist, albumName, stream_url, parent_path, meta_poster,
                          genre, release_date, album_artist, 0 as is_dir
                   FROM global_songs
                   WHERE name LIKE ? OR artist LIKE ? OR albumName LIKE ?
                   LIMIT 100""",
                (f"%{q}%", f"%{q}%", f"%{q}%")
            ).fetchall()
            return jsonify([dict(r) for r in rows])
    except Exception as e:
        print(f"[!] 검색 오류: {e}")
        return jsonify([])

@app.route('/api/metadata/status')
def get_meta():
    res = up_st.copy()
    try:
        with sqlite3.connect(DB_PATH, timeout=5) as conn:
            # 앨범(그룹) 단위로 상태를 집계하여 대시보드에 표시
            stats = conn.execute("""
                SELECT
                    COUNT(*),
                    COUNT(CASE WHEN status = 'success' THEN 1 END),
                    COUNT(CASE WHEN status = 'fail' THEN 1 END),
                    COUNT(CASE WHEN status = 'pending' THEN 1 END)
                FROM (
                    SELECT
                        CASE
                            WHEN MAX(meta_poster) IS NOT NULL AND MAX(meta_poster) NOT IN ('', 'FAIL') THEN 'success'
                            WHEN MAX(meta_poster) = 'FAIL' THEN 'fail'
                            ELSE 'pending'
                        END as status
                    FROM global_songs
                    GROUP BY artist, albumName
                )
            """).fetchone()

            if stats:
                res.update({
                    "db_total": stats[0] or 0,
                    "db_success": stats[1] or 0,
                    "db_fail": stats[2] or 0,
                    "db_pending": stats[3] or 0
                })
    except:
        pass
    return jsonify(res)


@app.route('/stream/<path:fp>')
def stream(fp): return send_from_directory(MUSIC_BASE, urllib.parse.unquote(fp))


if __name__ == '__main__':
    init_db()
    load_cache()
    app.run(host='0.0.0.0', port=4444, debug=False)
