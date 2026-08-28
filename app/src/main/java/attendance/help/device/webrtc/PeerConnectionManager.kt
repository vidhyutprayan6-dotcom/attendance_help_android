package attendance.help.device.webrtc

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import dagger.hilt.android.qualifiers.ApplicationContext
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoCapturer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import timber.log.Timber
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

data class WebRtcListeners(
    val onIceCandidate: (IceCandidate) -> Unit,
    val onConnectionChange: (PeerConnection.PeerConnectionState) -> Unit,
    val onDataMessage: (String) -> Unit,
    val onRemoteVideoTrack: (VideoTrack) -> Unit = {}
)

/**
 * WebRTC peer + local capture (camera or screen) + command data channel.
 *
 * Full control mode:
 * - Control publishes front camera (face) → Remote displays it.
 * - Remote publishes screen → Control displays it and sends touches.
 */
@Singleton
class PeerConnectionManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    val eglBase: EglBase = EglBase.create()

    private val mainHandler = Handler(Looper.getMainLooper())
    private var factory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var videoCapturer: VideoCapturer? = null
    private var surfaceHelper: SurfaceTextureHelper? = null
    private var videoSource: VideoSource? = null
    private var localVideoTrack: VideoTrack? = null
    private var audioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null
    private var dataChannel: DataChannel? = null
    private var listeners: WebRtcListeners? = null
    private val mediaRunning = AtomicBoolean(false)
    private var sharingScreen = false

    @Synchronized
    fun ensureInitialized() {
        if (factory != null) return
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
        )
        val encoder = DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)
        val decoder = DefaultVideoDecoderFactory(eglBase.eglBaseContext)
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoder)
            .setVideoDecoderFactory(decoder)
            .createPeerConnectionFactory()
        Timber.i("PeerConnectionFactory ready")
    }

    fun initLocalRenderer(renderer: SurfaceViewRenderer) {
        ensureInitialized()
        renderer.init(eglBase.eglBaseContext, null)
        renderer.setMirror(!sharingScreen)
        renderer.setEnableHardwareScaler(true)
    }

    fun initRemoteRenderer(renderer: SurfaceViewRenderer) {
        ensureInitialized()
        renderer.init(eglBase.eglBaseContext, null)
        renderer.setMirror(false)
        renderer.setEnableHardwareScaler(true)
    }

    @Synchronized
    fun createPeerConnection(isController: Boolean, listeners: WebRtcListeners, force: Boolean = false) {
        ensureInitialized()
        if (peerConnection != null && !force) {
            this.listeners = listeners
            return
        }
        closePeerOnly()
        this.listeners = listeners

        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
        )
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        peerConnection = factory!!.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onSignalingChange(newState: PeerConnection.SignalingState) = Unit
            override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) = Unit
            override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
            override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState) = Unit
            override fun onIceCandidate(candidate: IceCandidate) {
                listeners.onIceCandidate(candidate)
            }
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
            override fun onAddStream(stream: MediaStream) = Unit
            override fun onRemoveStream(stream: MediaStream) = Unit
            override fun onDataChannel(dc: DataChannel) {
                Timber.i("Remote data channel received")
                attachDataChannel(dc)
            }
            override fun onRenegotiationNeeded() = Unit
            override fun onAddTrack(receiver: RtpReceiver, mediaStreams: Array<out MediaStream>) {
                val track = receiver.track()
                if (track is VideoTrack) {
                    listeners.onRemoteVideoTrack(track)
                }
            }
            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                listeners.onConnectionChange(newState)
            }
        })

        if (isController) {
            dataChannel = peerConnection?.createDataChannel(
                "commands",
                DataChannel.Init().apply { ordered = true }
            )
            dataChannel?.let { attachDataChannel(it) }
        }
    }

    fun attachLocalVideoTo(renderer: SurfaceViewRenderer) {
        localVideoTrack?.addSink(renderer)
    }

    fun detachLocalVideoFrom(renderer: SurfaceViewRenderer) {
        localVideoTrack?.removeSink(renderer)
    }

    @Synchronized
    fun startCamera(preferFront: Boolean = true) {
        ensureInitialized()
        if (mediaRunning.get()) return
        val capturer = createFrontCapturer(preferFront) ?: run {
            Timber.e("No camera available")
            return
        }
        sharingScreen = false
        beginCapture(capturer, width = 1280, height = 720, fps = 30)
        Timber.i("Local camera started")
    }

    @Synchronized
    fun startScreenShare(permissionResultData: Intent) {
        ensureInitialized()
        if (mediaRunning.get()) return
        val capturer = ScreenCapturerAndroid(
            permissionResultData,
            object : MediaProjection.Callback() {
                override fun onStop() {
                    Timber.w("MediaProjection stopped by system")
                    mainHandler.post { stopCamera() }
                }
            }
        )
        sharingScreen = true
        val dm = context.resources.displayMetrics
        beginCapture(capturer, width = dm.widthPixels, height = dm.heightPixels, fps = 30)
        Timber.i("Screen share started")
    }

    private fun beginCapture(capturer: VideoCapturer, width: Int, height: Int, fps: Int) {
        videoCapturer = capturer
        surfaceHelper = SurfaceTextureHelper.create("CaptureThread", eglBase.eglBaseContext)
        videoSource = factory!!.createVideoSource(capturer.isScreencast)
        capturer.initialize(surfaceHelper, context, videoSource!!.capturerObserver)
        capturer.startCapture(width, height, fps)
        localVideoTrack = factory!!.createVideoTrack("AH_VIDEO", videoSource).apply {
            setEnabled(true)
        }
        audioSource = factory!!.createAudioSource(MediaConstraints())
        localAudioTrack = factory!!.createAudioTrack("AH_AUDIO", audioSource).apply {
            setEnabled(true)
        }
        peerConnection?.addTrack(localVideoTrack, listOf("AH_STREAM"))
        peerConnection?.addTrack(localAudioTrack, listOf("AH_STREAM"))
        mediaRunning.set(true)
    }

    @Synchronized
    fun stopCamera() {
        if (!mediaRunning.getAndSet(false)) return
        runCatching { videoCapturer?.stopCapture() }
        runCatching { videoCapturer?.dispose() }
        videoCapturer = null
        localVideoTrack?.dispose()
        localVideoTrack = null
        videoSource?.dispose()
        videoSource = null
        surfaceHelper?.dispose()
        surfaceHelper = null
        localAudioTrack?.dispose()
        localAudioTrack = null
        audioSource?.dispose()
        audioSource = null
        sharingScreen = false
        Timber.i("Local media stopped")
    }

    fun createOffer(onSuccess: (SessionDescription) -> Unit, onError: (String) -> Unit) {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
        }
        peerConnection?.createOffer(sdpObserver("offer", onSuccess, onError) { sdp ->
            peerConnection?.setLocalDescription(simpleSdpObserver(), sdp)
        }, constraints) ?: onError("No peer connection")
    }

    fun createAnswer(onSuccess: (SessionDescription) -> Unit, onError: (String) -> Unit) {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
        }
        peerConnection?.createAnswer(sdpObserver("answer", onSuccess, onError) { sdp ->
            peerConnection?.setLocalDescription(simpleSdpObserver(), sdp)
        }, constraints) ?: onError("No peer connection")
    }

    fun setRemoteDescription(sdp: SessionDescription, onDone: () -> Unit = {}) {
        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(p0: SessionDescription?) = Unit
            override fun onSetSuccess() = onDone()
            override fun onCreateFailure(p0: String?) = Timber.e("setRemote create fail %s", p0)
            override fun onSetFailure(p0: String?) = Timber.e("setRemote set fail %s", p0)
        }, sdp)
    }

    fun addIceCandidate(candidate: IceCandidate) {
        peerConnection?.addIceCandidate(candidate)
    }

    fun sendData(message: String) {
        val channel = dataChannel ?: return
        if (channel.state() != DataChannel.State.OPEN) {
            Timber.w("Data channel not open")
            return
        }
        val buffer = DataChannel.Buffer(ByteBuffer.wrap(message.toByteArray(Charsets.UTF_8)), false)
        channel.send(buffer)
    }

    fun isCameraRunning(): Boolean = mediaRunning.get()
    fun isScreenSharing(): Boolean = sharingScreen && mediaRunning.get()

    @Synchronized
    fun release() {
        stopCamera()
        closePeerOnly()
    }

    private fun closePeerOnly() {
        runCatching { dataChannel?.close() }
        dataChannel = null
        runCatching { peerConnection?.close() }
        runCatching { peerConnection?.dispose() }
        peerConnection = null
    }

    private fun attachDataChannel(channel: DataChannel) {
        dataChannel = channel
        channel.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) = Unit
            override fun onStateChange() {
                Timber.i("DataChannel state=%s", channel.state())
            }
            override fun onMessage(buffer: DataChannel.Buffer) {
                val data = ByteArray(buffer.data.remaining())
                buffer.data.get(data)
                val text = String(data, Charsets.UTF_8)
                mainHandler.post { listeners?.onDataMessage(text) }
            }
        })
    }

    private fun createFrontCapturer(preferFront: Boolean): VideoCapturer? {
        val enumerator = Camera2Enumerator(context)
        val deviceNames = enumerator.deviceNames
        val preferred = deviceNames.firstOrNull {
            if (preferFront) enumerator.isFrontFacing(it) else enumerator.isBackFacing(it)
        }
        val fallback = deviceNames.firstOrNull()
        val name = preferred ?: fallback ?: return null
        return enumerator.createCapturer(name, null)
    }

    private fun sdpObserver(
        label: String,
        onSuccess: (SessionDescription) -> Unit,
        onError: (String) -> Unit,
        afterCreate: (SessionDescription) -> Unit
    ) = object : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription) {
            afterCreate(sdp)
            onSuccess(sdp)
        }
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(error: String?) = onError(error ?: "create $label failed")
        override fun onSetFailure(error: String?) = onError(error ?: "set $label failed")
    }

    private fun simpleSdpObserver() = object : SdpObserver {
        override fun onCreateSuccess(p0: SessionDescription?) = Unit
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(p0: String?) = Timber.e(p0)
        override fun onSetFailure(p0: String?) = Timber.e(p0)
    }
}
