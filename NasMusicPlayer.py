from flask import Flask, jsonify, send_from_directory, request
import os
from flask_cors import CORS
import urllib.parse
import time
from threading import Thread

app = Flask(__name__)
CORS(app)

MUSIC_ROOT_DIR = "/volume2/video/GDS3/GDRIVE/MUSIC/국내/차트"
BASE_URL = "http://192.168.0.2:4444"

# 전역 캐시 변수
cache = {
    "themes": [],
    "details": {},
    "last_updated": 0
}

def get_songs_in_dir(directory, rel_path_prefix):
    songs = []
    try:
        if os.path.exists(directory):
            for file in sorted(os.listdir(directory)):
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
                        "stream_url": f"{BASE_URL}/stream/{urllib.parse.quote(rel_path_prefix)}/{urllib.parse.quote(file)}",
                        "artist": artist,
                        "albumName": os.path.basename(directory),
                        "parent_path": rel_path_prefix
                    })
    except Exception as e:
        print(f"Error reading songs: {e}")
    return songs

def scan_music_library():
    """
    NAS의 파일 구조를 미리 스캔하여 메모리에 캐싱합니다.
    이 작업이 완료되면 앱 응답 속도가 0.1초 내외로 줄어듭니다.
    """
    global cache
    print("--- Scanning Music Library Start ---")
    start_time = time.time()

    new_themes = []
    new_details = {}

    if os.path.exists(MUSIC_ROOT_DIR):
        # 1단계: 최상위 테마 목록 스캔
        for entry in sorted(os.listdir(MUSIC_ROOT_DIR)):
            full_path = os.path.join(MUSIC_ROOT_DIR, entry)
            if os.path.isdir(full_path):
                new_themes.append({"name": entry, "path": entry})

                # 2단계: 각 테마별 상세 구조(하위 폴더) 사전 스캔
                theme_details = []
                sub_entries = sorted(os.listdir(full_path))
                for sub_entry in sub_entries:
                    sub_path = os.path.join(full_path, sub_entry)
                    if os.path.isdir(sub_path):
                        songs = get_songs_in_dir(sub_path, f"{entry}/{sub_entry}")
                        if songs:
                            theme_details.append({
                                "category_name": sub_entry,
                                "songs": songs
                            })

                # 루트에 직접 있는 곡들도 스캔
                root_songs = get_songs_in_dir(full_path, entry)
                if root_songs:
                    theme_details.insert(0, {
                        "category_name": entry,
                        "songs": root_songs
                    })

                new_details[entry] = theme_details

    cache["themes"] = new_themes
    cache["details"] = new_details
    cache["last_updated"] = time.time()

    print(f"--- Scanning Finished! (Taken: {time.time() - start_time:.2f}s) ---")

@app.route('/api/themes', methods=['GET'])
def get_themes():
    return jsonify(cache["themes"])

@app.route('/api/theme-details/<path:theme_path>', methods=['GET'])
def get_theme_details(theme_path):
    decoded_path = urllib.parse.unquote(theme_path)
    # 캐시에서 즉시 반환 (I/O 없음)
    details = cache["details"].get(decoded_path, [])
    return jsonify(details)

@app.route('/api/refresh', methods=['GET'])
def refresh_cache():
    Thread(target=scan_music_library).start()
    return jsonify({"status": "Refresh started"})

@app.route('/stream/<path:file_path>', methods=['GET'])
def stream_file(file_path):
    decoded_file_path = urllib.parse.unquote(file_path)
    return send_from_directory(MUSIC_ROOT_DIR, decoded_file_path)

if __name__ == '__main__':
    # 시작할 때 한 번 스캔
    scan_music_library()
    app.run(host='0.0.0.0', port=4444, debug=False) # 디버그 모드 끄기 (스캔 중복 방지)
