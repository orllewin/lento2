package orllewin.lento

import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.*
import android.widget.ImageButton
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview

fun delay(ms: Int, action: () -> Unit){
    Handler(Looper.getMainLooper()).postDelayed({
        action.invoke()
    }, ms.toLong())
}

fun ImageAnalysis.Builder.aspect(ratio: Int): ImageAnalysis.Builder{
    setTargetAspectRatio(ratio)
    return this
}

fun ImageAnalysis.Builder.rotation(rotation: Int): ImageAnalysis.Builder{
    setTargetRotation(rotation)
    return this
}

fun ImageCapture.Builder.aspect(ratio: Int): ImageCapture.Builder{
    setTargetAspectRatio(ratio)
    return this
}

fun ImageCapture.Builder.mode(mode: Int): ImageCapture.Builder{
    setCaptureMode(mode)
    return this
}
fun ImageCapture.Builder.modeMaxQuality(): ImageCapture.Builder{
    setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
    return this
}

fun ImageCapture.Builder.rotation(rotation: Int): ImageCapture.Builder{
    setTargetRotation(rotation)
    return this
}

fun Preview.Builder.aspect(ratio: Int): Preview.Builder{
    setTargetAspectRatio(ratio)
    return this
}

fun Preview.Builder.rotation(rotation: Int): Preview.Builder{
    setTargetRotation(rotation)
    return this
}

fun Display.isPortrait(): Boolean = this.rotation == Surface.ROTATION_0

fun View.hide() {
    this.visibility = View.GONE
}
fun View.show() {
    this.visibility = View.VISIBLE
}
fun View.toggle() = when (this.visibility) {
    View.GONE -> show()
    else -> hide()
}

fun String.isNumber(): Boolean = this.toFloatOrNull() != null

/** Same as [AlertDialog.show] but setting immersive mode in the dialog's window */
fun AlertDialog.showImmersive() {
    // Set the dialog to not focusable
    window?.setFlags(
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)

    // Make sure that the dialog's window is in full screen
    window?.decorView?.systemUiVisibility = FLAGS_FULLSCREEN

    // Show the dialog while still in immersive mode
    show()

    // Set the dialog to focusable again
    window?.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
}

/** Pad this view with the insets provided by the device cutout (i.e. notch) */
@RequiresApi(Build.VERSION_CODES.P)
fun View.padWithDisplayCutout() {

    /** Helper method that applies padding from cutout's safe insets */
    fun doPadding(cutout: DisplayCutout) = setPadding(
        cutout.safeInsetLeft,
        cutout.safeInsetTop,
        cutout.safeInsetRight,
        cutout.safeInsetBottom)

    // Apply padding using the display cutout designated "safe area"
    rootWindowInsets?.displayCutout?.let { doPadding(it) }

    // Set a listener for window insets since view.rootWindowInsets may not be ready yet
    setOnApplyWindowInsetsListener { _, insets ->
        insets.displayCutout?.let { doPadding(it) }
        insets
    }
}

/** Combination of all flags required to put activity into immersive mode */
const val FLAGS_FULLSCREEN =
    View.SYSTEM_UI_FLAG_LOW_PROFILE or
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION

/** Milliseconds used for UI animations */
const val ANIMATION_FAST_MILLIS = 50L
const val ANIMATION_SLOW_MILLIS = 100L

/**
 * Simulate a button click, including a small delay while it is being pressed to trigger the
 * appropriate animations.
 */
fun ImageButton.simulateClick(delay: Long = ANIMATION_FAST_MILLIS) {
    performClick()
    isPressed = true
    invalidate()
    postDelayed({
        invalidate()
        isPressed = false
    }, delay)
}