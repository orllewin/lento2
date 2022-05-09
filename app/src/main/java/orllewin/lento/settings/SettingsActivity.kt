package orllewin.lento.settings

import android.content.SharedPreferences
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.ListPreference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import orllewin.lento2.R

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.settings_activity)
        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.settings, SettingsFragment())
                .commit()
        }
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
    }

    class SettingsFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.lento_prefs, rootKey)

            val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(requireContext())

            val ratioPref = findPreference<ListPreference>("ratio")
            when(prefs.getString("ratio", "sixteen_nine")){
                "sixteen_nine" -> ratioPref?.summary = "16:9"
                "four_three" -> ratioPref?.summary = "4:3"
            }
            ratioPref?.setOnPreferenceChangeListener { preference, newValue ->
                when(newValue as String){
                    "sixteen_nine" -> ratioPref.summary = "16:9"
                    "four_three" -> ratioPref.summary = "4:3"
                }
                true
            }
        }
    }
}