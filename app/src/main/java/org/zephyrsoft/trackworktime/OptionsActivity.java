/*
 * This file is part of TrackWorkTime (TWT).
 *
 * TWT is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * TWT is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with TWT. If not, see <http://www.gnu.org/licenses/>.
 */
package org.zephyrsoft.trackworktime;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.fragment.app.DialogFragment;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import org.pmw.tinylog.Logger;
import org.threeten.bp.LocalDate;
import org.zephyrsoft.trackworktime.backup.WorkTimeTrackerBackupManager;
import org.zephyrsoft.trackworktime.database.DAO;
import org.zephyrsoft.trackworktime.options.DurationPreferenceDialogFragment;
import org.zephyrsoft.trackworktime.options.DurationPreference;
import org.zephyrsoft.trackworktime.options.Key;
import org.zephyrsoft.trackworktime.options.TimePreferenceDialogFragment;
import org.zephyrsoft.trackworktime.options.TimePreference;
import org.zephyrsoft.trackworktime.options.TimeZonePreference;
import org.zephyrsoft.trackworktime.options.TimeZonePreferenceDialogFragment;
import org.zephyrsoft.trackworktime.util.PermissionsUtil;
import org.zephyrsoft.trackworktime.util.PreferencesUtil;

import java.text.DateFormat;
import java.util.Date;
import java.util.List;

/**
 * Activity to set the preferences of the application.
 *
 * @author Mathis Dirksen-Thedens
 */
public class OptionsActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getSupportFragmentManager()
            .beginTransaction()
            .replace(android.R.id.content, new SettingsFragment())
            .commit();
    }


    public static class SettingsFragment extends PreferenceFragmentCompat implements OnSharedPreferenceChangeListener {

        private WorkTimeTrackerBackupManager backupManager;

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            setPreferencesFromResource(R.xml.options, rootKey);

            backupManager = new WorkTimeTrackerBackupManager(requireContext());
            setTimestamps();
        }

        @Override
        public void onDisplayPreferenceDialog(Preference preference) {
            DialogFragment dialogFragment = null;

            if (preference instanceof TimePreference) {
                dialogFragment = new TimePreferenceDialogFragment();

            } else if (preference instanceof DurationPreference) {
                dialogFragment = new DurationPreferenceDialogFragment();

            } else if (preference instanceof TimeZonePreference) {
                dialogFragment = new TimeZonePreferenceDialogFragment();
            }

            if (dialogFragment != null) {
                Bundle bundle = new Bundle(1);
                bundle.putString("key", preference.getKey());
                dialogFragment.setArguments(bundle);
                dialogFragment.setTargetFragment(this, 0);
                dialogFragment.show(getParentFragmentManager(), null);
            } else {
                super.onDisplayPreferenceDialog(preference);
            }
        }

        @Override
        public void onResume() {
            super.onResume();
            getPreferenceManager().getSharedPreferences().registerOnSharedPreferenceChangeListener(this);
        }

        @Override
        public void onStop() {
            backupManager.checkIfBackupEnabledChanged();
            super.onStop();
        }

        @Override
        public void onPause() {
            super.onPause();
            getPreferenceManager().getSharedPreferences().unregisterOnSharedPreferenceChangeListener(this);

            // make sure that location-based tracking gets enabled/disabled
            Basics.getOrCreateInstance(requireContext().getApplicationContext()).safeCheckLocationBasedTracking();
        }

        @Override
        public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String keyName) {
            Key sectionToDisable = PreferencesUtil.check(sharedPreferences, keyName);
            if (PreferencesUtil.getBooleanPreference(sharedPreferences, sectionToDisable)) {
                Logger.warn("option {} is invalid => disabling option {}", keyName, sectionToDisable.getName());

                // show message to user
                Intent messageIntent = Basics
                    .getInstance()
                    .createMessageIntent(
                        "The option \""
                            + getString(sectionToDisable.getReadableNameResourceId())
                            + "\" was disabled due to invalid settings.\n\nYou can re-enable it after you have checked the values you entered in that section.",
                        null);

                startActivity(messageIntent);
                Logger.debug("Disabling section {}", keyName);

                // deactivate the section
                PreferencesUtil.disablePreference(sharedPreferences, sectionToDisable);
                // reload data in options view
                setPreferenceScreen(null);
                addPreferencesFromResource(R.xml.options);
            } else {
                if (Key.LOCATION_BASED_TRACKING_ENABLED.getName().equals(keyName)
                    && sharedPreferences.getBoolean(keyName, false)
                    ||
                    Key.WIFI_BASED_TRACKING_ENABLED.getName().equals(keyName)
                        && sharedPreferences.getBoolean(keyName, false)
                ) {
                    // TODO put this also into WorkTimeTrackerActivity.requestMissionPermissions()
                    List<String> missingPermissions = PermissionsUtil.missingPermissionsForTracking(getContext());
                    if (!missingPermissions.isEmpty()) {
                        new AlertDialog.Builder(getContext())
                            .setTitle(getString(R.string.locationPermissionsRequestTitle))
                            .setMessage(getString(R.string.locationPermissionsRequestText)
                                + (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                                    ? getString(R.string.locationPermissionsRequestTextSupplement)
                                    : ""))
                            .setNegativeButton(android.R.string.cancel, (dialog, which) -> {
                                Basics.getOrCreateInstance(getContext()).disableLocationBasedTracking();
                                reloadData();
                            })
                            .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                                requestPermissions(missingPermissions.toArray(new String[missingPermissions.size()]),
                                    Constants.MISSING_PRIVILEGE_ACCESS_COARSE_LOCATION_ID);
                            })
                            .create()
                            .show();
                    }
                }
            }

            // reset cache if preference changes time calculation
            Key key = Key.getKeyWithName(keyName);
            if (key != null && (
                key.equals(Key.HOME_TIME_ZONE) ||
                    key.equals(Key.ENABLE_FLEXI_TIME) ||
                    Key.ENABLE_FLEXI_TIME.equals(key.getParent())
            )) {
                Basics.getInstance().getTimerManager().invalidateCacheFrom((LocalDate) null);
            }
        }

        @Override
        public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
            if (requestCode == Constants.MISSING_PRIVILEGE_ACCESS_COARSE_LOCATION_ID) {
                // TODO put this also into WorkTimeTrackerActivity.onRequestPermissionsResult()
                List<String> ungranted = PermissionsUtil.notGrantedPermissions(permissions, grantResults);
                if (ungranted.isEmpty()) {
                    Basics basics = Basics.getOrCreateInstance(requireContext().getApplicationContext());
                    Boolean isActive = basics.isNotificationActive(Constants.MISSING_PRIVILEGE_ACCESS_COARSE_LOCATION_ID);
                    if (isActive == null || isActive) {
                        basics.removeNotification(Constants.MISSING_PRIVILEGE_ACCESS_COARSE_LOCATION_ID);
                    }
                    basics.enableLocationBasedTracking();
                } else {
                    final SharedPreferences.Editor editor = getPreferenceScreen().getSharedPreferences().edit();
                    editor.putBoolean(Key.LOCATION_BASED_TRACKING_ENABLED.getName(), false);
                    editor.putBoolean(Key.WIFI_BASED_TRACKING_ENABLED.getName(), false);
                    editor.apply();

                    Intent messageIntent = Basics.getInstance()
                        .createMessageIntent("This option needs location permission.", null);
                    startActivity(messageIntent);

                    reloadData();
                }
            }
            super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        }

        private void reloadData() {
            setPreferenceScreen(null);
            addPreferencesFromResource(R.xml.options);
        }

        private void setTimestamps() {
            final Preference lastModifiedPref = findPreference(getString(R.string.keyBackupLastModifiedTimestamp));
            final Preference lastBackupPref = findPreference(getString(R.string.keyBackupLastBackupTimestamp));
            if (lastModifiedPref == null || lastBackupPref == null) {
                Logger.warn("backup timestamps preference not found!");
                return;
            }
            final DAO dao = new DAO(requireContext());
            final long lastDbModification = dao.getLastDbModification();

            final DateFormat dateFormatUser = DateFormat.getDateInstance();
            final DateFormat timeFormatUser = DateFormat.getTimeInstance();

            final Date dateLocal = new Date(lastDbModification);
            final String dateLocalStr = dateFormatUser.format(dateLocal) + " "
                + timeFormatUser.format(dateLocal);
            lastModifiedPref.setSummary(dateLocalStr);

            final long dateBackupLong = backupManager.getLastBackupTimestamp();
            final String dateBackupStr;
            if (dateBackupLong == 0) {
                dateBackupStr = "-";
            } else {
                final Date dateBackup = new Date(dateBackupLong);
                dateBackupStr = dateFormatUser.format(dateBackup) + " "
                    + timeFormatUser.format(dateBackup);
            }

            lastBackupPref.setSummary(dateBackupStr);
            showTimestampPrefIcon(lastBackupPref, dateLocalStr, dateBackupStr);
        }

        private void showTimestampPrefIcon(final Preference timestampPref, final String dateLocalStr, final String dateBackupStr) {
            if (dateLocalStr.equals(dateBackupStr)) {
                timestampPref.setIcon(R.drawable.backup_ok);
            } else {
                timestampPref.setIcon(R.drawable.backup_not_ok);
            }
        }
    }
}
