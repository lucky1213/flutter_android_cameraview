package com.lucky1213.flutter_camera_view

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import androidx.core.content.ContextCompat
import com.otaliastudios.cameraview.*
import com.otaliastudios.cameraview.controls.*
import com.otaliastudios.cameraview.gesture.Gesture
import com.otaliastudios.cameraview.gesture.GestureAction
import com.otaliastudios.cameraview.markers.DefaultAutoFocusMarker
import com.otaliastudios.cameraview.size.SizeSelector
import com.otaliastudios.cameraview.size.SizeSelectors
import io.flutter.plugin.common.JSONMethodCodec
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.PluginRegistry.Registrar
import io.flutter.plugin.platform.PlatformView
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import kotlin.concurrent.thread

class AndroidCameraView
internal constructor(context: Context, methodChannel: MethodChannel, creationParams: Any) : PlatformView, MethodCallHandler, CameraListener() {
    private var cameraView: CameraView
    private var channel: MethodChannel
    private var file: File? = null
    private var storeThumbnail: Boolean = true
    private var context: Context = context
    private var thumbnailPath: String? = null
    private var thumbnailQuality: Int = 100
    private var mediaMetadataRetriever: MediaMetadataRetriever? = null
    private var saveToLibrary: Boolean = false

    init {
        cameraView = initView(context, creationParams as JSONObject)
        channel = methodChannel
        channel.setMethodCallHandler(this)
    }

    override fun getView(): View {
        Log.i("AndroidCameraView", "getView")
        return cameraView
    }

    override fun dispose() {
        Log.i("AndroidCameraView", "destroy")
        if (cameraView.isOpened) {
            cameraView.removeCameraListener(this)
            cameraView.close()
            cameraView.destroy()
        }
    }

    @SuppressLint("DefaultLocale")
    private fun initView(context: Context,options: JSONObject): CameraView {
        Log.i("AndroidCameraView", "initView: $options")
        val cameraView = CameraView(context)
        cameraView.facing = Facing.valueOf(options.optString("facing", "FRONT").toUpperCase())
        cameraView.mode = Mode.PICTURE
        cameraView.engine = Engine.CAMERA2
        cameraView.preview = Preview.GL_SURFACE
        cameraView.audioBitRate = 64000
        cameraView.flash = Flash.OFF
        cameraView.setAutoFocusMarker(DefaultAutoFocusMarker())
        cameraView.mapGesture(Gesture.PINCH, GestureAction.ZOOM) // Pinch to zoom!
        cameraView.mapGesture(Gesture.TAP, GestureAction.AUTO_FOCUS) // Tap to focus!
        cameraView.mapGesture(Gesture.SCROLL_VERTICAL, GestureAction.EXPOSURE_CORRECTION) // scroll_vertical to exposure!
        // set size
        val size: SizeSelector =
            SizeUtils.getSizeSelector(options.optString("resolutionPreset", "1080p"))
        cameraView.setPictureSize(size)
        cameraView.setVideoSize(size)
        cameraView.addCameraListener(this)
        cameraView.open()
        return cameraView
    }

    private fun storeThumbnailToFile(path: String, thumbnailPath: String? = null, quality: Int = 100, saveToLibrary: Boolean = false) : String? {
        mediaMetadataRetriever = mediaMetadataRetriever ?: MediaMetadataRetriever()
        mediaMetadataRetriever!!.setDataSource(path)
        val bitmap: Bitmap? = mediaMetadataRetriever!!.frameAtTime

        var format = Bitmap.CompressFormat.JPEG
        if (thumbnailPath != null) {
            val outputDir = File(thumbnailPath).parentFile!!
            if (!outputDir.exists()) {
                outputDir.mkdir()
            }
            val extension = MediaStoreUtils.getFileExtension(thumbnailPath)
            format = if (extension == "jpg" || extension == "jpeg") {
                Bitmap.CompressFormat.JPEG
            } else if (extension == "png") {
                Bitmap.CompressFormat.PNG
            } else {
                Bitmap.CompressFormat.JPEG
            }
        }

        val file = if (thumbnailPath != null) {
            File(thumbnailPath)
        } else {
            File(MediaStoreUtils.generateTempPath(context, Environment.DIRECTORY_PICTURES, extension = ".jpg", filename = File(path).nameWithoutExtension+"_thumbnail"))
        }
        if (file.exists()) {
            file.delete()
        }
        if (thumbnailPath != null) {
            val extension = MediaStoreUtils.getFileExtension(thumbnailPath)
            format = if (extension == "jpg" || extension == "jpeg") {
                Bitmap.CompressFormat.JPEG
            } else if (extension == "png") {
                Bitmap.CompressFormat.PNG
            } else {
                Bitmap.CompressFormat.JPEG
            }
        }

        try {
            //outputStream获取文件的输出流对象
            val fos: OutputStream = file.outputStream()
            //压缩格式为JPEG图像，压缩质量为100%
            bitmap!!.compress(format, quality, fos)
            fos.flush()
            fos.close()
            if (saveToLibrary) {
                MediaStoreUtils.insert(context, file, Environment.DIRECTORY_PICTURES)
            }
        } catch (e: Exception) {
            throw RuntimeException(e)
        }
        return file.absolutePath
    }

    override fun onVideoRecordingStart() {
        Log.i("AndroidCameraView", "onVideoRecordingStart:")
        super.onVideoRecordingStart()
        channel.invokeMethod("onVideoRecordingStart", null)
    }

    override fun onVideoRecordingEnd() {
        Log.i("AndroidCameraView", "onVideoRecordingEnd:")
        channel.invokeMethod("onVideoRecordingEnd", null)
    }

    override fun onCameraError(exception: CameraException) {
        exception.printStackTrace()
        Log.i("AndroidCameraView", "onCameraError:")
        super.onCameraError(exception)
        channel.invokeMethod("onCameraError", exception.message)
    }

    override fun onCameraOpened(options: CameraOptions) {
        super.onCameraOpened(options)
        channel.invokeMethod("onCameraOpened", null)
    }

    override fun onCameraClosed() {
        super.onCameraClosed()
        channel.invokeMethod("onCameraClosed", null)
    }

    private fun errorIfCameraNotOpened(result: MethodChannel.Result): Boolean {
        if (!cameraView.isOpened) {
            result.error("CameraError", "Camera is not opened.", null)
            return true
        }
        return false
    }

    private fun errorIfTakingVideo(result: MethodChannel.Result): Boolean {
        if (cameraView.isTakingVideo) {
            result.error("CameraError", "Already is recording video.", null)
            return true
        }
        return false
    }

    private fun errorIfTakingPicture(result: MethodChannel.Result): Boolean {
        if (cameraView.isTakingPicture) {
            result.error("CameraError", "Already is taking picture.", null)
            return true
        }
        return false
    }

    private fun errorIf(condition: Boolean, result: MethodChannel.Result, error: String): Boolean {
        if (condition) {
            result.error("CameraError", error, null)
            return true
        }
        return false
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        Log.i("AndroidCameraView", "onMethodCall: ${call.method} ${call.arguments}")
        val jsonArgs: JSONObject = if (call.arguments != null) {
            call.arguments as JSONObject
        } else {
            JSONObject()
        }
        when (call.method) {
            "startRecording" -> {
                if (errorIfCameraNotOpened(result)) return
                if (errorIfTakingVideo(result)) return
                if (errorIfTakingPicture(result)) return
                val fileInStr = jsonArgs.getString("file")
                storeThumbnail = jsonArgs.optBoolean("storeThumbnail", true)
                thumbnailPath = jsonArgs.optString("thumbnailPath")
                thumbnailQuality = jsonArgs.optInt("thumbnailQuality", 100)
                saveToLibrary = jsonArgs.optBoolean("saveToLibrary", false)

                val file = File(fileInStr)
                if (file.exists()) {
                    result.error("CameraError", "${file.path} already exists.", null)
                    return
                }

                if (!file.parentFile!!.exists()) {
                    file.parentFile!!.mkdir()
                }
                cameraView.takeVideoSnapshot(file)
                return result.success(true)
            }
            "takePicture" -> {
                if (errorIfCameraNotOpened(result)) return
                if (errorIfTakingVideo(result)) return
                if (errorIfTakingPicture(result)) return
                val fileInStr = jsonArgs.getString("file")
                saveToLibrary = jsonArgs.optBoolean("saveToLibrary", false)
                file = File(fileInStr)
                if (file!!.exists()) {
                    result.error("CameraError", "${file!!.path} already exists.", null)
                    return
                }
                if (!file!!.parentFile!!.exists()) {
                    file!!.parentFile!!.mkdir()
                }
                cameraView.mode = Mode.PICTURE
                cameraView.pictureSnapshotMetering = true
                cameraView.takePictureSnapshot()
                cameraView.addCameraListener(object : CameraListener() {
                    override fun onPictureTaken(picture: PictureResult) {
                        super.onPictureTaken(picture)
                        thread {
                            CameraUtils.writeToFile(picture.data, file!!)
                            Handler(Looper.getMainLooper()).post {
                                result.success(true)
                            }
                            if (saveToLibrary) {
                                MediaStoreUtils.insert(context, file!!)
                            }
                        }
                        cameraView.removeCameraListener(this)
                    }
                })
            }
            "startPreview" -> {
                if (!cameraView.isOpened) {
                    cameraView.open()
                }
                return result.success(true)
            }
            "stopPreview" -> {
                if (cameraView.isOpened) {
                    cameraView.close()
                }
                return result.success(true)
            }
            "stopRecording" -> {
                if (errorIfCameraNotOpened(result)) return
                cameraView.stopVideo()
                cameraView.addCameraListener(object : CameraListener() {
                    override fun onVideoTaken(video: VideoResult) {
                        super.onVideoTaken(video)
                        Log.i("AndroidCameraView", "onVideoTaken:" + video.maxDuration)
                        thread {
                            if (storeThumbnail) {
                                storeThumbnailToFile(video.file.absolutePath, thumbnailPath, thumbnailQuality, false)
                            }
                            Handler(Looper.getMainLooper()).post {
                                result.success(true)
                                channel.invokeMethod("onVideoTaken", null)
                            }
                            if (saveToLibrary) {
                                MediaStoreUtils.insert(context, video.file)
                            }
                        }
                        cameraView.removeCameraListener(this)
                    }
                })
            }
            "isPermissionsGranted" -> {
                result.success(ContextCompat.checkSelfPermission(registrar.context(), Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED && ContextCompat.checkSelfPermission(registrar.context(), Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
            }
            "setFacing" -> {
                if (errorIfCameraNotOpened(result)) return
                if (errorIfTakingPicture(result)) return
                if (errorIfTakingVideo(result)) return
                val facingInStr: String? = jsonArgs.getString("facing")
                if (errorIf(facingInStr == null, result, "set facing!")) return
                val facing: Facing = Facing.valueOf(facingInStr!!)
                cameraView.facing = facing
                cameraView.zoom = 0.toFloat()
                result.success(true)
            }
            "setFlash" -> {
                if (errorIfCameraNotOpened(result)) return
                if (errorIfTakingPicture(result)) return
                if (errorIfTakingVideo(result)) return
                val flashInStr: String? = jsonArgs.getString("flash")
                if (errorIf(flashInStr == null, result, "set flash!")) return
                val flash: Flash = Flash.valueOf(flashInStr!!)
                cameraView.flash = flash
                result.success(true)
            }
            "setZoom" -> {
                if (errorIfCameraNotOpened(result)) return
                cameraView.zoom = jsonArgs.optDouble("zoom", 0.0).toFloat()
            }
            "dispose" -> {
                cameraView.close()
                cameraView.destroy()
            }
        }
    }

}

class SizeUtils {
    companion object {
        private fun andMin(minWidth: Int, minHeight: Int): SizeSelector {
            val mw = SizeSelectors.minWidth(minWidth)
            val mh = SizeSelectors.minHeight(minHeight)
            return SizeSelectors.and(mw, mh)
        }

        private fun andMax(maxWidth: Int, maxHeight: Int): SizeSelector {
            val mw = SizeSelectors.maxWidth(maxWidth)
            val mh = SizeSelectors.maxHeight(maxHeight)
            return SizeSelectors.and(mw, mh)
        }

        fun getSizeSelector(sizeInStr: String): SizeSelector {
            return when (sizeInStr) {
                "2160p" -> SizeSelectors.or(andMin(3840, 2160), SizeSelectors.biggest())
                "1080p" -> SizeSelectors.or(andMin(1920, 1080), andMax(3840, 2060))
                "720p" -> SizeSelectors.or(andMin(1280, 720), andMax(1920, 1080))
                "540p" -> SizeSelectors.or(andMin(960, 540), andMax(1280, 720))
                "480p" -> SizeSelectors.or(andMin(720, 480), andMax(960, 540))
                else -> SizeSelectors.biggest()
            }
        }
    }
}