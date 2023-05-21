package vn.casperpas.sound_stream

import android.Manifest
import android.annotation.TargetApi
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.media.*
import android.media.AudioRecord.OnRecordPositionUpdateListener
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.util.Log
import androidx.annotation.NonNull
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.PluginRegistry
import io.flutter.plugin.common.PluginRegistry.Registrar
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.ShortBuffer

const val methodChannelName = "vn.casperpas.sound_stream:methods"
const val eventChannelName = "vn.casperpas.sound_stream:event"

enum class SoundStreamErrors {
    FailedToRecord,
    FailedToPlay,
    FailedToStop,
    FailedToWriteBuffer,
    Unknown,
}

enum class SoundStreamStatus {
    Unset,
    Initialized,
    Playing,
    Stopped,
}

/** SoundStreamPlugin */
public class SoundStreamPlugin : FlutterPlugin,
        MethodCallHandler,
        PluginRegistry.RequestPermissionsResultListener,
        ActivityAware
{
    private val logTag = "SoundStreamPlugin"
    private val audioRecordPermissionCode = 14887

    private lateinit var methodChannel: MethodChannel
    private var currentActivity: Activity? = null
    private var pluginContext: Context? = null
    private var permissionToRecordAudio: Boolean = false
    private var activeResult: Result? = null
    private var debugLogging: Boolean = false

    //========= Recorder's vars
    private val mRecordFormat = AudioFormat.ENCODING_PCM_16BIT
    private val mChannelOutMask = AudioFormat.CHANNEL_OUT_MONO
    private val mChannelInMask = AudioFormat.CHANNEL_IN_MONO
    private var mRecordSampleRate = 16000 // 16Khz
    private var mRecorderBufferSize = 8192
    private var mPeriodFrames = 8192
    private var audioData: ShortArray? = null
    private var mRecorder: AudioRecord? = null
    private var mListener: OnRecordPositionUpdateListener? = null

    //========= Player's vars
    private var mAudioTrack: AudioTrack? = null
    private var mPlayerSampleRate = 16000 // 16Khz
    private var mPlayerBufferSize = 10240
    private var mPlayerFormat: AudioFormat = AudioFormat.Builder()
            .setEncoding(mRecordFormat)
            .setChannelMask(mChannelOutMask)
            .setSampleRate(mPlayerSampleRate)
            .build()


    /** ======== Basic Plugin initialization ======== **/

    override fun onAttachedToEngine(@NonNull flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        onAttachedToEngine(flutterPluginBinding.applicationContext, flutterPluginBinding.binaryMessenger)
    }

    // This static function is optional and equivalent to onAttachedToEngine. It supports the old
    // pre-Flutter-1.12 Android projects. You are encouraged to continue supporting
    // plugin registration via this function while apps migrate to use the new Android APIs
    // post-flutter-1.12 via https://flutter.dev/go/android-project-migration.
    //
    // It is encouraged to share logic between onAttachedToEngine and registerWith to keep
    // them functionally equivalent. Only one of onAttachedToEngine or registerWith will be called
    // depending on the user's project. onAttachedToEngine or registerWith must both be defined
    // in the same class.
    companion object {
        @JvmStatic
        fun registerWith(registrar: Registrar) {
            val plugin = SoundStreamPlugin()
            plugin.currentActivity = registrar.activity()
            registrar.addRequestPermissionsResultListener(plugin)
            plugin.onAttachedToEngine(registrar.context(), registrar.messenger())
        }
    }

    private fun onAttachedToEngine(applicationContext: Context, messenger: BinaryMessenger) {
        pluginContext = applicationContext
        methodChannel = MethodChannel(messenger, methodChannelName)
        methodChannel.setMethodCallHandler(this)
    }

    override fun onMethodCall(@NonNull call: MethodCall, @NonNull result: Result) {
        try {
            when (call.method) {
                "hasPermission" -> hasPermission(result)
                "initializeRecorder" -> initializeRecorder(call, result)
                "startRecording" -> startRecording(result)
                "stopRecording" -> stopRecording(result)
                "initializePlayer" -> initializePlayer(call, result)
                "startPlayer" -> startPlayer(result)
                "stopPlayer" -> stopPlayer(result)
                "writeChunk" -> writeChunk(call, result)
                "setAudioOutput" -> setAudioOutput(call, result)
                else -> result.notImplemented()
            }
        } catch (e: Exception) {
            Log.e(logTag, "Unexpected exception", e)
            result.error(SoundStreamErrors.Unknown.name,
                    "Unexpected exception", e.localizedMessage)
        }
    }

    override fun onDetachedFromEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
        methodChannel.setMethodCallHandler(null)
        mListener?.onMarkerReached(null)
        mListener?.onPeriodicNotification(null)
        mListener = null
        mRecorder?.stop()
        mRecorder?.release()
        mRecorder = null
    }

    override fun onDetachedFromActivity() {
//        currentActivity
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        currentActivity = binding.activity
        binding.addRequestPermissionsResultListener(this)
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        currentActivity = binding.activity
        binding.addRequestPermissionsResultListener(this)
    }

    override fun onDetachedFromActivityForConfigChanges() {
//        currentActivity = null
    }

    @TargetApi(Build.VERSION_CODES.M)
    private fun setAudioOutput(call: MethodCall, result: Result) {
        val audioOutput = call.argument<String>("audioOutput") ?: "speaker"
        val audioManager = pluginContext?.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        if (audioOutput == "headphones") {
            audioManager.isSpeakerphoneOn = !areHeadphonesConnected(audioManager);
        }

        if (audioOutput == "speaker") {
            audioManager.isSpeakerphoneOn = true
        }

        result.success(null)
    }

     @TargetApi(Build.VERSION_CODES.M)
     private fun areHeadphonesConnected(audioManager: AudioManager): Boolean {
         val audioDevices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
    
         val isWiredHeadsetConnected = audioDevices.any { device ->
             device.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
             device.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
         }
    
         val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
         val isBluetoothHeadsetConnected = bluetoothAdapter?.getProfileConnectionState(BluetoothProfile.HEADSET) == BluetoothProfile.STATE_CONNECTED
    
         return isWiredHeadsetConnected || isBluetoothHeadsetConnected
     }

    /** ======== Plugin methods ======== **/
    private fun hasRecordPermission(): Boolean {
        if (permissionToRecordAudio) return true

        val localContext = pluginContext
        permissionToRecordAudio = localContext != null && ContextCompat.checkSelfPermission(localContext,
                Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        return permissionToRecordAudio

    }

    private fun hasPermission(result: Result) {
        result.success(hasRecordPermission())
    }

    private fun requestRecordPermission() {
        val localActivity = currentActivity
        if (!hasRecordPermission() && localActivity != null) {
            debugLog("requesting RECORD_AUDIO permission")
            ActivityCompat.requestPermissions(localActivity,
                    arrayOf(Manifest.permission.RECORD_AUDIO), audioRecordPermissionCode)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>,
                                            grantResults: IntArray): Boolean {
        when (requestCode) {
            audioRecordPermissionCode -> {
                if (grantResults != null) {
                    permissionToRecordAudio = grantResults.isNotEmpty() &&
                            grantResults[0] == PackageManager.PERMISSION_GRANTED
                }
                completeInitializeRecorder()
                return true
            }
        }
        return false
    }

    private fun initializeRecorder(@NonNull call: MethodCall, @NonNull result: Result) {
        mRecordSampleRate = call.argument<Int>("sampleRate") ?: mRecordSampleRate
        debugLogging = call.argument<Boolean>("showLogs") ?: false
        mPeriodFrames = AudioRecord.getMinBufferSize(mRecordSampleRate, mChannelInMask, mRecordFormat)
        mRecorderBufferSize = mPeriodFrames * 2
        audioData = ShortArray(mPeriodFrames)
        activeResult = result

        val localContext = pluginContext
        if (null == localContext) {
            completeInitializeRecorder()
            return
        }
        permissionToRecordAudio = ContextCompat.checkSelfPermission(localContext,
                Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (!permissionToRecordAudio) {
            requestRecordPermission()
        } else {
            debugLog("has permission, completing")
            completeInitializeRecorder()
        }
        debugLog("leaving initializeIfPermitted")
    }

    private fun initRecorder() {
        if (mRecorder?.state == AudioRecord.STATE_INITIALIZED) {
            return
        }
        mRecorder = AudioRecord(MediaRecorder.AudioSource.MIC, mRecordSampleRate, mChannelInMask, mRecordFormat, mRecorderBufferSize)

        if (mRecorder != null) {
            val id = mRecorder?.audioSessionId ?: 0

            if (AcousticEchoCanceler.isAvailable()) {
                val echo = AcousticEchoCanceler.create(id)
                echo.enabled = true
            }

            if (NoiseSuppressor.isAvailable()) {
                val noise = NoiseSuppressor.create(id)
                noise?.enabled = true
            }

            if (AutomaticGainControl.isAvailable()) {
                val gain = AutomaticGainControl.create(id)
                gain?.enabled = false
            }

            mListener = createRecordListener()
            mRecorder?.positionNotificationPeriod = mPeriodFrames
            mRecorder?.setRecordPositionUpdateListener(mListener)
        }
    }

    private fun completeInitializeRecorder() {

        debugLog("completeInitialize")
        val initResult: HashMap<String, Any> = HashMap()

        if (permissionToRecordAudio) {
            mRecorder?.release()
            initRecorder()
            initResult["isMeteringEnabled"] = true
            sendRecorderStatus(SoundStreamStatus.Initialized)
        }

        initResult["success"] = permissionToRecordAudio
        debugLog("sending result")
        activeResult?.success(initResult)
        debugLog("leaving complete")
        activeResult = null
    }

    private fun sendEventMethod(name: String, data: Any) {
        val eventData: HashMap<String, Any> = HashMap()
        eventData["name"] = name
        eventData["data"] = data
        methodChannel.invokeMethod("platformEvent", eventData)
    }

    private fun debugLog(msg: String) {
        if (debugLogging) {
            Log.d(logTag, msg)
        }
    }

    private fun startRecording(result: Result) {
        try {
            if (mRecorder!!.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                result.success(true)
                return
            }
            initRecorder()
            mRecorder!!.startRecording()

            sendRecorderStatus(SoundStreamStatus.Playing)
            result.success(true)
        } catch (e: IllegalStateException) {
            debugLog("record() failed")
            result.error(SoundStreamErrors.FailedToRecord.name, "Failed to start recording", e.localizedMessage)
        }
    }

    private fun stopRecording(result: Result) {
        try {
            if (mRecorder!!.recordingState == AudioRecord.RECORDSTATE_STOPPED) {
                result.success(true)
                return
            }
            mRecorder!!.stop()
            sendRecorderStatus(SoundStreamStatus.Stopped)
            result.success(true)
        } catch (e: IllegalStateException) {
            debugLog("record() failed")
            result.error(SoundStreamErrors.FailedToRecord.name, "Failed to start recording", e.localizedMessage)
        }
    }

    private fun sendRecorderStatus(status: SoundStreamStatus) {
        sendEventMethod("recorderStatus", status.name)
    }

    @TargetApi(Build.VERSION_CODES.M)
    private fun initializePlayer(@NonNull call: MethodCall, @NonNull result: Result) {
        mPlayerSampleRate = call.argument<Int>("sampleRate") ?: mPlayerSampleRate
        debugLogging = call.argument<Boolean>("showLogs") ?: false

        if (mAudioTrack?.state == AudioTrack.STATE_INITIALIZED) {
            mAudioTrack?.release()
        }

        mPlayerBufferSize = AudioTrack.getMinBufferSize(mPlayerSampleRate, mChannelOutMask, mRecordFormat)

        mPlayerFormat = AudioFormat.Builder()
            .setEncoding(mRecordFormat)
            .setChannelMask(mChannelOutMask)
            .setSampleRate(mPlayerSampleRate)
            .build()

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        val mAudioTrackBuilder = AudioTrack.Builder()
            .setAudioAttributes(audioAttributes)
            .setAudioFormat(mPlayerFormat)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(mPlayerBufferSize)

        if (mRecorder?.audioSessionId != null) {
            mAudioTrackBuilder.setSessionId(mRecorder?.audioSessionId ?: 0);
        }

        mAudioTrack = mAudioTrackBuilder.build()

        result.success(true)
        sendPlayerStatus(SoundStreamStatus.Initialized)
    }


    private fun writeChunk(@NonNull call: MethodCall, @NonNull result: Result) {
        val data = call.argument<ByteArray>("data")
        if (data != null) {
            pushPlayerChunk(data, result)
        } else {
            result.error(SoundStreamErrors.FailedToWriteBuffer.name, "Failed to write Player buffer", "'data' is null")
        }
    }
    private fun pushPlayerChunk(chunk: ByteArray, result: Result) {
        try {
            val buffer = ByteBuffer.wrap(chunk)
            val shortBuffer = ShortBuffer.allocate(chunk.size / 2)
            shortBuffer.put(buffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer())
            val shortChunk = shortBuffer.array()
            mAudioTrack?.write(shortChunk, 0, shortChunk.size)

            result.success(true)
        } catch (e: Exception) {
            result.error(SoundStreamErrors.FailedToWriteBuffer.name, "Failed to write Player buffer", e.localizedMessage)
        }
    }

    private fun startPlayer(result: Result) {
        try {
            if (mAudioTrack?.state == AudioTrack.PLAYSTATE_PLAYING) {
                result.success(true)
                return
            }

            mAudioTrack!!.play()
            sendPlayerStatus(SoundStreamStatus.Playing)
            result.success(true)
        } catch (e: Exception) {
            result.error(SoundStreamErrors.FailedToPlay.name, "Failed to start Player", e.localizedMessage)
        }
    }

    private fun stopPlayer(result: Result) {
        try {
            if (mAudioTrack?.state == AudioTrack.STATE_INITIALIZED) {
                mAudioTrack?.stop()
            }
            sendPlayerStatus(SoundStreamStatus.Stopped)
            result.success(true)
        } catch (e: Exception) {
            result.error(SoundStreamErrors.FailedToStop.name, "Failed to stop Player", e.localizedMessage)
        }
    }

    private fun sendPlayerStatus(status: SoundStreamStatus) {
        sendEventMethod("playerStatus", status.name)
    }

    private fun createRecordListener(): OnRecordPositionUpdateListener? {
        return object : OnRecordPositionUpdateListener {
            override fun onMarkerReached(recorder: AudioRecord) {
                recorder.read(audioData!!, 0, mRecorderBufferSize)
            }

            override fun onPeriodicNotification(recorder: AudioRecord) {
                val data = audioData!!
                val shortOut = recorder.read(data, 0, mPeriodFrames)
                // this condition to prevent app crash from happening in Android Devices
                // See issues: https://github.com/CasperPas/flutter-sound-stream/issues/25
                if (shortOut < 1) { return }
                // https://flutter.io/platform-channels/#codec
                // convert short to int because of platform-channel's limitation
                val byteBuffer = ByteBuffer.allocate(shortOut * 2)
                byteBuffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(data)

                sendEventMethod("dataPeriod", byteBuffer.array())
            }
        }
    }
}
