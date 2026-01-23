package com.nas.musicplayer

import kotlinx.cinterop.*
import platform.AVFoundation.*
import platform.AVFAudio.*
import platform.Foundation.*
import platform.Speech.*
import platform.UIKit.*
import platform.darwin.*

@OptIn(ExperimentalForeignApi::class)
class VoiceSearchHelper(
    private val onResult: (String, Boolean) -> Unit, // text, isFinal
    private val onError: (String) -> Unit
) {
    private val speechRecognizer = SFSpeechRecognizer(NSLocale(localeIdentifier = "ko-KR"))
    private var recognitionRequest: SFSpeechAudioBufferRecognitionRequest? = null
    private var recognitionTask: SFSpeechRecognitionTask? = null
    private val audioEngine = AVAudioEngine()
    private var timeoutTimer: NSTimer? = null
    private var currentTranscript = ""

    fun startListening() {
        println("VoiceSearchHelper: startListening called")
        if (speechRecognizer == null || !speechRecognizer.isAvailable()) {
            onError("음성 인식기를 사용할 수 없습니다.")
            return
        }

        stopListening()
        currentTranscript = ""
        
        triggerHaptic(UIImpactFeedbackStyle.UIImpactFeedbackStyleLight)

        SFSpeechRecognizer.requestAuthorization { status ->
            dispatch_async(dispatch_get_main_queue()) {
                if (status == SFSpeechRecognizerAuthorizationStatus.SFSpeechRecognizerAuthorizationStatusAuthorized) {
                    try {
                        setupAudioSessionForRecording()
                        startRecognition()
                        startTimeoutTimer(5.0) 
                    } catch (e: Exception) {
                        onError("시작 오류: ${e.message}")
                    }
                } else {
                    onError("권한 거부")
                }
            }
        }
    }

    private fun startTimeoutTimer(seconds: Double) {
        timeoutTimer?.invalidate()
        timeoutTimer = NSTimer.scheduledTimerWithTimeInterval(
            interval = seconds,
            repeats = false,
            block = { _ ->
                dispatch_async(dispatch_get_main_queue()) {
                    println("VoiceSearchHelper: Timeout reached. Current transcript: '$currentTranscript'")
                    if (currentTranscript.isNotBlank()) {
                        // 타임아웃 시 currentTranscript를 명시적으로 전달
                        onResult(currentTranscript, true)
                    }
                    stopListening()
                }
            }
        )
    }

    private fun triggerHaptic(style: UIImpactFeedbackStyle) {
        val generator = UIImpactFeedbackGenerator(style)
        generator.prepare()
        generator.impactOccurred()
    }

    private fun setupAudioSessionForRecording() {
        val audioSession = AVAudioSession.sharedInstance()
        try {
            audioSession.setCategory(AVAudioSessionCategoryPlayAndRecord, 
                withOptions = AVAudioSessionCategoryOptionDefaultToSpeaker or AVAudioSessionCategoryOptionAllowBluetooth, 
                error = null)
            audioSession.setMode(AVAudioSessionModeMeasurement, error = null)
            audioSession.setActive(true, error = null)
        } catch (e: Exception) {
            println("VoiceSearchHelper: AudioSession setup error: ${e.message}")
        }
    }

    private fun startRecognition() {
        recognitionRequest = SFSpeechAudioBufferRecognitionRequest().apply {
            shouldReportPartialResults = true
        }

        val inputNode = audioEngine.inputNode
        val recordingFormat = inputNode.outputFormatForBus(0u)
        
        inputNode.removeTapOnBus(0u)
        inputNode.installTapOnBus(0u, 1024u, recordingFormat) { buffer: AVAudioPCMBuffer?, _: AVAudioTime? ->
            recognitionRequest?.appendAudioPCMBuffer(buffer!!)
        }

        audioEngine.prepare()
        try {
            audioEngine.startAndReturnError(null)
        } catch (e: Exception) {
            onError("엔진 시작 실패")
            return
        }

        recognitionTask = speechRecognizer?.recognitionTaskWithRequest(recognitionRequest!!) { result: SFSpeechRecognitionResult?, error: NSError? ->
            dispatch_async(dispatch_get_main_queue()) {
                if (result != null) {
                    val newText = result.bestTranscription.formattedString
                    val isFinal = result.isFinal()
                    
                    if (newText.isNotBlank()) {
                        currentTranscript = newText
                    }
                    
                    println("VoiceSearchHelper: Result received - '$newText' (Total: '$currentTranscript'), isFinal: $isFinal")
                    
                    // 핵심: isFinal인데 텍스트가 비어있으면 currentTranscript를 대신 보냄
                    val textToSend = if (isFinal && newText.isBlank()) currentTranscript else newText
                    
                    if (textToSend.isNotBlank() || isFinal) {
                        onResult(textToSend, isFinal)
                    }
                    
                    if (!isFinal) {
                        startTimeoutTimer(3.0)
                    } else {
                        stopListening()
                    }
                }
                if (error != null) {
                    println("VoiceSearchHelper: Error - ${error.localizedDescription}")
                    stopListening()
                }
            }
        }
    }

    fun stopListening() {
        println("VoiceSearchHelper: stopListening called")
        timeoutTimer?.invalidate()
        timeoutTimer = null

        if (audioEngine.isRunning()) {
            audioEngine.stop()
            audioEngine.inputNode.removeTapOnBus(0u)
            triggerHaptic(UIImpactFeedbackStyle.UIImpactFeedbackStyleMedium)
        }
        
        recognitionRequest?.endAudio()
        recognitionRequest = null
        recognitionTask?.cancel()
        recognitionTask = null
        
        restoreAudioSessionForPlayback()
    }

    private fun restoreAudioSessionForPlayback() {
        try {
            val audioSession = AVAudioSession.sharedInstance()
            audioSession.setCategory(AVAudioSessionCategoryPlayback, error = null)
            audioSession.setMode(AVAudioSessionModeDefault, error = null)
            audioSession.setActive(true, withOptions = AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation, error = null)
        } catch (e: Exception) {}
    }
}
