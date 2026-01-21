package com.nas.musicplayer

import androidx.compose.runtime.remember
import androidx.compose.ui.window.ComposeUIViewController
import com.nas.musicplayer.db.getDatabase

fun MainViewController() = ComposeUIViewController {
    // 1. 데이터베이스 및 리포지토리 초기화
    val database = remember { getDatabase() }
    val repository = remember { MusicRepository(database.playlistDao(), database.recentSearchDao()) }
    
    // 2. 컨트롤러 및 뷰모델 초기화
    val controller = remember { MusicPlayerController() }
    val viewModel = remember { MusicPlayerViewModel(controller, repository) }

    // 3. iOS 로컬 노래 로드
    val localSongs = remember { LocalMusicLoader.loadLocalMusic() }

    // 4. 공통 App 호출
    App(
        musicRepository = repository,
        musicPlayerViewModel = viewModel,
        localSongs = localSongs
    )
}
