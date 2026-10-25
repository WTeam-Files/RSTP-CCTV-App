package com.zektopic.cctvapp

import android.media.MediaCodec
import com.pedro.common.AudioCodec
import com.pedro.common.ConnectChecker
import com.pedro.common.VideoCodec
import com.pedro.library.base.Camera2Base
import com.pedro.library.view.OpenGlView
import com.pedro.rtspserver.server.RtspServer
import com.pedro.rtspserver.util.RtspServerStreamClient
import java.nio.ByteBuffer

/**
 * The library's RtspServerCamera2 (final, so it cannot be extended) with one addition: every
 * encoded video frame is also handed to a [LiveVideoHub], which serves it to the dashboard
 * as live MP4. RTSP behaviour is unchanged.
 */
class CctvRtspCamera2(
    openGlView: OpenGlView,
    connectChecker: ConnectChecker,
    port: Int,
    private val hub: LiveVideoHub
) : Camera2Base(openGlView) {

    private val rtspServer = RtspServer(connectChecker, port)

    fun startStream() {
        super.startStream("")
    }

    override fun onAudioInfoImp(isStereo: Boolean, sampleRate: Int) {
        rtspServer.setAudioInfo(sampleRate, isStereo)
    }

    override fun startStreamImp(url: String) {
        rtspServer.startServer()
    }

    override fun stopStreamImp() {
        rtspServer.stopServer()
    }

    override fun getAudioDataImp(audioBuffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        rtspServer.sendAudio(audioBuffer, info)
    }

    override fun onVideoInfoImp(sps: ByteBuffer, pps: ByteBuffer?, vps: ByteBuffer?) {
        val newSps = sps.duplicate()
        val newPps = pps?.duplicate()
        val newVps = vps?.duplicate()
        rtspServer.setVideoInfo(newSps, newPps, newVps)
        try {
            if (pps != null) hub.onParameterSets(bytesOf(sps.duplicate()), bytesOf(pps.duplicate()))
        } catch (e: Exception) {
            android.util.Log.w("CctvRtspCamera2", "Live video parameter sets failed", e)
        }
    }

    override fun getVideoDataImp(videoBuffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        try {
            val view = videoBuffer.duplicate()
            view.position(info.offset)
            view.limit(info.offset + info.size)
            hub.onFrame(bytesOf(view), info.presentationTimeUs)
        } catch (e: Exception) {
            // Never let the browser path disturb RTSP.
        }
        rtspServer.sendVideo(videoBuffer, info)
    }

    override fun getStreamClient(): RtspServerStreamClient = RtspServerStreamClient(rtspServer)

    override fun setVideoCodecImp(codec: VideoCodec) {
        rtspServer.setVideoCodec(codec)
    }

    override fun setAudioCodecImp(codec: AudioCodec) {
        rtspServer.setAudioCodec(codec)
    }

    private fun bytesOf(buffer: ByteBuffer): ByteArray {
        val out = ByteArray(buffer.remaining())
        buffer.get(out)
        return out
    }
}
