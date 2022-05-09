package orllewin.lento

import androidx.camera.core.AspectRatio
import orllewin.lento2.R
import orllewin.lento2.databinding.CameraUiContainerBinding
import android.content.SharedPreferences
import androidx.preference.PreferenceManager


class SettingsFacade(private val binding: CameraUiContainerBinding, private val listener: SettingsListener) {

    private val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(binding.root.context)

    init {
        binding.settingsDebugSwitch.setOnCheckedChangeListener { _, debugActive ->
            listener.onDebugActive(debugActive)
        }

        binding.aspectRatioGroup.setOnCheckedChangeListener { _, checkedId ->
            when (checkedId) {
                R.id.ratio_4_3_toggle -> {
                    binding.ratioSmallSubtitle.text = "4:3"
                    listener.onChangeRatio(AspectRatio.RATIO_4_3)
                }
                R.id.ratio_16_9_toggle -> {
                    binding.ratioSmallSubtitle.text = "16:9"
                    listener.onChangeRatio(AspectRatio.RATIO_16_9)
                }
            }
        }

        binding.previewScaleGroup.setOnCheckedChangeListener { _, checkedId ->
            when (checkedId) {
                R.id.preview_cropped_toggle -> {
                    binding.previewScaleSmallSubtitle.text = "Cropped"
                    listener.onPreview(false)
                }
                R.id.preview_fullscreen_toggle -> {
                    binding.previewScaleSmallSubtitle.text = "Fullscreen"
                    listener.onPreview(true)
                }
            }
        }

        binding.settingsAnamorphicSwitch.setOnCheckedChangeListener { _, active ->
            listener.onAnamorphic(active, getScaleFactor(binding.anamorphicScaleFactor.text.toString()))
        }

        binding.allSettingsButton.setOnClickListener {
            listener.showAllSettings()
        }
    }

    private fun getScaleFactor(input: String): Float{
        return when {
            input.isNumber() -> input.toFloat()
            else -> 1f
        }
    }

    fun updateUI() {
        binding.settingsDebugSwitch.isChecked = prefs.getBoolean("debug_mode", false)

        val fullscreenPreview = prefs.getBoolean("fullscreen", false)
        when {
            fullscreenPreview -> {
                binding.previewScaleSmallSubtitle.text = "Fullscreen"
                binding.previewFullscreenToggle.isChecked = true
            }
            else -> {
                binding.previewScaleSmallSubtitle.text = "Cropped"
                binding.previewCroppedToggle.isChecked = true
            }
        }

        when(prefs.getString("ratio", "sixteen_nine")){
            "four_three" -> {
                binding.ratioSmallSubtitle.text = "4:3"
                binding.ratio43Toggle.isChecked = true
            }
            "sixteen_nine" -> {
                binding.ratioSmallSubtitle.text = "16:9"
                binding.ratio169Toggle.isChecked = true
            }
        }

    }

    interface SettingsListener{
        fun onDebugActive(active: Boolean)
        fun onChangeRatio(ratio: Int)
        fun onAnamorphic(active: Boolean, ratio: Float)
        fun onPreview(fullScreen: Boolean)
        fun showAllSettings()
    }
}