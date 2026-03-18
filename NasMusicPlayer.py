from flask import Flask, jsonify, send_from_directory, request, render_template_string
import os, sqlite3, json, urllib.parse, time, random, requests, subprocess, shutil, re
from flask_cors import CORS
from threading import Thread
from concurrent.futures import ThreadPoolExecutor, as_completed
import queue
import threading  # 상단 import에 추가

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

META_STRATEGIES = {
    "국내":   {"priority": ["maniadb", "deezer"], "clean": "korean"},
    "외국":   {"priority": ["itunes", "deezer"], "clean": "western"},
    "일본":   {"priority": ["itunes", "deezer"], "clean": "japanese"},
    "클래식": {"priority": ["itunes", "deezer"], "clean": "classical"},
    "DSD":    {"priority": ["itunes", "deezer"], "clean": "western"},
    "OST":    {"priority": ["itunes", "deezer"], "clean": "western"}
}

# 1. 상단 전역 변수 영역에 추가
cached_meta_stats = {"timestamp": 0, "data": None}
# 통계 데이터 캐시 변수 추가
cached_db_stats = {"data": None, "time": 0}

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
    <title>NasMusic Admin Pro</title>
    <style>
        :root { --bg: #0f172a; --card: #1e293b; --text: #f8fafc; --accent: #38bdf8; --success: #22c55e; --warning: #f59e0b; --danger: #ef4444; --border: #334155; }
        body { font-family: 'Pretendard', sans-serif; background: var(--bg); color: var(--text); margin: 0; padding: 0; overflow-x: hidden; }

        /* Layout */
        .sidebar { width: 260px; background: #020617; height: 100vh; position: fixed; border-right: 1px solid var(--border); z-index: 100; }
        .content { margin-left: 260px; padding: 40px; min-height: 100vh; }

        /* Sidebar Menu */
        .logo { padding: 30px; font-size: 1.6rem; font-weight: bold; color: var(--accent); border-bottom: 1px solid var(--border); letter-spacing: -1px; }
        .nav-item { padding: 18px 30px; cursor: pointer; transition: 0.3s; color: #94a3b8; display: flex; align-items: center; gap: 12px; font-size: 1rem; }
        .nav-item:hover { background: rgba(56, 189, 248, 0.05); color: var(--text); }
        .nav-item.active { background: rgba(56, 189, 248, 0.1); color: var(--accent); border-right: 4px solid var(--accent); font-weight: bold; }

        /* Dashboard Components */
        .dashboard-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(380px, 1fr)); gap: 25px; }
        .box { background: var(--card); padding: 25px; border-radius: 16px; border: 1px solid var(--border); box-shadow: 0 10px 15px -3px rgba(0,0,0,0.1); }
        .full-width { grid-column: 1 / -1; }

        h2 { margin-top: 0; font-size: 1.2rem; color: #cbd5e1; margin-bottom: 20px; display: flex; align-items: center; gap: 10px; }

        /* Stats */
        .stat-grid { display: grid; grid-template-columns: repeat(2, 1fr); gap: 15px; }
        .stat-card { background: rgba(15, 23, 42, 0.4); padding: 20px; border-radius: 12px; border: 1px solid var(--border); text-align: center; }
        .stat-value { font-size: 1.6rem; font-weight: bold; display: block; color: var(--accent); margin-bottom: 4px; }
        .stat-label { font-size: 0.8rem; color: #94a3b8; font-weight: 500; }

        /* Progress Bar */
        .progress-wrapper { margin-top: 20px; }
        .progress-info { display: flex; justify-content: space-between; font-size: 0.85rem; margin-bottom: 8px; color: #94a3b8; }
        .progress-bar { height: 10px; background: #1e293b; border-radius: 5px; overflow: hidden; border: 1px solid #334155; }
        .progress-fill { height: 100%; background: linear-gradient(90deg, #38bdf8, #818cf8); width: 0%; transition: width 0.6s cubic-bezier(0.4, 0, 0.2, 1); }

        /* Table & Inputs */
        .table-container { width: 100%; overflow-x: auto; margin-top: 10px; border-radius: 8px; border: 1px solid var(--border); }
        table { width: 100%; border-collapse: collapse; font-size: 14px; text-align: left; }
        th { padding: 15px; background: #0f172a; color: #94a3b8; font-weight: 600; border-bottom: 2px solid var(--border); }
        td { padding: 14px; border-bottom: 1px solid var(--border); color: #e2e8f0; }
        tr:hover { background: rgba(56, 189, 248, 0.03); }

        .log-container { background: #020617; color: #10b981; padding: 20px; height: 400px; overflow-y: auto; font-family: 'Fira Code', monospace; font-size: 13px; border-radius: 12px; border: 1px solid var(--border); line-height: 1.6; }

        button { background: var(--accent); color: #0f172a; border: none; padding: 12px 22px; border-radius: 8px; font-weight: 700; cursor: pointer; transition: 0.2s; display: inline-flex; align-items: center; justify-content: center; gap: 8px; }
        button:hover { transform: translateY(-2px); filter: brightness(1.1); box-shadow: 0 4px 12px rgba(56, 189, 248, 0.3); }
        button.secondary { background: #334155; color: white; }
        button.danger { background: var(--danger); color: white; }

        .sql-editor { width: 100%; height: 160px; background: #020617; color: #f8fafc; border: 1px solid var(--border); border-radius: 12px; padding: 20px; font-family: 'Fira Code', monospace; font-size: 14px; margin-bottom: 15px; resize: vertical; }

        .tab-content { display: none; animation: fadeIn 0.4s ease; }
        .tab-content.active { display: block; }
        @keyframes fadeIn { from { opacity: 0; transform: translateY(10px); } to { opacity: 1; transform: translateY(0); } }

        .filter-bar { display: flex; gap: 12px; margin-bottom: 25px; background: rgba(15, 23, 42, 0.3); padding: 15px; border-radius: 12px; border: 1px solid var(--border); }
        select, input { background: #020617; border: 1px solid var(--border); color: white; padding: 10px 15px; border-radius: 8px; outline: none; transition: 0.2s; }
        select:focus, input:focus { border-color: var(--accent); box-shadow: 0 0 0 2px rgba(56, 189, 248, 0.2); }
    </style>
</head>
<body>
    <div class="sidebar">
        <div class="logo">NasMusic PRO</div>
        <div class="nav-item active" onclick="switchTab('dashboard', this)">📊 실시간 모니터링</div>
        <div class="nav-item" onclick="switchTab('explorer', this)">🔎 데이터 브라우저</div>
        <div class="nav-item" onclick="switchTab('sql', this)">🛠️ SQL 런너 (Expert)</div>
    </div>

    <div class="content">
        <!-- [1] 실시간 모니터링 탭 -->
        <div id="tab-dashboard" class="tab-content active">
            <div class="dashboard-grid">
                <div class="box">
                    <h2>📁 인덱싱 엔진 상태</h2>
                    <div class="stat-grid">
                        <!-- 전체 항목 표시 카드 추가 -->
                        <div class="stat-card" style="grid-column: 1 / -1; background: rgba(56, 189, 248, 0.1); margin-bottom: 10px;">
                            <span id="meta-db-total" class="stat-value">0</span>
                            <span class="stat-label">전체 라이브러리 항목 (앨범/가수 그룹 기준)</span>
                        </div>
                        <div class="stat-card">
                            <span id="idx-total" class="stat-value">0</span>
                            <span class="stat-label">라이브러리 총 곡수</span>
                        </div>
                        <div class="stat-card">
                            <span id="idx-speed" class="stat-value">0</span>
                            <span class="stat-label">처리 속도 (곡/초)</span>
                        </div>
                    </div>
                    <!-- 인덱싱 엔진 상태 박스 내부, stat-grid 아래에 추가 -->
                    <div class="filter-bar" style="margin-top:20px; display: flex; gap: 8px; background: rgba(255,255,255,0.05); padding: 12px; border-radius: 10px;">
                        <select id="scan-target" style="flex: 1; background: #020617; border: 1px solid var(--border); color: white; padding: 8px;">
                            <option value="전체">📂 라이브러리 전체</option>
                            <option value="국내">🇰🇷 국내 폴더</option>
                            <option value="외국">🌎 외국 폴더</option>
                            <option value="일본">🇯🇵 일본</option>
                            <option value="클래식">🎻 클래식</option>
                            <option value="DSD">💿 DSD</option>
                            <option value="OST">🎬 OST</option>
                        </select>
                        <button onclick="startScan()" style="background: #6366f1; white-space: nowrap; padding: 8px 16px;">⚡ 선택 폴더 재스캔</button>
                    </div>
                    <div class="progress-wrapper">
                        <div class="progress-info">
                            <span id="idx-progress-text">진행률: 0%</span>
                            <span id="idx-eta">남은 시간 계산 중...</span>
                        </div>
                        <div class="progress-bar"><div id="idx-fill" class="progress-fill"></div></div>
                    </div>
                    <p id="idx-log" style="font-size: 0.85rem; color: #94a3b8; margin-top: 15px; background: rgba(0,0,0,0.2); padding: 10px; border-radius: 6px;">상태 확인 중...</p>
                </div>

                <div class="box">
                    <h2>🖼️ 메타데이터 매칭 현황</h2>
    <div class="stat-grid" style="grid-template-columns: repeat(2, 1fr); gap: 12px;">
        <div class="stat-card">
            <span id="meta-db-success" class="stat-value" style="color: var(--success);">0</span>
            <span class="stat-label">전체 성공 (OK)</span>
        </div>
        <div class="stat-card">
            <span id="meta-db-fail" class="stat-value" style="color: var(--danger);">0</span>
            <span class="stat-label">전체 실패 (초기화대상)</span>
        </div>
        <!-- 실시간 세션 정보 (작동 시 강조됨) -->
        <div class="stat-card" style="background: rgba(56, 189, 248, 0.05); border-color: rgba(56, 189, 248, 0.2);">
            <span id="meta-session-success" class="stat-value" style="font-size: 1.3rem; color: var(--accent);">0</span>
            <span class="stat-label" style="font-size: 0.75rem;">세션 성공</span>
        </div>
        <div class="stat-card" style="background: rgba(245, 158, 11, 0.05); border-color: rgba(245, 158, 11, 0.2);">
            <span id="meta-session-fail" class="stat-value" style="font-size: 1.3rem; color: var(--warning);">0</span>
            <span class="stat-label" style="font-size: 0.75rem;">세션 실패</span>
        </div>
    </div>
                    <div style="margin-top: 25px; display: grid; grid-template-columns: 1fr 1fr; gap: 12px;">
                        <button class="secondary" onclick="startMeta('국내')">🇰🇷 국내 엔진 가동</button>
                        <button class="secondary" onclick="startMeta('외국')">🌎 외국 엔진 가동</button>
                        <button class="secondary" onclick="startMeta('일본')">🇯🇵 일본</button>
                        <button class="secondary" onclick="startMeta('클래식')">🎻 클래식</button>
                        <button class="secondary" onclick="startMeta('DSD')">💿 DSD</button>
                        <button class="secondary" onclick="startMeta('OST')">🎬 OST</button>
                        <button class="danger" onclick="resetFail()">🔄 실패 항목 초기화</button>
                        <button style="flex: 2; background: #6366f1;" onclick="startMeta('')">🔥 전체 자동 갱신</button>
                        <button id="stop-btn" class="danger" onclick="stopMeta()" style="display:none;">🛑 엔진 즉시 중지</button>
                    </div>
                </div>

                <div class="box full-width">
                    <h2>📝 시스템 커널 로그</h2>
                    <div id="log-box" class="log-container"></div>
                </div>
            </div>
        </div>

        <!-- [2] 데이터 브라우저 탭 -->
        <div id="tab-explorer" class="tab-content">
            <div class="box full-width">
                <h2>🔎 데이터베이스 실시간 조회</h2>
                <div class="filter-bar">
                    <select id="exp-cat" onchange="loadExplorer(1)">
                        <option value="All">전체 카테고리</option>
                        <option value="국내">🇰🇷 국내</option>
                        <option value="외국">🌎 외국</option>
                        <option value="일본">🌎 일본</option>
                        <option value="클래식">🎻 클래식</option>
                        <option value="DSD">🎻 DSD</option>
                        <option value="OST">🎬 OST</option>
                    </select>
                    <label style="display: flex; align-items: center; gap: 6px; color: #94a3b8; font-size: 0.85rem; cursor: pointer; white-space: nowrap; padding: 0 10px;">
                        <input type="checkbox" id="exp-fail-only" onchange="loadExplorer(1)" style="width: 16px; height: 16px;">
                        ❌ 실패 항목만
                    </label>
                    <input type="text" id="exp-search" placeholder="가수, 제목, 앨범명 검색..." style="flex: 1;" onkeyup="if(event.key==='Enter') loadExplorer(1)">
                    <button onclick="loadExplorer(1)">검색/새로고침</button>
                </div>
                <div class="table-container">
                    <table>
                        <thead>
                            <tr>
                                <th style="width: 60px;">ID</th>
                                <th>제목</th>
                                <th>아티스트</th>
                                <th>앨범명</th>
                                <th style="width: 100px; text-align: center;">메타</th>
                                <th>장르</th>
                            </tr>
                        </thead>
                        <tbody id="exp-body"></tbody>
                    </table>
                </div>
                <div style="margin-top: 25px; display: flex; justify-content: space-between; align-items: center;">
                    <div style="color: #94a3b8; font-size: 0.9rem;">
                        페이지: <span id="exp-page" style="color: var(--accent); font-weight: bold;">1</span>
                    </div>
                    <div style="display: flex; gap: 10px;">
                        <button class="secondary" onclick="movePage(-1)">이전 페이지</button>
                        <button class="secondary" onclick="movePage(1)">다음 페이지</button>
                    </div>
                </div>
            </div>
        </div>

        <!-- [3] SQL 런너 탭 -->
        <div id="tab-sql" class="tab-content">
            <div class="box full-width">
                <h2>🛠️ SQL Expert Runner</h2>
                <p style="color: #94a3b8; font-size: 0.85rem; margin-bottom: 15px;">안전을 위해 <span style="color: var(--warning)">SELECT</span> 문만 실행 가능하도록 제한되어 있습니다.</p>
                <textarea id="sql-input" class="sql-editor" placeholder="예: SELECT name, artist, meta_poster FROM global_songs WHERE meta_poster IS NULL LIMIT 10;">SELECT name, artist, albumName, meta_poster FROM global_songs WHERE meta_poster = 'FAIL' LIMIT 20;</textarea>
                <button onclick="runSQL()">🚀 SQL 실행</button>
                <div id="sql-result-wrapper" class="table-container" style="margin-top: 25px; display: none;">
                    <table id="sql-table">
                        <thead id="sql-head"></thead>
                        <tbody id="sql-body"></tbody>
                    </table>
                </div>
                <div id="sql-error" style="margin-top: 15px; color: var(--danger); font-family: monospace; display: none; background: rgba(239, 68, 68, 0.1); padding: 15px; border-radius: 8px; border: 1px solid rgba(239, 68, 68, 0.2);"></div>
            </div>
        </div>
    </div>

    <script>
        let lastMetaLog = "";
        let expPage = 1;



        // 로그 출력 최적화
        function addLog(msg) {
            const box = document.getElementById('log-box');
            const div = document.createElement('div');
            div.style.padding = "2px 0";
            div.style.borderBottom = "1px solid rgba(255,255,255,0.03)";
            div.innerHTML = `<span style="color: #64748b; font-size: 11px;">[${new Date().toLocaleTimeString()}]</span> <span style="margin-left:8px;">${msg}</span>`;
            box.appendChild(div);
            box.scrollTop = box.scrollHeight;
            if(box.childElementCount > 400) box.removeChild(box.firstChild);
        }

        async function loadExplorer(page) {
            expPage = page;
            const cat = document.getElementById('exp-cat').value;
            const q = document.getElementById('exp-search').value;

            // [추가] 체크박스 상태 확인
            const failOnly = document.getElementById('exp-fail-only').checked;
            const tbody = document.getElementById('exp-body');

            if(!tbody) return; // 요소가 없으면 중단

            try {
                const res = await fetch(`/api/admin/data?category=${cat}&q=${encodeURIComponent(q)}&page=${page}&fail_only=${failOnly}`);
                const data = await res.json();

                // [중요] 데이터가 배열인지 확인 (객체면 에러 문구 출력)
                if (!Array.isArray(data)) {
                    console.error("Expected array but got:", data);
                    tbody.innerHTML = `<tr><td colspan="5" style="text-align:center; color:var(--danger); padding:20px;">서버 응답 형식이 올바르지 않습니다.</td></tr>`;
                    return;
                }

                if (data.length === 0) {
                    tbody.innerHTML = `<tr><td colspan="5" style="text-align:center; padding:20px; color:#64748b;">조회된 결과가 없습니다.</td></tr>`;
                } else {
                    tbody.innerHTML = data.map(r => `
                        <tr style="border-bottom: 1px solid #334155;">
                            <td style="padding:12px; color:#64748b;">${r.id}</td>
                            <td style="padding:12px; font-weight:600;">${r.name || '-'}</td>
                            <td style="padding:12px; color:var(--accent); font-weight:500;">${r.artist || '-'}</td>
                            <td style="padding:12px;">${r.albumName || '-'}</td>
                            <td style="padding:12px; text-align:center;">${r.meta_poster && r.meta_poster !== 'FAIL' ? '✅' : '❌'}</td>
                            <td style="padding:12px; text-align:center;">
                                <button onclick="openManualMatch(${r.id}, '${(r.artist||'').replace(/'/g, "\\'")}', '${(r.name||'').replace(/'/g, "\\'")}', '${(r.albumName||'').replace(/'/g, "\\'")}')"
                                        style="padding: 4px 8px; font-size: 11px; background:#4f46e5;">수동</button>
                            </td>
                        </tr>`).join('');
                }
                document.getElementById('exp-page').innerText = page + ' 페이지';
            } catch (e) {
                console.error("Load Error:", e);
                tbody.innerHTML = `<tr><td colspan="5" style="text-align:center; color:var(--danger); padding:20px;">데이터 로딩 중 치명적 오류 발생</td></tr>`;
            }
        }

        let currentManualTarget = null;

        function openManualMatch(id, artist, title, album) {
            currentManualTarget = { id, artist, album };
            document.getElementById('manual-modal').style.display = 'flex';
            document.getElementById('manual-search-q').value = `${artist} ${title}`;
            document.getElementById('manual-results').innerHTML = '<p style="color:#94a3b8;">검색 버튼을 눌러주세요.</p>';
        }

        function closeManualMatch() {
            document.getElementById('manual-modal').style.display = 'none';
        }

        async function searchManual() {
            const q = document.getElementById('manual-search-q').value;
            const resDiv = document.getElementById('manual-results');
            resDiv.innerHTML = '<p style="color:var(--accent);">검색 중...</p>';

            try {
                const res = await fetch(`/api/admin/search_meta?q=${encodeURIComponent(q)}`);
                const data = await res.json();

                if (data.length === 0) {
                    resDiv.innerHTML = '<p style="color:var(--danger);">결과가 없습니다.</p>';
                    return;
                }

                resDiv.innerHTML = data.map(item => `
                    <div style="cursor:pointer; border:1px solid #334155; padding:8px; border-radius:8px; text-align:center; transition:0.2s;"
                         onmouseover="this.style.borderColor='var(--accent)'" onmouseout="this.style.borderColor='#334155'"
                         onclick="applyManualMeta('${item.poster.replace(/'/g, "\\'")}')">
                        <img src="${item.poster}" style="width:100%; aspect-ratio:1; border-radius:4px; object-fit:cover; margin-bottom:8px;">
                        <div style="font-size:11px; font-weight:bold; white-space:nowrap; overflow:hidden; text-overflow:ellipsis; color:white;">${item.artist}</div>
                        <div style="font-size:10px; color:#94a3b8; white-space:nowrap; overflow:hidden; text-overflow:ellipsis;">${item.album}</div>
                    </div>
                `).join('');
            } catch (e) {
                resDiv.innerHTML = '<p style="color:var(--danger);">검색 중 오류 발생</p>';
            }
        }

        async function applyManualMeta(poster) {
            if (!confirm("이 이미지를 이 앨범의 모든 곡에 적용하시겠습니까?")) return;

            try {
                const res = await fetch('/api/admin/apply_meta', {
                    method: 'POST',
                    headers: {'Content-Type': 'application/json'},
                    body: JSON.stringify({
                        artist: currentManualTarget.artist,
                        album: currentManualTarget.album,
                        poster: poster
                    })
                });
                const data = await res.json();
                alert(data.message);
                closeManualMatch();
                loadExplorer(expPage); // 현재 페이지 새로고침
            } catch (e) {
                alert("적용 중 오류 발생");
            }
        }




        function movePage(d) {
            if(expPage + d >= 1) loadExplorer(expPage + d);
        }

        // 탭 전환 시 데이터 로딩
        function switchTab(tabId, el) {
            document.querySelectorAll('.tab-content').forEach(t => t.classList.remove('active'));
            document.querySelectorAll('.nav-item').forEach(n => n.classList.remove('active'));
            document.getElementById('tab-' + tabId).classList.add('active');
            el.classList.add('active');

            // 데이터 탐색기 탭 클릭 시 로드 함수 실행
            if(tabId === 'explorer') loadExplorer(1);
        }

        // SQL 다이렉트 런너
        async function runSQL() {
            const sql = document.getElementById('sql-input').value;
            const errorBox = document.getElementById('sql-error');
            const resultWrapper = document.getElementById('sql-result-wrapper');

            errorBox.style.display = 'none';
            resultWrapper.style.display = 'none';

            try {
                const res = await fetch('/api/admin/query', {
                    method: 'POST',
                    headers: {'Content-Type': 'application/json'},
                    body: JSON.stringify({query: sql})
                });
                const data = await res.json();

                if(data.error) {
                    errorBox.innerText = "⚠️ SQL Error: " + data.error;
                    errorBox.style.display = 'block';
                    return;
                }

                if(data.length === 0) {
                    errorBox.innerText = "조회 결과가 없습니다.";
                    errorBox.style.display = 'block';
                    return;
                }

                const cols = Object.keys(data[0]);
                document.getElementById('sql-head').innerHTML = `<tr>${cols.map(c=>`<th>${c}</th>`).join('')}</tr>`;
                document.getElementById('sql-body').innerHTML = data.map(r => `
                    <tr>${cols.map(c=>`<td>${r[c] !== null ? r[c] : '-'}</td>`).join('')}</tr>
                `).join('');
                resultWrapper.style.display = 'block';
            } catch(e) {
                errorBox.innerText = "⚠️ Network Error: " + e.message;
                errorBox.style.display = 'block';
            }
        }

        // 상태 실시간 업데이트 (2초마다)
        function updateStatus() {
            fetch('/api/indexing/status').then(r=>r.json()).then(d=>{
                document.getElementById('idx-total').innerText = d.songs_found.toLocaleString();
                document.getElementById('idx-speed').innerText = d.speed;
                const p = (d.total_dirs > 0 ? (d.processed_dirs / d.total_dirs * 100) : 0).toFixed(1);
                document.getElementById('idx-fill').style.width = p + '%';
                document.getElementById('idx-progress-text').innerText = `진행률: ${p}% (${d.processed_dirs.toLocaleString()} / ${d.total_dirs.toLocaleString()})`;
                document.getElementById('idx-eta').innerText = "예상 완료: " + d.eta;
                document.getElementById('idx-log').innerText = "🚀 " + d.last_log;
            });

    fetch('/api/metadata/status').then(r=>r.json()).then(d=>{
        // 1. 전체 라이브러리 항목 (앨범/가수 그룹 기준)
        const totalDbEl = document.getElementById('meta-db-total');
        if(totalDbEl) totalDbEl.innerText = (d.db_total || 0).toLocaleString();

        // 2. DB 전체 현황 (성공 / 실패+대기)
        document.getElementById('meta-db-success').innerText = (d.db_success || 0).toLocaleString();
        document.getElementById('meta-db-fail').innerText = ((d.db_fail || 0) + (d.db_pending || 0)).toLocaleString();

        // 3. 실시간 세션 현황
        document.getElementById('meta-session-success').innerText = (d.success || 0).toLocaleString();
        document.getElementById('meta-session-fail').innerText = (d.fail || 0).toLocaleString();

        // 4. 로그 및 상태 텍스트
        if(d.is_running) {
            document.getElementById('idx-log').innerText = `🔥 [${d.target}] 진행 중: ${d.current} / ${d.total} (세션 실패: ${d.fail})`;
        }

        if(d.last_log && d.last_log !== lastMetaLog) {
            addLog(`<span style="color:var(--accent); font-weight:bold;">[META]</span> ${d.last_log}`);
            lastMetaLog = d.last_log;
        }
    });
        }

        function startMeta(q) {
            if(!q) { // 전체 자동 갱신인 경우
                if(!confirm("전체 초기화 후 엔진을 가동하시겠습니까?")) return;
            } else { // 특정 카테고리(외국 등)인 경우
                if(!confirm(`${q} 카테고리의 이전 실패 기록을 초기화하고 새로 시작하시겠습니까?`)) return;
            }

            // 1단계: 실패 기록 초기화 API 호출
            fetch(`/api/metadata/reset_fail?q=${encodeURIComponent(q)}`)
                .then(r => r.json())
                .then(d => {
                    addLog(d.message);
                    // 2단계: 실패 초기화 성공 후 엔진 가동 API 호출
                    return fetch(`/api/metadata/start?q=${encodeURIComponent(q)}`);
                })
                .then(r => r.json())
                .then(d => {
                    addLog(d.message);
                });
        }

        async function startScan() {
            const target = document.getElementById('scan-target').value;
            if(!confirm(`[${target}] 폴더에서 새로운 파일을 검색하시겠습니까?\n(기존 데이터는 유지되며 새 파일만 추가됩니다.)`)) return;

            try {
                const res = await fetch(`/api/indexing/start?target=${encodeURIComponent(target)}`);
                const data = await res.json();
                if(data.status === 'ok') {
                    addLog(`<span style="color:var(--accent); font-weight:bold;">[SCAN]</span> ${data.message}`);
                } else {
                    alert(data.message);
                }
            } catch(e) {
                alert("서버 통신 오류가 발생했습니다.");
            }
        }

        function stopMeta() { fetch('/api/metadata/stop').then(r=>r.json()).then(d=>addLog(d.message)); }
        function resetFail() {
            // 1. 데이터 브라우저 탭에 있는 카테고리 선택기(exp-cat)에서 현재 선택된 카테고리를 가져옵니다.
            const cat = document.getElementById('exp-cat').value;

            // 2. 카테고리가 'All'이면 전체 초기화, 아니면 해당 카테고리만 초기화
            const confirmMsg = `${cat === 'All' ? '전체' : cat} 카테고리의 실패 기록을 초기화하시겠습니까?`;

            if(confirm(confirmMsg)) {
                // 3. 서버의 reset_fail API에 ?q=카테고리명 을 붙여서 보냅니다.
                fetch(`/api/metadata/reset_fail?q=${encodeURIComponent(cat === 'All' ? '' : cat)}`)
                    .then(r => r.json())
                    .then(d => {
                        alert(d.message);
                        addLog(d.message);
                    });
            }
        }
        setInterval(updateStatus, 10000);
        updateStatus(); // 즉시 실행
        addLog("NasMusic Pro 관리 콘솔에 연결되었습니다.");
    </script>
    <!-- 수동 매칭 모달 레이어 -->
    <div id="manual-modal" style="display:none; position:fixed; top:0; left:0; width:100%; height:100%; background:rgba(0,0,0,0.85); z-index:1000; justify-content:center; align-items:center; padding:20px;">
        <div class="box" style="width:100%; max-width:700px; max-height:90vh; overflow-y:auto; border:2px solid var(--accent); position:relative;">
            <div style="display:flex; justify-content:space-between; margin-bottom:20px;">
                <h2 style="margin:0; color:var(--accent);">🛠️ 메타데이터 수동 매칭</h2>
                <button class="secondary" onclick="closeManualMatch()" style="padding:5px 12px; cursor:pointer;">닫기</button>
            </div>
            <div class="filter-bar" style="margin-bottom:20px;">
                <input type="text" id="manual-search-q" style="flex:1;" onkeyup="if(event.key==='Enter') searchManual()">
                <button onclick="searchManual()">🔍 검색</button>
            </div>
            <div id="manual-results" style="display:grid; grid-template-columns: repeat(auto-fill, minmax(130px, 1fr)); gap:15px;">
                <!-- 검색 결과 리스트 -->
            </div>
        </div>
    </div>
</body>
</html>
'''


# ==========================================
# 3. 핵심 로직: DB, 스캔, 인덱싱 (중간 저장 및 이어하기)
# ==========================================
def init_db():
    print("[*] 🛠️ DB 엔진 최적화 및 인덱스 점검 중...")
    with sqlite3.connect(DB_PATH, timeout=600) as conn:
        conn.execute('PRAGMA journal_mode=WAL')
        conn.execute('PRAGMA cache_size = -2000000')  # 2GB 캐시
        conn.execute('PRAGMA synchronous = NORMAL')   # 쓰기 성능 향상
        conn.execute('PRAGMA mmap_size = 30000000000') # 30GB 메모리 맵핑 (검색 속도 폭발)
        conn.execute('PRAGMA temp_store = MEMORY')

        # 1. 기본 테이블 생성
        conn.execute('''
            CREATE TABLE IF NOT EXISTS global_songs (
                name TEXT, artist TEXT, albumName TEXT,
                stream_url TEXT, parent_path TEXT, meta_poster TEXT,
                genre TEXT, release_date TEXT, album_artist TEXT
            )
        ''')

        # 2. 사라진 테마 테이블 강제 생성
        conn.execute('''
            CREATE TABLE IF NOT EXISTS themes (
                type TEXT, name TEXT, path TEXT, image_url TEXT,
                PRIMARY KEY (type, name, path)
            )
        ''')

        # 3. 아티스트 캐시 테이블
        conn.execute('CREATE TABLE IF NOT EXISTS artists_cache (artist_name TEXT, cover TEXT, folder_type TEXT, PRIMARY KEY (artist_name, folder_type))')

        # 4. 필수 인덱스 (조회 속도용)
        conn.execute('CREATE INDEX IF NOT EXISTS idx_path ON global_songs(parent_path)')
        conn.execute('CREATE INDEX IF NOT EXISTS idx_meta_lookup ON global_songs(artist, albumName)')
        conn.commit()
    print("[*] ✅ DB 최적화 및 구조 복구 완료.")

def refresh_artist_cache():
    print("[*] 🔄 아티스트 목록 캐시 갱신 중... (대용량 데이터 최적화)")
    try:
        with sqlite3.connect(DB_PATH) as conn:
            conn.execute("DELETE FROM artists_cache")
            # 폴더별(국내, 외국 등) 유니크한 가수와 대표 이미지 추출
            conn.execute("""
                INSERT INTO artists_cache (artist_name, cover, folder_type)
                SELECT
                    TRIM(artist) as name,
                    MAX(meta_poster) as cover,
                    CASE
                        WHEN parent_path LIKE '국내%' THEN '국내'
                        WHEN parent_path LIKE '외국%' THEN '외국'
                        WHEN parent_path LIKE '일본%' THEN '일본'
                        WHEN parent_path LIKE 'DSD%' THEN 'DSD'
                        WHEN parent_path LIKE 'OST%' THEN 'OST'
                        WHEN parent_path LIKE '클래식%' THEN '클래식'
                        ELSE '기타'
                    END as folder
                FROM global_songs
                WHERE artist != '' AND artist != 'Unknown Artist'
                GROUP BY UPPER(TRIM(artist)), folder
            """)
            conn.commit()
        print("[*] ✅ 아티스트 캐시 갱신 완료!")
    except Exception as e:
        print(f"[!] 캐시 생성 에러: {e}")

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
    nm = os.path.splitext(f)[0]
    rel_dir = os.path.relpath(d, MUSIC_BASE)
    path_parts = rel_dir.split(os.sep)
    top_folder = path_parts[0]

    art = "Unknown Artist"
    tit = nm

    if top_folder == "일본":
        # 기존: art = path_parts[3] -> path_parts 길이에 따라 유연하게 변경
        # 예: 일본/가수/아티스트/앨범/곡.mp3
        if len(path_parts) >= 4:
            art = path_parts[2]  # 가수/아티스트명
            album = path_parts[3]  # 앨범명
        else:
            art = path_parts[1] if len(path_parts) > 1 else "Unknown"

        # 파일명에서 불필요한 번호 제거 후 제목으로 사용
        tit = re.sub(r'^\d+\.?\s*', '', nm).strip()
    else:
        # [클래식 분기]
        if top_folder == "클래식":
            # 1. 아티스트(작곡가): 폴더명(클래식/작곡가/...)을 최우선 신뢰
            art = path_parts[1] if len(path_parts) > 1 else "Classical"

            # 2. 파일명 정제 (앞의 기호 및 숫자 제거)
            clean_nm = re.sub(r'^[0-9\s\.\-_/]+', '', nm).strip()

            # 3. 곡 제목 추출: " - " 가 있으면 맨 앞부분만 곡 제목으로 (뒷부분 악장 정보 제외)
            if " - " in clean_nm:
                tit = clean_nm.split(" - ")[0].strip()
            else:
                tit = clean_nm

            # 제목이 너무 짧으면 전체 파일명을 제목으로 사용
            if len(tit) < 5: tit = clean_nm

        # [그 외 폴더 분기]
        else:
            if " - " in nm:
                parts = nm.split(" - ", 1)
                art = parts[0].split(". ", 1)[-1].strip() if ". " in parts[0] else parts[0].strip()
                tit = parts[1].strip()

            if top_folder == "OST" or "OST" in top_folder:
                if art.upper().startswith("CD") or art.isdigit(): art = os.path.basename(d)
                if len(tit) < 2: tit = nm
            elif top_folder == "외국":
                if art == "Unknown Artist" or art.upper().startswith("CD"):
                    art = path_parts[1] if len(path_parts) > 1 else "Pop"
            elif top_folder == "국내":
                if art == "Unknown Artist" or art.upper().startswith("CD"):
                    if len(path_parts) >= 3 and path_parts[1] == "가수":
                        art = path_parts[3] if len(path_parts) > 3 else path_parts[2]
                    else:
                        art = os.path.basename(d)

    rel_file = os.path.join(rel_dir, f)
    stream_url = f"{BASE_URL}/stream/{urllib.parse.quote(rel_file)}"
    return (tit, art, os.path.basename(d), stream_url, rel_dir)

def fix_unknown_artists_in_db(target_tag=None):
    print(f"[*] 🛠️ DB 내 Unknown Artist 복구 시작... (대상: {target_tag if target_tag else '전체'})")
    try:
        with sqlite3.connect(DB_PATH, timeout=120) as conn:
            # 타겟 태그가 있으면 LIKE 문에 반영, 없으면 전체 대상으로 쿼리
            sql = """UPDATE global_songs
                     SET artist = SUBSTR(parent_path, INSTR(parent_path, '/가수/') + 10,
                                  INSTR(SUBSTR(parent_path, INSTR(parent_path, '/가수/') + 10), '/') - 1)
                     WHERE artist = 'Unknown Artist' AND parent_path LIKE '%/가수/%'"""

            params = []
            if target_tag:
                sql += " AND parent_path LIKE ?"
                params.append(f"{target_tag}%")

            conn.execute(sql, params)
            conn.commit()
            print(f"[*] ✅ {target_tag if target_tag else '전체'} 복구 완료.")
    except Exception as e:
        print(f"[!] 복구 중 오류: {e}")


def process_path(full_path):
    if not full_path: return None
    try:
        return get_info(os.path.basename(full_path), os.path.dirname(full_path))
    except:
        return None


def scan_all_songs(target_folder=None):
    global idx_st
    if idx_st["is_running"]: return

    # target_folder가 있으면 해당 폴더만, 없으면 전체(MUSIC_BASE)
    scan_root = os.path.join(MUSIC_BASE, target_folder) if target_folder and target_folder != "전체" else MUSIC_BASE
    display_name = target_folder if target_folder and target_folder != "전체" else "전체"

    idx_st.update({
        "is_running": True, "songs_found": 0, "processed_dirs": 0, "total_dirs": 0,
        "start_time": time.time(), "speed": 0, "eta": "계산 중...",
        "last_log": f"🚀 [{display_name}] 스캔 엔진 가동! 목록 수집 중..."
    })

    try:
        with sqlite3.connect(DB_PATH) as conn:
            conn.execute(f"PRAGMA temp_store_directory = '{WRITEABLE_DIR}'")
            conn.execute("PRAGMA journal_mode = WAL")
            conn.execute("""
                CREATE TABLE IF NOT EXISTS global_songs_staging (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    name TEXT, artist TEXT, albumName TEXT,
                    stream_url TEXT, parent_path TEXT, meta_poster TEXT,
                    genre TEXT, release_date TEXT, album_artist TEXT
                )
            """)
            try: conn.execute("ALTER TABLE global_songs_staging ADD COLUMN albumName TEXT")
            except: pass
            conn.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_staging_url ON global_songs_staging(stream_url)")

            cursor = conn.execute("SELECT stream_url FROM global_songs_staging UNION SELECT stream_url FROM global_songs")
            indexed_urls = {row[0] for row in cursor.fetchall()}

        # [수정] scan_root를 사용하여 선택한 폴더만 탐색
        command = ['find', scan_root, '-type', 'f', '(', '-iname', '*.mp3', '-o', '-iname', '*.m4a', '-o', '-iname',
                   '*.flac', '-o', '-iname', '*.dsf', ')']
        all_files = []
        proc = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, encoding='utf-8')
        for line in iter(proc.stdout.readline, ''):
            all_files.append(line.strip())
            if len(all_files) % 10000 == 0:
                idx_st["last_log"] = f"🔍 {display_name} 파일 탐색 중... {len(all_files):,}개 발견"
        proc.wait()

        total_files = len(all_files)
        idx_st["total_dirs"] = total_files

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
            idx_st["last_log"] = f"✅ [{display_name}] 추가할 새 파일이 없습니다."
        else:
            idx_st["last_log"] = f"⏭️ {skipped_count:,}곡 유지, 새 파일 {len(files_to_process):,}곡 처리 시작!"
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
                            conn.executemany("INSERT OR IGNORE INTO global_songs_staging (name, artist, albumName, stream_url, parent_path, meta_poster) VALUES (?,?,?,?,?,NULL)", batch)
                            conn.commit()
                        done_in_this_run += len(batch)
                        idx_st["processed_dirs"] = skipped_count + done_in_this_run
                        elapsed = time.time() - processing_start
                        speed = int(done_in_this_run / elapsed) if elapsed > 0 else 0
                        idx_st.update({"speed": speed, "last_log": f"⏳ {display_name} 처리 중: {idx_st['processed_dirs']:,} / {total_files:,}"})
                        batch = []

            if batch:
                with sqlite3.connect(DB_PATH) as conn:
                    conn.executemany("INSERT OR IGNORE INTO global_songs_staging (name, artist, albumName, stream_url, parent_path, meta_poster) VALUES (?,?,?,?,?,NULL)", batch)
                    conn.commit()

        idx_st["last_log"] = f"💾 [{display_name}] 라이브러리 병합 중..."
        finalize_library()

    except Exception as e:
        idx_st.update({"is_running": False, "last_log": f"❌ 오류: {str(e)}"})

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
        # 인덱스 재생성
        conn.execute("CREATE INDEX IF NOT EXISTS idx_grouping ON global_songs(artist, albumName)")
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


# 전역 락 추가
update_lock = threading.Lock()


# ==========================================
# 4. 메타데이터 엔진 (국내 폴더 우선순위 적용)
# ==========================================
def clean_query_text(text, is_artist=False, folder_mode="western"):
    if not text or text == 'Unknown Artist': return ""
    s = str(text)


    # [수정] 일본어 모드 처리 추가
    if folder_mode == "japanese":
        # 일본어(가타카나, 히라가나, 한자)와 영어/숫자만 보존
        s = re.sub(r'[^\w\s가-힣ぁ-んァ-ヶー一-龠a-zA-Z0-9]', ' ', s)

    # [1] 클래식 전용 정제
    elif folder_mode == "classical":
        if not is_artist:
            # 1. 조성(Key) 정보의 다국어 나열 제거 (sol maggiore G-Dur 등)
            s = re.sub(r'(?i)\b(sol|maggiore|majeur|Dur|moll|flat|sharp|major|minor)\b', ' ', s)

            # 2. 작품 번호(Op, K, BWV, KV, D, RV 등)를 찾으면 그 뒤의 악장 정보는 과감히 절삭
            # 예: "Symphony No.5 Op.67 - I. Allegro" -> "Symphony No.5 Op.67"
            match = re.search(r'(?i)\b(Op|No|K|KV|BWV|D|RV|TWV|HWV)\.?\s?\d+', s)
            if match:
                # 작품 번호 뒤에 오는 " - " 나 악장 정보(I. II.) 기점으로 잘라냄
                s = s[:match.end() + 15]  # 번호 뒤에 약간의 여유만 두고 잘라냄
        else:
            # 연주자 정보 제거 (conducted by, orchestra 등)
            s = re.sub(r'(?i)(violin|piano|cello|conducted|orch\.|ensemble|philharmonic).*$', '', s)

    # [2] 서구권/클래식 공통 (기존 유지)
    elif folder_mode in ["western", "classical"]:
        s = re.sub(r'(?i)TOTP[\s\-_]?\d{4}[\s\-_]?\d{2}', ' ', s)
        s = re.sub(r'(?i)\b(CD|Track|Part|Disc|Side|Vol|Volume|Selection)\s?\d+\b', ' ', s)
        if is_artist:
            s = re.split(r'(?i)\s+(featuring|feat|ft|with|vs|cond\.|dir\.)\b', s)[0]

    # [3] 전 카테고리 공통
    s = re.sub(r'\(.*?\)|\[.*?\]|\{.*?\}', ' ', s)
    s = re.sub(r'\d{2,4}[\.\-/]\d{2}[\.\-/]\d{2}', ' ', s)
    s = re.sub(r'\b(19|20)\d{2}\b', ' ', s)

    if folder_mode in ["western", "classical"]:
        s = re.sub(r"[^a-zA-Z0-9\s&'!\.\-\u00C0-\u00FF]", " ", s) # 유럽 악센트 보존
    else:
        s = re.sub(r'[^\w\s가-힣ぁ-んァ-ヶー一-龠\u4e00-\u9fff]', ' ', s)

    s = re.sub(r'^[0-9\s\.\-_/]+', '', s.strip())
    s = re.sub(r'\s{2,}', ' ', s)
    return s.strip()


def fetch_metadata_smart(artist, album, title, folder_type=None):
    strategy = META_STRATEGIES.get(folder_type, {"priority": ["itunes", "deezer"], "clean": "western"})
    clean_mode = "japanese" if folder_type == "일본" else strategy["clean"]

    q_art = clean_query_text(artist, is_artist=True, folder_mode=clean_mode)
    q_tit = clean_query_text(title, is_artist=False, folder_mode=clean_mode)
    q_alb = clean_query_text(album, is_artist=False, folder_mode=clean_mode)

    strategies = []

    if folder_type == "일본":
        # 일본 음악은 앨범명 검색이 최우선
        if q_alb: strategies.append(("", q_alb))
        if q_art and q_alb: strategies.append((q_art, q_alb))
        if q_art and q_tit: strategies.append((q_art, q_tit))

    elif folder_type == "클래식":
        # 클래식은 개별 곡보다는 앨범(앨범 아트가 같음)으로 검색하는 게 훨씬 정확
        if album:
            strategies.append(("", album))  # 앨범명만 검색
        if q_art and album:
            strategies.append((q_art, album))  # 아티스트 + 앨범명
        # 제목(곡명)은 최후의 수단으로
        if q_tit:
            strategies.append(("", q_tit))

    else:
        if q_art and q_tit: strategies.append((q_art, q_tit))
        if q_art and q_alb: strategies.append((q_art, q_alb))

    for a_q, b_q in strategies:
        for engine in strategy["priority"]:
            res = None
            if engine == "deezer":
                res = fetch_deezer_metadata(a_q, b_q)
            elif engine == "itunes":
                res = fetch_itunes_metadata(a_q, b_q)
            if res and res.get('poster'): return res
        time.sleep(0.1)
    return None

def fetch_deezer_metadata(artist, title_or_album):
    if not title_or_album: return None
    try:
        # 1. 필드 기반 정밀 검색
        query = f'track:"{title_or_album}"'
        if artist and artist != 'Unknown Artist':
            query = f'artist:"{artist}" ' + query

        url = f"https://api.deezer.com/search?q={urllib.parse.quote(query)}&limit=1"
        res = requests.get(url, timeout=5).json()

        # 2. 결과 없으면 일반 텍스트 검색으로 재시도
        if (not res.get("data") or len(res["data"]) == 0) and artist:
            query_gen = f"{artist} {title_or_album}"
            url = f"https://api.deezer.com/search?q={urllib.parse.quote(query_gen)}&limit=1"
            res = requests.get(url, timeout=5).json()

        if res.get("data") and len(res["data"]) > 0:
            track = res["data"][0]
            alb = track.get('album', {})
            if alb.get('cover_xl'):
                return {"poster": alb['cover_xl'], "album_artist": track.get('artist', {}).get('name')}
    except:
        pass
    return None

def fetch_maniadb_metadata(artist, album):
    # 앨범으로 먼저 찾고, 없으면 곡(song)으로 한 번 더 찾습니다.
    for mode in ['album', 'song']:
        try:
            url = f"http://www.maniadb.com/api/search/{urllib.parse.quote(f'{artist} {album}')}/?sr={mode}&display=1&key=example&v=0.5"
            res = requests.get(url, timeout=8, headers={'User-Agent': 'Mozilla/5.0'})
            if res.status_code == 200:
                img = re.search(r'<image><!\[CDATA\[(.*?)]]>', res.text)
                if img: return {"poster": img.group(1).replace("/s/", "/l/"), "genre": "K-Pop"}
        except:
            continue
    return None


def fetch_itunes_metadata(artist, term):
    """서구권 음원에 가장 강력한 iTunes API (고해상도 커버 지원)"""
    if not term: return None
    try:
        q = f"{artist} {term}".strip()
        url = f"https://itunes.apple.com/search?term={urllib.parse.quote(q)}&limit=1&entity=song"
        res = requests.get(url, timeout=5).json()
        if res.get("resultCount", 0) > 0:
            item = res["results"][0]
            # 100x100 이미지를 1000x1000 고해상도로 변경
            poster = item.get('artworkUrl100', '').replace('100x100bb', '1000x1000bb')
            return {
                "poster": poster,
                "genre": item.get('primaryGenreName'),
                "release_date": item.get('releaseDate'),
                "album_artist": item.get('artistName')
            }
    except: pass
    return None


def fetch_deezer_metadata(artist, term, search_type="track"):
    """search_type을 track 또는 album으로 명확히 구분"""
    if not term: return None
    try:
        # 1. Strict Search (필터 사용)
        q = f'artist:"{artist}" {search_type}:"{term}"' if artist else f'{search_type}:"{term}"'
        url = f"https://api.deezer.com/search?q={urllib.parse.quote(q)}&limit=1"
        res = requests.get(url, timeout=5).json()

        if res.get("data"):
            data = res["data"][0]
            alb = data.get('album', {})
            if alb.get('cover_xl'):
                return {"poster": alb['cover_xl'], "album_artist": data.get('artist', {}).get('name')}

        # 2. Fuzzy Search (실패 시 일반 키워드 검색)
        q_gen = f"{artist} {term}"
        url_gen = f"https://api.deezer.com/search?q={urllib.parse.quote(q_gen)}&limit=1"
        res_gen = requests.get(url_gen, timeout=5).json()
        if res_gen.get("data"):
            data = res_gen["data"][0]
            alb = data.get('album', {})
            if alb.get('cover_xl'):
                return {"poster": alb['cover_xl'], "album_artist": data.get('artist', {}).get('name')}
    except:
        pass
    return None

def update_theme_incremental(category, name, path, poster_url=None):
    """
    기존 테마 DB를 삭제하지 않고, 변경된 아티스트/앨범 정보만 갱신(UPSERT)합니다.
    """
    try:
        with sqlite3.connect(DB_PATH, timeout=60) as conn:
            # 1. 포스터 URL이 없으면 DB에서 최신 정보를 재조회
            if not poster_url:
                row = conn.execute(
                    "SELECT meta_poster FROM global_songs WHERE (artist=? OR albumName=?) AND meta_poster IS NOT NULL AND meta_poster != 'FAIL' LIMIT 1",
                    (name, name)
                ).fetchone()
                poster_url = row[0] if row else None

            # 2. INSERT OR REPLACE (UPSERT)로 안전하게 갱신
            conn.execute(
                "INSERT OR REPLACE INTO themes (type, name, path, image_url) VALUES (?,?,?,?)",
                (category, name, path, poster_url)
            )
            conn.commit()
    except Exception as e:
        print(f"[!] 테마 갱신 에러: {e}")


def start_metadata_update_thread(query_tag=None):
    global up_st
    if up_st["is_running"]: return

    up_st["is_running"] = True
    up_st["target"] = query_tag if query_tag else "전체"
    display_name = up_st["target"]

    # 테마 갱신을 위한 큐와 워커 스레드 설정
    theme_q = queue.Queue()

    def theme_worker():
        while True:
            item = theme_q.get()
            if item is None: break
            update_theme_incremental(*item)
            theme_q.task_done()

    Thread(target=theme_worker, daemon=True).start()

    up_st["last_log"] = f"[*] 1단계: {display_name} 가수명 일괄 복구 중..."

    try:
        fix_unknown_artists_in_db(target_tag=query_tag)
        up_st["last_log"] = f"[*] 2단계: {display_name} 매칭 대상 조회 중..."

        with sqlite3.connect(DB_PATH, timeout=120) as conn:
            conn.row_factory = sqlite3.Row
            sql = """SELECT artist, albumName, MAX(name) as title
                     FROM global_songs
                     WHERE (meta_poster IS NULL OR meta_poster = '' OR meta_poster = 'FAIL')
                     AND artist != 'Unknown Artist' AND artist != '' """
            params = []
            if query_tag:
                sql += " AND parent_path LIKE ?"
                params.append(f"{query_tag}%")
            sql += " GROUP BY artist, albumName LIMIT 30000"
            targets = conn.execute(sql, params).fetchall()

        if not targets:
            up_st["last_log"] = f"✅ {display_name}: 모든 대상이 이미 매칭되었습니다."
            up_st["is_running"] = False
            return

        up_st.update({"total": len(targets), "current": 0, "success": 0, "fail": 0})
        db_q = queue.Queue()

        def db_worker():
            batch = []
            while True:
                try:
                    item = db_q.get()
                    if item is None: break
                    batch.append(item)
                    if len(batch) >= 100:
                        save_batch(batch)
                        batch = []
                except:
                    pass
            if batch: save_batch(batch)

        def save_batch(items):
            try:
                with sqlite3.connect(DB_PATH, timeout=60) as conn:
                    for art, alb, res in items:
                        if res and res.get('poster'):
                            conn.execute(
                                "UPDATE global_songs SET meta_poster=?, genre=?, release_date=?, album_artist=? WHERE artist=? AND albumName=?",
                                (res['poster'], res.get('genre'), res.get('release_date'), res.get('album_artist'), art,
                                 alb))
                            with update_lock:
                                up_st["success"] += 1
                        else:
                            conn.execute("UPDATE global_songs SET meta_poster='FAIL' WHERE artist=? AND albumName=?",
                                         (art, alb))
                            with update_lock:
                                up_st["fail"] += 1
                    conn.commit()
            except Exception as e:
                with update_lock:
                    up_st["last_log"] = f"⚠️ DB 저장 오류: {str(e)}"

        Thread(target=db_worker, daemon=True).start()

        with ThreadPoolExecutor(max_workers=5) as executor:
            futures = []
            for row in targets:
                if not up_st["is_running"]: break

                def run_match(r=row, f_type=query_tag):
                    try:
                        res = fetch_metadata_smart(r['artist'], r['albumName'], r['title'], folder_type=f_type)

                        # 테마 갱신을 직접 호출하지 않고 큐에 넣음 (DB Lock 방지)
                        if res and res.get('poster'):
                            cat = 'artists' if f_type == '외국' else 'charts'
                            theme_q.put((cat, r['artist'], f"{f_type}/가수/{r['artist']}", res['poster']))

                        db_q.put((r['artist'], r['albumName'], res))

                        with update_lock:
                            up_st["current"] += 1
                            log_art = clean_query_text(r['artist'], is_artist=True)
                            log_tit = clean_query_text(r['title'], is_artist=False)
                            up_st[
                                "last_log"] = f"[{display_name}] {up_st['current']}/{up_st['total']} | {log_art} - {log_tit} -> {'✅' if res else '❌'}"
                    except Exception as e:
                        with update_lock:
                            up_st["current"] += 1
                            up_st[
                                "last_log"] = f"[{display_name}] {up_st['current']}/{up_st['total']} | 오류: {str(e)[:20]}"

                futures.append(executor.submit(run_match))
                time.sleep(0.02)
            for future in as_completed(futures): pass

        db_q.put(None)
        theme_q.put(None)  # 테마 갱신 작업 마무리
        up_st["last_log"] = f"🏁 {display_name} 엔진 작업 완료!"

    except Exception as e:
        up_st["last_log"] = f"❌ {display_name} 엔진 중단됨: {str(e)}"
    finally:
        up_st["is_running"] = False

@app.route('/api/library/browse')
def browse_library():
    path = request.args.get('path', '').strip()
    if not path: return jsonify({"error": "Path is required"}), 400

    try:
        with sqlite3.connect(DB_PATH, timeout=20) as conn:
            conn.row_factory = sqlite3.Row
            search_path = path if path.endswith('/') else path + '/'

            # 🚀 한 번의 쿼리로 하위 폴더들과 각각의 대표 이미지를 즉시 가져옴
            rows = conn.execute("""
                SELECT parent_path, MAX(meta_poster) as poster
                FROM global_songs
                WHERE parent_path LIKE ?
                GROUP BY parent_path
            """, (f"{search_path}%",)).fetchall()

            sub_folders = {}
            for r in rows:
                p_path = r['parent_path']
                if p_path == path: continue
                rel = p_path[len(search_path):]
                segment = rel.split('/')[0]
                if segment:
                    # 이미지가 있는 폴더를 우선적으로 캐싱
                    if segment not in sub_folders or (not sub_folders[segment] and r['poster']):
                        sub_folders[segment] = r['poster']

            if sub_folders:
                result = [{
                    "name": name, "path": f"{search_path}{name}", "is_dir": True,
                    "cover": p if p and p not in ('', 'FAIL') else None
                } for name, p in sorted(sub_folders.items())]
                return jsonify(result)

            # 🎵 노래 목록 반환 시 rowid AS id 를 추가하여 곡 전환 문제 해결
            songs = conn.execute("""
                SELECT rowid AS id, name, artist, albumName, stream_url, parent_path, meta_poster
                FROM global_songs WHERE parent_path = ? ORDER BY name
            """, (path,)).fetchall()
            return jsonify([{**dict(s), "is_dir": False, "path": s['parent_path']} for s in songs])
    except Exception as e:
        return jsonify({"error": str(e)}), 500



@app.route('/api/admin/data', methods=['GET'])
def get_admin_data():
    """데이터 탐색기용 API - 디버깅 로그 포함"""
    cat = request.args.get('category', 'All')
    q = request.args.get('q', '').strip()
    page = int(request.args.get('page', 1))

    # [추가] 실패 필터 파라미터 받기
    show_fail = request.args.get('fail_only', 'false') == 'true'

    limit = 50
    offset = (page - 1) * limit

    # [로그] 요청 파라미터 확인
    print(f"--- [DEBUG] get_admin_data Call ---")
    print(f"[*] Category: {cat}, Search: '{q}', Page: {page}")

    # [수정] id -> rowid AS id (SQLite 내장 행 번호 사용)
    query = "SELECT rowid AS id, name, artist, albumName, meta_poster, genre FROM global_songs WHERE 1=1"
    params = []

    if cat != 'All' and cat != '':
        query += " AND parent_path LIKE ?"
        params.append(f"{cat}%")

    if q:
        query += " AND (name LIKE ? OR artist LIKE ? OR albumName LIKE ?)"
        params.extend([f"%{q}%", f"%{q}%", f"%{q}%"])

    # [추가] 실패 항목만 보기 필터 로직
    if show_fail:
        query += " AND (meta_poster = 'FAIL' OR meta_poster IS NULL OR meta_poster = '')"

    query += f" ORDER BY id DESC LIMIT {limit} OFFSET {offset}"

    # [로그] 최종 쿼리와 파라미터 확인
    print(f"[*] SQL Query: {query}")
    print(f"[*] SQL Params: {params}")

    try:
        with sqlite3.connect(DB_PATH) as conn:
            conn.row_factory = sqlite3.Row
            rows = conn.execute(query, params).fetchall()

            # [로그] 결과 개수 확인
            print(f"[*] Found Rows: {len(rows)}")
            print(f"----------------------------------")

            return jsonify([dict(r) for r in rows])
    except Exception as e:
        print(f"[!] Admin API Error: {e}")
        import traceback
        traceback.print_exc()  # 상세 에러 스택트레이스 출력
        return jsonify([])


@app.route('/api/admin/search_meta')
def admin_search_meta():
    q = request.args.get('q', '').strip()
    if not q: return jsonify([])
    try:
        # Deezer API를 활용해 수동 검색 결과 10개 반환
        url = f"https://api.deezer.com/search?q={urllib.parse.quote(q)}&limit=10"
        res = requests.get(url, timeout=5).json()
        results = []
        if res.get("data"):
            for t in res["data"]:
                results.append({
                    "title": t.get('title'),
                    "artist": t.get('artist', {}).get('name'),
                    "album": t.get('album', {}).get('title'),
                    "poster": t.get('album', {}).get('cover_xl')
                })
        return jsonify(results)
    except Exception as e:
        return jsonify({"error": str(e)})


@app.route('/api/admin/apply_meta', methods=['POST'])
def admin_apply_meta():
    data = request.json
    artist = data.get('artist')
    album = data.get('album')
    poster = data.get('poster')

    if not artist or not album or not poster:
        return jsonify({"error": "필수 데이터 누락"}), 400

    try:
        with sqlite3.connect(DB_PATH) as conn:
            # 선택한 가수와 앨범명을 가진 모든 곡의 메타데이터를 일괄 업데이트 (매우 효율적)
            conn.execute(
                "UPDATE global_songs SET meta_poster=? WHERE artist=? AND albumName=?",
                (poster, artist, album)
            )
            conn.commit()
        return jsonify({"status": "ok", "message": f"[{artist} - {album}] 메타데이터가 일괄 적용되었습니다."})
    except Exception as e:
        return jsonify({"error": str(e)})


@app.route('/api/admin/query', methods=['POST'])
def run_query():
    """Expert용 SQL 실행 API - SELECT문만 허용"""
    sql = request.json.get('query', '').strip()
    if not sql.upper().startswith("SELECT"):
        return jsonify({"error": "보안상 SELECT 쿼리만 실행 가능합니다."})

    forbidden = ["DROP", "DELETE", "UPDATE", "INSERT", "ALTER", "TRUNCATE"]
    if any(k in sql.upper() for k in forbidden):
        return jsonify({"error": "데이터 변경(DML/DDL) 권한이 없습니다."})

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


# @app.route('/api/themes')
# def get_themes():
#     # [수정] 전체를 보내지 말고, 각각 10개씩만 잘라서 테스트해보세요.
#     # 이렇게 해서 앱이 안 죽으면, 데이터 양이 문제인 게 확실합니다.
#     return jsonify({
#         "charts": cache["charts"][:10],
#         "collections": cache["collections"][:10],
#         "artists": cache["artists"][:10],
#         "genres": cache["genres"][:10]
#     })

# [교체할 API] 전체 데이터 덤프 대신 목록만 우선 제공
@app.route('/api/themes/list')
def get_themes_list():
    """앱 초기 화면용: 메타데이터 없는 단순 경로 목록만 반환"""
    return jsonify({
        "charts": [{"name": c["name"], "path": c["path"], "image_url": c["image_url"]} for c in cache["charts"]],
        "collections": [{"name": c["name"], "path": c["path"], "image_url": c["image_url"]} for c in cache["collections"]],
        "artists": [{"name": c["name"], "path": c["path"], "image_url": c["image_url"]} for c in cache["artists"]],
        "genres": [{"name": g["name"], "path": g["path"], "image_url": g["image_url"]} for g in cache["genres"]]
    })

@app.route('/api/themes/<category>')
def get_themes_by_category(category):
    """
    카테고리별로 페이징된 테마 목록을 반환
    예: /api/themes/charts?page=1
    """
    page = int(request.args.get('page', 1))
    limit = 50
    offset = (page - 1) * limit

    # 캐시에서 해당 카테고리 데이터 가져오기
    data = cache.get(category, [])
    paginated_data = data[offset: offset + limit]

    return jsonify(paginated_data)

@app.route('/api/theme-details/<path:tp>')
def get_details(tp):
    p = urllib.parse.unquote(tp)
    page = int(request.args.get('page', 1))  # 페이지 파라미터 추가
    limit = 100  # 한 번에 100개씩만
    offset = (page - 1) * limit

    with sqlite3.connect(DB_PATH) as conn:
        conn.row_factory = sqlite3.Row
        # LIMIT과 OFFSET 추가 (가장 중요)
        rows = conn.execute(
            """SELECT rowid AS id, name, artist, albumName, stream_url, parent_path, meta_poster
               FROM global_songs WHERE parent_path LIKE ?
               ORDER BY artist, name ASC LIMIT ? OFFSET ?""",
            (f"{p}%", limit, offset)
        ).fetchall()
        return jsonify([dict(r) for r in rows])


@app.route('/api/top100')
def get_top100():
    try:
        base_rel_path = os.path.relpath(WEEKLY_CHART_PATH, MUSIC_BASE).replace('\\', '/')

        with sqlite3.connect(DB_PATH, timeout=20) as conn:
            conn.row_factory = sqlite3.Row

            # 1. 최신 주차 폴더명 하나만 찾기 (가장 정확한 쿼리)
            latest_folder_row = conn.execute(
                "SELECT parent_path FROM global_songs WHERE parent_path LIKE ? GROUP BY parent_path ORDER BY MAX(rowid) DESC LIMIT 1",
                (f"%{base_rel_path}%",)
            ).fetchone()

            if not latest_folder_row: return jsonify([])
            latest_path = latest_folder_row['parent_path']

            # 2. [핵심] 100곡만 확실하게 제한하여 가져오기
            rows = conn.execute(
                """SELECT rowid AS id, name, artist, albumName, stream_url, parent_path, meta_poster
                   FROM global_songs
                   WHERE parent_path = ?
                   ORDER BY name ASC LIMIT 100""",
                (latest_path,)
            ).fetchall()

            result = [dict(r) for r in rows]
            # 3. 파일명 숫자 정렬은 서버에서 하지 말고 앱으로 넘기는 것이 서버 부하 방지에 좋습니다.
            return jsonify(result)
    except Exception as e:
        print(f"[!] Top100 오류: {e}")
        return jsonify([])

@app.route('/api/search')
def search_songs():
    """서버 내 라이브러리 검색 API (앱 필터링 대응)"""
    q = request.args.get('q', '').strip()
    if not q: return jsonify([])
    try:
        with sqlite3.connect(DB_PATH) as conn:
            conn.row_factory = sqlite3.Row
            # 0 as is_dir을 추가하여 앱에서 '노래'로 정상 인식하게 함
            rows = conn.execute(
                """SELECT rowid AS id, name, artist, albumName, stream_url, parent_path, meta_poster,
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



@app.route('/api/refresh')
def refresh():
    Thread(target=rebuild_library).start()
    return jsonify({"status": "started"})


@app.route('/api/metadata/reset_fail')
def reset_fail():
    # 관리자 페이지에서 전달받은 카테고리(q) 파라미터 확인
    cat = request.args.get('q')
    try:
        with sqlite3.connect(DB_PATH) as conn:
            # 기본 SQL: 실패 기록만 초기화
            sql = "UPDATE global_songs SET meta_poster = NULL WHERE (meta_poster = 'FAIL' OR meta_poster = '')"
            params = []

            # 카테고리가 지정되어 있다면 해당 경로의 노래들만 타겟팅
            if cat and cat != "All":
                sql += " AND parent_path LIKE ?"
                params.append(f"{cat}%")

            cursor = conn.execute(sql, params)
            count = cursor.rowcount
            conn.commit()

        msg = f"🔄 {'전체' if not cat or cat == 'All' else cat} 카테고리의 실패 기록 {count:,}개를 초기화했습니다."
        up_st["last_log"] = msg
        return jsonify({"status": "ok", "message": msg})
    except Exception as e:
        error_msg = f"❌ 초기화 중 오류: {str(e)}"
        up_st["last_log"] = error_msg
        return jsonify({"status": "error", "message": error_msg})


# 기존 run_query API가 이미 있다면 아래 코드로 교체하여
# 에러 발생 시 상세 정보가 리턴되도록 개선합니다.
# @app.route('/api/admin/query', methods=['POST'])
# def run_query():
#     sql = request.json.get('query')
#     # SELECT 문만 허용하여 데이터 안전 보장
#     if not sql.strip().upper().startswith("SELECT"):
#         return jsonify({"error": "안전을 위해 SELECT 문만 실행 가능합니다."})
#     try:
#         with sqlite3.connect(DB_PATH, timeout=10) as conn:
#             conn.row_factory = sqlite3.Row
#             res = conn.execute(sql).fetchall()
#             return jsonify([dict(r) for r in res])
#     except Exception as e:
#         return jsonify({"error": str(e)})

@app.route('/api/metadata/status')
def get_meta():
    global cached_db_stats
    now = time.time()

    # 30초 이내의 데이터는 캐시에서 즉시 반환
    if cached_db_stats["data"] and (now - cached_db_stats["time"] < 30):
        res = up_st.copy()
        res.update(cached_db_stats["data"])
        return jsonify(res)

    res = up_st.copy()
    try:
        with sqlite3.connect(DB_PATH, timeout=5) as conn:
            # 47만 건의 통계는 매우 무거운 작업입니다.
            stats = conn.execute("""
                SELECT COUNT(*), COUNT(CASE WHEN status='success' THEN 1 END),
                       COUNT(CASE WHEN status='fail' THEN 1 END), COUNT(CASE WHEN status='pending' THEN 1 END)
                FROM (
                    SELECT CASE WHEN MAX(meta_poster) IS NOT NULL AND MAX(meta_poster) NOT IN ('', 'FAIL') THEN 'success'
                                WHEN MAX(meta_poster) = 'FAIL' THEN 'fail' ELSE 'pending' END as status
                    FROM global_songs GROUP BY artist, albumName
                )
            """).fetchone()
            if stats:
                stat_data = {"db_total": stats[0], "db_success": stats[1], "db_fail": stats[2], "db_pending": stats[3]}
                cached_db_stats = {"data": stat_data, "time": now}
                res.update(stat_data)
    except:
        pass
    return jsonify(res)

# ==========================================
# 5. 애플뮤직 스타일 통합 검색 및 계층형 API
# ==========================================

# 1. 통합 검색 API (아티스트, 앨범, 노래를 한 번에!)
@app.route('/api/library/search_integrated')
def search_integrated():
    """애플뮤직 스타일 통합 검색 (아티스트, 앨범, 노래 한 번에 조회)"""
    q = request.args.get('q', '').strip()
    if not q: return jsonify({"artists": [], "albums": [], "songs": []})

    try:
        with sqlite3.connect(DB_PATH) as conn:
            conn.row_factory = sqlite3.Row
            search_val = f"%{q}%"

            # 1. 아티스트 검색 (최대 5명 - 원형 프로필용)
            artists = conn.execute(
                """SELECT TRIM(artist) as name, MAX(meta_poster) as cover
                   FROM global_songs
                   WHERE artist LIKE ?
                   GROUP BY UPPER(TRIM(artist)) LIMIT 5""",
                (search_val,)
            ).fetchall()

            # 2. 앨범 검색 (최대 15개 - 가로 스크롤용)
            albums = conn.execute(
                """SELECT albumName as name, artist, MAX(meta_poster) as imageUrl,
                          CAST(MAX(SUBSTR(release_date, 1, 4)) AS INTEGER) as year
                   FROM global_songs
                   WHERE albumName LIKE ?
                   GROUP BY albumName, artist LIMIT 15""",
                (search_val,)
            ).fetchall()

            # 3. 노래 검색 (최대 50곡 - 세로 리스트용)
            songs = conn.execute(
                """SELECT rowid AS id, name, artist, albumName, stream_url, parent_path, meta_poster,
                          genre, release_date, album_artist, 0 as is_dir
                   FROM global_songs WHERE name LIKE ? OR artist LIKE ? LIMIT 50""",
                (search_val, search_val)
            ).fetchall()

            return jsonify({
                "artists": [dict(r) for r in artists],
                "albums": [dict(r) for r in albums],
                "songs": [dict(r) for r in songs]
            })
    except Exception as e:
        print(f"Integrated Search Error: {e}")
        return jsonify({"error": str(e)})

@app.route('/api/library/artists/<folder_type>')
def get_library_artists(folder_type):
    page = int(request.args.get('page', 1))
    limit = 60
    offset = (page - 1) * limit
    try:
        with sqlite3.connect(DB_PATH) as conn:
            conn.row_factory = sqlite3.Row
            rows = conn.execute(
                """SELECT TRIM(artist) as clean_artist, MAX(meta_poster) as cover
                   FROM global_songs
                   WHERE parent_path LIKE ?
                   GROUP BY UPPER(TRIM(artist))
                   ORDER BY clean_artist ASC
                   LIMIT ? OFFSET ?""",
                (f"{folder_type}%", limit, offset)
            ).fetchall()
            return jsonify([{"name": r['clean_artist'], "cover": r['cover']} for r in rows])
    except Exception as e:
        return jsonify([])

# 2. 특정 가수의 앨범 목록 조회 (애플뮤직 스타일 1단계)
@app.route('/api/library/albums_by_artist/<artist_name>')
def get_albums_by_artist(artist_name):
    try:
        name = urllib.parse.unquote(artist_name).strip()
        with sqlite3.connect(DB_PATH) as conn:
            conn.row_factory = sqlite3.Row
            rows = conn.execute(
                """SELECT albumName as name, artist, MAX(meta_poster) as imageUrl,
                          CAST(MAX(SUBSTR(release_date, 1, 4)) AS INTEGER) as year
                   FROM global_songs
                   WHERE UPPER(TRIM(artist)) = UPPER(TRIM(?))
                   GROUP BY albumName
                   ORDER BY year DESC""",
                (name,)
            ).fetchall()
            return jsonify([dict(r) for r in rows])
    except Exception as e:
        return jsonify([])

# 3. 특정 앨범의 전곡 조회 (애플뮤직 스타일 2단계 - 컴필레이션 지원)
@app.route('/api/library/songs_by_album/<artist_name>/<album_name>')
def get_songs_by_album(artist_name, album_name):
    try:
        art = urllib.parse.unquote(artist_name).strip()
        alb = urllib.parse.unquote(album_name).strip()
        with sqlite3.connect(DB_PATH) as conn:
            conn.row_factory = sqlite3.Row
            # 1. 먼저 해당 가수의 해당 앨범이 있는 대표 폴더를 찾음
            path_row = conn.execute(
                """SELECT parent_path FROM global_songs
                   WHERE UPPER(TRIM(artist)) = UPPER(TRIM(?))
                   AND UPPER(TRIM(albumName)) = UPPER(TRIM(?)) LIMIT 1""",
                (art, alb)
            ).fetchone()

            if path_row:
                # 🚀 [수정] rowid AS id 를 추가하여 앱이 클릭한 곡을 정확히 찾게 함
                rows = conn.execute(
                    """SELECT rowid AS id, name, artist, albumName, stream_url, parent_path, meta_poster,
                              genre, release_date, album_artist, 0 as is_dir
                       FROM global_songs WHERE parent_path = ? ORDER BY name""",
                    (path_row['parent_path'],)
                ).fetchall()
                return jsonify([dict(r) for r in rows])
            return jsonify([])
    except Exception as e:
        print(f"Error in get_songs_by_album: {e}")
        return jsonify([])

# 4. 아티스트 페이징 목록 (무한스크롤 지원)
@app.route('/api/library/artists_paged/<folder_type>')
def get_library_artists_paged(folder_type):
    page = int(request.args.get('page', 1))
    limit = 60
    offset = (page - 1) * limit
    try:
        with sqlite3.connect(DB_PATH) as conn:
            conn.row_factory = sqlite3.Row
            # 캐시 테이블 조회로 성능 극대화
            rows = conn.execute(
                "SELECT artist_name as name, cover FROM artists_cache WHERE folder_type = ? ORDER BY name ASC LIMIT ? OFFSET ?",
                (folder_type, limit, offset)
            ).fetchall()
            return jsonify([dict(r) for r in rows])
    except:
        return jsonify([])

@app.route('/api/indexing/start')
def start_indexing():
    target = request.args.get('target', '전체')
    if not idx_st["is_running"]:
        Thread(target=scan_all_songs, args=(target,)).start()
        return jsonify({"status": "ok", "message": f"[{target}] 스캔을 시작합니다."})
    else:
        return jsonify({"status": "error", "message": "이미 다른 스캔이 진행 중입니다."})

@app.route('/stream/<path:fp>')
def stream(fp): return send_from_directory(MUSIC_BASE, urllib.parse.unquote(fp))


if __name__ == '__main__':
    init_db()
    load_cache()
    app.run(host='0.0.0.0', port=4444, debug=False)
