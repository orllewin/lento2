package orllewin.lento

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.hardware.display.DisplayManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.*
import android.webkit.MimeTypeMap
import androidx.camera.core.*
import androidx.camera.core.ImageCapture.Metadata
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.net.toFile
import androidx.core.view.isVisible
import androidx.core.view.setPadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.navigation.NavController
import androidx.navigation.Navigation
import com.bumptech.glide.Glide
import com.bumptech.glide.request.RequestOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import orllewin.lento.settings.SettingsActivity
import orllewin.lento2.databinding.CameraUiContainerBinding
import orllewin.lento2.databinding.FragmentCameraBinding
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.collections.ArrayList
import orllewin.lento2.R

/** Helper type alias used for analysis use case callbacks */
typealias LumaListener = (luma: Double) -> Unit
val EXTENSION_WHITELIST = arrayOf("JPG")
class CameraFragment : Fragment() {

    private lateinit var bindingCamera: FragmentCameraBinding
    private lateinit var bindingUI: CameraUiContainerBinding

    private lateinit var outputDirectory: File
    private lateinit var broadcastManager: LocalBroadcastManager

    lateinit var settingsFacade: SettingsFacade

    private var displayId: Int = -1
    private var aspectRatio: Int = -1
    private var lensFacing: Int = CameraSelector.LENS_FACING_BACK
    private var preview: Preview? = null
    private var imageCapture: ImageCapture? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null

    private val displayManager by lazy {
        requireContext().getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    }

    private lateinit var cameraExecutor: ExecutorService

    private val volumeDownReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(KEY_EVENT_EXTRA, KeyEvent.KEYCODE_UNKNOWN)) {
                KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    bindingUI.portraitCameraCaptureButton.simulateClick()
                }
            }
        }
    }

    /**
     * We need a display listener for orientation changes that do not trigger a configuration
     * change, for example if we choose to override config change in manifest or for 180-degree
     * orientation changes.
     */
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) = view?.let { view ->
            if (displayId == this@CameraFragment.displayId) {
                Log.d(TAG, "Rotation changed: ${view.display.rotation}")
                imageCapture?.targetRotation = view.display.rotation
                imageAnalyzer?.targetRotation = view.display.rotation

                when {
                    view.display.isPortrait() -> {
                        bindingUI.portraitShutterArea.show()
                        bindingUI.landscapeShutterArea.hide()
                    }
                    else -> {
                        bindingUI.portraitShutterArea.hide()
                        bindingUI.landscapeShutterArea.show()
                    }
                }
            }
        } ?: Unit
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        bindingCamera = FragmentCameraBinding.inflate(inflater, container, false)
        return bindingCamera.root
    }

    @SuppressLint("MissingPermission")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        bindingUI = CameraUiContainerBinding.inflate(LayoutInflater.from(requireContext()), bindingCamera.root, true)

        cameraExecutor = Executors.newSingleThreadExecutor()// Initialize our background executor
        broadcastManager = LocalBroadcastManager.getInstance(view.context)

        val filter = IntentFilter().apply { addAction(KEY_EVENT_ACTION) }
        broadcastManager.registerReceiver(volumeDownReceiver, filter)

        displayManager.registerDisplayListener(displayListener, null)// Every time the orientation of device changes, update rotation for use cases
        outputDirectory = MainActivity.getOutputDirectory(requireContext())// Determine the output directory

        bindingCamera.viewFinder.post {
            displayId = bindingCamera.viewFinder.display.displayId
            updateCameraUi()
            setUpCamera()
        }
    }

    override fun onResume() {
        super.onResume()
        when {
            !PermissionsFragment.hasPermissions(requireContext()) -> {
                nav().navigate(R.id.action_camera_to_permissions)
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        bindCameraUseCases()// Rebind the camera with the updated display metrics
        updateCameraSwitchButton()// Enable or disable switching between cameras
    }

    override fun onDestroyView() {
        super.onDestroyView()
        cameraExecutor.shutdown()
        broadcastManager.unregisterReceiver(volumeDownReceiver)
        displayManager.unregisterDisplayListener(displayListener)
    }

    private fun nav(): NavController = Navigation.findNavController(requireActivity(), R.id.fragment_container)


    private fun setGalleryThumbnail(uri: Uri) {
        bindingUI.photoViewButton.post {
            bindingUI.photoViewButton.setPadding(resources.getDimension(R.dimen.stroke_small).toInt())
            Glide.with(bindingUI.photoViewButton).load(uri).apply(RequestOptions.circleCropTransform()).into(bindingUI.photoViewButton)
        }
    }

    private fun setUpCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()

            lensFacing = when {
                hasBackCamera() -> CameraSelector.LENS_FACING_BACK
                hasFrontCamera() -> CameraSelector.LENS_FACING_FRONT
                else -> throw IllegalStateException("Back and front camera are unavailable")
            }

            updateCameraSwitchButton()
            bindCameraUseCases()
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    private fun bindCameraUseCases() {
        val screenAspectRatio = when (aspectRatio) {
            -1 -> AspectRatio.RATIO_4_3
            else -> aspectRatio
        }

        val rotation = bindingCamera.viewFinder.display.rotation

        val cameraProvider = cameraProvider ?: throw IllegalStateException("Camera initialization failed.")
        val cameraSelector = CameraSelector.Builder().requireLensFacing(lensFacing).build()

        preview = Preview.Builder().aspect(screenAspectRatio).rotation(rotation).build()
        imageCapture = ImageCapture.Builder().modeMaxQuality().aspect(screenAspectRatio).rotation(rotation).build()
        imageAnalyzer = ImageAnalysis.Builder().aspect(screenAspectRatio).rotation(rotation).build().also {
            it.setAnalyzer(cameraExecutor, LuminosityAnalyzer { luma ->
                //todo histogram? whatever, nothing?
                Log.d(TAG, "Average luminosity: $luma")
            })
        }

        cameraProvider.unbindAll()

        try {
            camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture, imageAnalyzer)
            preview?.setSurfaceProvider(bindingCamera.viewFinder.surfaceProvider)
            observeCameraState(camera?.cameraInfo!!)
        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
        }
    }

    private fun observeCameraState(cameraInfo: CameraInfo) {

        if(!bindingCamera.debug.isVisible) return

        cameraInfo.cameraState.observe(viewLifecycleOwner) { cameraState ->
            run {
                val message = when (cameraState.type) {
                    CameraState.Type.PENDING_OPEN -> "Camera state: PENDING_OPEN (close other apps)\n"
                    CameraState.Type.OPENING -> "Camera state: OPENING (show camera ui)\n"
                    CameraState.Type.OPEN -> "Camera state: OPEN (begin processing)\n"
                    CameraState.Type.CLOSING -> "Camera state: CLOSING (close camera ui)\n"
                    CameraState.Type.CLOSED -> "Camera state: CLOSED (free camera resources)\n"
                }
                bindingCamera.debug.append(message)
                bindingCamera.debugContainer.fullScroll(View.FOCUS_DOWN)
            }

            cameraState.error?.let { error ->
                val message = when (error.code) {
                    CameraState.ERROR_STREAM_CONFIG -> "Camera error: ERROR_STREAM_CONFIG (use cases setup incorrectly?)"
                    CameraState.ERROR_CAMERA_IN_USE -> "Camera error: ERROR_CAMERA_IN_USE (close other camera apps?)"
                    CameraState.ERROR_MAX_CAMERAS_IN_USE -> "Camera error: ERROR_MAX_CAMERAS_IN_USE (close other camera apps?)"
                    CameraState.ERROR_OTHER_RECOVERABLE_ERROR -> "Camera error: ERROR_OTHER_RECOVERABLE_ERROR"
                    CameraState.ERROR_CAMERA_DISABLED -> "Camera error: ERROR_CAMERA_DISABLED"
                    CameraState.ERROR_CAMERA_FATAL_ERROR -> "Camera error: ERROR_CAMERA_FATAL_ERROR (reboot device?)"
                    CameraState.ERROR_DO_NOT_DISTURB_MODE_ENABLED -> "Camera error: ERROR_DO_NOT_DISTURB_MODE_ENABLED (disable do not disturb mode)"
                    else -> "Camera error: Unknown Error"
                }
                bindingCamera.debug.append(message)
                bindingCamera.debugContainer.fullScroll(View.FOCUS_DOWN)
            }
        }
    }

    private fun updateCameraUi() {
        // Remove previous UI if any
        bindingUI.root.let {
           // bindingCamera.root.removeView(it)
        }

        // In the background, load latest photo taken (if any) for gallery thumbnail
        lifecycleScope.launch(Dispatchers.IO) {
            outputDirectory.listFiles { file ->
                EXTENSION_WHITELIST.contains(file.extension.uppercase())
            }?.maxOrNull()?.let {
                setGalleryThumbnail(Uri.fromFile(it))
            }
        }

        // Listener for button used to capture photo
        bindingUI.portraitCameraCaptureButton.setOnClickListener {

            // Get a stable reference of the modifiable image capture use case
            imageCapture?.let { imageCapture ->

                // Create output file to hold the image
                val photoFile = createFile(outputDirectory, FILENAME, PHOTO_EXTENSION)

                // Setup image capture metadata
                val metadata = Metadata().apply {

                    // Mirror image when using the front camera
                    isReversedHorizontal = lensFacing == CameraSelector.LENS_FACING_FRONT
                }

                // Create output options object which contains file + metadata
                val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile)
                        .setMetadata(metadata)
                        .build()

                // Setup image capture listener which is triggered after photo has been taken
                imageCapture.takePicture(
                        outputOptions, cameraExecutor, object : ImageCapture.OnImageSavedCallback {
                    override fun onError(exc: ImageCaptureException) {
                        Log.e(TAG, "Photo capture failed: ${exc.message}", exc)
                    }

                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                        val savedUri = output.savedUri ?: Uri.fromFile(photoFile)
                        Log.d(TAG, "Photo capture succeeded: $savedUri")

                        // We can only change the foreground Drawable using API level 23+ API
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            // Update the gallery thumbnail with latest picture taken
                            setGalleryThumbnail(savedUri)
                        }

                        // Implicit broadcasts will be ignored for devices running API level >= 24
                        // so if you only target API level 24+ you can remove this statement
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                            requireActivity().sendBroadcast(
                                    Intent(android.hardware.Camera.ACTION_NEW_PICTURE, savedUri)
                            )
                        }

                        // If the folder selected is an external media directory, this is
                        // unnecessary but otherwise other apps will not be able to access our
                        // images unless we scan them using [MediaScannerConnection]
                        val mimeType = MimeTypeMap.getSingleton()
                                .getMimeTypeFromExtension(savedUri.toFile().extension)
                        MediaScannerConnection.scanFile(
                                context,
                                arrayOf(savedUri.toFile().absolutePath),
                                arrayOf(mimeType)
                        ) { _, uri ->
                            Log.d(TAG, "Image capture scanned into media store: $uri")
                        }
                    }
                })

                // We can only change the foreground Drawable using API level 23+ API
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {

                    // Display flash animation to indicate that photo was captured
                    bindingCamera.root.postDelayed({
                        bindingCamera.root.foreground = ColorDrawable(Color.WHITE)
                        bindingCamera.root.postDelayed(
                                { bindingCamera.root.foreground = null }, ANIMATION_FAST_MILLIS)
                    }, ANIMATION_SLOW_MILLIS)
                }
            }
        }

        // Setup for button used to switch cameras
        bindingUI.portraitCameraSwitchButton.let {

            // Disable the button until the camera is set up
            it.isEnabled = false

            // Listener for button used to switch cameras. Only called if the button is enabled
            it.setOnClickListener {
                lensFacing = if (CameraSelector.LENS_FACING_FRONT == lensFacing) {
                    CameraSelector.LENS_FACING_BACK
                } else {
                    CameraSelector.LENS_FACING_FRONT
                }
                // Re-bind use cases to update selected camera
                bindCameraUseCases()
            }
        }

        // Listener for button used to view the most recent photo
        bindingUI.photoViewButton.setOnClickListener {
            // Only navigate when the gallery has photos
            if (true == outputDirectory.listFiles()?.isNotEmpty()) {
                val bundle = Bundle()
                bundle.putString("path", outputDirectory.absolutePath)
                //todo - new navigation to images
            }
        }

        settingsFacade = SettingsFacade(bindingUI, object: SettingsFacade.SettingsListener{
            override fun onDebugActive(active: Boolean) {
                when {
                    active -> {
                        bindingCamera.debugContainer.show()
                    }
                    else -> {
                        bindingCamera.debugContainer.hide()
                    }
                }
            }

            override fun onChangeRatio(ratio: Int) {
                aspectRatio = ratio
                bindCameraUseCases()
            }

            override fun onAnamorphic(active: Boolean, ratio: Float) = when {
                active -> bindingCamera.viewFinder.scaleX = ratio
                else -> bindingCamera.viewFinder.scaleX = 1f
            }

            override fun onPreview(fullScreen: Boolean) = when {
                fullScreen -> bindingCamera.viewFinder.scaleType = PreviewView.ScaleType.FILL_START
                else -> bindingCamera.viewFinder.scaleType = PreviewView.ScaleType.FIT_CENTER
            }

            override fun showAllSettings() {
                bindingUI.portraitSettingsArrow?.animate()?.rotationX(0f)?.setDuration(200)?.start()
                bindingUI.settingsLayout?.toggle()
                startActivity(Intent(requireContext(), SettingsActivity::class.java))
            }
        })

        bindingUI.settingsButton.setOnClickListener {
            if(bindingUI.settingsLayout.isVisible){
                bindingUI.portraitSettingsArrow.animate()?.rotationX(0f)?.setDuration(200)?.start()
            }else{
                settingsFacade.updateUI()
                bindingUI.portraitSettingsArrow.animate()?.rotationX(180f)?.setDuration(200)?.start()
            }
            bindingUI.settingsLayout.toggle()
        }
    }

    /** Enabled or disabled a button to switch cameras depending on the available cameras */
    private fun updateCameraSwitchButton() {
        try {
            bindingUI.portraitCameraSwitchButton.isEnabled = hasBackCamera() && hasFrontCamera()
        } catch (exception: CameraInfoUnavailableException) {
            bindingUI.portraitCameraSwitchButton.isEnabled = false
        }
    }

    /** Returns true if the device has an available back camera. False otherwise */
    private fun hasBackCamera(): Boolean {
        return cameraProvider?.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) ?: false
    }

    /** Returns true if the device has an available front camera. False otherwise */
    private fun hasFrontCamera(): Boolean {
        return cameraProvider?.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) ?: false
    }

    /**
     * Our custom image analysis class.
     *
     * <p>All we need to do is override the function `analyze` with our desired operations. Here,
     * we compute the average luminosity of the image by looking at the Y plane of the YUV frame.
     */
    private class LuminosityAnalyzer(listener: LumaListener? = null) : ImageAnalysis.Analyzer {
        private val frameRateWindow = 8
        private val frameTimestamps = ArrayDeque<Long>(5)
        private val listeners = ArrayList<LumaListener>().apply { listener?.let { add(it) } }
        private var lastAnalyzedTimestamp = 0L
        var framesPerSecond: Double = -1.0
            private set

        /**
         * Used to add listeners that will be called with each luma computed
         */
        fun onFrameAnalyzed(listener: LumaListener) = listeners.add(listener)

        /**
         * Helper extension function used to extract a byte array from an image plane buffer
         */
        private fun ByteBuffer.toByteArray(): ByteArray {
            rewind()    // Rewind the buffer to zero
            val data = ByteArray(remaining())
            get(data)   // Copy the buffer into a byte array
            return data // Return the byte array
        }

        /**
         * Analyzes an image to produce a result.
         *
         * <p>The caller is responsible for ensuring this analysis method can be executed quickly
         * enough to prevent stalls in the image acquisition pipeline. Otherwise, newly available
         * images will not be acquired and analyzed.
         *
         * <p>The image passed to this method becomes invalid after this method returns. The caller
         * should not store external references to this image, as these references will become
         * invalid.
         *
         * @param image image being analyzed VERY IMPORTANT: Analyzer method implementation must
         * call image.close() on received images when finished using them. Otherwise, new images
         * may not be received or the camera may stall, depending on back pressure setting.
         *
         */
        override fun analyze(image: ImageProxy) {
            // If there are no listeners attached, we don't need to perform analysis
            if (listeners.isEmpty()) {
                image.close()
                return
            }

            // Keep track of frames analyzed
            val currentTime = System.currentTimeMillis()
            frameTimestamps.push(currentTime)

            // Compute the FPS using a moving average
            while (frameTimestamps.size >= frameRateWindow) frameTimestamps.removeLast()
            val timestampFirst = frameTimestamps.peekFirst() ?: currentTime
            val timestampLast = frameTimestamps.peekLast() ?: currentTime
            framesPerSecond = 1.0 / ((timestampFirst - timestampLast) / frameTimestamps.size.coerceAtLeast(1).toDouble()) * 1000.0

            // Analysis could take an arbitrarily long amount of time
            // Since we are running in a different thread, it won't stall other use cases

            lastAnalyzedTimestamp = frameTimestamps.first

            // Since format in ImageAnalysis is YUV, image.planes[0] contains the luminance plane
            val buffer = image.planes[0].buffer

            // Extract image data from callback object
            val data = buffer.toByteArray()

            // Convert the data into an array of pixel values ranging 0-255
            val pixels = data.map { it.toInt() and 0xFF }

            // Compute average luminance for the image
            val luma = pixels.average()

            // Call all listeners with new value
            listeners.forEach { it(luma) }

            image.close()
        }
    }

    companion object {
        private const val TAG = "Lento2"
        private const val FILENAME = "yyyy-MM-dd-HH-mm-ss-SSS"
        private const val PHOTO_EXTENSION = ".jpg"
        private fun createFile(baseFolder: File, format: String, extension: String) = File(baseFolder, SimpleDateFormat(format, Locale.US).format(System.currentTimeMillis()) + extension)
    }
}
