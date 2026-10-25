package com.xyz.relativity.kineticespeedometer;

import android.content.pm.PackageManager;
import android.os.Bundle;
import android.text.InputType;
import android.widget.EditText;

import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.preference.EditTextPreference;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

public class SettingsActivity extends AppCompatActivity {
	private static final String KEY_VERSION = "version";

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_settings);
		if (savedInstanceState == null) {
			getSupportFragmentManager().beginTransaction()
					.replace(R.id.settingsContainer, new SettingsFragment())
					.commit();
		}
		ActionBar actionBar = getSupportActionBar();
		if (actionBar != null) {
			actionBar.setDisplayHomeAsUpEnabled(true);
		}
	}

	@Override
	public boolean onSupportNavigateUp() {
		finish();
		return true;
	}

	public static class SettingsFragment extends PreferenceFragmentCompat {
		@Override
		public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
			setPreferencesFromResource(R.xml.preferences, rootKey);

			final ListPreference unitPreference = findPreference(AppSettings.KEY_SPEED_UNIT);
			final EditTextPreference gaugeMaxPreference = findPreference(AppSettings.KEY_GAUGE_MAX);
			final EditTextPreference massPreference = findPreference(AppSettings.KEY_VEHICLE_MASS);
			final ListPreference themePreference = findPreference(AppSettings.KEY_THEME);
			final Preference versionPreference = findPreference(KEY_VERSION);

			if (gaugeMaxPreference != null) {
				gaugeMaxPreference.setOnBindEditTextListener(new EditTextPreference.OnBindEditTextListener() {
					@Override
					public void onBindEditText(@NonNull EditText editText) {
						editText.setInputType(InputType.TYPE_CLASS_NUMBER);
						editText.selectAll();
					}
				});
				gaugeMaxPreference.setSummaryProvider(new Preference.SummaryProvider<EditTextPreference>() {
					@Override
					public CharSequence provideSummary(@NonNull EditTextPreference preference) {
						return getString(R.string.pref_gauge_max_summary, preference.getText(), getString(currentUnit().speedLabel));
					}
				});
				gaugeMaxPreference.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
					@Override
					public boolean onPreferenceChange(@NonNull Preference preference, Object newValue) {
						float metresPerSecond = currentUnit().toMps(AppSettings.parseFloat(String.valueOf(newValue), Float.NaN));
						return metresPerSecond >= AppSettings.MIN_GAUGE_MAX_MPS && metresPerSecond <= AppSettings.MAX_GAUGE_MAX_MPS;
					}
				});
			}

			if (unitPreference != null) {
				unitPreference.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
					@Override
					public boolean onPreferenceChange(@NonNull Preference preference, Object newValue) {
						// Runs before the new unit is stored: convert the dial maximum so it keeps its meaning.
						AppSettings.SpeedUnit from = currentUnit();
						AppSettings.SpeedUnit to = AppSettings.SpeedUnit.fromKey(String.valueOf(newValue));
						if (gaugeMaxPreference != null && from != to) {
							float fallback = from.fromMps(AppSettings.SpeedUnit.KPH.toMps(AppSettings.DEFAULT_GAUGE_MAX_KPH));
							float current = AppSettings.parseFloat(gaugeMaxPreference.getText(), fallback);
							float converted = to.fromMps(from.toMps(current));
							gaugeMaxPreference.setText(String.valueOf(Math.max(10, Math.round(converted / 10f) * 10)));
						}
						return true;
					}
				});
			}

			if (massPreference != null) {
				massPreference.setOnBindEditTextListener(new EditTextPreference.OnBindEditTextListener() {
					@Override
					public void onBindEditText(@NonNull EditText editText) {
						editText.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
						editText.selectAll();
					}
				});
				massPreference.setSummaryProvider(new Preference.SummaryProvider<EditTextPreference>() {
					@Override
					public CharSequence provideSummary(@NonNull EditTextPreference preference) {
						String text = preference.getText();
						float mass = AppSettings.parseFloat(text, 0f);
						return mass > 0f
								? getString(R.string.pref_vehicle_mass_summary, text.trim())
								: getString(R.string.pref_vehicle_mass_summary_empty);
					}
				});
				massPreference.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
					@Override
					public boolean onPreferenceChange(@NonNull Preference preference, Object newValue) {
						String text = String.valueOf(newValue).trim();
						return text.isEmpty() || AppSettings.parseFloat(text, -1f) >= 0f;
					}
				});
			}

			if (themePreference != null) {
				themePreference.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
					@Override
					public boolean onPreferenceChange(@NonNull Preference preference, Object newValue) {
						AppCompatDelegate.setDefaultNightMode(AppSettings.nightModeFor(String.valueOf(newValue)));
						return true;
					}
				});
			}

			if (versionPreference != null) {
				try {
					String versionName = requireContext().getPackageManager()
							.getPackageInfo(requireContext().getPackageName(), 0).versionName;
					versionPreference.setSummary(versionName);
				} catch (PackageManager.NameNotFoundException ignored) {
				}
			}
		}

		private AppSettings.SpeedUnit currentUnit() {
			return new AppSettings(requireContext()).speedUnit();
		}
	}
}
