/*
 * Copyright 2023 LiveKit, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.livekit.android.room

import android.os.SystemClock
import androidx.annotation.VisibleForTesting
import com.google.protobuf.ByteString
import io.livekit.android.ConnectOptions
import io.livekit.android.RoomOptions
import io.livekit.android.dagger.InjectionNames
import io.livekit.android.events.DisconnectReason
import io.livekit.android.events.convert
import io.livekit.android.room.participant.ParticipantTrackPermission
import io.livekit.android.room.track.TrackException
import io.livekit.android.room.util.MediaConstraintKeys
import io.livekit.android.room.util.createAnswer
import io.livekit.android.room.util.setLocalDescription
import io.livekit.android.util.CloseableCoroutineScope
import io.livekit.android.util.Either
import io.livekit.android.util.LKLog
import io.livekit.android.webrtc.RTCStatsGetter
import io.livekit.android.webrtc.copy
import io.livekit.android.webrtc.isConnected
import io.livekit.android.webrtc.isDisconnected
import io.livekit.android.webrtc.toProtoSessionDescription
import kotlinx.coroutines.*
import livekit.LivekitModels
import livekit.LivekitRtc
import livekit.LivekitRtc.JoinResponse
import livekit.LivekitRtc.ReconnectResponse
import org.webrtc.*
import org.webrtc.PeerConnection.RTCConfiguration
import org.webrtc.RtpTransceiver.RtpTransceiverInit
import java.net.ConnectException
import java.nio.ByteBuffer
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * @suppress
 */
@Singleton
class RTCEngine
@Inject
internal constructor(
    val client: SignalClient,
    private val pctFactory: PeerConnectionTransport.Factory,
    @Named(InjectionNames.DISPATCHER_IO)
    private val ioDispatcher: CoroutineDispatcher,
) : SignalClient.Listener, DataChannel.Observer {
    internal var listener: Listener? = null

    /**
     * Reflects the combined connection state of SignalClient and primary PeerConnection.
     */
    internal var connectionState: ConnectionState = ConnectionState.DISCONNECTED
        set(value) {
            val oldVal = field
            field = value
            if (value == oldVal) {
                return
            }
            when (value) {
                ConnectionState.CONNECTED -> {
                    if (oldVal == ConnectionState.DISCONNECTED) {
                        LKLog.d { "primary ICE connected" }
                        listener?.onEngineConnected()
                    } else if (oldVal == ConnectionState.RECONNECTING) {
                        LKLog.d { "primary ICE reconnected" }
                        listener?.onEngineReconnected()
                    }
                }

                ConnectionState.DISCONNECTED -> {
                    LKLog.d { "primary ICE disconnected" }
                    if (oldVal == ConnectionState.CONNECTED) {
                        reconnect()
                    }
                }

                else -> {
                }
            }
        }

    internal var reconnectType: ReconnectType = ReconnectType.DEFAULT
    private var reconnectingJob: Job? = null
    private var fullReconnectOnNext = false

    private val pendingTrackResolvers: MutableMap<String, Continuation<LivekitModels.TrackInfo>> =
        mutableMapOf()
    private var sessionUrl: String? = null
    private var sessionToken: String? = null
    private var connectOptions: ConnectOptions? = null
    private var lastRoomOptions: RoomOptions? = null
    private var participantSid: String? = null

    private val publisherObserver = PublisherTransportObserver(this, client)
    private val subscriberObserver = SubscriberTransportObserver(this, client)

    private var publisher: PeerConnectionTransport? = null
    private var subscriber: PeerConnectionTransport? = null

    private var reliableDataChannel: DataChannel? = null
    private var reliableDataChannelSub: DataChannel? = null
    private var lossyDataChannel: DataChannel? = null
    private var lossyDataChannelSub: DataChannel? = null

    private var isSubscriberPrimary = false
    private var isClosed = true

    private var hasPublished = false

    private var coroutineScope = CloseableCoroutineScope(SupervisorJob() + ioDispatcher)

    init {
        client.listener = this
    }

    suspend fun join(
        url: String,
        token: String,
        options: ConnectOptions,
        roomOptions: RoomOptions,
    ): JoinResponse {
        coroutineScope.close()
        coroutineScope = CloseableCoroutineScope(SupervisorJob() + ioDispatcher)
        sessionUrl = url
        sessionToken = token
        connectOptions = options
        lastRoomOptions = roomOptions
        return joinImpl(url, token, options, roomOptions)
    }

    suspend fun joinImpl(
        url: String,
        token: String,
        options: ConnectOptions,
        roomOptions: RoomOptions,
    ): JoinResponse {
        val joinResponse = client.join(url, token, options, roomOptions)
        listener?.onJoinResponse(joinResponse)
        isClosed = false
        listener?.onSignalConnected(false)

        isSubscriberPrimary = joinResponse.subscriberPrimary

        configure(joinResponse, options)

        // create offer
        if (!this.isSubscriberPrimary) {
            negotiatePublisher()
        }
        client.onReadyForResponses()
        return joinResponse
    }

    private suspend fun configure(joinResponse: JoinResponse, connectOptions: ConnectOptions) {
        if (publisher != null && subscriber != null) {
            // already configured
            return
        }

        participantSid = if (joinResponse.hasParticipant()) {
            joinResponse.participant.sid
        } else {
            null
        }

        // Setup peer connections
        val rtcConfig = makeRTCConfig(Either.Left(joinResponse), connectOptions)

        publisher?.close()
        publisher = pctFactory.create(
            rtcConfig,
            publisherObserver,
            publisherObserver,
        )
        subscriber?.close()
        subscriber = pctFactory.create(
            rtcConfig,
            subscriberObserver,
            null,
        )

        val connectionStateListener: (PeerConnection.PeerConnectionState) -> Unit = { newState ->
            LKLog.v { "onIceConnection new state: $newState" }
            if (newState.isConnected()) {
                connectionState = ConnectionState.CONNECTED
            } else if (newState.isDisconnected()) {
                connectionState = ConnectionState.DISCONNECTED
            }
        }

        if (joinResponse.subscriberPrimary) {
            // in subscriber primary mode, server side opens sub data channels.
            subscriberObserver.dataChannelListener = onDataChannel@{ dataChannel: DataChannel ->
                when (dataChannel.label()) {
                    RELIABLE_DATA_CHANNEL_LABEL -> reliableDataChannelSub = dataChannel
                    LOSSY_DATA_CHANNEL_LABEL -> lossyDataChannelSub = dataChannel
                    else -> return@onDataChannel
                }
                dataChannel.registerObserver(this)
            }

            subscriberObserver.connectionChangeListener = connectionStateListener
            // Also reconnect on publisher disconnect
            publisherObserver.connectionChangeListener = { newState ->
                if (newState.isDisconnected()) {
                    reconnect()
                }
            }
        } else {
            publisherObserver.connectionChangeListener = connectionStateListener
        }

        // data channels
        val reliableInit = DataChannel.Init()
        reliableInit.ordered = true
        reliableDataChannel = publisher?.withPeerConnection {
            createDataChannel(
                RELIABLE_DATA_CHANNEL_LABEL,
                reliableInit,
            ).apply { registerObserver(this@RTCEngine) }
        }

        val lossyInit = DataChannel.Init()
        lossyInit.ordered = true
        lossyInit.maxRetransmits = 0
        lossyDataChannel = publisher?.withPeerConnection {
            createDataChannel(
                LOSSY_DATA_CHANNEL_LABEL,
                lossyInit,
            ).apply { registerObserver(this@RTCEngine) }
        }
    }

    /**
     * @param builder an optional builder to include other parameters related to the track
     */
    suspend fun addTrack(
        cid: String,
        name: String,
        kind: LivekitModels.TrackType,
        builder: LivekitRtc.AddTrackRequest.Builder = LivekitRtc.AddTrackRequest.newBuilder(),
    ): LivekitModels.TrackInfo {
        if (pendingTrackResolvers[cid] != null) {
            throw TrackException.DuplicateTrackException("Track with same ID $cid has already been published!")
        }
        // Suspend until signal client receives message confirming track publication.
        return suspendCoroutine { cont ->
            pendingTrackResolvers[cid] = cont
            client.sendAddTrack(cid, name, kind, builder)
        }
    }

    internal suspend fun createSenderTransceiver(
        rtcTrack: MediaStreamTrack,
        transInit: RtpTransceiverInit,
    ): RtpTransceiver? {
        return publisher?.withPeerConnection {
            addTransceiver(rtcTrack, transInit)
        }
    }

    fun updateSubscriptionPermissions(
        allParticipants: Boolean,
        participantTrackPermissions: List<ParticipantTrackPermission>,
    ) {
        client.sendUpdateSubscriptionPermissions(allParticipants, participantTrackPermissions)
    }

    fun updateMuteStatus(sid: String, muted: Boolean) {
        client.sendMuteTrack(sid, muted)
    }

    fun close(reason: String = "Normal Closure") {
        if (isClosed) {
            return
        }
        LKLog.v { "Close - $reason" }
        isClosed = true
        reconnectingJob?.cancel()
        reconnectingJob = null
        coroutineScope.close()
        hasPublished = false
        sessionUrl = null
        sessionToken = null
        connectOptions = null
        lastRoomOptions = null
        participantSid = null
        closeResources(reason)
        connectionState = ConnectionState.DISCONNECTED
    }

    private fun closeResources(reason: String) {
        publisherObserver.connectionChangeListener = null
        subscriberObserver.connectionChangeListener = null
        publisher?.closeBlocking()
        publisher = null
        subscriber?.closeBlocking()
        subscriber = null

        fun DataChannel?.completeDispose() {
            this?.unregisterObserver()
            this?.close()
            this?.dispose()
        }
        reliableDataChannel?.completeDispose()
        reliableDataChannel = null
        reliableDataChannelSub?.completeDispose()
        reliableDataChannelSub = null
        lossyDataChannel?.completeDispose()
        lossyDataChannel = null
        lossyDataChannelSub?.completeDispose()
        lossyDataChannelSub = null
        isSubscriberPrimary = false
        client.close(reason = reason)
    }

    /**
     * reconnect Signal and PeerConnections
     */
    @Synchronized
    internal fun reconnect() {
        if (reconnectingJob?.isActive == true) {
            LKLog.d { "Reconnection is already in progress" }
            return
        }
        if (this.isClosed) {
            LKLog.d { "Skip reconnection - engine is closed" }
            return
        }
        val url = sessionUrl
        val token = sessionToken
        if (url == null || token == null) {
            LKLog.w { "couldn't reconnect, no url or no token" }
            return
        }
        val forceFullReconnect = fullReconnectOnNext
        fullReconnectOnNext = false
        val job = coroutineScope.launch {
            connectionState = ConnectionState.RECONNECTING
            listener?.onEngineReconnecting()

            val reconnectStartTime = SystemClock.elapsedRealtime()
            for (retries in 0 until MAX_RECONNECT_RETRIES) {
                ensureActive()
                if (retries != 0) {
                    yield()
                }

                if (isClosed) {
                    LKLog.v { "RTCEngine closed, aborting reconnection" }
                    break
                }

                var startDelay = 100 + retries.toLong() * retries * 500
                if (startDelay > 5000) {
                    startDelay = 5000
                }

                LKLog.i { "Reconnecting to signal, attempt ${retries + 1}" }
                delay(startDelay)

                val isFullReconnect = when (reconnectType) {
                    // full reconnect after first try.
                    ReconnectType.DEFAULT -> retries != 0 || forceFullReconnect
                    ReconnectType.FORCE_SOFT_RECONNECT -> false
                    ReconnectType.FORCE_FULL_RECONNECT -> true
                }

                val connectOptions = connectOptions ?: ConnectOptions()
                if (isFullReconnect) {
                    LKLog.v { "Attempting full reconnect." }
                    try {
                        closeResources("Full Reconnecting")
                        listener?.onFullReconnecting()
                        joinImpl(url, token, connectOptions, lastRoomOptions ?: RoomOptions())
                    } catch (e: Exception) {
                        LKLog.w(e) { "Error during reconnection." }
                        // reconnect failed, retry.
                        continue
                    }
                } else {
                    LKLog.v { "Attempting soft reconnect." }
                    subscriber?.prepareForIceRestart()
                    try {
                        val response = client.reconnect(url, token, participantSid)
                        if (response is Either.Left) {
                            val reconnectResponse = response.value
                            val rtcConfig = makeRTCConfig(Either.Right(reconnectResponse), connectOptions)
                            subscriber?.updateRTCConfig(rtcConfig)
                            publisher?.updateRTCConfig(rtcConfig)
                        }
                        client.onReadyForResponses()
                    } catch (e: Exception) {
                        LKLog.w(e) { "Error during reconnection." }
                        // ws reconnect failed, retry.
                        continue
                    }

                    LKLog.v { "ws reconnected, restarting ICE" }
                    listener?.onSignalConnected(!isFullReconnect)

                    // trigger publisher reconnect
                    // only restart publisher if it's needed
                    if (hasPublished) {
                        negotiatePublisher()
                    }
                }

                ensureActive()
                if (isClosed) {
                    LKLog.v { "RTCEngine closed, aborting reconnection" }
                    break
                }

                // wait until ICE connected
                val endTime = SystemClock.elapsedRealtime() + MAX_ICE_CONNECT_TIMEOUT_MS
                if (hasPublished) {
                    while (SystemClock.elapsedRealtime() < endTime) {
                        if (publisher?.isConnected() == true) {
                            LKLog.v { "publisher reconnected to ICE" }
                            break
                        }
                        delay(100)
                    }
                }

                ensureActive()
                if (isClosed) {
                    LKLog.v { "RTCEngine closed, aborting reconnection" }
                    break
                }

                while (SystemClock.elapsedRealtime() < endTime) {
                    if (subscriber?.isConnected() == true) {
                        LKLog.v { "reconnected to ICE" }
                        connectionState = ConnectionState.CONNECTED
                        break
                    }
                    delay(100)
                }

                ensureActive()
                if (isClosed) {
                    LKLog.v { "RTCEngine closed, aborting reconnection" }
                    break
                }
                if (connectionState == ConnectionState.CONNECTED &&
                    (!hasPublished || publisher?.isConnected() == true)
                ) {
                    client.onPCConnected()
                    listener?.onPostReconnect(isFullReconnect)
                    return@launch
                }

                val curReconnectTime = SystemClock.elapsedRealtime() - reconnectStartTime
                if (curReconnectTime > MAX_RECONNECT_TIMEOUT) {
                    break
                }
            }

            close("Failed reconnecting")
            listener?.onEngineDisconnected(DisconnectReason.UNKNOWN_REASON)
        }

        reconnectingJob = job
        job.invokeOnCompletion {
            if (reconnectingJob == job) {
                reconnectingJob = null
            }
        }
    }

    internal fun negotiatePublisher() {
        if (!client.isConnected) {
            return
        }

        hasPublished = true

        coroutineScope.launch {
            publisher?.negotiate?.invoke(getPublisherOfferConstraints())
        }
    }

    internal suspend fun sendData(dataPacket: LivekitModels.DataPacket) {
        ensurePublisherConnected(dataPacket.kind)

        val buf = DataChannel.Buffer(
            ByteBuffer.wrap(dataPacket.toByteArray()),
            true,
        )

        val channel = dataChannelForKind(dataPacket.kind)
            ?: throw TrackException.PublishException("channel not established for ${dataPacket.kind.name}")

        channel.send(buf)
    }

    private suspend fun ensurePublisherConnected(kind: LivekitModels.DataPacket.Kind) {
        if (!isSubscriberPrimary) {
            return
        }

        if (publisher == null) {
            throw RoomException.ConnectException("Publisher isn't setup yet! Is room not connected?!")
        }

        if (publisher?.isConnected() != true &&
            publisher?.iceConnectionState() != PeerConnection.IceConnectionState.CHECKING
        ) {
            // start negotiation
            this.negotiatePublisher()
        }

        val targetChannel = dataChannelForKind(kind) ?: throw IllegalArgumentException("Unknown data packet kind!")
        if (targetChannel.state() == DataChannel.State.OPEN) {
            return
        }

        // wait until publisher ICE connected
        val endTime = SystemClock.elapsedRealtime() + MAX_ICE_CONNECT_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < endTime) {
            if (publisher?.isConnected() == true && targetChannel.state() == DataChannel.State.OPEN) {
                return
            }
            delay(50)
        }

        throw ConnectException("could not establish publisher connection")
    }

    private fun dataChannelForKind(kind: LivekitModels.DataPacket.Kind) =
        when (kind) {
            LivekitModels.DataPacket.Kind.RELIABLE -> reliableDataChannel
            LivekitModels.DataPacket.Kind.LOSSY -> lossyDataChannel
            else -> null
        }

    private fun getPublisherOfferConstraints(): MediaConstraints {
        return MediaConstraints().apply {
            with(mandatory) {
                add(
                    MediaConstraints.KeyValuePair(
                        MediaConstraintKeys.OFFER_TO_RECV_AUDIO,
                        MediaConstraintKeys.FALSE,
                    ),
                )
                add(
                    MediaConstraints.KeyValuePair(
                        MediaConstraintKeys.OFFER_TO_RECV_VIDEO,
                        MediaConstraintKeys.FALSE,
                    ),
                )
                if (connectionState == ConnectionState.RECONNECTING) {
                    add(
                        MediaConstraints.KeyValuePair(
                            MediaConstraintKeys.ICE_RESTART,
                            MediaConstraintKeys.TRUE,
                        ),
                    )
                }
            }
        }
    }

    private fun makeRTCConfig(
        serverResponse: Either<JoinResponse, ReconnectResponse>,
        connectOptions: ConnectOptions,
    ): RTCConfiguration {
        // Convert protobuf ice servers
        val serverIceServers = run {
            val servers = mutableListOf<PeerConnection.IceServer>()
            val responseServers = when (serverResponse) {
                is Either.Left -> serverResponse.value.iceServersList
                is Either.Right -> serverResponse.value.iceServersList
            }
            for (serverInfo in responseServers) {
                servers.add(serverInfo.toWebrtc())
            }

            if (servers.isEmpty()) {
                servers.addAll(SignalClient.DEFAULT_ICE_SERVERS)
            }
            servers
        }

        val rtcConfig = connectOptions.rtcConfig?.copy()?.apply {
            val mergedServers = iceServers.toMutableList()
            if (connectOptions.iceServers != null) {
                connectOptions.iceServers.forEach { server ->
                    if (!mergedServers.contains(server)) {
                        mergedServers.add(server)
                    }
                }
            }

            // Only use server-provided servers if user doesn't provide any.
            if (mergedServers.isEmpty()) {
                iceServers.forEach { server ->
                    if (!mergedServers.contains(server)) {
                        mergedServers.add(server)
                    }
                }
            }

            iceServers = mergedServers
        }
            ?: RTCConfiguration(serverIceServers).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                continualGatheringPolicy =
                    PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            }

        val clientConfig = when (serverResponse) {
            is Either.Left -> {
                if (serverResponse.value.hasClientConfiguration()) {
                    serverResponse.value.clientConfiguration
                } else {
                    null
                }
            }

            is Either.Right -> {
                if (serverResponse.value.hasClientConfiguration()) {
                    serverResponse.value.clientConfiguration
                } else {
                    null
                }
            }
        }
        if (clientConfig != null) {
            if (clientConfig.forceRelay == LivekitModels.ClientConfigSetting.ENABLED) {
                rtcConfig.iceTransportsType = PeerConnection.IceTransportsType.RELAY
            }
        }

        return rtcConfig
    }

    internal interface Listener {
        fun onEngineConnected()
        fun onEngineReconnected()
        fun onEngineReconnecting()
        fun onEngineDisconnected(reason: DisconnectReason)
        fun onFailToConnect(error: Throwable)
        fun onJoinResponse(response: JoinResponse)
        fun onAddTrack(receiver: RtpReceiver, track: MediaStreamTrack, streams: Array<out MediaStream>)
        fun onUpdateParticipants(updates: List<LivekitModels.ParticipantInfo>)
        fun onActiveSpeakersUpdate(speakers: List<LivekitModels.SpeakerInfo>)
        fun onRemoteMuteChanged(trackSid: String, muted: Boolean)
        fun onRoomUpdate(update: LivekitModels.Room)
        fun onConnectionQuality(updates: List<LivekitRtc.ConnectionQualityInfo>)
        fun onSpeakersChanged(speakers: List<LivekitModels.SpeakerInfo>)
        fun onUserPacket(packet: LivekitModels.UserPacket, kind: LivekitModels.DataPacket.Kind)
        fun onStreamStateUpdate(streamStates: List<LivekitRtc.StreamStateInfo>)
        fun onSubscribedQualityUpdate(subscribedQualityUpdate: LivekitRtc.SubscribedQualityUpdate)
        fun onSubscriptionPermissionUpdate(subscriptionPermissionUpdate: LivekitRtc.SubscriptionPermissionUpdate)
        fun onSignalConnected(isResume: Boolean)
        fun onFullReconnecting()
        suspend fun onPostReconnect(isFullReconnect: Boolean)
        fun onLocalTrackUnpublished(trackUnpublished: LivekitRtc.TrackUnpublishedResponse)
    }

    companion object {
        private const val RELIABLE_DATA_CHANNEL_LABEL = "_reliable"
        private const val LOSSY_DATA_CHANNEL_LABEL = "_lossy"
        internal const val MAX_DATA_PACKET_SIZE = 15000
        private const val MAX_RECONNECT_RETRIES = 10
        private const val MAX_RECONNECT_TIMEOUT = 60 * 1000
        private const val MAX_ICE_CONNECT_TIMEOUT_MS = 20000

        internal val CONN_CONSTRAINTS = MediaConstraints().apply {
            with(optional) {
                add(MediaConstraints.KeyValuePair("DtlsSrtpKeyAgreement", "true"))
            }
        }
    }

    // ---------------------------------- SignalClient.Listener --------------------------------------//

    override fun onAnswer(sessionDescription: SessionDescription) {
        val signalingState = runBlocking { publisher?.signalingState() }
        LKLog.v { "received server answer: ${sessionDescription.type}, $signalingState" }
        coroutineScope.launch {
            LKLog.i { sessionDescription.toString() }
            when (val outcome = publisher?.setRemoteDescription(sessionDescription)) {
                is Either.Left -> {
                    // do nothing.
                }

                is Either.Right -> {
                    LKLog.e { "error setting remote description for answer: ${outcome.value} " }
                }

                else -> {
                    LKLog.w { "publisher is null, can't set remote description." }
                }
            }
        }
    }

    override fun onOffer(sessionDescription: SessionDescription) {
        val signalingState = runBlocking { publisher?.signalingState() }
        LKLog.v { "received server offer: ${sessionDescription.type}, $signalingState" }
        coroutineScope.launch {
            // TODO: This is a potentially very long lock hold. May need to break up.
            val answer = subscriber?.withPeerConnection {
                run {
                    when (
                        val outcome =
                            subscriber?.setRemoteDescription(sessionDescription)
                    ) {
                        is Either.Right -> {
                            LKLog.e { "error setting remote description for answer: ${outcome.value} " }
                            return@withPeerConnection null
                        }

                        else -> {}
                    }
                }

                if (isClosed) {
                    return@withPeerConnection null
                }

                val answer = run {
                    when (val outcome = createAnswer(MediaConstraints())) {
                        is Either.Left -> outcome.value
                        is Either.Right -> {
                            LKLog.e { "error creating answer: ${outcome.value}" }
                            return@withPeerConnection null
                        }
                    }
                }

                if (isClosed) {
                    return@withPeerConnection null
                }

                run<Unit> {
                    when (val outcome = setLocalDescription(answer)) {
                        is Either.Right -> {
                            LKLog.e { "error setting local description for answer: ${outcome.value}" }
                            return@withPeerConnection null
                        }

                        else -> {}
                    }
                }

                if (isClosed) {
                    return@withPeerConnection null
                }
                return@withPeerConnection answer
            }
            answer?.let {
                client.sendAnswer(it)
            }
        }
    }

    override fun onTrickle(candidate: IceCandidate, target: LivekitRtc.SignalTarget) {
        LKLog.v { "received ice candidate from peer: $candidate, $target" }
        when (target) {
            LivekitRtc.SignalTarget.PUBLISHER -> {
                publisher?.addIceCandidate(candidate)
                    ?: LKLog.w { "received candidate for publisher when we don't have one. ignoring." }
            }

            LivekitRtc.SignalTarget.SUBSCRIBER -> {
                subscriber?.addIceCandidate(candidate)
                    ?: LKLog.w { "received candidate for subscriber when we don't have one. ignoring." }
            }

            else -> LKLog.i { "unknown ice candidate target?" }
        }
    }

    override fun onLocalTrackPublished(response: LivekitRtc.TrackPublishedResponse) {
        val cid = response.cid ?: run {
            LKLog.e { "local track published with null cid?" }
            return
        }

        val track = response.track
        if (track == null) {
            LKLog.d { "local track published with null track info?" }
        }

        LKLog.v { "local track published $cid" }
        val cont = pendingTrackResolvers.remove(cid)
        if (cont == null) {
            LKLog.d { "missing track resolver for: $cid" }
            return
        }
        cont.resume(response.track)
    }

    override fun onParticipantUpdate(updates: List<LivekitModels.ParticipantInfo>) {
        listener?.onUpdateParticipants(updates)
    }

    override fun onSpeakersChanged(speakers: List<LivekitModels.SpeakerInfo>) {
        listener?.onSpeakersChanged(speakers)
    }

    override fun onClose(reason: String, code: Int) {
        LKLog.i { "received close event: $reason, code: $code" }
        reconnect()
    }

    override fun onRemoteMuteChanged(trackSid: String, muted: Boolean) {
        listener?.onRemoteMuteChanged(trackSid, muted)
    }

    override fun onRoomUpdate(update: LivekitModels.Room) {
        listener?.onRoomUpdate(update)
    }

    override fun onConnectionQuality(updates: List<LivekitRtc.ConnectionQualityInfo>) {
        listener?.onConnectionQuality(updates)
    }

    override fun onLeave(leave: LivekitRtc.LeaveRequest) {
        if (leave.canReconnect) {
            // reconnect will be triggered on close.
            fullReconnectOnNext = true
        } else {
            close()
            val disconnectReason = leave.reason.convert()
            listener?.onEngineDisconnected(disconnectReason)
        }
    }

    // Signal error
    override fun onError(error: Throwable) {
        if (connectionState == ConnectionState.CONNECTING) {
            listener?.onFailToConnect(error)
        }
    }

    override fun onStreamStateUpdate(streamStates: List<LivekitRtc.StreamStateInfo>) {
        listener?.onStreamStateUpdate(streamStates)
    }

    override fun onSubscribedQualityUpdate(subscribedQualityUpdate: LivekitRtc.SubscribedQualityUpdate) {
        listener?.onSubscribedQualityUpdate(subscribedQualityUpdate)
    }

    override fun onSubscriptionPermissionUpdate(subscriptionPermissionUpdate: LivekitRtc.SubscriptionPermissionUpdate) {
        listener?.onSubscriptionPermissionUpdate(subscriptionPermissionUpdate)
    }

    override fun onRefreshToken(token: String) {
        sessionToken = token
    }

    override fun onLocalTrackUnpublished(trackUnpublished: LivekitRtc.TrackUnpublishedResponse) {
        listener?.onLocalTrackUnpublished(trackUnpublished)
    }

    // --------------------------------- DataChannel.Observer ------------------------------------//

    override fun onBufferedAmountChange(previousAmount: Long) {
    }

    override fun onStateChange() {
    }

    override fun onMessage(buffer: DataChannel.Buffer?) {
        if (buffer == null) {
            return
        }
        val dp = LivekitModels.DataPacket.parseFrom(ByteString.copyFrom(buffer.data))
        when (dp.valueCase) {
            LivekitModels.DataPacket.ValueCase.SPEAKER -> {
                listener?.onActiveSpeakersUpdate(dp.speaker.speakersList)
            }

            LivekitModels.DataPacket.ValueCase.USER -> {
                listener?.onUserPacket(dp.user, dp.kind)
            }

            LivekitModels.DataPacket.ValueCase.VALUE_NOT_SET,
            null,
            -> {
                LKLog.v { "invalid value for data packet" }
            }
        }
    }

    fun sendSyncState(
        subscription: LivekitRtc.UpdateSubscription,
        publishedTracks: List<LivekitRtc.TrackPublishedResponse>,
    ) {
        val answer = runBlocking {
            subscriber?.withPeerConnection { localDescription?.toProtoSessionDescription() }
        }

        val dataChannelInfos = LivekitModels.DataPacket.Kind.values()
            .toList()
            .mapNotNull { kind -> dataChannelForKind(kind) }
            .map { dataChannel ->
                LivekitRtc.DataChannelInfo.newBuilder()
                    .setId(dataChannel.id())
                    .setLabel(dataChannel.label())
                    .build()
            }

        val syncState = with(LivekitRtc.SyncState.newBuilder()) {
            if (answer != null) {
                setAnswer(answer)
            }
            setSubscription(subscription)
            addAllPublishTracks(publishedTracks)
            addAllDataChannels(dataChannelInfos)
            build()
        }

        client.sendSyncState(syncState)
    }

    fun getPublisherRTCStats(callback: RTCStatsCollectorCallback) {
        runBlocking {
            publisher?.withPeerConnection { getStats(callback) }
                ?: callback.onStatsDelivered(RTCStatsReport(0, emptyMap()))
        }
    }

    fun getSubscriberRTCStats(callback: RTCStatsCollectorCallback) {
        runBlocking {
            subscriber?.withPeerConnection { getStats(callback) }
                ?: callback.onStatsDelivered(RTCStatsReport(0, emptyMap()))
        }
    }

    fun createStatsGetter(sender: RtpSender): RTCStatsGetter {
        val p = publisher
        return { statsCallback: RTCStatsCollectorCallback ->
            runBlocking {
                p?.withPeerConnection {
                    getStats(sender, statsCallback)
                } ?: statsCallback.onStatsDelivered(RTCStatsReport(0, emptyMap()))
            }
        }
    }

    fun createStatsGetter(receiver: RtpReceiver): RTCStatsGetter {
        val p = subscriber
        return { statsCallback: RTCStatsCollectorCallback ->
            runBlocking {
                p?.withPeerConnection {
                    getStats(receiver, statsCallback)
                } ?: statsCallback.onStatsDelivered(RTCStatsReport(0, emptyMap()))
            }
        }
    }

    internal fun registerTrackBitrateInfo(cid: String, trackBitrateInfo: TrackBitrateInfo) {
        publisher?.registerTrackBitrateInfo(cid, trackBitrateInfo)
    }

    internal fun removeTrack(rtcTrack: MediaStreamTrack) {
        runBlocking {
            publisher?.withPeerConnection {
                val senders = this.senders
                for (sender in senders) {
                    val t = sender.track() ?: continue
                    if (t.id() == rtcTrack.id()) {
                        this@withPeerConnection.removeTrack(sender)
                    }
                }
            }
        }
    }

    @VisibleForTesting
    internal suspend fun getPublisherPeerConnection() =
        publisher?.withPeerConnection { this }!!

    @VisibleForTesting
    internal suspend fun getSubscriberPeerConnection() =
        subscriber?.withPeerConnection { this }!!
}

/**
 * @suppress
 */
enum class ReconnectType {
    DEFAULT,
    FORCE_SOFT_RECONNECT,
    FORCE_FULL_RECONNECT,
}

internal fun LivekitRtc.ICEServer.toWebrtc() = PeerConnection.IceServer.builder(urlsList)
    .setUsername(username ?: "")
    .setPassword(credential ?: "")
    .setTlsAlpnProtocols(emptyList())
    .setTlsEllipticCurves(emptyList())
    .createIceServer()
